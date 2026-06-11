# SuliWeb - PST Email Processor

Spring Boot 4.0 alkalmazás Microsoft Outlook PST fájlok feldolgozásához. PST fájlokat keres, emaileket és csatolmányokat kinyeri belőlük, MongoDB-ben tárolja a metaadatokat. Elasticsearch 8 alapú e-Discovery pipeline-nal, Neo4j 5.26 Knowledge Graph-fal, Python FastAPI sidecar-ral, JWT-alapú autentikációval és Astro 6 + React 19 frontend dashboarddal rendelkezik.

## Funkciók

- **PST fájlkeresés** - Helyi könyvtárakban vagy Synology NAS-on keres PST fájlokat
- **Email kinyerés** - PST fájlokból emaileket és csatolmányokat nyer ki párhuzamos feldolgozással (Virtual Threads)
- **PST fájl státuszok** - `New`, `Processed`, `Modified`, `Invalid`, `Missing`
- **PST duplikátum-szűrés** - SHA-256 hash alapú deduplikáció
- **Csatolmány deduplikáció** - Hash alapú fájltárolás; azonos tartalmú csatolmány csak egyszer foglal helyet lemezen
- **Szüneteltetés/folytatás** - Hosszú feldolgozási műveletek vezérlése
- **e-Discovery pipeline** - Elasticsearch 8 teljes szöveges keresés; reply chain levágás (Python sidecar); csatolmány → Markdown konverzió; Message-ID alapú dedup; magyar szövegelemzés (asciifolding + stopword)
- **Knowledge Graph** - Neo4j 5.26 kommunikációs gráf (Person, Thread, Concept csúcsok); NER entitáskinyerés Ollama-val; szál bejárás; fogalom közelség alapú keresés
- **GraphRAG chat** - Entitáskinyerés → Neo4j kontextus → Ollama LLM válasz (streaming + nem-streaming)
- **Synology integráció** - NAS Universal Search API-n keresztül keres PST fájlokat párhuzamosan
- **PDF űrlap kitöltés** - iText alapú PDF form filler
- **JWT autentikáció** - Spring Security 7, access token (8 óra) + refresh token (7 nap), BCrypt
- **Modern dashboard** - Astro 6 + React 19 + Tailwind CSS 4 reszponzív frontend

## Architektúra

```
┌──────────────────────────────────────────────────────────────────┐
│                       Frontend (Astro 6)                          │
│   Dashboard │ Emails │ e-Discovery │ Knowledge Graph │ RAG Chat  │
│        :80 (nginx) → proxy → :8080  |  :4321 (dev)               │
├──────────────────────────────────────────────────────────────────┤
│                  Spring Boot Backend (:8080)                       │
│  PST feldolgozás → MongoDB (metadata, auth, progress)            │
│  e-Discovery:  Elasticsearch 8  (full-text, dedup, highlight)    │
│  Knowledge Graph: Neo4j 5.26 (persons, threads, concepts, NER)   │
│  GraphRAG: EntityExtraction → Neo4j kontextus → Ollama LLM chat  │
├──────────────────────────────────────────────────────────────────┤
│  MongoDB (:27017)     Elasticsearch (:9200)     Neo4j (:7474)    │
│  Python sidecar (:8001, strip-reply + markitdown)                │
│  Ollama (:11434, Mac host – NER + GraphRAG chat)                 │
└──────────────────────────────────────────────────────────────────┘
```

## Feldolgozási folyamat

