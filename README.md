# SuliWeb - PST Email Processor

Spring Boot 4.0 application for processing Microsoft Outlook PST files. Finds PST files, extracts emails and attachments, and stores the metadata in MongoDB. Includes an Elasticsearch 9 e-Discovery pipeline, a separate attachment-processing pipeline, a Neo4j 5.26 Knowledge Graph with Agents-K1 claim/evidence extraction, an MCP (Model Context Protocol) server, a Python FastAPI sidecar, JWT-based authentication, and an Astro 5 + React 19 frontend dashboard.

## Features

- **PST file discovery** - Searches local directories or a Synology NAS for PST files
- **Email extraction** - Extracts emails and attachments from PST files with parallel processing (Virtual Threads)
- **PST file statuses** - `New`, `Processed`, `Modified`, `Invalid`, `Missing`
- **PST duplicate filtering** - SHA-256 hash-based deduplication
- **Attachment deduplication** - Hash-based file storage; identical attachment content is stored on disk only once
- **Pause/resume** - Control over long-running processing operations
- **e-Discovery pipeline** - Elasticsearch 9 full-text search of email bodies; reply-chain stripping done in Java; Message-ID based dedup; Hungarian text analysis (`hungarian_stemmed` + `hungarian_ascii` analyzers)
- **Attachment processing pipeline** - Separate, on-demand pipeline that converts attachments to Markdown via the Python sidecar and indexes them into their own Elasticsearch index (`attachment_archive`), deduplicated by content hash
- **Dead-letter queue** - Failed attachment conversions are logged in MongoDB (`failed_conversions`); both single-item and bulk retry are available via the REST API
- **Knowledge Graph** - Neo4j 5.26 communication graph (Person, Thread, Concept, Claim, Evidence, Mechanism, MethodLineage nodes); K1 entity/claim extraction via Agents-K1 4B (OpenAI-compatible); two-phase pipeline (virtual-thread extraction + fixed-pool Neo4j writes); thread traversal; concept-proximity search
- **GraphRAG chat** - Entity extraction → Neo4j context → Ollama LLM response (streaming + non-streaming)
- **MCP server** - Spring AI 2.x MCP (Model Context Protocol) server exposed over SSE at `/mcp`; provides `search_ediscovery_emails` and `query_k1_knowledge_graph` tools and a `k1_scp_context` prompt for multi-hop K1 graph traversal; compatible with Claude Desktop and any MCP client
- **Python MCP sidecar** - FastMCP server mounted at `/mcp` on the Python sidecar (`:8001`); exposes `convert_file_to_markdown` tool and `file://attachments/hashes/{hash_id}` resource over SSE
- **Synology integration** - Searches for PST files on a NAS in parallel via the Universal Search API
- **PDF form filling** - iText-based PDF form filler
- **JWT authentication** - Spring Security 7, access token (8h) + refresh token (7d), BCrypt
- **Modern dashboard** - Astro 5 + React 19 + Tailwind CSS 4 responsive frontend

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                       Frontend (Astro 5)                              │
│ Dashboard │ Emails │ e-Discovery │ Attachment processing │ KG │ RAG   │
│        :80 (nginx) → proxy → :8080  |  :4321 (dev)                   │
├──────────────────────────────────────────────────────────────────────┤
│                  Spring Boot Backend (:8080)                           │
│  PST processing → MongoDB (metadata, auth, progress)                  │
│  e-Discovery:  Elasticsearch 9 (full-text, dedup, highlight)          │
│  Attachment processing: Elasticsearch 9 (separate index)              │
│  Knowledge Graph: Neo4j 5.26 (Person/Thread/Concept/Claim/Evidence/  │
│                   Mechanism/MethodLineage nodes)                       │
│  K1 extraction: Agents-K1 4B @ localhost:8000/v1 (OpenAI-compat.)    │
│  GraphRAG: EntityExtraction → Neo4j context → Ollama LLM chat        │
│  MCP server: SSE at /mcp  (tools + prompts for Claude Desktop)        │
├──────────────────────────────────────────────────────────────────────┤
│  MongoDB (:27017)     Elasticsearch (:9200)     Neo4j (:7474)        │
│  Python sidecar (:8001) — attachment→markdown  + FastMCP at /mcp     │
│  Agents-K1 4B (:8000, OpenAI-compat., runs on host)                  │
│  Ollama (:11434, runs on host – GraphRAG LLM chat)                   │
│  mcp-neo4j-cypher (Docker) — direct Cypher MCP tool for Neo4j        │
└──────────────────────────────────────────────────────────────────────┘
```

## Processing flow

```
PST file(s)
    │
    ▼ POST /pst/processFromDb  (Processing page)
