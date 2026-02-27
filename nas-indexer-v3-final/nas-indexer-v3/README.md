# NAS Indexer & RAG Pipeline

A sophisticated document indexing and retrieval system designed to work with NAS (Network Attached Storage) systems, specifically targeting Synology NAS devices.

## Architecture

This system implements a complete RAG (Retrieval-Augmented Generation) pipeline that processes files from a NAS storage system:

```
MacBook M4 Max (128 GB RAM)
├── Docker Compose (restart: always)
│   ├── nas-monitor      → Synology API, terhelés mérés
│   ├── indexer          → SMB mount, SHA256, SQLite
│   ├── pst-extractor    → PST → emailek + csatolmányok
│   ├── embedder         → szöveg kinyerés + Qdrant
│   ├── qdrant           → vektortároló (port 6333)
│   └── dashboard        → web UI (port 8080)
├── Ollama (natív, Metal GPU)
│   ├── deepseek-ocr     → képek, szkennelt doksik
│   └── nomic-embed-text → embedding
└── SMB mount: /Volumes/nas → Synology DS418 /volume1
```

## Services

### Go Services
- **nas-monitor**: Interacts with Synology API to monitor system utilization
- **indexer**: Scans the NAS filesystem, computes file hashes, and stores metadata in SQLite
- **dashboard**: Provides web API and frontend UI for visualization and management

### Python Services
- **pst-extractor**: Processes PST files from Microsoft Outlook to extract emails and attachments
- **embedder**: Extracts text from various document types and creates vector embeddings for RAG

## Features

- Multi-file Format Support: Processes PDF, DOCX, PPTX, XLSX, images, text files, and PST files
- Duplicate Detection: Uses SHA256, content hash, and message ID for identifying duplicates
- RAG Pipeline: Full pipeline from file scanning to vector storage in Qdrant for semantic search
- Smart Scheduling: Uses ActivityGuard to avoid system overload, running primarily during off-peak hours
- Crash Recovery: Implements crash-safe scanning with progress tracking
- Docker-based Deployment: Uses Docker Compose for easy deployment and orchestration

## Database Schema

The system uses SQLite with WAL mode for shared access between Go and Python services, with tables for:
- Files metadata
- Email extraction results
- Attachments
- Duplicate groups and decisions
- Search chunks
- Scan run history

## Implementation Status

This repository contains the planning documentation and structure. Implementation is in progress.