```
PST fájl(ok)
    │
    ▼ POST /pst/processFromDb  (/feldolgozás oldal)
┌─────────────────────────────────────────┐
│ 1. PST BEOLVASÁS                        │
│  • java-libpst, virtuális szálak        │
│  • Dedup: SHA-256(pstFileName+msgId)    │
│  • Csatolmány dedup: hash-alapú tárolás │
└──────────────┬──────────────────────────┘
               │ MongoDB: emails + attachments
               ▼
┌─────────────────────────────────────────┐
│ 2. E-DISCOVERY INDEXELÉS                │
│  Trigger: POST /api/ediscovery/ingest   │
│  Auto-trigger: MongoDB Change Stream    │
│  (insert / update / delete → auto-sync) │
│                                         │
│  Emailenként (50/batch, max 8 párhuzamos│
│  Python hívás):                         │
│  a) Reply chain levágás (email-reply-   │
│     parser via Python sidecar)          │
│  b) Csatolmány → Markdown (markitdown   │
│     via Python sidecar; SHA-256 dedup)  │
│                                         │
│  Idempotens bulk create → ES-be;        │
│  409 conflict = már indexelve, skip     │
└──────────────┬──────────────────────────┘
               │ Elasticsearch: email_archive index
               ▼
┌─────────────────────────────────────────┐
│ 3. KNOWLEDGE GRAPH ÉPÍTÉS               │
│  Trigger: POST /api/kg/ingest           │
│                                         │
│  Emailenként (virtuális szálak,         │
│  deadlock esetén 3× retry):             │
│  • Person csúcsok — email cím alapján   │
│    merge (sender + recipients + CC)     │
│  • Thread csúcs — conversationId merge  │
│  • Email csúcs + összes összekötés      │
│  • Concept csúcsok — NER entitások      │
│    (Ollama llama3.2: PERSON/ORG/        │
│    TOPIC/LOCATION)                      │
│  • Attachment csúcsok (SHA-256)         │
│                                         │
│  Élek: SENT · TO · CC · BELONGS_TO ·   │
│  REPLY_TO · MENTIONS · HAS_ATTACHMENT  │
│  COMMUNICATES_WITH (számláló + dátum)  │
└──────────────┬──────────────────────────┘
               │ Neo4j: Person/Email/Thread/Concept gráf
               ▼
       GraphRAG chat (/api/kg/chat/stream)
       Entitáskinyerés → Neo4j kontextus → Ollama LLM
```

**Fontos tudnivalók:**
- Az e-Discovery indexelés **automatikusan** lefut, ha MongoDB-ben email változik (Change Stream). Manuális újraindexelés: `POST /api/ediscovery/ingest/{mongoEmailId}`.
- A Knowledge Graph építés **nem automatikus** — PST feldolgozás után kézzel kell indítani.
- Mindhárom lépés idempotens: újrafuttatás nem duplikál adatot.
- Ha Ollama nem elérhető, a KG ingestion NER nélkül, Concept csúcsok nélkül fut le.

## Előfeltételek

- Java 25 (Eclipse Temurin JDK ajánlott)
- Maven 3.9+
- MongoDB 7+ (vagy Docker)
- Node.js 22+ (frontend fejlesztéshez)
- Docker + Docker Compose (konténerizált futtatáshoz)
- Ollama – a gazdagépen fut (`localhost:11434`), **nem Docker-ben**

## Gyors indítás

### Docker-rel (ajánlott)

```bash
# Teljes stack indítása
docker compose up -d

# Build-del újraindítás
docker compose up -d --build

# Logok megtekintése
docker compose logs -f

# Leállítás
docker compose down
```

Az alkalmazás elérhető: `http://localhost` (frontend + API proxy), `http://localhost:8080` (backend direkt)

**Docker szolgáltatások:**

| Szolgáltatás | Port | Leírás |
|---|---|---|
| `frontend` | 80 | Astro statikus fájlok + nginx reverse proxy |
| `backend` | 8080 | Spring Boot API |
| `mongo` | 27017 | MongoDB 7 (email metadata, auth, progress) |
| `suliweb-elasticsearch` | 9200 | Elasticsearch 8.17.0 (e-Discovery full-text index) |
| `suliweb-neo4j` | 7474/7687 | Neo4j 5.26 Community + APOC (Knowledge Graph) |
| `python-processor` | 8001 | FastAPI sidecar (reply-strip + markitdown konverzió) |

**Volumes:**
- `mongodb_data` - MongoDB adatok
- `attachments` - Kinyert email csatolmányok (`hashes/` almappa)
- `pst_source` - PST forrásfájlok (read-only mount)
- `elasticsearch_data` - Elasticsearch index
- `neo4j_data` - Neo4j gráfadatok
- `neo4j_logs` - Neo4j logok
- `logs` - Backend alkalmazás logok

**Bind mountok (helyi NAS / hálózati meghajtók):**

```yaml
- type: bind
  source: /Volumes/archiv   # gazdagépen lévő tényleges elérési út
  target: /Volumes/archiv   # ugyanaz mint az adatbázisban tárolt path
  read_only: true
```

### Lokálisan

```bash
# MongoDB + Elasticsearch + Neo4j indítása (ha nincs Docker)
mongod
# ES és Neo4j lokálisan is futtatható, vagy docker compose -f docker-compose.yml up suliweb-elasticsearch suliweb-neo4j python-processor

# Backend build és indítás
mvn clean install
mvn spring-boot:run

# Frontend indítása (külön terminálban)
cd frontend
npm install
npm run dev
```

