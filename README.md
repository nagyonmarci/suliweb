# SuliWeb - PST Email Processor

Spring Boot 4.0 alkalmazás Microsoft Outlook PST fájlok feldolgozásához. PST fájlokat keres, emaileket és csatolmányokat kinyeri belőlük, majd MongoDB-ben tárolja az adatokat. Synology NAS integrációval, JWT-alapú autentikációval, RAG szemantikus kereséssel és Astro 6 + React 19 frontend dashboarddal rendelkezik.

## Funkciók

- **PST fájlkeresés** - Helyi könyvtárakban vagy Synology NAS-on keres PST fájlokat
- **Email kinyerés** - PST fájlokból emaileket és csatolmányokat nyer ki párhuzamos feldolgozással (10 szál)
- **PST fájl státuszok** - `New`, `Processed`, `Modified`, `Invalid` (érvénytelen PST formátum), `Missing` (fájl nem elérhető)
- **PST duplikátum-szűrés** - SHA-256 hash alapú deduplikáció: azonos tartalmú fájlok csak egyszer kerülnek feldolgozásra; az adatbázisban külön gomb törli a duplikált rekordokat
- **Csatolmány deduplikáció** - Hash alapú fájltárolás: azonos tartalmú csatolmány csak egyszer foglal helyet lemezen, de minden e-mailből hivatkozható; a duplikált DB rekordok törölhetők
- **Szüneteltetés/folytatás** - Hosszú feldolgozási műveletek vezérlése
- **Csatolmány mentés** - Konfigurálható könyvtárba ment (`/app/attachments/hashes/{sha256}`)
- **Keresés** - Tárgy, feladó, címzett, mappa, fontosság szerinti szűrés
- **RAG szemantikus keresés** - Ollama embedding + MongoDB Atlas Vector Search email tartalmak és csatolmányok között
- **Synology integráció** - NAS Universal Search API-n keresztül keres PST fájlokat; duplikátum-szűréssel menti az adatbázisba
- **PDF űrlap kitöltés** - iText alapú PDF form filler
- **JWT autentikáció** - Spring Security 7 alapú, access token (8 óra) + refresh token (7 nap), BCrypt jelszókezelés
- **Titkosított ZIP feltöltés** - zip4j alapú titkosított archívum feltöltés/letöltés
- **Strukturált naplózás** - Központi MongoDB alapú naplózás (CentralLogger)
- **Modern dashboard** - Astro 6 + React 19 + Tailwind CSS 4 reszponzív frontend; Files és Attachments oldalon duplikátum-kezelő tab
- **Docker támogatás** - Teljes stack konténerizáció (frontend + backend + MongoDB + Ollama)

## Architektúra

```
┌─────────────────────────────────────────────────────┐
│                    Frontend (Astro)                  │
│  Dashboard │ Email Browser │ File List │ Processing  │
│       :80 (nginx) → proxy → :8080 | :4321 (dev)     │
├─────────────────────────────────────────────────────┤
│               Spring Boot Backend (:8080)            │
│  Controllers → Services → Repositories → MongoDB    │
│  RAG Pipeline: Tika → Chunking → Ollama Embedding   │
├─────────────────────────────────────────────────────┤
│   MongoDB Atlas Local (emails, chunks, vector index) │
│   Ollama (:11434) │ Synology NAS API │ Fájlrendszer │
└─────────────────────────────────────────────────────┘
```

## Előfeltételek

- Java 25 (Eclipse Temurin JDK ajánlott)
- Maven 3.9+
- MongoDB 7+ (vagy Docker)
- Node.js 22+ (frontend fejlesztéshez)
- Docker + Docker Compose (konténerizált futtatáshoz)

## Gyors indítás

### Docker-rel (ajánlott)

