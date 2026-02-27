# PST Extractor Skill

## Cél
PST fájlok feldolgozása: emailek + csatolmányok kinyerése, duplikátum detekció.

## Könyvtár
`pypff` (libpff Python binding) – egyetlen megbízható PST parser.

```
pip install pypff  # vagy: apt-get install libpff-dev python3-pypff
```

## PST megnyitás és bejárás

```python
import pypff

def process_pst(pst_path: str):
    pf = pypff.file()
    pf.open(pst_path)
    root = pf.get_root_folder()
    walk_folder(root, pst_path)
    pf.close()

def walk_folder(folder, pst_path: str):
    for i in range(folder.number_of_sub_messages):
        msg = folder.get_sub_message(i)
        process_message(msg, pst_path)
    for i in range(folder.number_of_sub_folders):
        walk_folder(folder.get_sub_folder(i), pst_path)
```

## Email kinyerés

```python
def process_message(msg, pst_path: str) -> dict:
    return {
        "message_id": msg.get_transport_headers()  # Message-ID header kinyerése
                         .split("Message-ID:")[1].split("\n")[0].strip()
                         if "Message-ID:" in (msg.get_transport_headers() or "") else None,
        "subject":    msg.subject,
        "sender":     msg.sender_name,
        "sent_at":    msg.delivery_time.timestamp() if msg.delivery_time else None,
        "body_text":  msg.plain_text_body or "",
        "pst_path":   pst_path,
    }
```

## Csatolmány kinyerés

```python
def extract_attachments(msg, email_id: int, message_id: str, extracted_base: str):
    pst_stem = Path(pst_path).stem
    safe_mid = re.sub(r'[^\w@.-]', '_', message_id or str(email_id))
    
    for i in range(msg.number_of_attachments):
        att = msg.get_attachment(i)
        filename = att.name or f"attachment_{i}"
        out_dir = Path(extracted_base) / pst_stem / safe_mid
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / filename
        
        with open(out_path, 'wb') as f:
            f.write(att.read_buffer(att.size))
        
        yield {
            "original_name": filename,
            "extension": Path(filename).suffix.lower(),
            "size": att.size,
            "extracted_path": str(out_path),
        }
```

## Message-ID alapú duplikátum detekció

```python
# Az emails tábla message_id mezője UNIQUE – INSERT OR IGNORE-t használj
con.execute("""
    INSERT OR IGNORE INTO emails (message_id, pst_path, subject, ...)
    VALUES (?, ?, ?, ...)
""", (...))
# Ha 0 sor érintett: duplikátum, bekerül a dup_groups táblába
```

## Kimeneti könyvtár struktúra

```
/nas/_extracted/
└── {pst_stem}/
    └── {safe_message_id}/
        ├── document.pdf
        ├── image.jpg
        └── data.xlsx
```
