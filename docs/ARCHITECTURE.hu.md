# SuliWeb Architektúra

A kódbázis részletes, fájlonkénti bemutatása. Gyors indításhoz és API referenciához lásd a [README.md](../README.md)-t. Ez a dokumentum mélyebbre megy: mit csinál pontosan az egyes osztály, hogyan működik végig a három háttér-pipeline, és miért születtek bizonyos tervezési döntések.

A dokumentum angol verziója: [ARCHITECTURE.en.md](ARCHITECTURE.en.md).

## Tartalom

1. [A rendszer áttekintése](#1-a-rendszer-áttekintése)
2. [Backend rétegek](#2-backend-rétegek)
3. [A három pipeline](#3-a-három-pipeline)
4. [GraphRAG chat](#4-graphrag-chat)
5. [Frontend architektúra](#5-frontend-architektúra)
6. [Infrastruktúra](#6-infrastruktúra)
7. [Tesztelési stratégia](#7-tesztelési-stratégia)

---

## 1. A rendszer áttekintése

A SuliWeb végig-feldolgozza a Microsoft Outlook PST archívumokat: megtalálja a fájlokat, kinyeri az emaileket és csatolmányokat, eltárolja őket, indexeli kereséshez, és felépít egy tudásgráfot, amin egy LLM tud "gondolkodni".

```
PST fájlok  →  MongoDB (az emailek/csatolmányok/metaadatok elsődleges tárolója)
                  │
                  ├──→ Elasticsearch (teljes szöveges keresés: email_archive, attachment_archive)
                  │
                  └──→ Neo4j (tudásgráf: emberek, szálak, fogalmak, kommunikációs háló)
                            │
                            └──→ Ollama (NER a gráfépítéshez, LLM a GraphRAG chathez)
```

Három egymástól független Spring `@Service` pipeline olvas a MongoDB-ből, és tölti fel a két downstream tároló egyikét. Egymásnak sosem írnak — a MongoDB mindig az upstream forrás. Ez azt jelenti, hogy bármelyik nulláról újrafuttatható a másik kettő érintése nélkül: az Elasticsearch újraindexelése nem hat a tudásgráfra, és fordítva.

A backend egyetlen Spring Boot 4.0 (Spring Framework 7, Java 25) alkalmazás. A frontend egy különálló Astro 5 + React 19 statikus oldal, amit nginx szolgál ki, és `/api/**` útvonalon keresztül beszél a backenddel egy reverse proxyn át (Docker esetén Caddy mindkettő előtt).

## 2. Backend rétegek

### 2.1 `config/`

| Osztály | Felelősség |
|---|---|
| `SecurityConfig` | Fail-secure allowlist: minden útvonal alapból tiltott, kivéve egy explicit lista publikus/jogosultsághoz kötött mintát. A JWT filter a `UsernamePasswordAuthenticationFilter` elé van bekötve. A CORS a `cors.allowed-origins`-ből konfigurálódik. |
| `JwtConfig` | `@ConfigurationProperties` a JWT secret és token-élettartamok számára. |
| `ElasticsearchConfig` | Felépíti az `ElasticsearchClient` bean-t és a `pythonProcessorClient` `WebClient` bean-t (a csatolmány→markdown sidecarhoz). `ApplicationReadyEvent`-re létrehozza az `email_archive` és `attachment_archive` indexeket, ha még nem léteznek, magyar nyelvtudatos analyzerekkel (lásd [3.2](#32-e-discovery-indexelés-elasticsearch)). |
| `RagConfig` | `@ConfigurationProperties(prefix = "rag")`: Ollama base URL, alapértelmezett chat modell, alapértelmezett chat kontextusméret (`chatContextTopK`), alapértelmezett előzményhossz. Az `ollamaWebClient` bean-t is ez adja. 2026-ban lecsupaszítva csak a valóban olvasott mezőkre — korábban ~15 mezőt hordozott egy törölt vektor-embedding RAG pipeline-ból (Qdrant + chunking + embedding szolgáltatások), amik közül semelyik nem volt sehova bekötve. |
| `SynologyConfig` | Synology NAS kapcsolat alapértékei (`@ConfigurationProperties(prefix = "synology")`). |
| `ModelMapperConfig` | `ModelMapper` bean DTO↔entitás leképezéshez, ahol használt. |

### 2.2 `controller/`

Vékony REST controllerek; alig van bennük üzleti logika. A teljes végpontlista HTTP metódusokkal és jogosultsági követelményekkel a README-ben van. Néhány kiemelendő:

- **`KnowledgeGraphController`** (`/api/kg`) — ingest/status/graph-stats/network/thread/concept/chat/chat-stream/models. A `chat`/`chat/stream` végpontok egyszerűen továbbítanak a `GraphRagChatService`-hez.
- **`EDiscoveryController`** (`/api/ediscovery`) — ingest/search/retry-failed/status, az `EDiscoveryIngestionService` és `EDiscoverySearchService` mögött.
- **`AttachmentProcessingController`** (`/api/attachments/processing`) — különálló az `AttachmentController`-től (ami egy egyszerű böngésző/kereső/letöltő/dedup API a MongoDB csatolmány-rekordok felett). Ez a controller a [3.3](#33-csatolmány-feldolgozás-különálló-pipeline)-ban leírt markdown-konverziós és ES-indexelő pipeline-t indítja.
- **`PipelineController`** (`/api/pipeline`) — egy teljes futást orkesztrál (PST felderítés → feldolgozás → ES indexelés → KG ingestion) a `PipelineOrchestrationService`-en keresztül, fázisonkénti progress riportolással.
- **`AppSettingsController`** (`/api/settings`) — a lentebb 2.4-ben leírt futásidőben hangolható beállításokat teszi elérhetővé.

Biztonsági megjegyzés: minden végpont, ami egy path variable-t visszaad sima szöveges válasz törzsében (`ResponseEntity.ok("... " + id)`), explicit `Content-Type: text/plain`-t állít be, hogy a kliens ne interpretálja a választ HTML/JS-ként (ezt a reflected-XSS mintát a CodeQL jelzi — javítva az `EDiscoveryController`-ben és `AttachmentProcessingController`-ben).

### 2.3 `domain/` és `domain/graph/`

A `domain/` tartalmazza a MongoDB dokumentumokat (`Email`, `Attachment`, `FailedConversion`, `FileInfo`, `User`, `AppSettings`, …). A `domain/graph/` a Spring Data Neo4j `@Node` osztályokat tartalmazza, amik a tudásgráfot alkotják:

| Node | Kulcs tulajdonságok | Kapcsolatok |
|---|---|---|
| `PersonNode` | `email` (egyedi kulcs), `name`, `organization` (az email domainből származtatva) | `COMMUNICATES_WITH` → másik `PersonNode` (darabszám + utolsó dátum) |
| `EmailNode` | `messageId`, `mongoId` (visszahivatkozás a MongoDB `Email`-re), `subject`, `date`, `pstFileName`, `pstOwner` | `SENT` (bejövő, a feladó `PersonNode`-tól), `TO`/`CC` (kimenő, a címzett `PersonNode`-okhoz), `BELONGS_TO` (a `ThreadNode`-hoz), `REPLY_TO` (a szülő `EmailNode`-hoz), `HAS_ATTACHMENT` (az `AttachmentNode`-hoz), `MENTIONS` (a `ConceptNode`-hoz) |
| `ThreadNode` | `threadId` (= MongoDB `conversationId`), `subject`, `lastActivity` | — |
| `ConceptNode` | `name`, `type` (`PERSON`/`ORG`/`TOPIC`/`LOCATION` — megjegyzés: egy `PERSON` típusú `ConceptNode` más mint egy `PersonNode`; az előbbi egy NER által kinyert említés, az utóbbi egy valódi feladó/címzett) | — |
| `AttachmentNode` | `sha256`, `filename`, `markdownContent` (jelenleg mindig üres — lásd lentebb) | — |
| `OrganizationNode` | a jelenlegi ingestion kód nem használja; visszafelé kompatibilitás miatt megtartva | — |

Egy 2026-os tisztítás eltávolított egy teljesen különálló, használaton kívüli MongoDB-alapú "node type" kísérletet (`domain/nodetypes/`: `Contract`, `Institution`, `ServiceProvider`, `CommitmentClaim`), ami a fenti Neo4j gráfmodellt megelőzte, és sosem volt sehova bekötve.

Az `AttachmentNode.markdownContent` feltétel nélkül `""`-ra van állítva a `KnowledgeGraphIngestionService`-ben — a markdown konverzió a különálló csatolmány-feldolgozó pipeline-ban történik ([3.3](#33-csatolmány-feldolgozás-különálló-pipeline)) és az Elasticsearch-be indexel, nem a gráfba. A kettő összekötése (hogy a gráf node hordozza a valódi kinyert szöveget) egy természetes következő lépés lenne, ha a csatolmányok teljes szöveges keresése a gráf oldalán is követelmény lesz.

### 2.4 `service/` — a nem-pipeline szolgáltatások

- **`AppSettingsService`** — a futásidejű konfigurációs réteg. Minden `getEffectiveX()` metódus ugyanazt a mintát követi: kikeresi a singleton `AppSettings` Mongo dokumentumot; ha van benne nem-null/nem-üres/pozitív override, azt használja; egyébként visszaesik a `@ConfigurationProperties` (`RagConfig`) vagy `@Value` (`kg.ingestion.*`) statikus alapértékre. Ez teszi lehetővé, hogy egy admin a `/settings` felületről hangolja a `chatModel`, `nerModel`, `chatMaxHistoryTurns`, `chatContextTopK`, `kgBatchSize` és `kgMaxConcurrentWrites` értékeket újratelepítés nélkül.
- **`ProgressTracker`** — egyetlen globális progress állapot (jelenlegi művelet neve, feldolgozott/összes, százalék), amit a PST feldolgozás, e-Discovery indexelés, csatolmány-feldolgozás és KG ingestion egyaránt használ. Gyakorlatban ezek közül mindig csak egy fut egyszerre, így egy közös tracker szándékos döntés, nem hiba.
- **`PipelineOrchestrationService`** — egy többfázisú futást vezérel (`PstFinderService` → `PstProcessorService` → `EDiscoveryIngestionService` → `KnowledgeGraphIngestionService`) fázisonkénti `StageProgress`-szel (állapotgép: `PENDING → RUNNING → DONE/FAILED/SKIPPED`), a globális `ProgressTracker`-től függetlenül.
- **`PstProcessorService` / `PstFinderService` / `SynologyPstFinderService` / `SynologyApiClient` / `SynologySettingsService` / `FileService` / `FileAccessService` / `FileUploadService` / `PdfFormFillerService`** — PST felderítés, feldolgozás (`java-libpst`-vel, virtuális szálakon), Synology NAS integráció, fájlrendszer-hozzáférés védelme, ZIP feltöltés kezelése, PDF űrlap kitöltés. Ezek függetlenek az ELK/KG/LLM témáktól, amikre ez a dokumentum fókuszál; lásd az egyes osztályok inline Javadoc/kommentjeit.

### 2.5 `service/rag/` — szövegkinyerés, NER, GraphRAG chat

- **`TextExtractionService`** — Apache Tika alapú szövegkinyerés csatolmányokból (PDF, DOC, XLS, …), plusz Java-natív válaszlánc-levágás email törzsekhez (egy korábbi Python-alapú levágót váltott le teljesítmény miatt).
- **`NerExtractor`** (interfész) + **`EntityExtractionService`** (implementáció) — meghívja az Ollama `/api/generate` végpontját egy fix prompttal, ami `{name, type}` entitások JSON tömbjét kéri (`PERSON`/`ORG`/`TOPIC`/`LOCATION`), a bemenetet 3000 karakterre vágva. A parse-olás szándékosan defenzív: elfogad sima JSON tömböt, egy objektumot ami valamelyik értékében hordozza a tömböt, vagy sima stringek listáját (ezeket `TOPIC`-ként kezeli). Bármilyen hiba — hálózati, hibás JSON, bármi — üres listát ad vissza dobás helyett; a gráfépítés úgy van megtervezve, hogy gracefully degradáljon, ha az Ollama nem elérhető. A `NerExtractor` interfész kizárólag azért létezik, hogy a tesztek mockkal helyettesíthessék a `WebClient` kezelése nélkül.
- **`GraphRagChatService`** — a GraphRAG chat motor:
  1. Entitások kinyerése a felhasználó kérdéséből (`EntityExtractionService`).
  2. Minden entitáshoz lekéri a `GraphSearchService.findEmailsByConceptProximity()`-től a közeli emaileket, `mongoId` alapján dedupolva, amíg `topK` el nem érik (`topK` alapértéke `AppSettingsService.getEffectiveChatContextTopK()`, ha a hívó `<= 0`-t ad át).
  3. Kontextus blokkot épít ezekből az emailekből (tárgy/dátum/PST fájl/törzs első 500 karaktere).
  4. Elküldi a `system` (kontextusra alapozott instrukciók) + levágott `history` + `user` üzenetet az Ollama `/api/chat`-jének, blokkolóan (`chat()`) vagy token streamként (`chatStream()`, ami egy záró `{"done":true,"sources":[...]}` eseményt is kibocsát, hogy a frontend a stream végén forráslinkeket tudjon renderelni).

  Ha az entitáskinyerés semmit nem talál, a kontextus üres, és a system prompt ezt explicit megmondja — az LLM utasítást kap, hogy inkább vallja be, ha nincs elég információja, mint hogy kitaláljon valamit.

### 2.6 `repository/` és `repository/graph/`

Standard Spring Data repository-k. A gráf repository-k `@Query`-vel, literál Cypherrel dolgoznak, ha egy levezetett metódusnál (derived method) többre van szükség:

- `EmailNodeRepository.findByConceptProximity` — `MATCH (e:Email)-[:MENTIONS]->(c:Concept) WHERE c.name CONTAINS $conceptName RETURN e ORDER BY e.date DESC LIMIT $limit`. Megjegyzés: ez egy substring egyezés, nem valódi proximity/hasonlósági pontszám — a "proximity" itt gráf-szomszédosságot jelent (egy lépés a `MENTIONS`-en át), nem szemantikai távolságot.
- `EmailNodeRepository.ensureConcepts` / `linkEmailToConcepts` — csak a `KnowledgeGraphIngestionService.ingestConceptsOnly()` retry-with-backoff útja használja (lásd [3.4](#34-knowledge-graph-ingestion)).
- `PersonNodeRepository.findCommunicationPartners[InRange]` — egy lépéses `COMMUNICATES_WITH` bejárás, opcionálisan a kapcsolat `lastDate` tulajdonsága szerint szűrve.

## 3. A három pipeline

Mindhárom az `EmailRepository`/`AttachmentRepository`-ból (MongoDB) olvas, és egymástól függetlenül indítható REST-en, a `PipelineOrchestrationService`-szel, vagy (e-Discovery esetén) automatikusan.

### 3.1 PST → MongoDB

A `PstProcessorService` `.pst` fájlokat olvas a `java-libpst`-vel virtuális szálakon, kiszámol egy `SHA-256(pstFileName + msgId)` dedup kulcsot minden emailhez és egy tartalom-hash dedup kulcsot minden csatolmányhoz (egyszer tárolva az `attachments/hashes/` alatt, függetlenül attól, hogy hány email hivatkozik rá), levágja a válaszláncot a törzsből (`TextExtractionService`), és `Email` + `Attachment` dokumentumokat ír a MongoDB-be. A PST fájl feldolgozási állapota (`New`/`Processed`/`Modified`/`Invalid`/`Missing`) a `FileInfo`-ban követett.

### 3.2 e-Discovery indexelés (Elasticsearch)

Az `EDiscoveryIngestionService.ingestAll()` végiglapoz minden `Email`-en, egy ES bulk-index műveletet épít minden emailhez az `email_archive` indexbe: `subject`, `bodyDelta` (a már levágott törzs), `sender`/`senderName`, `recipients`, `date`, `pstFileName`/`pstOwner`, `threadId`. Csatolmányokhoz **nem** nyúl — ez korábban itt történt inline (a Python sidecar hívása csatolmányonként az email indexelés közben), de szétválasztásra került egy saját pipeline-ba (3.3), hogy egy lassú/hibázó csatolmány-konverzió ne tudja blokkolni vagy lassítani az email indexelést.

Az indexelés automatikusan is triggerelődik az `EDiscoveryChangeStreamListener`-rel, ami a MongoDB `emails` kollekciójának change streamjét figyeli, és újraindexel (vagy töröl az ES-ből) insert/update/delete-re — így a keresési index szinkronban marad anélkül, hogy minden PST feldolgozás után manuálisan újra kellene futtatni.

Az `EDiscoverySearchService.search()` egy `multi_match` lekérdezést épít a `subject`/`bodyDelta`/`senderName` és azok `.ascii` almezői felett (így a stemmelt-és-ékezetes és az ékezet-nélküli lekérdezések is találnak), `fuzziness: AUTO` és `prefix_length: 2`-vel, hogy az elgépelt szavak is találjanak (pl. "kontrakt" megtalálja a "kontraktus"-t) anélkül, hogy a fuzzy matching rövid prefixeket értelmetlenségekre futtatna. Opcionális `sender`/`pstOwner`/`pstFileName` term filterek és egy `date` range filter ÉS-elve kerül be egy `bool` lekérdezésbe, ha megadottak. A highlight a `bodyDelta`-n kérve (egy fragment, 200 karakter), és `snippet`-ként jelenik meg az eredményben.

**Index analyzerek**: `hungarian_stemmed` (standard tokenizer → lowercase → magyar stopwords → magyar stemmer) és `hungarian_ascii` (standard tokenizer → lowercase → asciifolding → magyar stopwords, stemmelés nélkül) indexenkénti definíció, minden szöveges mezőn fő mező/`.ascii` almező párként használva. Megjegyzés: Elasticsearch 9-től a sima `"hungarian"` token filter név már nem hivatkozható közvetlenül egy custom analyzer filter listájában — explicit named custom filterként kell deklarálni (`type: stemmer, language: hungarian`). Ez némán elrontotta az index létrehozást az ES 8→9.4.2 upgrade után, amíg észre nem vettük és javítottuk.

### 3.3 Csatolmány-feldolgozás (különálló pipeline)

Az `AttachmentProcessingService.processAll()` az összes `Attachment` Mongo rekordot tartalom-hash szerint csoportosítja (így egy sok emailen megosztott azonos fájl pontosan egyszer kerül konvertálásra), kihagy minden hash-t, amihez már létezik dokumentum az `attachment_archive` ES indexben (egy `exists` ellenőrzés id = hash alapján, így az újrafuttatás olcsó), a maradékot Markdownná konvertálja a Python `markitdown` sidecaron keresztül (`POST /convert-attachment`), és bulk-indexeli a `{hash, filename, contentType, pstFileName, emailIds[], markdownContent}`-et az `attachment_archive`-ba. A konverziós hibák ugyanabba a `FailedConversion` dead-letter kollekcióba kerülnek, amit az e-Discovery is használ (`FailureType.ATTACHMENT_CONVERT`), egyenkénti vagy tömeges újrafuttatással a `/api/attachments/processing/retry-failed[/​{id}]`-n keresztül.

Ez szándékosan egy *különálló* ES index az `email_archive`-tól, nem egy nested mező az email dokumentumon — a csatolmány-tartalom keresése és az email-tartalom keresése különböző use case-ek, különböző eredmény-alakkal, és a szétválasztás azt jelenti, hogy egy lassú csatolmány-konverziós futás sosem blokkolja az email keresés elérhetőségét.

### 3.4 Knowledge Graph ingestion

A `KnowledgeGraphIngestionService`-nek két belépési pontja van, ami egy pipeline-alakot oszt meg (`runBatchedPipeline`, amit a kettő közti duplikáció megszüntetésére vezettünk be):

1. **`ingestAll()`** — minden, a gráfban még nem szereplő `Email`-hez (`emailNodeRepo.existsByMessageId`) lefuttatja a NER-t (`EntityExtractionService`), és megírja a `Person`/`Thread`/`Email`/`Attachment`/`Concept` node-okat és minden kapcsolatukat (lásd 2.3 táblázatát).
2. **`ingestConceptsOnly()`** — a fordított szűrő: csak a gráfban *már* szereplő emailek, újra lefuttatja a NER-t, és összevonja az új `Concept` node-okat + `MENTIONS` linkeket az email egyéb adatainak érintése nélkül. Hasznos, ha javítjuk a NER promptot, és nem akarjuk újraépíteni a teljes gráfot.

A megosztott pipeline-alak két fázis batch oldalanként:

- **1. fázis (NER)** — `Executors.newVirtualThreadPerTaskExecutor()`-on fut: a korlátlan konkurencia rendben van, mivel ez a fázis I/O-kötött (Ollama-ra várakozás), nem CPU-kötött.
- **2. fázis (írás)** — egy fix méretű poolon fut (`AppSettingsService.getEffectiveKgMaxConcurrentWrites()`, alapérték 4): a Neo4j írási konkurencia szándékosan korlátozott, mivel kontrollálatlan párhuzamos írások átfedő node-okra növelik a lock-contention/deadlock kockázatot.

A `mergePerson`/`mergeThread`/`mergeConcept` `synchronized` instance metódusok — ez sorosítja a get-or-create-et a teljes szolgáltatáson (nem csak node-típusonként), ami valamennyi párhuzamosságot egy egyszerű garanciáért ad fel: két konkurens író sosem versenyezhet azon, hogy két `PersonNode`-ot hozzon létre ugyanahhoz az email címhez. Az `ingestConceptsOnly` írási útja emellett a `writeWithRetry`-n megy keresztül, ami az `ensureConcepts`/`linkEmailToConcepts`-et (custom Cypher `MERGE` lekérdezések, amik megkerülik a synchronized helpereket) exponenciális backoff + jitterrel próbálja újra észlelt deadlock esetén — ez a két lekérdezés a korlátozott írási poolon belül fut a merge helperek synchronized védőhálója nélkül, így egy retry loop az olcsóbb megoldás azokat is sorosítani helyett.

A `KnowledgeGraphController.triggerIngestion()`/`triggerConceptReingestion()` mindkettő először `isRunning()`-ot ellenőriz, és a kiválasztott metódust egy virtuális szálon indítja; a `getStats()` a `ratePerMin`/`etaSeconds`-t a `processedCount`/`startedAt`-ból számolja ki, minden lekérdezéskor újraszámolva, nem cache-elve.

## 4. GraphRAG chat

Lásd [2.5](#25-servicerag--szövegkinyerés-ner-graphrag-chat) a `GraphRagChatService` belső működéséért. A frontend oldal (a `KnowledgeGraph.tsx` "Chat" tabja és a külön `RagChat.tsx` oldal) saját, session-enkénti beszélgetés-előzményt tart `localStorage`-ban (nincs szerver-oldali perzisztencia), minden körben visszaküldi, hogy az LLM-nek legyen follow-up kontextusa, és a streamelt tokeneket fokozatosan rendereli SSE-n keresztül.

## 5. Frontend architektúra

Astro 5, `output: static`-kal — minden oldal teljesen statikus HTML build időben, React 19 "island"-okkal (`client:load`) minden interaktív részhez. Ennek egy közvetlen következménye, amit érdemes tudni: bármi, amit a kliens-oldali állapotra (mint a kiválasztott UI nyelv) reagálnia kell, **nem** élhet sima Astro markupban, mert az markup egyszer sül be build időben, és sosem renderelődik újra. Ez az oka, hogy a sidebar navigáció, ami korábban szerver-renderelt `<aside>` markup volt a `Layout.astro`-ban, React island-dá (`Sidebar.tsx`) alakult, amikor a nyelvváltás bekerült — nem volt más módja, hogy a nav feliratok megváltozzanak teljes oldal-újratöltés nélkül.

### 5.1 i18n

A `src/lib/i18n.ts` egyetlen `i18next` + `react-i18next` instance-t inicializál (a fordítások a `src/locales/{hu,en}.json`-ból, a nyelv `localStorage['lang']`-ből olvasva/írva, alapérték `hu`). Minden komponens, aminek fordított szövegre van szüksége, importálja ezt a modult (a side-effecting `i18next.init()` hívásáért) és meghívja a `useTranslation()`-t. Mivel a `react-i18next` alapból a globális `i18next` singletonra esik vissza, ha nincs `I18nextProvider` a fa körül, minden island egy oldalon automatikusan megosztja az állapotot — a nyelv váltása minden mountolt komponenst újrarenderel az aktuális oldalon újratöltés nélkül, még ha minden island-ot külön hidratáltak is.

Két kivétel a "használd a `useTranslation()`-t" alól:
- **A `Layout.astro` oldal-cím** — a `title` prop egy fordítási kulcs (pl. `"pages.dashboard"`), szerver-oldalon feloldva a `hu.json`-nal a statikus HTML `<title>`/`<h2>`-jéhez, majd kliens-oldalon újra feloldva egy kis inline `<script>`-tel, ami közvetlenül importálja az `i18n` modult és figyeli a `languageChanged`-et.
- **A `rag.astro` tab sávja** — három feliratot egy vanilla `<script>` renderel (nem React island, hogy elkerülje a hidratációs versenyfeltételt a már ott lévő tab-toggle logikával), ez is közvetlenül az `i18n.t()`-t használva.

### 5.2 Oldalak és komponensek

A legtöbb oldal (`src/pages/*.astro`) egy vékony `<Layout><Komponens client:load /></Layout>` wrapper egy komponens köré a `src/components/`-ból. A leképezés többnyire 1:1 és önmagáért beszél a fájlnevekből; néhány kiemelendő:

- Az `attachments.astro` (meglévő csatolmány-rekordok böngészése/keresése/letöltése/dedupja) és az `attachment-processing.astro` (a markdown-konverziós pipeline indítása és monitorozása) szándékosan különálló oldalak/sidebar bejegyzések — különböző célok, különböző közönség.
- A `login.astro` egyáltalán nem használja a `Layout.astro`-t (nincs sidebar bejelentkezés előtt); a form a `LoginForm.tsx`-ben él kifejezetten azért, hogy a nyelvváltó ott is működjön.
- A `rag.astro` három komponenst host-ol egy tab sáv mögött: `RagChat.tsx` (csak akkor jelenik meg, ha a bejelentkezett felhasználónak van `RAG_CHAT` jogosultsága), és két `RagSearch.tsx` instance (`mode="search"`/`mode="manage"`), amik — mivel a régi vektor-embedding RAG pipeline törölve lett — most egyszerűen a `/ediscovery`-re és `/knowledge-graph`-ra irányítják a felhasználót, saját keresési/kezelési felület helyett.

## 6. Infrastruktúra

Hat Docker Compose szolgáltatás: `suliweb-frontend` (Astro build → nginx), `suliweb-backend` (Spring Boot, multi-stage Maven build), `suliweb-mongo`, `suliweb-elasticsearch` (9.4.2), `suliweb-neo4j` (5.26 Community + APOC), `python-processor` (FastAPI + `markitdown`). Egy Caddy reverse proxy a stack előtt TLS-t terminál és health-checkeli mindkét (frontend és backend) upstreamet. Az Ollama a Docker **hoston** fut, nem konténerként — `host.docker.internal:11434`-ként érhető el a network-ön belülről.

A CI (`.github/workflows/ci.yml`) minden push/PR-on futtat backend teszteket + JaCoCo lefedettséget, frontend lint + build-et, SAST-ot (SpotBugs, CodeQL Java-hoz és JS/TS-hez), SCA-t (OWASP Dependency-Check), container scant (Trivy), secret scant (Gitleaks), és Dockerfile lintet (Hadolint); a `Docker Build + Push to GHCR` csak `master`-en fut.

## 7. Tesztelési stratégia

A backend tesztek sima JUnit 5 + Mockito unit tesztek — nincs Testcontainers, nincs embedded Mongo/Neo4j/Elasticsearch. Ez azt jelenti:

- A sima logika (entitás-validáció, JSON parse-olás, üzenet-előzmény levágás, dedup kulcs szerint) közvetlenül van tesztelve.
- Bármi, ami MongoDB-vel/Neo4j-vel/Elasticsearch-csel/Ollamával beszél, a repository/kliens interfész mockolásával van tesztelve, nem a valódi rendszer felállításával. A `WebClient` és `Neo4jClient` fluent builder láncai Mockito `RETURNS_DEEP_STUBS`-szal vannak mockolva, nem kézzel felépített stub minden lánc-metódushoz.
- Ahol egy tesztnek egy lekérdezés *szerkezetéről* kell állítania valamit, nem csak az eredményéről (pl. "tartalmazza-e az e-Discovery keresési kérés a `fuzziness: AUTO`-t?"), a kérés objektum egy `ArgumentCaptor`-ral van elfogva, és az Elasticsearch Java kliens saját JSON szerializációjával vizsgálva (`co.elastic.clients.json.JsonpUtils.toString(...)`), nem a lekérdezés-építő logika tesztben való újraimplementálásával.
- A konkurencia-érzékeny védelmek (mint a `KnowledgeGraphIngestionService` "már fut" ellenőrzése) egy valódi második szállal és egy rövid, determinisztikus sleep-pel vannak tesztelve, nem kihagyva — inline jelezve, ha egy védelem valóban nem tesztelhető így flakiness nélkül.

Szándékosan nincs teszt az `ElasticsearchConfig` index-bootstrap logikájára, sem a Docker/Caddy/CI konfigurációra magára — ezeket a stack valódi futtatásával ellenőrizzük, nem unit tesztekkel.