```bash
# Teljes stack indítása (frontend + backend + MongoDB + Ollama)
docker compose up -d

# Build nélküli újraindítás
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
| `mongo` | 27017 | MongoDB Atlas Local 8.0 (vector search támogatás) |
| `mongo-init` | - | Vector search index létrehozása (egyszer fut le) |
| `ollama` | 11434 | Ollama LLM szerver (embedding generálás) |
| `ollama-pull` | - | `nomic-embed-text` modell automatikus letöltése |

**Volumes:**
- `mongodb_data` - MongoDB adatok
- `attachments` - Kinyert email csatolmányok (`hashes/` almappa: hash-alapú deduplikált tárolás)
- `pst_source` - PST forrásfájlok (read-only mount)
- `ollama_data` - Ollama modellek cache

**Bind mountok (helyi NAS / hálózati meghajtók):**

A `docker-compose.yml`-ben a bind mountok `target` értékét a MongoDB-ben tárolt fájl path-oknak megfelelően kell beállítani:
```yaml
- type: bind
  source: /Volumes/archiv   # gazdagépen lévő tényleges elérési út
  target: /Volumes/archiv   # ugyanaz mint az adatbázisban tárolt path
  read_only: true
```
Ha a Synology keresés által mentett path-ok (pl. `/mnt/nas/archiv/...`) eltérnek a gazdagépen lévő mount pontoktól (pl. `/Volumes/archiv/...`), a PST feldolgozás `Missing` státusszal jelöli a fájlokat.

### Lokálisan

```bash
# MongoDB indítása (ha nincs Docker)
mongod --auth

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
│   ├── BackendApplication.java          # Belépési pont
│   ├── config/
│   │   ├── SecurityConfig.java          # Spring Security
│   │   ├── ModelMapperConfig.java       # ModelMapper bean
│   │   ├── SynologyConfig.java          # Synology NAS konfiguráció
│   │   └── RagConfig.java              # RAG pipeline konfiguráció
│   ├── controller/
│   │   ├── EmailController.java         # /api/emails - CRUD + keresés
│   │   ├── PstFinderController.java     # /find - PST fájl keresés
│   │   ├── PstProcessorController.java  # /pst - PST feldolgozás
│   │   ├── SynologyPstFinderController.java  # /find/synology
│   │   ├── RagController.java           # /api/rag - RAG keresés + indexelés
│   │   ├── ProgressController.java      # /api/progress
│   │   ├── FileInfoController.java      # /api/file-infos
│   │   ├── FileUploadController.java    # /api/files/upload
│   │   ├── FileController.java          # /index
│   │   └── PdfFormFillerController.java # /pdf/fill
│   ├── service/
│   │   ├── PstProcessorService.java     # PST feldolgozás (párhuzamos)
│   │   ├── PstFinderService.java        # PST fájl keresés
│   │   ├── SynologyApiClient.java       # Synology REST kliens
│   │   ├── SynologyPstFinderService.java # Synology PST keresés
│   │   ├── FileService.java             # Fájl indexelés
│   │   ├── FileUploadService.java       # ZIP tömörítés + titkosítás
│   │   ├── PdfFormFillerService.java    # PDF űrlap kitöltés
│   │   ├── ProgressTracker.java         # Folyamat állapot követés
│   │   └── rag/
│   │       ├── ChunkingService.java     # Szöveg darabolás (overlap)
│   │       ├── TextExtractionService.java # Apache Tika szövegkinyerés
│   │       ├── EmbeddingService.java    # Ollama embedding generálás
│   │       ├── RagIngestionService.java # Email+csatolmány indexelés
│   │       └── RagSearchService.java    # Szemantikus keresés (vector search)
│   ├── domain/
│   │   ├── Email.java                   # Email MongoDB dokumentum
│   │   ├── DocumentChunk.java           # RAG chunk + embedding vektor
│   │   ├── FileInfo.java                # PST fájl információ
│   │   ├── FileEntity.java              # Általános fájl entitás
│   │   ├── User.java                    # Felhasználó
│   │   ├── Organization.java            # Szervezet
│   │   ├── Authority.java               # Jogosultság
│   │   ├── LogEntry.java                # Napló bejegyzés
│   │   └── ProgressState.java           # Feldolgozás állapota
│   ├── repository/                      # MongoDB repository-k
│   ├── dto/                             # Data Transfer Objektumok
│   ├── exceptionhandler/                # Globális hibakezelés
│   ├── logger/                          # Központi naplózás (MongoDB)
│   └── util/                            # Hash, fájl I/O, HTML sanitizer
├── src/test/java/hu/fmdev/backend/     # Unit tesztek
│   ├── controller/
│   │   ├── RagControllerTest.java
│   │   └── SynologyPstFinderControllerTest.java
│   ├── service/
│   │   ├── ProgressTrackerTest.java
│   │   ├── PstProcessorServiceTest.java
│   │   ├── SynologyPstFinderServiceTest.java
│   │   └── rag/
│   │       ├── ChunkingServiceTest.java
│   │       ├── TextExtractionServiceTest.java
│   │       ├── EmbeddingServiceTest.java
│   │       ├── RagIngestionServiceTest.java
│   │       └── RagSearchServiceTest.java
│   └── util/
│       └── HashUtilTest.java
├── src/main/resources/
│   └── application.properties           # Alkalmazás konfiguráció
├── frontend/                            # Astro 6 + React dashboard
│   ├── src/
│   │   ├── pages/                       # Oldalak (index, emails, files, processing, synology)
│   │   ├── components/                  # React komponensek
│   │   ├── layouts/                     # Astro layout (reszponzív sidebar)
│   │   ├── styles/global.css            # Tailwind CSS 4 import
│   │   └── lib/api.ts                   # API kliens
│   ├── Dockerfile                       # Multi-stage build (Node 22 → nginx)
│   ├── nginx.conf                       # Reverse proxy konfiguráció
│   ├── package.json
│   └── astro.config.mjs
├── pom.xml                              # Maven konfiguráció
├── Dockerfile                           # Multi-stage build (JDK 25 → JRE 25)
├── docker-compose.yml                   # Teljes stack (6 szolgáltatás, health checks)
├── .env.example                         # Környezeti változók sablon
├── .dockerignore                        # Docker build kizárások
└── CLAUDE.md                            # Claude Code fejlesztési útmutató
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
| `/api/emails` | GET | Összes email lekérdezése |
| `/api/emails/search` | GET | Keresés szűrőkkel (subject, sender, recipient, folder, importance, isRead) |
| `/api/emails/{id}` | GET | Email ID alapján |
| `/api/emails/{id}` | PUT | Email módosítása |
| `/api/emails/{id}` | DELETE | Email törlése |

