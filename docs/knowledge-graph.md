# Knowledge Graph (KG) – Részletes Dokumentáció

## Áttekintés

A projekt Knowledge Graph-ja egy **Neo4j** alapú gráfadatbázis, amely az emaileket, személyeket, beszélgetés-szálakat és kinyert fogalmakat (concepts) kapcsolja össze. Célja, hogy lehetővé tegye a komplex összefüggés-alapú keresést és a GraphRAG (Graph + Retrieval-Augmented Generation) chat funkciót.

A KG építése az `/api/kg/ingest` endpointon keresztül történik, és folyamatosan frissíthető.

---

## Adatmodell (Nodes & Relationships)

### Csomópontok (Nodes)

| Node típusa       | Osztály                  | Tulajdonságok                              | Leírás |
|-------------------|--------------------------|--------------------------------------------|--------|
| **Person**        | `PersonNode`            | `email`, `name`, `organization`            | Személyek (feladók/címzettek) |
| **Email**         | `EmailNode`             | `messageId`, `mongoId`, `subject`, `date`, `pstFileName` | Az email maga |
| **Thread**        | `ThreadNode`            | `threadId`, `subject`, `lastActivity`      | Beszélgetés szál |
| **Concept**       | `ConceptNode`           | `name`, `type` (PERSON, ORG, TOPIC, LOCATION) | Kinyert entitások/fogalmak (pl. "AI projekt") |
| **Attachment**    | `AttachmentNode`        | `sha256`, `filename`                       | Csatolmányok |

### Kapcsolatok (Relationships)

| Kapcsolat típusa       | Irány          | Leírás |
|------------------------|----------------|--------|
| `SENT`                 | Person → Email | Ki küldte az emailt |
| `TO` / `CC`            | Email → Person | Címzettek |
| `MENTIONS`             | Email → Concept | Az email milyen fogalmakat említ |
| `COMMUNICATES_WITH`    | Person → Person | Kommunikációs kapcsolat (count + lastDate tulajdonságokkal) |
| `PART_OF` / `BELONGS_TO` | Email → Thread | Melyik szálhoz tartozik |
| `REPLY_TO`             | Email → Email  | Válasz-kapcsolat |
| `HAS_ATTACHMENT`       | Email → Attachment | Csatolmányok |

---

## Konkrét példa lépésről lépésre

**Bemeneti email:**

```text
Feladó: tamas@company.hu (Tamás Kovács)
Címzettek: anna@company.hu, marketing@company.hu
Tárgy: AI projekt tervezése
Szöveg: "A következő sprintben integráljuk a LangChain-t a rendszerbe. 
         Fontos a marketing csapat bevonása."
ConversationId: "thread-xyz-001"
```

### 1. NER feldolgozás (`NerExtractor`)

Az `NerExtractor` (jelenleg helyettesítő implementáció) kinyeri:
- `AI projekt` → Concept (type: TOPIC)
- `LangChain` → Concept (type: TOPIC)
- `marketing csapat` → Concept (type: ORG)

### 2. Gráfba írás (`KnowledgeGraphIngestionService.doWriteEmail()`)

Létrehozott elemek Neo4j-ban:

```cypher
// Csomópontok
(p1:Person {email: "tamas@company.hu", name: "Tamás Kovács", organization: "company.hu"})
(e1:Email {messageId: "thread-xyz-001-abc", subject: "AI projekt tervezése", date: "2026-06-12..."})
(t1:Thread {threadId: "thread-xyz-001"})
(c1:Concept {name: "AI projekt", type: "TOPIC"})
(c2:Concept {name: "LangChain", type: "TOPIC"})
(c3:Concept {name: "marketing csapat", type: "ORG"})

// Kapcsolatok
(p1)-[:SENT]->(e1)
(e1)-[:TO]->(p2:Person {email: "anna@company.hu"})
(e1)-[:TO]->(p3:Person {email: "marketing@company.hu"})
(e1)-[:MENTIONS]->(c1)
(e1)-[:MENTIONS]->(c2)
(e1)-[:MENTIONS]->(c3)
(p1)-[:COMMUNICATES_WITH {count: 23, lastDate: "2026-06-12"}]->(p2)
(e1)-[:PART_OF]->(t1)
```

---

## Főbb szolgáltatások

### 1. `KnowledgeGraphIngestionService`
- `ingestAll()`: Teljes email állomány feldolgozása batch-ekben
- `ingestConceptsOnly()`: Csak a Concept kapcsolatok újragenerálása
- Párhuzamos feldolgozás (Virtual Threads + Fixed Thread Pool)
- Progress tracking

### 2. `GraphSearchService`
- `findCommunicationPartners()`
- `getThreadEmails()`
- `findEmailsByConceptProximity()` – concept alapú keresés

### 3. `GraphRagChatService`
- Graph + vector search + LLM kombináció
- `/api/kg/chat/stream` endpoint
- Kontextusban használja a gráf kapcsolatokat a pontosabb válaszokhoz

### 4. Frontend (`KnowledgeGraph.tsx`)
- KG állapot lekérdezés
- Ingestion indítás
- Hálózati vizualizáció
- Concept és személy alapú keresés
- Chat felület

---

## Használat

### Admin felületen:
- **"Knowledge Graph építése"** gomb → teljes újraindexelés
- **"Koncepciók újraépítése"** gomb → csak concept kapcsolatok frissítése

### API endpointok:
- `POST /api/kg/ingest`
- `POST /api/kg/reingest-concepts`
- `GET /api/kg/status`
- `GET /api/kg/concept/{name}`
- `GET /api/kg/persons/{email}/network`
- `POST /api/kg/chat` és `/api/kg/chat/stream`

---

## Jövőbeli fejlesztési irányok

- Teljes GraphRAG pipeline finomhangolása
- Időalapú szűrés a COMMUNICATES_WITH kapcsolatokon
- Automatikus inkrementális frissítés (csak új emailek)
- Vizualizáció javítása (React Flow / Cytoscape)

---

**Dokumentáció készítő:** Kilo AI Assistant  
**Dátum:** 2026. június 12.
**Branch:** `feature/elk`

Ez a dokumentáció a `docs/knowledge-graph.md` fájlban is elérhető lesz a repositoryban.
