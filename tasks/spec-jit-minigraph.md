# Spec: JIT Mini-gráf e-Discovery találatokból

## Cél

Az e-Discovery keresési találatokból on-the-fly részgráf megjelenítése — kiválasztott emailek
kommunikációs összefüggéseinek vizualizálása a Knowledge Graph adatai alapján, anélkül hogy
a felhasználónak a KG oldalra kellene navigálnia.

## Előfeltétel

- 1–6. fázis lezárva (kész)
- KG ingest lefutott (van adat Neo4j-ban)

## Backend — új endpoint

```
POST /api/ediscovery/minigraph
Body: { "mongoEmailIds": ["id1", "id2", ...] }   // max 50
```

### Válasz (új DTO)

```json
{
  "nodes": [
    { "id": "person:alice@example.com", "type": "PERSON", "label": "Alice" },
    { "id": "email:msg-123",            "type": "EMAIL",  "label": "Re: Szerződés" },
    { "id": "concept:TOPIC:EU-jog",     "type": "CONCEPT","label": "EU-jog" }
  ],
  "edges": [
    { "source": "person:alice@example.com", "target": "email:msg-123", "type": "SENT" },
    { "source": "email:msg-123", "target": "concept:TOPIC:EU-jog",    "type": "MENTIONS" }
  ]
}
```

### Implementációs terv

1. `GraphSearchService.buildMiniGraph(List<String> mongoEmailIds)` — Neo4j lekérdezés:
   ```cypher
   MATCH (e:Email) WHERE e.mongoId IN $ids
   OPTIONAL MATCH (p:Person)-[:SENT|TO|CC]->(e)
   OPTIONAL MATCH (e)-[:MENTIONS]->(c:Concept)
   RETURN e, p, c
   ```
2. Csomópontok és élek deduplikálása Java oldalon (Set<String> id alapján)
3. Max 200 csomópont visszaadva (ha több: CONCEPT csomópontok kihagyva először, majd TO/CC személyek)
4. `EDiscoveryController.miniGraph()` — `@PreAuthorize` nem szükséges, már `authenticated()` elég

## Frontend — e-Discovery oldal bővítése

- Keresési találatok mellett jelölőnégyzetek (checkbox) az emaileken
- "Mini-gráf mutatása" gomb aktív, ha ≥2 email ki van jelölve
- Jobb oldali panel (vagy modal): force-directed gráf `d3-force` vagy `react-force-graph-2d`
  - Csomópont szín: PERSON=kék, EMAIL=lila, CONCEPT=zöld
  - Csomópont méret: EMAIL csomóponton a szubjekt tooltip-ben
  - Klikk csomóponton: infó panel (email esetén megnyílik az e-Discovery részletview)

## Kockázatok és döntési pontok

| Kérdés | Döntés szükséges |
|---|---|
| d3-force vs react-force-graph-2d | react-force-graph-2d egyszerűbb React integráció |
| Modal vs split view | Split view jobban illeszkedik az e-Discovery oldalhoz |
| Max csomópont limit | 200 csomópont fölött lassul a browser — CONCEPT kihagyás az első tradeoff |
| Jogosultság | `authenticated()` elég, nincs ADMIN-only adat |

## Implementációs sorrend

1. `MiniGraphDto` (nodes + edges)
2. `GraphSearchService.buildMiniGraph()`
3. `EDiscoveryController` új endpoint
4. Frontend checkbox state az EDiscovery komponensben
5. `react-force-graph-2d` csomag + `MiniGraph.tsx` komponens
6. Integráció az `EDiscoverySearch.tsx`-be

## Elfogadási kritériumok

- 5 kijelölt emailre a gráf < 2 másodperc alatt megjelenik
- Duplikált csomópontok nem jelennek meg (sender egy személyként látszik)
- KG ingest nélkül graceful empty state ("Nincs gráfadat — futtasd a KG építést")