### PST feldolgozás
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/find/pstToTxt` | GET | PST fájlok keresése → szövegfájlba mentés |
| `/find/pst` | GET | PST fájlok keresése → adatbázisba mentés |
| `/find/synology` | GET | PST keresés Synology NAS-on |
| `/find/synologyToDb` | GET | Synology PST fájlok → adatbázisba mentés |
| `/pst/processFromFile` | POST | Feltöltött PST fájl feldolgozása |
| `/pst/processFromTxt` | POST | PST fájlok feldolgozása szövegfájl alapján |
| `/pst/processFromDb` | POST | PST fájlok feldolgozása adatbázisból |
| `/pst/pause` | POST | Feldolgozás szüneteltetése |
| `/pst/resume` | POST | Feldolgozás folytatása |

### RAG (Retrieval-Augmented Generation)
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/rag/ingest` | POST | Összes feldolgozatlan email indexelése (chunk + embedding) |
| `/api/rag/ingest/{emailId}` | POST | Egy email újraindexelése |
| `/api/rag/embed` | POST | Pending chunk-ok embedding generálása |
| `/api/rag/search?q=&topK=` | GET | Szemantikus keresés (chunk szintű találatok) |
| `/api/rag/search/emails?q=&topK=` | GET | Szemantikus keresés (email szintű csoportosítás) |
| `/api/rag/context?q=&topK=` | GET | LLM-nek formázott kontextus lekérdezés |
| `/api/rag/stats` | GET | Indexelési statisztikák |
| `/api/rag/health` | GET | Ollama + indexelés állapot |

