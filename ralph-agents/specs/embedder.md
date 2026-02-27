# Spec – Embedder Service

## Acceptance criteria

- Csak `rag_winner=1` ÉS `embed_status='pending'` fájlokon fut
- PDF, DOCX, PPTX, XLSX, TXT szöveg kinyerve és chunk-olva
- JPG, PNG, TIFF, BMP képek DeepSeek-OCR-rel feldolgozva
- Minden chunk `nomic-embed-text` vektorral feltöltve Qdrant-ba
- `chunks` tábla és `qdrant_id` mezők szinkronban
- `embed_status` frissítve: `done` | `error` | `skipped`
- Már feldolgozott fájlok (`embed_status='done'`) nem dolgozódnak fel újra
- Service az `activity_guard.py` mintát követi

## Chunking szabályok

- Chunk méret: 512 szó
- Overlap: 64 szó
- Minimum chunk hossz: 50 karakter – rövidebb chunk-ot kihagyni
- Emails body_text is chunk-olandó (source_type='email')
- Attachment-ök is chunk-olandók (source_type='attachment')

## Kép feldolgozás szabályok

- Kép < 10 KB: `embed_status='skipped'`
- EXIF GPS adat jelen van: `embed_status='skipped'` (fotó, nem dokumentum)
- Egyéb: típus detekció → prompt választás → deepseek-ocr → text → chunk

## Qdrant collection

- Név: `nas_documents`
- Vector size: 768 (nomic-embed-text)
- Distance: Cosine
- Payload minden ponton: `source_type`, `source_id`, `chunk_index`, `text`

## content_hash generálás

Minden szövegkinyerés után számold ki:
```python
content_hash = sha256(" ".join(text.lower().split()).encode()).hexdigest()
```
Frissítsd a `files.content_hash` mezőt – ez alapján fut a dedup level 2.

## Tiltott

- Ne processeld `rag_winner=0` fájlokat
- Ne törölj Qdrant vektorokat – csak hozzáadás/frissítés
- Ollama URL: mindig `http://host.docker.internal:11434` Docker-ből
