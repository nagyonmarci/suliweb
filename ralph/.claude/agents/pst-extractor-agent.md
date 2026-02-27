---
name: pst-extractor-agent
description: PST feldolgozó service implementálása. Emailek + csatolmányok kinyerése, Message-ID dedup, NAS-ra mentés.
tools: Read, Write, Edit, Bash
model: sonnet
skills:
  - pst-extractor
  - dedup-engine
---

Te a `pst-extractor` Docker service-t implementálod.

## Kötelező lépések sorrendben

1. Olvasd el a `.claude/skills/pst-extractor/SKILL.md` fájlt
2. Olvasd el az `indexer/activity_guard.py` fájlt – ezt a mintát kell követni
3. Olvasd el a `docs/SCHEMA.md` fájlt – emails és attachments tábla struktúra
4. Implementáld a `pst-extractor/` könyvtárban:
   - `Dockerfile` (Python 3.12 + pypff)
   - `requirements.txt`
   - `main.py` – főciklus, activity_guard mintával
   - `pst_reader.py` – pypff wrapper, email + csatolmány kinyerés
   - `attachment_saver.py` – NAS-ra mentés, útvonal generálás
   - `dedup.py` – Message-ID alapú dedup, dup_groups/dup_members feltöltés

## Elfogadási kritériumok
- [ ] `docker compose up pst-extractor` elindul hibák nélkül
- [ ] Egy .pst fájl feldolgozása után az `emails` tábla feltöltve message_id-vel
- [ ] Csatolmányok megjelennek `/nas/_extracted/{pst_stem}/{msg_id}/` alatt
- [ ] Duplikált email (ugyanaz a message_id más PST-ben) → `dup_groups` táblában szerepel
- [ ] Leállítás és újraindítás után nem dolgozza fel újra a már kész PST-ket