### Csatolmányok
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/attachments` | GET | Csatolmányok listája (max 1000, creationTime DESC) |
| `/api/attachments/search` | GET | Keresés (filename, extension, size, sender, dátum) |
| `/api/attachments/count` | GET | Csatolmányok száma |
| `/api/attachments/duplicate-stats` | GET | Duplikátum statisztika (összes, egyedi, megosztott, törlendő) |
| `/api/attachments/deduplicate` | POST | Ugyanazon e-mailhez kétszer mentett azonos fájl (emailId+hash) törlése |
| `/api/attachments/email/{emailId}` | GET | Email csatolmányai |
| `/api/attachments/{id}/download` | GET | Csatolmány letöltése |

### Egyéb
| Végpont | Metódus | Leírás |
|---------|---------|--------|
| `/api/progress` | GET | Feldolgozás állapota |
| `/api/file-infos` | GET | Fájl információk |
| `/api/file-infos/counts` | GET | PST számlálók (total/pending/processed) |
| `/api/file-infos/duplicates` | GET | Azonos tartalmú PST fájlok csoportjai |
| `/api/file-infos/compute-hashes` | POST | SHA-256 hash kiszámítása az adatbázisban lévő fájlokhoz |
| `/api/file-infos/deduplicate` | POST | Duplikált PST rekordok törlése (hash+méret egyezés alapján) |
| `/pst/processSelected` | POST | Kijelölt PST fájlok feldolgozása (saveAttachments flag-gel) |
| `/api/files/upload` | POST | Fájl feltöltés (ZIP titkosítással) |
| `/pdf/fill` | POST | PDF űrlap kitöltése |

## Konfiguráció

Az `application.properties` fő beállításai:

```properties
# MongoDB kapcsolat
spring.data.mongodb.uri=mongodb://admin:example@localhost:27018/emails?authSource=admin

# Csatolmányok mentési helye
attachments.directory=/app/attachments

# RAG pipeline
rag.ollama-base-url=http://localhost:11434
rag.embedding-model=nomic-embed-text
rag.embedding-dimensions=768
rag.chunk-size=512
rag.chunk-overlap=64
rag.search-top-k=10
rag.search-min-score=0.5
rag.ingestion-threads=4

# Synology NAS (opcionális)
synology.host=                          # NAS IP/hostname
synology.username=                      # NAS felhasználó
synology.password=                      # NAS jelszó
synology.path-prefix=/volume1           # NAS elérési út prefix
synology.local-mount-prefix=/mnt/nas    # Lokális mount pont
synology.search-extensions=pst,ost      # Keresett kiterjesztések
synology.batch-size=100                 # Keresési batch méret
```

## Technológiai stack

**Backend:**
- Java 25 (Eclipse Temurin) + Spring Boot 4.0 (Spring Framework 7, Jakarta EE 11)
- Spring Web, WebFlux, Data MongoDB, Security, Thymeleaf, OAuth2
- java-libpst - PST fájl feldolgozás
- Apache Tika - Szövegkinyerés (PDF, DOC, XLS, PPT, stb.)
- iText PDF 8 + Apache PDFBox 3 - PDF kezelés
- zip4j - ZIP tömörítés/titkosítás
- JSch - SSH műveletek
- Lombok, ModelMapper

**Frontend:**
- Astro 6 - Statikus oldal generálás (Vite 7)
- React 19.2 - Interaktív komponensek
- Tailwind CSS 4 - Stílusok (@tailwindcss/vite plugin)

**RAG pipeline:**
- Ollama - Lokális embedding generálás (nomic-embed-text, 768 dimenzió)
- MongoDB Atlas Vector Search - Cosine hasonlóság alapú keresés
- Apache Tika - Csatolmány szövegkinyerés (PDF, Office, stb.)

**Infrastruktúra:**
- MongoDB Atlas Local 8.0 - Adattárolás + vector search
- Ollama - LLM szerver (GPU támogatás)
- Docker + Docker Compose - Konténerizáció (6 szolgáltatás, multi-stage build)
- nginx - Frontend szervírozás + API reverse proxy
- Java 25 LTS (Eclipse Temurin)

## Fejlesztés

```bash
# Tesztek futtatása
mvn test