┌─────────────────────────────────────────┐
│ 1. PST READING                          │
│  • java-libpst, virtual threads         │
│  • Dedup: SHA-256(pstFileName+msgId)    │
│  • Attachment dedup: hash-based storage │
│  • Reply-chain stripping done in Java   │
└──────────────┬──────────────────────────┘
               │ MongoDB: emails + attachments
               ▼
┌─────────────────────────────────────────┐
│ 2. E-DISCOVERY INDEXING                 │
│  Trigger: POST /api/ediscovery/ingest   │
│  Auto-trigger: MongoDB Change Stream    │
│  (insert / update / delete → auto-sync) │
│                                         │
│  Indexes email subject/body/metadata    │
│  into Elasticsearch (no attachments)    │
└──────────────┬──────────────────────────┘
               │ Elasticsearch: email_archive index
               ▼
       Full-text search (/api/ediscovery/search)

┌─────────────────────────────────────────┐
│ 2b. ATTACHMENT PROCESSING (separate,    │
│     on-demand pipeline)                 │
│  Trigger: POST /api/attachments/        │
│           processing/start              │
│                                         │
│  Groups attachments by content hash,    │
│  converts each unique file to Markdown  │
│  via the Python sidecar (markitdown),   │
│  skips files already indexed            │
└──────────────┬──────────────────────────┘
               │ Elasticsearch: attachment_archive index
               ▼
┌─────────────────────────────────────────┐
│ 3. KNOWLEDGE GRAPH BUILD                │
│  Trigger: POST /api/kg/ingest           │
│                                         │
│  Two-phase pipeline:                    │
│  Phase 1 — K1 extraction (virtual      │
│  threads, Agents-K1 4B OpenAI API):    │
│  • Named entities (PERSON/ORG/TOPIC/   │
│    LOCATION)                            │
│  • Claims (FACTUAL/CAUSAL/NORMATIVE/   │
│    SPECULATIVE) with confidence score   │
│  • Evidence (DIRECT_QUOTE/CITATION/    │
│    INFERENCE) supporting each claim     │
│  • Mechanisms (COMPUTATIONAL/LEGAL/    │
│    FINANCIAL/ORGANIZATIONAL)            │
│  • Relations (PROVES/CONTRADICTS/      │
│    INVOLVES/MENTIONS)                   │
│  Phase 2 — Neo4j write (fixed pool,    │
│    max-concurrent-writes=4):            │
│  • Person nodes — merged by email       │
│    address (sender + recipients + CC)   │
│  • Thread node — merged by              │
│    conversationId                       │
│  • Email node + all relationships       │
│  • Concept, Claim, Evidence, Mechanism  │
│    nodes from K1 output                 │
│  • Attachment nodes (SHA-256)           │
│                                         │
│  Edges: SENT · TO · CC · BELONGS_TO ·  │
│  REPLY_TO · MENTIONS · HAS_ATTACHMENT  │
│  COMMUNICATES_WITH (count + date)       │
│  PROVES · CONTRADICTS · SUPPORTED_BY   │
│  INVOLVES · EXTENDS                     │
└──────────────┬──────────────────────────┘
               │ Neo4j: Person/Email/Thread/Concept/
               │        Claim/Evidence/Mechanism graph
               ▼
       GraphRAG chat (/api/kg/chat/stream)
       Entity extraction → Neo4j context → Ollama LLM

