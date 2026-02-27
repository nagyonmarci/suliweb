# Embedder Skill

## Cél
Szöveg kinyerés minden fájltípusból, chunking, embedding → Qdrant.
**Csak `rag_winner=1` elemeken fut.**

## Szövegkinyerők

### PDF – PyMuPDF
```python
import fitz
def extract_pdf(path: str) -> str:
    doc = fitz.open(path)
    return "\n".join(page.get_text() for page in doc)
```

### DOCX
```python
from docx import Document
def extract_docx(path: str) -> str:
    doc = Document(path)
    return "\n".join(p.text for p in doc.paragraphs if p.text.strip())
```

### PPTX
```python
from pptx import Presentation
def extract_pptx(path: str) -> str:
    prs = Presentation(path)
    texts = []
    for slide in prs.slides:
        for shape in slide.shapes:
            if hasattr(shape, "text"):
                texts.append(shape.text)
    return "\n".join(texts)
```

### XLSX
```python
import openpyxl
def extract_xlsx(path: str) -> str:
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    rows = []
    for ws in wb.worksheets:
        for row in ws.iter_rows(values_only=True):
            rows.append(" | ".join(str(c) for c in row if c is not None))
    return "\n".join(rows)
```

### Képek – DeepSeek-OCR @ Ollama

```python
import httpx, base64

PROMPTS = {
    "document": "Convert the document to markdown.",
    "table":    "Given the layout of the image.",
    "figure":   "Parse the figure.",
    "general":  "Free OCR.",
}

def detect_image_type(path: str) -> str:
    from PIL import Image
    img = Image.open(path)
    w, h = img.size
    ratio = h / w
    # Portré arány + nagy felbontás → valószínűleg szkennelt dok
    if ratio > 1.2 and w > 800:
        return "document"
    if ratio < 0.6:
        return "figure"
    return "general"

def extract_image(path: str, ollama_host: str) -> str:
    img_type = detect_image_type(path)
    prompt = PROMPTS[img_type]
    
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode()
    
    r = httpx.post(f"{ollama_host}/api/generate", json={
        "model": "deepseek-ocr",
        "prompt": prompt,
        "images": [b64],
        "stream": False,
    }, timeout=60)
    return r.json()["response"]
```

## Chunking

```python
def chunk_text(text: str, chunk_size: int = 512, overlap: int = 64) -> list[str]:
    words = text.split()
    chunks = []
    i = 0
    while i < len(words):
        chunk = " ".join(words[i:i + chunk_size])
        chunks.append(chunk)
        i += chunk_size - overlap
    return [c for c in chunks if len(c.strip()) > 50]  # üres chunk-ok kizárva
```

## Embedding – nomic-embed-text @ Ollama

```python
def embed(text: str, ollama_host: str) -> list[float]:
    r = httpx.post(f"{ollama_host}/api/embeddings", json={
        "model": "nomic-embed-text",
        "prompt": text,
    }, timeout=30)
    return r.json()["embedding"]
```

## Qdrant feltöltés

```python
from qdrant_client import QdrantClient
from qdrant_client.models import PointStruct, VectorParams, Distance

COLLECTION = "nas_documents"
VECTOR_SIZE = 768  # nomic-embed-text dimenziója

def ensure_collection(client: QdrantClient):
    if COLLECTION not in [c.name for c in client.get_collections().collections]:
        client.create_collection(COLLECTION, vectors_config=VectorParams(
            size=VECTOR_SIZE, distance=Distance.COSINE
        ))

def upsert_chunks(client: QdrantClient, source_type: str, source_id: str, chunks: list[str], ollama_host: str):
    points = []
    for i, text in enumerate(chunks):
        vector = embed(text, ollama_host)
        points.append(PointStruct(
            id=abs(hash(f"{source_type}:{source_id}:{i}")) % (2**63),
            vector=vector,
            payload={
                "source_type": source_type,
                "source_id": source_id,
                "chunk_index": i,
                "text": text,
            }
        ))
    client.upsert(collection_name=COLLECTION, points=points)
```

## Tartalom hash (content_hash) generálás

```python
import hashlib
def content_hash(text: str) -> str:
    normalized = " ".join(text.lower().split())  # whitespace normalizálás
    return hashlib.sha256(normalized.encode()).hexdigest()
```
