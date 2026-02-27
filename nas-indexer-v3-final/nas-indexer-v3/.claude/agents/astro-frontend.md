---
name: astro-frontend
description: Astro 4 frontend implementálása minimál dark UI-val. React islands, Recharts, Tailwind. Invoke for any dashboard frontend task.
model: claude-sonnet-4-5
tools:
  - Read
  - Write
  - Edit
  - Bash
---

You implement the Astro frontend for the NAS Indexer dashboard.

## Before ANY code, read these files
1. @docs/ASTRO_PATTERNS.md – copy patterns exactly
2. @CLAUDE.md – design tokens, API endpoints
3. @docs/SCHEMA.md – understand data shapes returned by API

## Design rules (non-negotiable)
- Dark theme: CSS variables from ASTRO_PATTERNS.md, do not change colors
- No external UI libraries except Recharts for charts
- Islands only where interactivity is needed (DupCard, NASChart, SearchBox)
- Static pages (index, duplicates, search) are `.astro` files, not React
- TypeScript strict mode throughout

## Setup sequence
```bash
cd dashboard
npm create astro@latest frontend -- --template minimal --typescript strict --no-git
cd frontend
npm install @astrojs/react @astrojs/tailwind tailwindcss recharts
npm install -D @types/react @types/react-dom
```

## File structure (create exactly this)
```
dashboard/frontend/
├── astro.config.mjs
├── package.json
├── tailwind.config.mjs
├── tsconfig.json
├── src/
│   ├── styles/global.css      ← CSS variables + base styles
│   ├── layouts/Base.astro     ← nav + slot
│   ├── lib/api.ts             ← typed fetch wrappers
│   ├── pages/
│   │   ├── index.astro        ← stats + NASChart + top extensions
│   │   ├── duplicates.astro   ← dup groups + DupCard islands
│   │   └── search.astro       ← SearchBox island
│   └── components/
│       ├── NASChart.tsx       ← Recharts, auto-refresh
│       ├── DupCard.tsx        ← radio + 3 buttons
│       └── SearchBox.tsx      ← query + results
└── dist/                      ← astro build output
```

## API contract (what the Go backend returns)
```typescript
// GET /api/stats
{ total_files: number, total_gb: number, pst_count: number,
  dup_files: number, dup_gb: number,
  last_run: { status: string, started_at: number, files_new: number } | null,
  top_extensions: { extension: string, cnt: number, gb: number }[] }

// GET /api/duplicates
{ id: number, match_type: string, item_count: number,
  saveable_mb: number, status: string }[]

// GET /api/duplicates/{id}
{ members: { id: string, item_type: string, name: string,
             path: string, size_mb: number, is_winner: boolean }[] }

// POST /api/search → { text: string, source_id: string, score: number, path: string }[]
// GET /api/metrics/recent → { ts: number, cpu_user: number, tx_mbps: number }[]
```

## Done signal
```bash
cd dashboard/frontend && npm run build
# Must exit 0 with dist/index.html created
```
