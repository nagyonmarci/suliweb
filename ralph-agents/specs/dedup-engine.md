# Spec – Dedup Engine

## Acceptance criteria

- Minden SHA256 duplikátum csoport megjelenik a `dup_groups` táblában `match_type='sha256'`-val
- Minden content_hash duplikátum (ahol SHA256 különböző) megjelenik `match_type='content_hash'`-sel
- Minden Message-ID duplikátum (több PST-ben szereplő email) megjelenik `match_type='message_id'`-vel
- Minden `dup_group`-hoz legalább 2 `dup_members` bejegyzés tartozik
- `saveable_mb` helyesen számolt: total_mb × (count-1)/count
- Automatikus winner javaslat: az `is_winner=1` tag a legjobb útvonalú elem
- Duplikált csoportok ismételt futásnál frissülnek (nem duplikálódnak) – UNIQUE(match_type, hash_value)

## Winner javaslat logika

Pontszámítás (magasabb = jobb):
1. Negatív pontja van, ha útvonalában szerepel: `/temp`, `/backup`, `/old`, `/archive`, `/copy`
2. Újabb `mtime` = jobb
3. Hosszabb fájlnév = jobb (általában a végső, megnevezett verzió)

## Tiltott

- Ne állítsd be automatikusan a `rag_winner` flaget – ez a felhasználó dolga a dashboardon
- Ne törölj semmilyen fájlt vagy emailt
- Ne futtasd a dedup engine-t, amíg az aktuális PST feldolgozása nem fejezett be