┌─────────────────────────────────────────┐
│ 4. MCP (Model Context Protocol)         │
│                                         │
│  Spring AI MCP server (SSE, :8080/mcp) │
│  Tools:                                 │
│  • search_ediscovery_emails — ES full-  │
│    text search over the email archive   │
│  • query_k1_knowledge_graph — read-only │
│    Cypher queries against Neo4j         │
│  Prompt:                                │
│  • k1_scp_context — SCP-style context  │
│    with schema + multi-hop Cypher       │
│    traversal patterns                   │
│                                         │
│  Python sidecar FastMCP (:8001/mcp)    │
│  Tools:                                 │
│  • convert_file_to_markdown — converts  │
│    an attachment file to Markdown       │
│  Resources:                             │
│  • file://attachments/hashes/{hash_id} │
└─────────────────────────────────────────┘
```

**Important notes:**
- e-Discovery indexing runs **automatically** when an email changes in MongoDB (Change Stream). Manual re-index: `POST /api/ediscovery/ingest/{mongoEmailId}`.
- Attachment processing and Knowledge Graph building are **not automatic** — both must be triggered manually after PST processing.
- All pipelines are idempotent: re-running does not duplicate data.
- If the Agents-K1 endpoint (`localhost:8000/v1`) is unavailable, KG ingestion continues without claim/entity extraction — nodes are still created without Concept/Claim/Evidence enrichment.
- If Ollama is unavailable, GraphRAG chat returns an error but all other pipelines are unaffected.
- A failed attachment conversion does not abort the pipeline — it is recorded in the `failed_conversions` collection and can be retried via `POST /api/attachments/processing/retry-failed`.

## Prerequisites

- Java 25 (Eclipse Temurin JDK recommended)
- Maven 3.9+
- MongoDB 8 (or Docker)
- Node.js 22+ (for frontend development)
- Docker + Docker Compose (for containerized runs)
- Ollama – runs on the host (`localhost:11434`), **not in Docker**

## Quick start

### With Docker (recommended)

```bash
# Start the full stack
docker compose up -d

# Rebuild and restart
docker compose up -d --build

# View logs
docker compose logs -f

# Stop
docker compose down
```

The app is available at `http://localhost` (frontend + API proxy) and `http://localhost:8080` (backend directly).

**Docker services:**

| Service | Port | Description |
|---|---|---|
| `suliweb-frontend` | 80 | Astro static files + nginx reverse proxy |
| `suliweb-backend` | 8080 | Spring Boot API + MCP server (`/mcp` SSE) |
| `suliweb-mongo` | 27017 | MongoDB 8 (email metadata, auth, progress) |
| `suliweb-elasticsearch` | 9200 | Elasticsearch 9.4.2 (e-Discovery + attachment full-text indexes) |
| `suliweb-neo4j` | 7474/7687 | Neo4j 5.26 Community + APOC (Knowledge Graph) |
| `python-processor` | 8001 | FastAPI sidecar (attachment→markdown + FastMCP at `/mcp`) |
| `mcp-neo4j-cypher` | — | MCP tool for direct Cypher queries against Neo4j (internal) |

**Volumes:**
- `mongodb_data` - MongoDB data
- `mongodb_configdb` - MongoDB config data
- `attachments` - Extracted email attachments (`hashes/` subfolder)
- `pst_source` - PST source files (read-only mount)
- `elasticsearch_data` - Elasticsearch indices
- `neo4j_data` - Neo4j graph data
- `neo4j_logs` - Neo4j logs
- `logs` - Backend application logs

**Bind mounts (local NAS / network drives):**

```yaml
- type: bind
  source: ${DATA_ROOT:-/Volumes}   # configurable via .env, e.g. DATA_ROOT=/mnt/nas
  target: ${DATA_ROOT:-/Volumes}   # search directories are configurable in the admin UI
  read_only: true
```

### Locally

```bash
# Start MongoDB + Elasticsearch + Neo4j (if not using Docker)
mongod
# ES and Neo4j can also run locally, or: docker compose up suliweb-elasticsearch suliweb-neo4j python-processor

# Build and run the backend
mvn clean install
mvn spring-boot:run

# Run the frontend (separate terminal)
cd frontend
npm install
npm run dev
```

## Project structure