# Egy adott teszt futtatása
mvn test -Dtest=ChunkingServiceTest

# Frontend fejlesztői szerver
cd frontend && npm run dev

# Frontend build
cd frontend && npm run build
```

## Docker Compose szolgáltatások

A `docker-compose.yml` 6 szolgáltatást tartalmaz, health check-kel és restart policy-val:

| Szolgáltatás | Restart | Health check | Leírás |
|---|---|---|---|
| `frontend` | unless-stopped | backend healthy | Astro 6 → nginx (:80) |
| `backend` | unless-stopped | `/api/progress` curl | Spring Boot (:8080) |
| `mongo` | unless-stopped | `mongosh ping` | MongoDB Atlas Local 8.0 (:27017) |
| `mongo-init` | no | - | Vector search index (egyszer fut) |
| `ollama` | unless-stopped | `/api/tags` curl | Ollama LLM (:11434, GPU) |
| `ollama-pull` | no | - | `nomic-embed-text` letöltés |

**Környezeti változók** - `.env` fájlból olvasva (lásd `.env.example`):

```bash
# .env fájl létrehozása
cp .env.example .env
# Szerkesztés a saját értékekre (Synology, MongoDB jelszó, stb.)
```

```bash
# Indítás (minden szolgáltatás)
docker compose up -d

# Csak backend + MongoDB (RAG nélkül)
docker compose up -d backend mongo

# Health check állapot ellenőrzés
docker compose ps

