# Astro Frontend Patterns

## Setup

```bash
npm create astro@latest frontend -- --template minimal --typescript strict
cd frontend
npm install @astrojs/react tailwindcss @astrojs/tailwind recharts
npm install -D @types/react
```

## astro.config.mjs

```js
import { defineConfig } from 'astro/config';
import react from '@astrojs/react';
import tailwind from '@astrojs/tailwind';

export default defineConfig({
  output: 'static',          // statikus build, Go serválja
  integrations: [react(), tailwind()],
  build: { assets: '_assets' },
});
```

## Design tokens – minimál dark (meglévő stílus)

```css
/* src/styles/global.css */
:root {
  --bg:       #0f1117;
  --surface:  #1a1d2e;
  --border:   #2d3748;
  --text:     #e2e8f0;
  --muted:    #718096;
  --blue:     #63b3ed;
  --green:    #68d391;
  --yellow:   #f6e05e;
  --red:      #fc8181;
  --orange:   #f6ad55;
}
```

## Base layout (src/layouts/Base.astro)

```astro
---
interface Props { title: string; active: string; }
const { title, active } = Astro.props;
---
<!DOCTYPE html>
<html lang="hu">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title} – NAS Indexer</title>
  <link rel="stylesheet" href="/styles/global.css">
</head>
<body class="bg-[#0f1117] text-[#e2e8f0] min-h-screen">
  <nav class="border-b border-[#2d3748] bg-[#1a1d2e] px-6 py-3 flex gap-6">
    <span class="text-[#63b3ed] font-semibold">📁 NAS Indexer</span>
    <a href="/"            class:list={["nav-link", {active: active==='stats'}]}>Áttekintés</a>
    <a href="/duplicates"  class:list={["nav-link", {active: active==='dupes'}]}>Duplikátumok</a>
    <a href="/search"      class:list={["nav-link", {active: active==='search'}]}>Keresés</a>
  </nav>
  <main class="p-6">
    <slot />
  </main>
</body>
</html>
```

## API hívások frontendről

```typescript
// src/lib/api.ts
const BASE = import.meta.env.DEV ? 'http://localhost:8080' : '';

export async function getStats() {
  const r = await fetch(`${BASE}/api/stats`);
  return r.json();
}

export async function getDuplicates(status = 'pending') {
  const r = await fetch(`${BASE}/api/duplicates?status=${status}`);
  return r.json();
}

export async function decide(groupId: number, action: string, winnerId?: string) {
  const r = await fetch(`${BASE}/api/duplicates/${groupId}/decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, winner_id: winnerId }),
  });
  return r.json();
}

