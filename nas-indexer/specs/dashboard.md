# Spec – Dashboard

## Acceptance criteria – Dedup UI

- `GET /api/duplicates?status=pending` visszaadja a csoportokat `saveable_mb` szerint csökkenő sorrendben
- `GET /api/duplicates/{id}` visszaadja a csoport tagjait fájl metaadatokkal (name, size, path, mtime)
- `POST /api/duplicates/{id}/decision` elfogadja: `{"action": "keep_one"|"keep_all"|"dismiss", "winner_id": "..."}`
- `keep_one` esetén: winner `rag_winner=1`, többiek `rag_winner=0`, group `status='reviewed'`
- `keep_all` esetén: mindenki `rag_winner=1`, group `status='reviewed'`
- `dismiss` esetén: group `status='dismissed'`, `rag_winner` nem változik
- UI: kártyás nézet, match_type badge (sha256 = kék, content_hash = sárga, message_id = zöld)
- UI: javasolt winner kiemelve más háttérrel
- UI: döntés után kártya eltűnik az animációval, számláló frissül

## Acceptance criteria – RAG Search

- `POST /api/search` body: `{"query": str, "limit": int}` → nomic-embed-text embed → Qdrant keresés
- Visszaad: `[{text, source_type, source_id, score, path}]`
- UI: keresőmező, Enter-re küld, találatok kártyákban szöveg előnézettel és forrás linkkel
- Score megjelenítve 0.00–1.00 formátumban

## Meglévő funkciók megőrzése

- NAS metrikák grafikon marad
- Scan státusz kártya marad
- PST lista marad
- Fájltípus eloszlás tábla marad

## Tiltott

- Ne nyiss WRITE SQLite kapcsolatot a dashboard-ból az adatbázisba (csak a decision endpoint-ok módosíthatnak)
- Ne töröljön semmilyen fájlt vagy emailt
