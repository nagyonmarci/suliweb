# SuliWeb Architecture

A detailed, file-by-file walkthrough of the codebase. For a quick start and API reference, see the main [README.md](../README.md). This document goes deeper: what each class is responsible for, how the three background pipelines work end to end, and why certain design decisions were made.

A Hungarian translation of this document is available at [ARCHITECTURE.hu.md](ARCHITECTURE.hu.md).

## Table of contents

1. [High-level shape of the system](#1-high-level-shape-of-the-system)
2. [Backend layers](#2-backend-layers)
3. [The three pipelines](#3-the-three-pipelines)
4. [GraphRAG chat](#4-graphrag-chat)
5. [Frontend architecture](#5-frontend-architecture)
6. [Infrastructure](#6-infrastructure)
7. [Testing strategy](#7-testing-strategy)

---

## 1. High-level shape of the system

SuliWeb processes Microsoft Outlook PST archives end to end: find the files, extract emails and attachments, store them, index them for search, and build a knowledge graph that an LLM can reason over.

```
PST files  →  MongoDB (source of truth for emails/attachments/metadata)
                  │
                  ├──→ Elasticsearch (full-text search: email_archive, attachment_archive)
                  │
                  └──→ Neo4j (knowledge graph: people, threads, concepts, communication network)
                            │
                            └──→ Ollama (NER for graph building, LLM for GraphRAG chat)
```

Three independent Spring `@Service` pipelines read from MongoDB and populate one of the two downstream stores. They never write to each other; MongoDB is always the upstream source. This means any of the three can be re-run from scratch without touching the others — re-indexing Elasticsearch doesn't affect the knowledge graph, and vice versa.

The backend is a single Spring Boot 4.0 (Spring Framework 7, Java 25) application. The frontend is a separate Astro 5 + React 19 static site, served by nginx, talking to the backend over `/api/**` via a reverse proxy (Caddy in front of both in Docker).

## 2. Backend layers

### 2.1 `config/`

| Class | Responsibility |
|---|---|
| `SecurityConfig` | Fail-secure allowlist: every route is denied by default except an explicit list of public/role-gated patterns. JWT filter is wired in before `UsernamePasswordAuthenticationFilter`. CORS is configured from `cors.allowed-origins`. |
| `JwtConfig` | `@ConfigurationProperties` for JWT secret and token TTLs. |
| `ElasticsearchConfig` | Builds the `ElasticsearchClient` bean and the `pythonProcessorClient` `WebClient` bean (for the attachment-to-markdown sidecar). On `ApplicationReadyEvent`, creates the `email_archive` and `attachment_archive` indices if they don't exist yet, with Hungarian-aware analyzers (see [3.2](#32-e-discovery-indexing-elasticsearch)). |
| `RagConfig` | `@ConfigurationProperties(prefix = "rag")`: Ollama base URL, default chat model, default chat context size (`chatContextTopK`), default history length. Also provides the `ollamaWebClient` bean. Trimmed down in 2026 to only the fields actually read anywhere — it used to carry ~15 fields left over from a deleted vector-embedding RAG pipeline (Qdrant + chunking + embedding services), none of which were wired to anything. |
| `SynologyConfig` | Synology NAS connection defaults (`@ConfigurationProperties(prefix = "synology")`). |
| `ModelMapperConfig` | `ModelMapper` bean for DTO↔entity mapping where used. |

### 2.2 `controller/`

Thin REST controllers; almost no business logic lives here. The full endpoint list with HTTP methods and auth requirements is in the README. A few worth calling out:

- **`KnowledgeGraphController`** (`/api/kg`) — ingest/status/graph-stats/network/thread/concept/chat/chat-stream/models. The `chat`/`chat/stream` endpoints just forward to `GraphRagChatService`.
- **`EDiscoveryController`** (`/api/ediscovery`) — ingest/search/retry-failed/status, backed by `EDiscoveryIngestionService` and `EDiscoverySearchService`.
- **`AttachmentProcessingController`** (`/api/attachments/processing`) — separate from `AttachmentController` (which is a plain browse/search/download/dedupe API over MongoDB attachment records). This controller triggers the markdown-conversion-and-ES-indexing pipeline described in [3.3](#33-attachment-processing-separate-pipeline).
- **`PipelineController`** (`/api/pipeline`) — orchestrates a full run (PST discovery → processing → ES indexing → KG ingestion) via `PipelineOrchestrationService`, with per-stage progress reporting.
- **`AppSettingsController`** (`/api/settings`) — exposes the runtime-tunable settings described in 2.4 below.

Security note: any endpoint that echoes a path variable back into a plain-text response body (`ResponseEntity.ok("... " + id)`) explicitly sets `Content-Type: text/plain` to prevent a client from interpreting the response as HTML/JS (a reflected-XSS pattern CodeQL flags — fixed in `EDiscoveryController` and `AttachmentProcessingController`).

### 2.3 `domain/` and `domain/graph/`

`domain/` holds the MongoDB documents (`Email`, `Attachment`, `FailedConversion`, `FileInfo`, `User`, `AppSettings`, …). `domain/graph/` holds the Spring Data Neo4j `@Node` classes that make up the knowledge graph:

| Node | Key properties | Relationships |
|---|---|---|
| `PersonNode` | `email` (unique key), `name`, `organization` (derived from email domain) | `COMMUNICATES_WITH` → other `PersonNode` (count + last date) |
| `EmailNode` | `messageId`, `mongoId` (back-reference to the MongoDB `Email`), `subject`, `date`, `pstFileName`, `pstOwner` | `SENT` (incoming, from sender `PersonNode`), `TO`/`CC` (outgoing, to recipient `PersonNode`s), `BELONGS_TO` (to `ThreadNode`), `REPLY_TO` (to parent `EmailNode`), `HAS_ATTACHMENT` (to `AttachmentNode`), `MENTIONS` (to `ConceptNode`) |
| `ThreadNode` | `threadId` (= MongoDB `conversationId`), `subject`, `lastActivity` | — |
| `ConceptNode` | `name`, `type` (`PERSON`/`ORG`/`TOPIC`/`LOCATION` — note: a `ConceptNode` of type `PERSON` is different from a `PersonNode`; the former is an NER-extracted mention, the latter is a real sender/recipient) | — |
| `AttachmentNode` | `sha256`, `filename`, `markdownContent` (currently always empty — see note below) | — |
| `OrganizationNode` | unused by current ingestion code; kept for forward compatibility | — |

A 2026 cleanup removed an entirely separate, unused MongoDB-based "node type" experiment (`domain/nodetypes/`: `Contract`, `Institution`, `ServiceProvider`, `CommitmentClaim`) that predated the Neo4j graph model above and was never wired to any service.

`AttachmentNode.markdownContent` is set to `""` unconditionally in `KnowledgeGraphIngestionService` — the markdown conversion happens in the separate attachment-processing pipeline ([3.3](#33-attachment-processing-separate-pipeline)) and indexes into Elasticsearch, not into the graph. Wiring those two together (so the graph node carries the actual extracted text) is a natural next step if KG-side full-text-on-attachments becomes a requirement.

### 2.4 `service/` — the non-pipeline services

- **`AppSettingsService`** — the runtime-configuration layer. Each `getEffectiveX()` method follows the same pattern: look up a singleton `AppSettings` Mongo document; if it has a non-null/non-blank/positive override, use it; otherwise fall back to the static default from `@ConfigurationProperties` (`RagConfig`) or `@Value` (`kg.ingestion.*`). This lets an admin tune `chatModel`, `nerModel`, `chatMaxHistoryTurns`, `chatContextTopK`, `kgBatchSize`, and `kgMaxConcurrentWrites` from the `/settings` UI without redeploying.
- **`ProgressTracker`** — a single global progress state (current operation name, processed/total, percentage) used by PST processing, e-Discovery ingestion, attachment processing, and KG ingestion alike. Only one of these runs at a time in practice, so a shared tracker is intentional, not an oversight.
- **`PipelineOrchestrationService`** — drives a multi-stage run (`PstFinderService` → `PstProcessorService` → `EDiscoveryIngestionService` → `KnowledgeGraphIngestionService`) with per-stage `StageProgress` (state machine: `PENDING → RUNNING → DONE/FAILED/SKIPPED`), independent of the global `ProgressTracker`.
- **`PstProcessorService` / `PstFinderService` / `SynologyPstFinderService` / `SynologyApiClient` / `SynologySettingsService` / `FileService` / `FileAccessService` / `FileUploadService` / `PdfFormFillerService`** — PST discovery, parsing (via `java-libpst`, on virtual threads), Synology NAS integration, filesystem access guarding, ZIP upload handling, and PDF form filling. These are independent of the ELK/KG/LLM concerns this document focuses on; see inline Javadoc/comments in each class.

### 2.5 `service/rag/` — text extraction, NER, GraphRAG chat

- **`TextExtractionService`** — Apache Tika-based text extraction from attachments (PDF, DOC, XLS, …), plus Java-native reply-chain stripping for email bodies (replaced an earlier Python-based stripper for performance).
- **`NerExtractor`** (interface) + **`EntityExtractionService`** (implementation) — calls Ollama's `/api/generate` with a fixed prompt asking for a JSON array of `{name, type}` entities (`PERSON`/`ORG`/`TOPIC`/`LOCATION`), truncating input to 3000 characters. Parsing is deliberately defensive: it accepts a bare JSON array, an object with the array nested inside any of its values, or a list of bare strings (treated as `TOPIC`). Any failure — network error, malformed JSON, anything — returns an empty list rather than throwing; graph ingestion is designed to degrade gracefully when Ollama is unavailable. The `NerExtractor` interface exists purely so tests can substitute a mock without dealing with `WebClient`.
- **`GraphRagChatService`** — the GraphRAG chat engine:
  1. Extract entities from the user's question (`EntityExtractionService`).
  2. For each entity, ask `GraphSearchService.findEmailsByConceptProximity()` for nearby emails, deduplicating by `mongoId` until `topK` is reached (`topK` defaults to `AppSettingsService.getEffectiveChatContextTopK()` if the caller passes `<= 0`).
  3. Build a context block from those emails' subject/date/PST file/first-500-chars-of-body.
  4. Send `system` (context-grounded instructions) + trimmed `history` + `user` message to Ollama's `/api/chat`, either blocking (`chat()`) or as a token stream (`chatStream()`, which also emits a final `{"done":true,"sources":[...]}` event so the frontend can render source links after the stream ends).

  If entity extraction finds nothing, the context is empty and the system prompt says so explicitly — the LLM is instructed to admit it doesn't have enough information rather than hallucinate.

### 2.6 `repository/` and `repository/graph/`

Standard Spring Data repositories. The graph repositories use `@Query` with literal Cypher for anything beyond a derived-method lookup:

- `EmailNodeRepository.findByConceptProximity` — `MATCH (e:Email)-[:MENTIONS]->(c:Concept) WHERE c.name CONTAINS $conceptName RETURN e ORDER BY e.date DESC LIMIT $limit`. Note this is a substring match, not a real proximity/similarity score — "proximity" here means graph adjacency (one hop via `MENTIONS`), not semantic distance.
- `EmailNodeRepository.ensureConcepts` / `linkEmailToConcepts` — used only by `KnowledgeGraphIngestionService.ingestConceptsOnly()`'s retry-with-backoff path (see [3.4](#34-knowledge-graph-ingestion)).
- `PersonNodeRepository.findCommunicationPartners[InRange]` — one-hop `COMMUNICATES_WITH` traversal, optionally filtered by the relationship's `lastDate` property.

## 3. The three pipelines

All three read from `EmailRepository`/`AttachmentRepository` (MongoDB) and are triggered independently via REST, by `PipelineOrchestrationService`, or (for e-Discovery) automatically.

### 3.1 PST → MongoDB

`PstProcessorService` reads `.pst` files via `java-libpst` on virtual threads, computes a `SHA-256(pstFileName + msgId)` dedup key per email and a content-hash dedup key per attachment (stored once under `attachments/hashes/`, regardless of how many emails reference the same file), strips the reply chain from the body (`TextExtractionService`), and writes `Email` + `Attachment` documents to MongoDB. PST file processing state (`New`/`Processed`/`Modified`/`Invalid`/`Missing`) is tracked in `FileInfo`.

### 3.2 e-Discovery indexing (Elasticsearch)

`EDiscoveryIngestionService.ingestAll()` pages through every `Email`, building one ES bulk-index operation per email into the `email_archive` index: `subject`, `bodyDelta` (the already-stripped body), `sender`/`senderName`, `recipients`, `date`, `pstFileName`/`pstOwner`, `threadId`. It does **not** touch attachments — that used to happen inline here (calling the Python sidecar per attachment during email indexing) but was split out into its own pipeline (3.3) so that a slow/failing attachment conversion can no longer block or slow down email indexing.

Indexing is also triggered automatically by `EDiscoveryChangeStreamListener`, which watches the MongoDB `emails` collection's change stream and re-indexes (or deletes from ES) on insert/update/delete — so the search index stays in sync without a manual re-run after every PST processing pass.

`EDiscoverySearchService.search()` builds a `multi_match` query across `subject`/`bodyDelta`/`senderName` and their `.ascii` sub-fields (so both stemmed-and-accented and accent-folded queries match), with `fuzziness: AUTO` and `prefix_length: 2` so typos still match (e.g. "kontrakt" matches "kontraktus") without fuzzy-matching short prefixes into nonsense. Optional `sender`/`pstOwner`/`pstFileName` term filters and a `date` range filter are ANDed in via a `bool` query when provided. Highlighting is requested on `bodyDelta` (one fragment, 200 chars) and surfaced as `snippet` in the result.

**Index analyzers**: `hungarian_stemmed` (standard tokenizer → lowercase → Hungarian stopwords → Hungarian stemmer) and `hungarian_ascii` (standard tokenizer → lowercase → asciifolding → Hungarian stopwords, no stemming) are defined per-index and used as a main-field/`.ascii`-subfield pair on every text field. Note: as of Elasticsearch 9, the bare token filter name `"hungarian"` can no longer be referenced directly in a custom analyzer's filter list — it must be declared as a named custom filter (`type: stemmer, language: hungarian`) first. This broke index creation silently after the ES 8→9.4.2 upgrade until it was caught and fixed.

### 3.3 Attachment processing (separate pipeline)

`AttachmentProcessingService.processAll()` groups all `Attachment` Mongo records by content hash (so an identical file shared across many emails is converted exactly once), skips any hash whose document already exists in the `attachment_archive` ES index (an `exists` check by id = hash, making re-runs cheap), converts the remainder to Markdown via the Python `markitdown` sidecar (`POST /convert-attachment`), and bulk-indexes `{hash, filename, contentType, pstFileName, emailIds[], markdownContent}` into `attachment_archive`. Conversion failures are recorded in the same `FailedConversion` dead-letter collection used by e-Discovery (`FailureType.ATTACHMENT_CONVERT`), retryable one-by-one or in bulk via `/api/attachments/processing/retry-failed[/​{id}]`.

This is deliberately a *separate* ES index from `email_archive`, not a nested field on the email document — searching attachment content and searching email content are different use cases with different result shapes, and decoupling them means a slow attachment-conversion run never blocks email search availability.

### 3.4 Knowledge Graph ingestion

`KnowledgeGraphIngestionService` has two entry points that share one pipeline shape (`runBatchedPipeline`, introduced to remove duplication between the two):

1. **`ingestAll()`** — for every `Email` not yet present in the graph (`emailNodeRepo.existsByMessageId`), run NER (`EntityExtractionService`) and write `Person`/`Thread`/`Email`/`Attachment`/`Concept` nodes and all their relationships (see 2.3's table).
2. **`ingestConceptsOnly()`** — the inverse filter: only emails *already* in the graph, re-run NER, and merge any new `Concept` nodes + `MENTIONS` links without touching the rest of the email's data. Useful after improving the NER prompt without wanting to rebuild the whole graph.

The shared pipeline shape is two phases per batch page:

- **Phase 1 (NER)** — runs on `Executors.newVirtualThreadPerTaskExecutor()`: unbounded concurrency is fine because this phase is I/O-bound (waiting on Ollama), not CPU-bound.
- **Phase 2 (write)** — runs on a fixed-size pool (`AppSettingsService.getEffectiveKgMaxConcurrentWrites()`, default 4): Neo4j write concurrency is bounded deliberately, since uncontrolled parallel writes to overlapping nodes increase lock-contention/deadlock risk.

`mergePerson`/`mergeThread`/`mergeConcept` are `synchronized` instance methods — this serializes get-or-create across the whole service (not just per-node-type), which trades some parallelism for a simple guarantee that two concurrent writers can never race to create two `PersonNode`s for the same email address. The `ingestConceptsOnly` write path additionally goes through `writeWithRetry`, which retries `ensureConcepts`/`linkEmailToConcepts` (custom Cypher `MERGE` queries that bypass the synchronized helpers) with exponential backoff + jitter on a detected deadlock — those two queries run inside the bounded write pool without the `synchronized` safety net the merge helpers provide, so a retry loop is the cheaper fix versus serializing them too.

`KnowledgeGraphController.triggerIngestion()`/`triggerConceptReingestion()` both check `isRunning()` first and start the chosen method on a virtual thread; `getStats()` computes `ratePerMin`/`etaSeconds` from `processedCount`/`startedAt`, recalculated on every poll rather than cached.

## 4. GraphRAG chat

See [2.5](#25-servicerag--text-extraction-ner-graphrag-chat) for `GraphRagChatService` internals. The frontend side (`KnowledgeGraph.tsx`'s "Chat" tab and the standalone `RagChat.tsx` page) maintains its own per-session conversation history in `localStorage` (not persisted server-side), sends it back on every turn so the LLM has follow-up context, and renders streamed tokens progressively via SSE.

## 5. Frontend architecture

Astro 5 with `output: static` — every page is fully static HTML at build time, with React 19 "islands" (`client:load`) for anything interactive. This has one direct consequence worth knowing: anything that needs to react to client-side state (like the selected UI language) **cannot** live in plain Astro markup, because that markup is baked once at build time and never re-rendered. This is why the sidebar navigation, which used to be server-rendered `<aside>` markup in `Layout.astro`, was converted into a `Sidebar.tsx` React island when language switching was added — there was no other way to make the nav labels change without a full page reload.

### 5.1 i18n

`src/lib/i18n.ts` initializes a single `i18next` + `react-i18next` instance (resources bundled from `src/locales/{hu,en}.json`, language read from/written to `localStorage['lang']`, default `hu`). Every component that needs translated text imports this module (for its side-effecting `i18next.init()` call) and calls `useTranslation()`. Because `react-i18next` defaults to the global `i18next` singleton when no `I18nextProvider` wraps the tree, every island on a page shares state automatically — flipping the language re-renders every mounted component on the current page without a reload, even though each island was hydrated independently.

Two exceptions to "use `useTranslation()`":
- **`Layout.astro`'s page title** — the `title` prop is a translation key (e.g. `"pages.dashboard"`), resolved server-side against `hu.json` for the static HTML's `<title>`/`<h2>`, then re-resolved client-side via a small inline `<script>` that imports the same `i18n` module directly and listens for `languageChanged`.
- **`rag.astro`'s tab bar** — three labels rendered by a vanilla `<script>` (not a React island, to avoid a hydration race with the existing tab-toggle logic that already lived there), also using `i18n.t()` directly.

### 5.2 Pages and components

Most pages (`src/pages/*.astro`) are a thin `<Layout><Component client:load /></Layout>` wrapper around one component from `src/components/`. The mapping is mostly 1:1 and self-explanatory from the file names; a few worth noting:

- `attachments.astro` (browse/search/download/dedupe existing attachment records) vs. `attachment-processing.astro` (trigger and monitor the markdown-conversion pipeline) are deliberately separate pages/sidebar entries — different purposes, different audiences.
- `login.astro` does not use `Layout.astro` at all (no sidebar before authentication); its form lives in `LoginForm.tsx` specifically so the language switcher works there too.
- `rag.astro` hosts three components behind a tab bar: `RagChat.tsx` (only shown if the logged-in user has the `RAG_CHAT` authority), and two instances of `RagSearch.tsx` (`mode="search"`/`mode="manage"`) which, since the old vector-embedding RAG pipeline was removed, now just point the user at `/ediscovery` and `/knowledge-graph` instead of offering search/management UI of their own.

## 6. Infrastructure

Six Docker Compose services: `suliweb-frontend` (Astro build → nginx), `suliweb-backend` (Spring Boot, multi-stage Maven build), `suliweb-mongo`, `suliweb-elasticsearch` (9.4.2), `suliweb-neo4j` (5.26 Community + APOC), `python-processor` (FastAPI + `markitdown`). A Caddy reverse proxy in front of the stack terminates TLS and health-checks both frontend and backend upstreams. Ollama runs on the Docker **host**, not as a container — `host.docker.internal:11434` from inside the network.

CI (`.github/workflows/ci.yml`) runs backend tests + JaCoCo coverage, frontend lint + build, SAST (SpotBugs, CodeQL for Java and JS/TS), SCA (OWASP Dependency-Check), container scanning (Trivy), secret scanning (Gitleaks), and Dockerfile linting (Hadolint) on every push/PR; `Docker Build + Push to GHCR` only runs on `master`.

## 7. Testing strategy

Backend tests are plain JUnit 5 + Mockito unit tests — no Testcontainers, no embedded Mongo/Neo4j/Elasticsearch. This means:

- Pure logic (entity validation, JSON parsing, message-history trimming, dedup-by-key) is tested directly.
- Anything that talks to MongoDB/Neo4j/Elasticsearch/Ollama is tested by mocking the repository/client interface, not by standing up the real thing. `WebClient`'s and `Neo4jClient`'s fluent builder chains are mocked with Mockito's `RETURNS_DEEP_STUBS` rather than hand-rolling a stub for every method in the chain.
- Where a test needs to assert something about a query's *structure* rather than just its outcome (e.g. "does the e-Discovery search request actually include `fuzziness: AUTO`?"), the request object is captured with an `ArgumentCaptor` and inspected via the Elasticsearch Java client's own JSON serialization (`co.elastic.clients.json.JsonpUtils.toString(...)`) rather than re-implementing query-building logic in the test.
- Concurrency-sensitive guards (like `KnowledgeGraphIngestionService`'s "already running" check) are tested with a real second thread and a short, deterministic sleep rather than skipped — flagged inline if a guard genuinely can't be tested this way without flakiness.

There is intentionally no test for `ElasticsearchConfig`'s index-bootstrap logic or for the Docker/Caddy/CI configuration itself — those are verified by actually running the stack, not by unit tests.