## Projekt struktúra

```
suliweb/
├── src/main/java/hu/fmdev/backend/
│   ├── BackendApplication.java
│   ├── config/
│   │   ├── SecurityConfig.java
│   │   ├── ElasticsearchConfig.java     # ES kliens + email_archive index létrehozás
│   │   ├── RagConfig.java               # Ollama WebClient konfiguráció
│   │   ├── ModelMapperConfig.java
│   │   └── SynologyConfig.java
│   ├── controller/
│   │   ├── EmailController.java
│   │   ├── EDiscoveryController.java    # /api/ediscovery – ingest + keresés
│   │   ├── KnowledgeGraphController.java # /api/kg – gráf + chat
│   │   ├── RagController.java           # /api/rag – GraphRAG chat (backward compat)
│   │   ├── PstFinderController.java
│   │   ├── PstProcessorController.java
│   │   ├── SynologyPstFinderController.java
│   │   ├── ProgressController.java
│   │   ├── FileInfoController.java
│   │   ├── FileUploadController.java
│   │   ├── FileController.java
│   │   └── PdfFormFillerController.java
│   ├── service/
│   │   ├── EDiscoveryIngestionService.java  # ES bulk indexelés, reply-strip, att konverzió
│   │   ├── EDiscoverySearchService.java     # ES bool query, highlight, szűrők
│   │   ├── KnowledgeGraphIngestionService.java # Neo4j gráf építés (Virtual Threads)
│   │   ├── GraphSearchService.java          # Neo4j lekérdezések (hálózat, szál, fogalom)
│   │   ├── PstProcessorService.java
│   │   ├── PstFinderService.java
│   │   ├── SynologyApiClient.java
│   │   ├── SynologyPstFinderService.java
│   │   ├── FileService.java
│   │   ├── FileUploadService.java
│   │   ├── PdfFormFillerService.java
│   │   ├── ProgressTracker.java
│   │   └── rag/
│   │       ├── TextExtractionService.java   # Apache Tika szövegkinyerés
│   │       ├── EntityExtractionService.java # Ollama NER (PERSON/ORG/TOPIC/LOCATION)
│   │       └── GraphRagChatService.java     # Neo4j kontextus → Ollama LLM chat + stream
│   ├── domain/
│   │   ├── Email.java
│   │   ├── FileInfo.java
│   │   ├── FileEntity.java
│   │   ├── User.java
│   │   ├── Organization.java
│   │   ├── Authority.java
│   │   ├── LogEntry.java
│   │   ├── ProgressState.java
│   │   └── graph/                           # Neo4j @Node osztályok
│   │       ├── PersonNode.java
│   │       ├── OrganizationNode.java
│   │       ├── EmailNode.java
│   │       ├── ThreadNode.java
│   │       ├── AttachmentNode.java
│   │       └── ConceptNode.java
│   ├── repository/
│   │   └── graph/                           # Neo4j repository-k
│   │       ├── PersonNodeRepository.java
│   │       ├── EmailNodeRepository.java
│   │       ├── ThreadNodeRepository.java
│   │       └── ConceptNodeRepository.java
│   ├── dto/
│   ├── exceptionhandler/
│   ├── logger/
│   └── util/
├── src/test/java/hu/fmdev/backend/
├── src/main/resources/
│   ├── application.properties
│   └── application-docker.properties
├── python-processor/                        # FastAPI sidecar
│   ├── main.py                              # /strip-reply + /convert-attachment
│   ├── requirements.txt
│   └── Dockerfile
├── frontend/
│   ├── src/
│   │   ├── pages/
│   │   │   ├── index.astro
│   │   │   ├── emails.astro
│   │   │   ├── ediscovery.astro             # e-Discovery keresőoldal
│   │   │   ├── knowledge-graph.astro        # Knowledge Graph oldal
│   │   │   ├── rag.astro
│   │   │   ├── files.astro
│   │   │   ├── attachments.astro
│   │   │   ├── processing.astro
│   │   │   ├── synology.astro
│   │   │   └── users.astro
│   │   ├── components/
│   │   │   ├── EDiscoverySearch.tsx         # ES keresőűrlap + snippet highlight
│   │   │   ├── KnowledgeGraph.tsx           # Hálózat / szál / fogalom keresés + streaming chat
│   │   │   ├── RagChat.tsx
│   │   │   ├── RagSearch.tsx                # GraphRAG állapotpanel + navigáció
│   │   │   ├── Dashboard.tsx
│   │   │   ├── EmailBrowser.tsx
│   │   │   ├── FileList.tsx
│   │   │   ├── AttachmentList.tsx
│   │   │   ├── PstProcessing.tsx
│   │   │   ├── SynologyPanel.tsx
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
├── .env.example
├── .dockerignore
└── CLAUDE.md
```