# GPU nélkül (Ollama CPU módban fut)
# Töröld a deploy.resources szekciót a docker-compose.yml-ből
```

**Volumes:**
- `mongodb_data` - MongoDB adatok
- `attachments` - Kinyert csatolmányok
- `pst_source` - PST forrásfájlok (read-only)
- `ollama_data` - Ollama modellek
- `logs` - Backend alkalmazás logok

## Fejlesztési javaslatok

A projekt továbbfejlesztéséhez javasolt eszközök és minták:

### Tesztelés
- **Testcontainers** - Integrációs tesztek valódi MongoDB-vel konténerben (`@Testcontainers` + `MongoDBContainer`). A jelenlegi unit tesztek Mockito-val mockólják a repository réteget; Testcontainers-szel a teljes pipeline (service → repository → MongoDB) tesztelhető
- **ArchUnit** - Architektúra szabályok automatikus ellenőrzése (pl. service réteg ne függjön közvetlenül controller-től)
- **WireMock** - Synology API és Ollama HTTP hívások integrációs tesztelése (a jelenlegi MockWebServer-es megoldás kiváltására is alkalmas)

### Monitorozás és dokumentáció
- **Spring Boot Actuator** - `/actuator/health`, `/actuator/metrics`, `/actuator/info` végpontok; Prometheus/Grafana integrációhoz `micrometer-registry-prometheus` dependency
- **SpringDoc OpenAPI (Swagger)** - Automatikus API dokumentáció generálás a meglévő controllerekből (`springdoc-openapi-starter-webmvc-ui`), elérhető a `/swagger-ui.html` címen
- **Structured logging** - `logback-logstash-encoder` JSON formátumú logokhoz, ELK/Loki stack-be gyűjtéshez

### CI/CD
- **GitHub Actions** - Automatikus build, teszt futtatás és Docker image push. Javasolt pipeline:
  ```
  push → mvn test → mvn package → docker build → docker push → deploy
  ```
- **Testcontainers Cloud** - CI környezetben a MongoDB tesztek futtatásához
- **Dependabot / Renovate** - Automatikus dependency frissítések (Spring Boot, npm csomagok)

### Biztonság
- **JWT autentikáció** ✅ Megvalósítva: `JwtTokenProvider`, `JwtAuthenticationFilter`, `AuthController`, BCrypt jelszókezelés, access (8 óra) + refresh token (7 nap)
- **Vault / AWS Secrets Manager** - JWT titok és MongoDB/Synology jelszavak externalizálása (jelenleg `.env` fájl)
- **CORS szűkítés** - Jelenleg `*` (minden origin); produkcióban specifikus originekre korlátozandó
- **OWASP ZAP** - Automatikus biztonsági szkennelés a CI pipeline-ban

### Teljesítmény
- **Spring Cache (`@Cacheable`)** - Email lekérdezések és RAG keresési eredmények cache-elése
- **MongoDB indexek** - Compound indexek a gyakori keresési mintákhoz (`sender + receivedTime`, `subject text index`)
- **~~Ollama batch embedding~~** - ✅ Megvalósítva: `EmbeddingService.embedBatch()` + párhuzamos feldolgozás (`rag.embedding-threads`, `rag.ingestion-batch-size`)

### Go/Rust átállás elemzése

A projekt szűk keresztmetszetei GPU inference (Ollama) és I/O (MongoDB, fájl olvasás), nem CPU számítás. Nyelv csere **nem javasolt**:

| Szempont | Java 25 (jelenlegi) | Go | Rust |
|----------|---------------------|-----|------|
| PST könyvtár | java-libpst ✅ | ❌ nincs | ❌ nincs |
| Szöveg kinyerés | Tika ✅ (300+ formátum) | ❌ nincs egyenértékű | ❌ nincs |
| HTTP kliens | Netty ✅ | net/http ✅ | reqwest ✅ |
| MongoDB driver | ✅ érett | ✅ érett | ✅ érett |
| GC latency | ZGC <1ms | ~1-10ms | nincs GC |
| Memória | ~200-500MB | ~50-150MB | ~30-100MB |
| Spring ökoszisztéma | ✅ teljes | ❌ | ❌ |

**Konklúzió:** A batch embedding optimalizáció Java-ban 5-10x gyorsulást ad, ami nagyobb nyereség mint egy teljes nyelv csere. Java 25 Virtual Threads + ZGC versenyképes I/O workload-oknál. A java-libpst és Apache Tika Go/Rust-ban nem létezik — pótlásuk nem reális.

### Autentikáció

✅ **Megvalósítva** (`claude/spring-security` ágon): natív Spring Security 7 + JWT (HS256).

- `JwtTokenProvider` — token generálás és validálás
- `JwtAuthenticationFilter` — Bearer token kiolvasása minden kérésnél
- `MongoUserDetailsService` — felhasználó betöltése MongoDB-ből
- `UserSeeder` — alapértelmezett admin felhasználó létrehozása induláskor
- `AuthController` — `/api/auth/login`, `/register`, `/refresh`, `/me`
- `SecurityConfig` — stateless session, CSRF letiltva, végpont védelem

**Védett végpontok:**

| Útvonal | Hozzáférés |
|---------|-----------|
| `/api/auth/**` | Publikus |
| `/api/rag/**`, `/api/progress` | Publikus |
| `/api/**` | JWT szükséges |
| `/find/**`, `/pst/**`, `/pdf/**` | Publikus (fejlesztési állapot) |

## Licenc

Privát projekt.