```
suliweb/
├── src/main/java/hu/fmdev/backend/
│   ├── BackendApplication.java
│   ├── config/
│   │   ├── SecurityConfig.java          # Fail-secure allowlist + JWT filter chain
│   │   ├── ElasticsearchConfig.java     # ES client + email_archive/attachment_archive indices + Hungarian analyzers
│   │   ├── RagConfig.java               # Ollama WebClient configuration
│   │   ├── JwtConfig.java
│   │   ├── ModelMapperConfig.java
│   │   └── SynologyConfig.java
│   ├── controller/
│   │   ├── AuthController.java
│   │   ├── EmailController.java
│   │   ├── EDiscoveryController.java          # /api/ediscovery – ingest + search
│   │   ├── AttachmentController.java          # /api/attachments – browse, search, download, dedupe
│   │   ├── AttachmentProcessingController.java # /api/attachments/processing – markdown conversion + ES indexing
│   │   ├── KnowledgeGraphController.java      # /api/kg – graph + chat
│   │   ├── AppSettingsController.java         # /api/settings – runtime-tunable ingestion settings
│   │   ├── PipelineController.java            # /api/pipeline – orchestrated multi-stage run
│   │   ├── LogController.java                 # /api/logs – application logs
│   │   ├── UserController.java                # /api/users – user management
│   │   ├── PstFinderController.java
│   │   ├── PstFinderSettingsController.java
│   │   ├── PstProcessorController.java
│   │   ├── SynologyPstFinderController.java
│   │   ├── SynologySettingsController.java
│   │   ├── ProgressController.java
│   │   ├── FileInfoController.java
│   │   ├── FileUploadController.java
│   │   ├── FileController.java
│   │   └── PdfFormFillerController.java
│   ├── service/
│   │   ├── EDiscoveryIngestionService.java     # Bulk-indexes emails into email_archive
│   │   ├── EDiscoverySearchService.java        # ES bool query, highlight, filters
│   │   ├── EDiscoveryChangeStreamListener.java # Auto re-index on MongoDB change
│   │   ├── AttachmentProcessingService.java    # Hash-dedup → markdown conversion → attachment_archive
│   │   ├── KnowledgeGraphIngestionService.java # Neo4j graph build (Virtual Threads + K1 extraction)
│   │   ├── GraphSearchService.java             # Neo4j queries (network, thread, concept)
│   │   ├── PipelineOrchestrationService.java   # Multi-stage orchestrated run
│   │   ├── AppSettingsService.java
│   │   ├── PstProcessorService.java
│   │   ├── PstFinderService.java
│   │   ├── PstFinderSettingsService.java
│   │   ├── SynologyApiClient.java
│   │   ├── SynologyPstFinderService.java
│   │   ├── SynologySettingsService.java
│   │   ├── FileService.java
│   │   ├── FileAccessService.java
│   │   ├── FileUploadService.java
│   │   ├── PdfFormFillerService.java
│   │   ├── ProgressTracker.java
│   │   ├── auth/                               # JWT + user details services
│   │   └── rag/
│   │       ├── TextExtractionService.java      # Apache Tika text extraction + reply-chain stripping
│   │       ├── EntityExtractionService.java    # Agents-K1 extraction (entities + claims + evidence + mechanisms)
│   │       ├── NerExtractor.java               # Interface: extract() + extractK1()
│   │       ├── K1ExtractionOutput.java         # Record: entities, claims, evidence, mechanisms, relations
│   │       └── GraphRagChatService.java        # Neo4j context → Ollama LLM chat + stream
│   ├── domain/
│   │   ├── Email.java
│   │   ├── Attachment.java
│   │   ├── FailedConversion.java               # Dead-letter record (MongoDB)
│   │   ├── AppSettings.java
│   │   ├── PipelineStatus.java / StageProgress.java / StageState.java
│   │   ├── FileInfo.java
│   │   ├── FileEntity.java
│   │   ├── User.java
│   │   ├── Organization.java
│   │   ├── Authority.java
│   │   ├── LogEntry.java
│   │   ├── ProgressState.java
│   │   ├── PstFinderSettings.java / SynologySettings.java
│   │   └── graph/                              # Neo4j @Node classes
│   │       ├── PersonNode.java
│   │       ├── OrganizationNode.java
│   │       ├── EmailNode.java                  # + PROVES / CONTRADICTS → ClaimNode
│   │       ├── ThreadNode.java
│   │       ├── AttachmentNode.java
│   │       ├── ConceptNode.java
│   │       ├── ClaimNode.java                  # K1: text, claimType, confidence
│   │       ├── EvidenceNode.java               # K1: text, evidenceType, sourceRef
│   │       ├── MechanismNode.java              # K1: name, description, mechanismType
│   │       ├── MethodLineageNode.java          # K1: methodName, version, EXTENDS chain
│   │       └── CommunicatesWithRel.java
│   ├── mcp/
│   │   ├── EDiscoveryMcpTools.java             # @Tool: search_ediscovery_emails
│   │   └── KnowledgeGraphMcpTools.java         # @Tool: query_k1_knowledge_graph + k1_scp_context prompt
│   ├── repository/
│   │   ├── FailedConversionRepository.java     # MongoDB dead-letter CRUD
│   │   ├── AttachmentRepository.java
│   │   ├── EmailRepository.java
│   │   └── graph/                              # Neo4j repositories
│   │       ├── PersonNodeRepository.java
│   │       ├── EmailNodeRepository.java
│   │       ├── ThreadNodeRepository.java
│   │       ├── ConceptNodeRepository.java
│   │       ├── ClaimNodeRepository.java
│   │       ├── EvidenceNodeRepository.java
│   │       └── MechanismNodeRepository.java
│   ├── dto/
│   ├── exceptionhandler/
│   ├── logger/
│   └── util/
├── src/test/java/hu/fmdev/backend/
│   ├── security/SecurityFilterChainTest.java       # Security filter chain integration tests
│   └── service/EDiscoveryFailedConversionTest.java # Dead-letter unit tests
├── src/main/resources/
│   ├── application.properties
│   └── application-docker.properties
├── python-processor/                        # FastAPI sidecar
│   ├── main.py                              # /convert-attachment
│   ├── requirements.txt
│   └── Dockerfile
├── frontend/
│   ├── src/
│   │   ├── pages/
│   │   │   ├── index.astro
│   │   │   ├── emails.astro
│   │   │   ├── ediscovery.astro             # e-Discovery search page
│   │   │   ├── attachments.astro            # attachment browser
│   │   │   ├── attachment-processing.astro  # attachment markdown/ES processing page
│   │   │   ├── knowledge-graph.astro        # Knowledge Graph page
│   │   │   ├── rag.astro
│   │   │   ├── files.astro
│   │   │   ├── processing.astro
│   │   │   ├── pipeline.astro
│   │   │   ├── synology.astro
│   │   │   ├── settings.astro
│   │   │   ├── logs.astro                   # Application log viewer
│   │   │   ├── users.astro
│   │   │   └── login.astro
│   │   ├── components/
│   │   │   ├── EDiscoverySearch.tsx         # ES search form + snippet highlighting
│   │   │   ├── AttachmentList.tsx           # Attachment browser
│   │   │   ├── AttachmentProcessing.tsx     # Start/stats/retry UI for attachment processing
│   │   │   ├── KnowledgeGraph.tsx           # Network / thread / concept search + streaming chat
│   │   │   ├── RagChat.tsx
│   │   │   ├── RagSearch.tsx                # GraphRAG status panel + navigation
│   │   │   ├── Dashboard.tsx
│   │   │   ├── EmailBrowser.tsx
│   │   │   ├── FileList.tsx
│   │   │   ├── PstProcessing.tsx
│   │   │   ├── PipelineView.tsx
│   │   │   ├── AppSettings.tsx
│   │   │   ├── SynologyPanel.tsx
│   │   │   ├── Logs.tsx                     # Log viewer (filter, sort, auto-refresh)
│   │   │   └── UserManagement.tsx
│   │   ├── layouts/Layout.astro
│   │   ├── styles/global.css
│   │   └── lib/api.ts
│   ├── Dockerfile
│   ├── nginx.conf
│   ├── package.json
│   └── astro.config.mjs
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── Caddyfile
├── .env.example
├── .dockerignore
└── CLAUDE.md
```

