# Spec – PST Extractor Service

## Acceptance criteria

- Minden PST fájl feldolgozódik, amelyet az `indexer` már indexelt (`files.extension='.pst'`)
- Email rekordok `message_id` alapján deduplikáltak – ugyanaz az email több PST-ben is csak egyszer kerül az `emails` táblába
- Csatolmányok fizikailag kimentve: `/mnt/extracted/{pst_stem}/{safe_message_id}/{filename}`
- Csatolmányok `attachments` táblában szerepelnek `extracted_path`-szal
- Már feldolgozott PST-k nem dolgozódnak fel újra (scan_state alapú)
- Service az `activity_guard.py` mintát követi (időzítés + NAS terhelés)
- Crash-safe: újraindítás után folytatja, nem kezdi elölről

## Email kötelező mezők

| Mező | Forrás |
|---|---|
| `message_id` | RFC Message-ID transport header |
| `subject` | msg.subject |
| `sender` | msg.sender_name |
| `sent_at` | msg.delivery_time.timestamp() |
| `body_text` | msg.plain_text_body |
| `body_hash` | sha256(normalize(body_text)) |
| `pst_path` | forrás PST fájl útvonala |

## Csatolmány kötelező mezők

| Mező | Forrás |
|---|---|
| `original_name` | att.name |
| `extension` | Path(att.name).suffix.lower() |
| `size` | att.size |
| `sha256` | sha256(kimentett fájl) |
| `extracted_path` | /mnt/extracted/{pst_stem}/{safe_mid}/{name} |

## Csatolmány útvonal szabály

```python
pst_stem  = Path(pst_path).stem          # pl. "mailbox_2020"
safe_mid  = re.sub(r'[^\w@.-]', '_', message_id or str(email_id))[:80]
out_dir   = Path(extracted_base) / pst_stem / safe_mid
```

## Duplikátum logika

Lásd: `@.claude/skills/dedup-engine/SKILL.md`
Minden PST feldolgozása után hívd meg a dedup engine-t.

## Tiltott

- Ne töröld a duplikált emaileket – csak jelöld a `dup_groups`/`dup_members` táblákban
- Ne módosítsd az `rag_winner` flaget – azt a dashboard dedup UI kezeli