export async function search(query: string, limit = 5) {
  const r = await fetch(`${BASE}/api/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, limit }),
  });
  return r.json();
}
```

## Island komponens minta (React)

```tsx
// src/components/DupCard.tsx
import { useState } from 'react';
import { decide } from '../lib/api';

interface Member {
  id: string;
  item_type: string;
  name: string;
  path: string;
  size_mb: number;
  is_winner: boolean;
}

interface DupCardProps {
  group: {
    id: number;
    match_type: 'sha256' | 'content_hash' | 'message_id';
    item_count: number;
    saveable_mb: number;
    members: Member[];
  };
  onResolved: (id: number) => void;
}

const BADGE = {
  sha256:       'bg-blue-900 text-blue-300',
  content_hash: 'bg-yellow-900 text-yellow-300',
  message_id:   'bg-green-900 text-green-300',
};

export default function DupCard({ group, onResolved }: DupCardProps) {
  const suggested = group.members.find(m => m.is_winner);
  const [selected, setSelected] = useState(suggested?.id ?? group.members[0]?.id);
  const [loading, setLoading] = useState(false);

  async function handleDecide(action: string) {
    setLoading(true);
    await decide(group.id, action, action === 'keep_one' ? selected : undefined);
    onResolved(group.id);
  }

  return (
    <div className="bg-[#1a1d2e] border border-[#2d3748] rounded-lg p-4 mb-3">
      <div className="flex items-center gap-3 mb-3">
        <span className={`text-xs font-bold px-2 py-0.5 rounded ${BADGE[group.match_type]}`}>
          {group.match_type}
        </span>
        <span className="text-[#718096] text-sm">
          {group.item_count} fájl · {group.saveable_mb.toFixed(0)} MB megtakarítható
        </span>
      </div>

      <div className="space-y-2 mb-4">
        {group.members.map(m => (
          <label key={m.id}
            className={`flex items-center gap-3 p-2 rounded cursor-pointer
              ${selected === m.id ? 'bg-[#1e2a3a] border border-[#63b3ed]' : 'border border-transparent'}`}>
            <input type="radio" name={`g-${group.id}`} value={m.id}
              checked={selected === m.id}
              onChange={() => setSelected(m.id)}
              className="accent-[#63b3ed]" />
            <div className="flex-1 min-w-0">
              <div className="font-medium text-sm truncate">{m.name}</div>
              <div className="text-[#718096] text-xs truncate">{m.path}</div>
            </div>
            <span className="text-[#a0aec0] text-xs whitespace-nowrap">
              {m.size_mb.toFixed(1)} MB
            </span>
            {m.is_winner && (
              <span className="text-xs text-[#68d391]">★ javasolt</span>
            )}
          </label>
        ))}
      </div>

      <div className="flex gap-2">
        <button onClick={() => handleDecide('keep_one')} disabled={loading}
          className="px-3 py-1.5 bg-[#63b3ed] text-[#0f1117] text-sm font-medium rounded hover:opacity-90 disabled:opacity-50">
          Ezt tartom meg
        </button>
        <button onClick={() => handleDecide('keep_all')} disabled={loading}
          className="px-3 py-1.5 border border-[#2d3748] text-sm rounded hover:bg-[#2d3748] disabled:opacity-50">
          Mindet megtartom
        </button>
        <button onClick={() => handleDecide('dismiss')} disabled={loading}
          className="px-3 py-1.5 text-[#718096] text-sm rounded hover:text-[#e2e8f0] disabled:opacity-50">
          Kihagyom
        </button>
      </div>
    </div>
  );
}
```

## NAS metrikák chart (Recharts Island)

```tsx
// src/components/NASChart.tsx
import { useEffect, useState } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

export default function NASChart() {
  const [data, setData] = useState([]);

  useEffect(() => {
    const load = async () => {
      const r = await fetch('/api/metrics/recent?minutes=60');
      const raw = await r.json();
      setData(raw.map(m => ({
        t:   new Date(m.ts * 1000).toLocaleTimeString('hu', {hour:'2-digit', minute:'2-digit'}),
        cpu: m.cpu_user,
        tx:  m.tx_mbps,
      })));
    };
    load();
    const id = setInterval(load, 30000);
    return () => clearInterval(id);
  }, []);

  return (
    <ResponsiveContainer width="100%" height={120}>
      <LineChart data={data}>
        <XAxis dataKey="t" tick={{fill:'#718096', fontSize:10}} interval="preserveStartEnd" />
        <YAxis tick={{fill:'#718096', fontSize:10}} />
        <Tooltip contentStyle={{background:'#1a1d2e', border:'1px solid #2d3748', borderRadius:6}} />
        <Line type="monotone" dataKey="cpu" stroke="#63b3ed" dot={false} strokeWidth={1.5} name="CPU %" />
        <Line type="monotone" dataKey="tx"  stroke="#68d391" dot={false} strokeWidth={1.5} name="TX MB/s" />
      </LineChart>
    </ResponsiveContainer>
  );
}
```

## Dev proxy (astro.config.mjs kiegészítés)

```js
// DEV módban a Go API-t proxyzza
vite: {
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
},
```

## Build + integráció Go-val

```makefile
# dashboard/Makefile
build-frontend:
	cd frontend && npm run build

build-go:
	CGO_ENABLED=1 go build -o dashboard .

build: build-frontend build-go

# Go main.go-ban:
# r.Handle("/*", http.FileServer(http.Dir("./frontend/dist")))
```