## API endpoints

### Authentication
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/auth/register` | POST | Register a new user — `ROLE_ADMIN` |
| `/api/auth/login` | POST | Log in → access + refresh token |
| `/api/auth/refresh` | POST | Refresh the access token |
| `/api/auth/me` | GET | Current authenticated user |

### Emails
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/emails` | GET | All emails |
| `/api/emails/count` | GET | Email count |
| `/api/emails/search` | GET | Search (subject, sender, recipient, folder, importance) |
| `/api/emails/{id}` | GET | Get email by ID |
| `/api/emails` | POST | Create email |
| `/api/emails/{id}` | PUT | Update email |
| `/api/emails/{id}` | DELETE | Delete email |

### PST processing
| Endpoint | Method | Description |
|---------|---------|--------|
| `/find/pst` | GET | Search for PST files → save to DB — `ROLE_ADMIN` |
| `/find/synology` | GET | Search for PST files on Synology NAS — `ROLE_ADMIN` |
| `/find/synologyToDb` | GET | Synology PST files → save to DB — `ROLE_ADMIN` |
| `/api/synology/settings` | GET / PUT | Synology connection settings |
| `/api/pst-finder/settings` | GET / PUT | PST finder settings (PUT — `ROLE_ADMIN`) |
| `/pst/processFromDb` | POST | Process PST files from the database — `ROLE_ADMIN` |
| `/pst/processFromFile`, `/processFromTxt`, `/processSelected` | POST | Alternative processing entry points — `ROLE_ADMIN` |
| `/pst/saveAttachmentsFromDb` | POST | Re-save attachments for already-processed PSTs — `ROLE_ADMIN` |
| `/pst/pause` / `/pst/resume` | POST | Pause / resume processing — `ROLE_ADMIN` |

