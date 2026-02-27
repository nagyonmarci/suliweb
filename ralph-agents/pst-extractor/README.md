# PST Extractor

This component extracts email data and attachments from PST files.

## Features

- Parses PST files using pypff library
- Extracts email metadata (subject, sender, recipients, timestamps)
- Saves email body text
- Extracts and saves attachments to the extracted directory
- Integrates with the main database for tracking processed files
- Follows the same activity guard pattern as other services

## Database Schema

The extractor uses the following database tables:

### emails
Stores email information extracted from PST files.

```sql
CREATE TABLE emails (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id      TEXT    UNIQUE,
    pst_path        TEXT    NOT NULL REFERENCES files(path),
    subject         TEXT,
    sender          TEXT,
    recipients      TEXT,
    sent_at         REAL,
    body_text       TEXT,
    body_hash       TEXT,
    has_attachments BOOLEAN DEFAULT 0,
    attachment_count INTEGER DEFAULT 0,
    extracted_at    REAL,
    embed_status    TEXT DEFAULT 'pending',
    embedded_at     REAL,
    rag_winner      BOOLEAN DEFAULT 1
);
```

### attachments
Stores information about attachments found in emails.

```sql
CREATE TABLE attachments (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    email_id        INTEGER NOT NULL REFERENCES emails(id),
    original_name   TEXT    NOT NULL,
    extension       TEXT    NOT NULL,
    size            INTEGER,
    sha256          TEXT,
    content_hash    TEXT,
    extracted_path  TEXT,
    extracted_at    REAL,
    embed_status    TEXT DEFAULT 'pending',
    embedded_at     REAL,
    rag_winner      BOOLEAN DEFAULT 1
);
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `NAS_HOST` | NAS IP address | `192.168.1.100` |
| `NAS_USER` | NAS username | `admin` |
| `NAS_PASS` | NAS password | (empty) |
| `DB_PATH` | Path to SQLite database | `/data/index.db` |
| `EXTRACTED_PATH` | Path to save extracted attachments | `/mnt/extracted` |
| `ACTIVE_HOURS_START` | Start of active hours | `22` |
| `ACTIVE_HOURS_END` | End of active hours | `6` |
| `WEEKEND_ALWAYS_ACTIVE` | Activate on weekends | `true` |
| `MAX_NAS_CPU` | Max CPU usage percentage | `50` |
| `MAX_NAS_TX_MBPS` | Max network TX MB/s | `40` |
| `POLL_INTERVAL_SECONDS` | Polling interval | `60` |
| `RESCAN_DELAY_SECONDS` | Delay between scans | `21600` |
| `LOG_PATH` | Path to log file | `/data/pst-extractor.log` |

## Architecture

The PST extractor follows the same pattern as the indexer:

1. **Activity Guard**: Checks if it's within active hours and if NAS is not overloaded
2. **Main Loop**: Continuously scans for new PST files to process
3. **PST Reading**: Uses pypff to parse PST files
4. **Attachment Saving**: Saves attachments to the extracted directory
5. **Database Integration**: Stores email and attachment metadata in the database