## API végpontok

### Autentikáció
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/auth/register` | POST | Új felhasználó regisztrálása |
| `/api/auth/login` | POST | Bejelentkezés → access + refresh token |
| `/api/auth/refresh` | POST | Access token megújítása refresh tokennel |
| `/api/auth/me` | GET | Bejelentkezett felhasználó adatai |

### Emailek
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/emails` | GET | Összes email |
| `/api/emails/search` | GET | Keresés (subject, sender, recipient, folder, importance) |
| `/api/emails/{id}` | GET | Email ID alapján |
| `/api/emails/{id}` | PUT | Email módosítása |
| `/api/emails/{id}` | DELETE | Email törlése |

### PST feldolgozás
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/find/pst` | GET | PST fájlok keresése → adatbázisba mentés |
| `/find/synology` | GET | PST keresés Synology NAS-on |
| `/find/synologyToDb` | GET | Synology PST fájlok → adatbázisba mentés |
| `/pst/processFromDb` | POST | PST fájlok feldolgozása adatbázisból |
| `/pst/pause` | POST | Feldolgozás szüneteltetése |
| `/pst/resume` | POST | Feldolgozás folytatása |
| `/pst/processSelected` | POST | Kijelölt PST fájlok feldolgozása |

### e-Discovery (Elasticsearch)
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/ediscovery/ingest` | POST | Összes email ES indexelése (reply-strip + csatolmány konverzió) |
| `/api/ediscovery/ingest/{id}` | POST | Egy email újraindexelése |
| `/api/ediscovery/search` | GET | Teljes szöveges keresés (`q`, `topK`, `sender`, `pstOwner`, `pstFileName`, `dateFrom`, `dateTo`) |
| `/api/ediscovery/status` | GET | Indexelés állapota + statisztikák |

### Knowledge Graph (Neo4j)
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/kg/ingest` | POST | Gráf építés indítása (NER entitáskinyerés + Neo4j) |
| `/api/kg/status` | GET | Gráf építés állapota + statisztikák |
| `/api/kg/persons/{email}/network` | GET | Kommunikációs partnerek listája |
| `/api/kg/thread/{threadId}` | GET | Szál emailjei időrendben |
| `/api/kg/concept/{name}` | GET | Fogalom közelségű emailek (`topK`) |
| `/api/kg/chat` | POST | GraphRAG chat (Neo4j kontextus + Ollama LLM) |
| `/api/kg/chat/stream` | POST | GraphRAG chat streaming (SSE) |

### RAG / GraphRAG chat
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/rag/chat` | POST | GraphRAG chat (backward kompatibilis alias) |
| `/api/rag/chat/stream` | POST | GraphRAG chat streaming (SSE) |
| `/api/rag/health` | GET | Ollama + KG ingestion állapota |
| `/api/rag/models` | GET | Elérhető Ollama modellek |

### Csatolmányok
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/attachments` | GET | Csatolmányok listája |
| `/api/attachments/search` | GET | Keresés (filename, extension, size, sender, dátum) |
| `/api/attachments/count` | GET | Csatolmányok száma |
| `/api/attachments/duplicate-stats` | GET | Duplikátum statisztika |
| `/api/attachments/deduplicate` | POST | Duplikált DB rekordok törlése |
| `/api/attachments/email/{emailId}` | GET | Email csatolmányai |
| `/api/attachments/{id}/download` | GET | Csatolmány letöltése |

### Egyéb
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/progress` | GET | Feldolgozás állapota |
| `/api/file-infos` | GET | PST fájl információk |
| `/api/file-infos/counts` | GET | PST számlálók (total/pending/processed) |
| `/api/file-infos/duplicates` | GET | Azonos tartalmú PST fájlok csoportjai |
| `/api/file-infos/compute-hashes` | POST | SHA-256 hash kiszámítása |
| `/api/file-infos/deduplicate` | POST | Duplikált PST rekordok törlése |
| `/api/files/upload` | POST | Fájl feltöltés (ZIP titkosítással) |
| `/pdf/fill` | POST | PDF űrlap kitöltése |

## Konfiguráció

Az `application.properties` fő beállításai:

```properties
# MongoDB kapcsolat
spring.data.mongodb.uri=mongodb://localhost:27017/emails?directConnection=true

# Csatolmányok mentési helye
attachments.directory=/app/attachments

# Elasticsearch (e-Discovery)
ediscovery.es.url=http://localhost:9200
ediscovery.python.url=http://localhost:8001

# Neo4j Knowledge Graph
spring.neo4j.uri=bolt://localhost:7687
spring.neo4j.authentication.username=neo4j
spring.neo4j.authentication.password=suliweb

# Ollama (a gazdagépen fut, nem Dockerben)
rag.ollama-base-url=http://localhost:11434
rag.chat-model=llama3.1:8b
rag.chat-context-top-k=8

# Synology NAS (opcionális)
synology.host=
synology.username=
synology.local-mount-prefix=/mnt/nas
synology.search-extensions=pst,ost
```

## Technológiai stack

**Backend:**
- Java 25 (Eclipse Temurin) + Spring Boot 4.0 (Spring Framework 7, Jakarta EE 11)
- Spring Web, WebFlux, Data MongoDB, Data Neo4j, Security
- java-libpst – PST fájl feldolgozás
- Apache Tika – Szövegkinyerés (PDF, DOC, XLS, PPT, stb.)
- Elasticsearch Java API Client 8.17.0 – e-Discovery full-text indexelés
- iText PDF 8 + Apache PDFBox 3 – PDF kezelés
- zip4j – ZIP tömörítés/titkosítás
- Lombok, ModelMapper

**Python sidecar (`python-processor/`):**
- FastAPI + uvicorn – `/strip-reply` (email-reply-parser), `/convert-attachment` (markitdown)
- Soft-fail szemantika: hiba esetén üres string, nem abort; csatolmány konverzióra nincs karakterkorlát

**Frontend:**
- Astro 6 – Statikus oldal generálás (Vite 7)
- React 19.2 – Interaktív komponensek
- Tailwind CSS 4 – Stílusok

**Adatbázisok és külső szolgáltatások:**
- MongoDB 7 – Email metaadatok, auth, progress tracking
- Elasticsearch 8.17.0 – e-Discovery full-text index (magyar szövegelemzés: asciifolding + hungarian_stop)
- Neo4j 5.26 Community + APOC – Knowledge Graph (Person, Thread, Concept, Attachment csúcsok)
- Ollama – NER entitáskinyerés és GraphRAG LLM chat (gazdagépen fut, `host.docker.internal:11434`)

**Infrastruktúra:**
- Docker + Docker Compose – 6 szolgáltatás, multi-stage build, health check-ek
- nginx – Frontend szervírozás + API reverse proxy
- Java 25 LTS (Eclipse Temurin)

## Fejlesztés

```bash
# Tesztek futtatása
mvn test

# Egy adott teszt futtatása
mvn test -Dtest=TextExtractionServiceTest

# Frontend fejlesztői szerver
cd frontend && npm run dev

# Frontend build
cd frontend && npm run build

# Csak az adatbázisok indítása (lokális fejlesztéshez)
docker compose up -d mongo suliweb-elasticsearch suliweb-neo4j python-processor
```

## Docker Compose szolgáltatások

| Szolgáltatás | Restart | Health check | Leírás |
|---|---|---|---|
| `frontend` | unless-stopped | backend healthy | Astro 6 → nginx (:80) |
| `backend` | unless-stopped | `/api/progress` curl | Spring Boot (:8080) |
| `mongo` | unless-stopped | `mongosh ping` | MongoDB (:27017) |
| `suliweb-elasticsearch` | unless-stopped | `/_cluster/health` curl | Elasticsearch 8.17.0 (:9200) |
| `suliweb-neo4j` | unless-stopped | `:7474` wget | Neo4j 5.26 Community (:7474/:7687) |
| `python-processor` | unless-stopped | `/health` curl | FastAPI sidecar (:8001) |

**Környezeti változók** – `.env` fájlból olvasva (lásd `.env.example`):

```bash
cp .env.example .env
# Szerkesztés: NEO4J_PASSWORD, JWT_SECRET, Synology adatok stb.
```

## Védett végpontok

| Útvonal | Hozzáférés |
|---------|-----------|
| `/api/auth/**` | Publikus |
| `/api/rag/**`, `/api/progress` | Publikus |
| `/api/**` | JWT szükséges |
| `/find/**`, `/pst/**`, `/pdf/**` | Publikus (fejlesztési állapot) |

## Licenc

Privát projekt.