### e-Discovery (Elasticsearch)
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/ediscovery/ingest` | POST | Index all emails into Elasticsearch — `ROLE_ADMIN` |
| `/api/ediscovery/ingest/{id}` | POST | Re-index a single email — `ROLE_ADMIN` |
| `/api/ediscovery/retry-failed` | POST | Retry all failed conversions — `ROLE_ADMIN` |
| `/api/ediscovery/retry-failed/{id}` | POST | Retry a single `FailedConversion` record — `ROLE_ADMIN` |
| `/api/ediscovery/failed` | GET | List unresolved conversion failures |
| `/api/ediscovery/search` | GET | Full-text search (`q`, `topK`, `sender`, `pstOwner`, `pstFileName`, `dateFrom`, `dateTo`) |
| `/api/ediscovery/status` | GET | Indexing status + stats (incl. `failedCount`) |

### Attachment processing (Elasticsearch, separate from e-Discovery)
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/attachments/processing/start` | POST | Start markdown conversion + ES indexing of all attachments — `ROLE_ADMIN` |
| `/api/attachments/processing/status` | GET | Processing status + stats (incl. `failedCount`) |
| `/api/attachments/processing/failed` | GET | List unresolved conversion failures |
| `/api/attachments/processing/retry-failed` | POST | Retry all failed conversions — `ROLE_ADMIN` |
| `/api/attachments/processing/retry-failed/{id}` | POST | Retry a single failed conversion — `ROLE_ADMIN` |

### Knowledge Graph (Neo4j)
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/kg/ingest` | POST | Start graph build (NER extraction + Neo4j) — `ROLE_ADMIN` |
| `/api/kg/reingest-concepts` | POST | Rebuild Concept nodes only — `ROLE_ADMIN` |
| `/api/kg/status` | GET | Graph build status + stats |
| `/api/kg/graph-stats` | GET | Aggregate graph statistics |
| `/api/kg/persons/{email}/network` | GET | List of communication partners |
| `/api/kg/thread/{threadId}` | GET | Thread emails in chronological order |
| `/api/kg/concept/{name}` | GET | Emails near a concept (`topK`) |
| `/api/kg/chat` | POST | GraphRAG chat (Neo4j context + Ollama LLM) |
| `/api/kg/chat/stream` | POST | GraphRAG chat, streaming (SSE) |
| `/api/kg/models` | GET | Available Ollama models |

### Attachments (browse / manage)
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/attachments` | GET | List attachments |
| `/api/attachments/search` | GET | Search (filename, extension, size, sender, date) |
| `/api/attachments/count` | GET | Attachment count |
| `/api/attachments/duplicate-stats` | GET | Duplicate statistics |
| `/api/attachments/deduplicate` | POST | Delete duplicate DB records |
| `/api/attachments/email/{emailId}` | GET | Attachments for an email |
| `/api/attachments/{id}/download` | GET | Download an attachment |

### Pipeline orchestration
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/pipeline/start` | POST | Start an orchestrated multi-stage run — `ROLE_ADMIN` |
| `/api/pipeline/status` | GET | Status of all pipeline stages |

### Settings
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/settings` | GET | Effective runtime ingestion settings |
| `/api/settings` | PUT | Update runtime ingestion settings — `ROLE_ADMIN` |

### Users
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/users` | GET | List users — `ROLE_ADMIN` |
| `/api/users/{id}` | GET | User details — `ROLE_ADMIN` |
| `/api/users` | POST | Create a user — `ROLE_ADMIN` |
| `/api/users/{id}` | PUT | Update a user — `ROLE_ADMIN` |
| `/api/users/{id}` | DELETE | Delete a user — `ROLE_ADMIN` |
| `/api/users/authorities` | GET | List available authorities — `ROLE_ADMIN` |
| `/api/users/{id}/files` | GET / PUT | A user's accessible PST files — `ROLE_ADMIN` |

### MCP (Model Context Protocol)
| Endpoint | Transport | Description |
|---------|---------|--------|
| `/mcp` (Spring Boot :8080) | SSE | MCP server: tools (`search_ediscovery_emails`, `query_k1_knowledge_graph`) + prompt (`k1_scp_context`) |
| `/mcp` (Python sidecar :8001) | SSE | FastMCP server: tool (`convert_file_to_markdown`) + resource (`file://attachments/hashes/{hash_id}`) |

### Python sidecar (`/` on :8001)
| Endpoint | Method | Description |
|---------|---------|--------|
| `/health` | GET | Sidecar health check |
| `/convert-attachment` | POST | Convert a file to Markdown via markitdown |
| `/strip-reply` | POST | Strip reply chains from email bodies |
| `/parse-document` | POST | Parse a Markdown document into K1ParsedDocument (text_blocks, tables, entities, multimodal_evidence, citations, relations) |

### Other
| Endpoint | Method | Description |
|---------|---------|--------|
| `/api/progress` | GET | Processing status (public) |
| `/actuator/health` | GET | Application health check (public) |
| `/api/logs` | GET | Application logs (`level`, `from`, `to`, `sort`, `limit` filters) |
| `/api/file-infos` | GET | PST file information |
| `/api/file-infos/counts` | GET | PST counters (total/pending/processed) |
| `/api/file-infos/duplicates` | GET | Groups of identical-content PST files |
| `/api/file-infos/compute-hashes` | POST | Compute SHA-256 hashes |
| `/api/file-infos/deduplicate` | POST | Delete duplicate PST records |
| `/api/files/upload` | POST | File upload (with ZIP encryption) |
| `/pdf/fill` | POST | Fill a PDF form — `ROLE_ADMIN` |

## Configuration

Key settings in `application.properties`:

```properties
# MongoDB connection
spring.data.mongodb.uri=mongodb://localhost:27017/emails?directConnection=true

# Attachment storage location
attachments.directory=/app/attachments

# Elasticsearch (e-Discovery + attachment processing)
ediscovery.es.url=http://localhost:9200
ediscovery.python.url=http://localhost:8001
ediscovery.ingestion.batch-size=200
ediscovery.ingestion.bulk-size=200
attachment.processing.bulk-size=200
attachment.processing.python-concurrency=24

# Neo4j Knowledge Graph
spring.neo4j.uri=${NEO4J_URI:bolt://localhost:7687}
spring.neo4j.authentication.username=neo4j
spring.neo4j.authentication.password=${NEO4J_PASSWORD}

# Knowledge Graph ingestion tuning (defaults; can be overridden at runtime via /api/settings)
kg.ingestion.batch-size=100
kg.ingestion.max-concurrent-writes=4

# Ollama (runs on the host, not in Docker — GraphRAG chat only)
rag.ollama-base-url=http://localhost:11434
rag.chat-model=llama3.2
rag.chat-context-top-k=8

# Agents-K1 4B (OpenAI-compatible endpoint — runs on the host, not in Docker)
# Powers entity extraction, claim detection, evidence linking in the KG pipeline
spring.ai.openai.base-url=http://localhost:8000/v1
spring.ai.openai.api-key=local
spring.ai.openai.chat.options.model=agents-k1-4b

# MCP server
spring.ai.mcp.server.name=suliweb-mcp
spring.ai.mcp.server.sse-message-endpoint=/mcp/messages
spring.ai.mcp.server.capabilities.tool=true

# Synology NAS (optional)
synology.host=
synology.username=
synology.local-mount-prefix=/mnt/nas
synology.search-extensions=pst,ost

# Actuator (only health is public)
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
```

## Tech stack

**Backend:**
- Java 25 (Eclipse Temurin) + Spring Boot 4.0 (Spring Framework 7, Jakarta EE 11)
- Spring Web, WebFlux, Data MongoDB, Data Neo4j, Security
- java-libpst – PST file processing
- Apache Tika – Text extraction (PDF, DOC, XLS, PPT, etc.)
- Elasticsearch Java API Client 9.4.2 – e-Discovery and attachment full-text indexing
- iText PDF 8 + Apache PDFBox 3 – PDF handling
- zip4j – ZIP compression/encryption
- Lombok, ModelMapper

**Python sidecar (`python-processor/`):**
- FastAPI + uvicorn – `/convert-attachment` (markitdown), `/strip-reply`, `/parse-document`
- FastMCP (`mcp[cli]>=1.9.0`) – MCP SSE server mounted at `/mcp` (tool + resource)
- Soft-fail semantics: errors return an empty result rather than aborting; failures are logged for retry

**Frontend:**
- Astro 5 – Static site generation (Vite 6)
- React 19.2 – Interactive components
- Tailwind CSS 4 – Styling

**Databases and external services:**
- MongoDB 8 – Email metadata, auth, progress tracking
- Elasticsearch 9.4.2 – e-Discovery (`email_archive`) and attachment (`attachment_archive`) full-text indices; Hungarian text analysis (`hungarian_stemmed` Snowball stemmer + `hungarian_ascii` asciifolding analyzer, `.ascii` subfield for accent-insensitive search)
- Neo4j 5.26 Community + APOC – Knowledge Graph (Person, Thread, Concept, Claim, Evidence, Mechanism, MethodLineage, Attachment nodes)
- Spring AI 2.0.0-M6 – MCP server (`spring-ai-starter-mcp-server-webmvc`) + OpenAI-compat. client (`spring-ai-starter-model-openai`)
- Agents-K1 4B – structured knowledge extraction (entities, claims, evidence, mechanisms, relations) via OpenAI-compatible endpoint (runs on the host, `localhost:8000/v1`)
- Ollama – GraphRAG LLM chat (runs on the host, `host.docker.internal:11434`)

**Infrastructure:**
- Docker + Docker Compose – 6 services, multi-stage builds, health checks
- Caddy – TLS-terminating reverse proxy in front of the stack (HTTPS, health-checked upstreams)
- nginx – Frontend asset serving + API reverse proxy inside the frontend container
- Java 25 LTS (Eclipse Temurin)

**CI/DevSecOps (`.github/workflows/`):**
- JaCoCo – code coverage
- Trivy – dependency CVE scan (HIGH/CRITICAL → build fail) + container scan + SBOM (CycloneDX)
- Gitleaks – secret-leak detection
- SpotBugs – SAST static analysis (SARIF → GitHub Security tab)
- OWASP Dependency-Check – SCA vulnerability analysis (NVD database)
- Hadolint – Dockerfile lint
- CodeQL – SAST for Java and JavaScript/TypeScript
- Dependency Review – license/vulnerability gate on pull requests

## Development

```bash
# Run tests
mvn test

# Run a single test
mvn test -Dtest=TextExtractionServiceTest

# Frontend dev server
cd frontend && npm run dev

# Frontend build
cd frontend && npm run build

# Start only the databases (for local development)
docker compose up -d suliweb-mongo suliweb-elasticsearch suliweb-neo4j python-processor
```

## Docker Compose services

| Service | Restart | Health check | Description |
|---|---|---|---|
| `suliweb-frontend` | unless-stopped | backend healthy | Astro 5 → nginx (:80) |
| `suliweb-backend` | unless-stopped | `/actuator/health` curl | Spring Boot (:8080) + MCP SSE |
| `suliweb-mongo` | unless-stopped | `mongosh ping` | MongoDB 8 (:27017) |
| `suliweb-elasticsearch` | unless-stopped | `/_cluster/health` curl | Elasticsearch 9.4.2 (:9200) |
| `suliweb-neo4j` | unless-stopped | `:7474` wget | Neo4j 5.26 Community (:7474/:7687) |
| `python-processor` | unless-stopped | `/health` curl | FastAPI sidecar (:8001) + FastMCP SSE |
| `mcp-neo4j-cypher` | unless-stopped | — | MCP Cypher tool (internal, no port exposed) |

**Environment variables** – read from a `.env` file (see `.env.example`):

```bash
cp .env.example .env
# Edit: NEO4J_PASSWORD, JWT_SECRET, Synology credentials, etc.
```

## Security

Fail-secure explicit allowlist — every endpoint is denied by default except the ones below:

| Path | Access |
|---------|-----------|
| `POST /api/auth/login`, `POST /api/auth/refresh` | Public |
| `GET /api/progress`, `GET /actuator/health` | Public |
| `/pst/**`, `/find/**`, `/pdf/**` | `ROLE_ADMIN` |
| `POST /api/ediscovery/ingest`, `/retry-failed` | `ROLE_ADMIN` |
| `POST /api/kg/ingest` | `ROLE_ADMIN` |
| `POST /api/auth/register`, `POST /api/users`, `DELETE /api/users/**` | `ROLE_ADMIN` |
| Every other `/api/**` route | Authenticated user (`authenticated`) |

`@EnableMethodSecurity` + `@PreAuthorize("hasAuthority('ROLE_ADMIN')")` annotations on the relevant controllers provide a second layer of defense.

## License

Private project.
