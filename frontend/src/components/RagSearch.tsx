import { useState, useEffect } from 'react';
import { api, type RagHealth } from '../lib/api';

type RagMode = 'all' | 'search' | 'manage';

export default function RagSearch({ mode = 'all' }: { mode?: RagMode }) {
  const [health, setHealth] = useState<RagHealth | null>(null);

  useEffect(() => {
    loadHealth();
    const interval = setInterval(loadHealth, 5000);
    return () => clearInterval(interval);
  }, []);

  async function loadHealth() {
    try {
      setHealth(await api.ragHealth());
    } catch { /* backend may not be running */ }
  }

  const stats = health?.stats;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className={`rounded-xl border p-4 ${health?.ollamaAvailable ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'}`}>
          <p className="text-xs font-medium opacity-75">Ollama</p>
          <p className={`text-lg font-bold ${health?.ollamaAvailable ? 'text-green-700' : 'text-red-700'}`}>
            {health === null ? '...' : health.ollamaAvailable ? 'Elérhető' : 'Nem elérhető'}
          </p>
        </div>
        <div className="rounded-xl border p-4 bg-blue-50 border-blue-200">
          <p className="text-xs font-medium text-blue-600">Feldolgozott emailek (KG)</p>
          <p className="text-lg font-bold text-blue-700">{stats?.embeddedChunks?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-amber-50 border-amber-200">
          <p className="text-xs font-medium text-amber-600">Függőben</p>
          <p className="text-lg font-bold text-amber-700">{stats?.pendingChunks?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-gray-50 border-gray-200">
          <p className="text-xs font-medium text-gray-600">Összes email</p>
          <p className="text-lg font-bold text-gray-700">{stats?.totalEmails?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
      </div>

      {(mode === 'search' || mode === 'all') && (
        <div className="bg-blue-50 border border-blue-200 rounded-xl p-5">
          <h3 className="font-semibold text-blue-800 mb-2">A szemantikus keresés átalakult</h3>
          <p className="text-sm text-blue-700 mb-3">
            A chunk-alapú vektoros keresés helyett két fejlettebb keresési felület érhető el:
          </p>
          <div className="flex flex-wrap gap-3">
            <a href="/ediscovery" className="inline-flex items-center gap-2 px-4 py-2 bg-white border border-blue-300 text-blue-700 text-sm font-medium rounded-lg hover:bg-blue-50 transition-colors">
              ⚖ e-Discovery – Teljes szöveges keresés
            </a>
            <a href="/knowledge-graph" className="inline-flex items-center gap-2 px-4 py-2 bg-white border border-blue-300 text-blue-700 text-sm font-medium rounded-lg hover:bg-blue-50 transition-colors">
              🕸 Knowledge Graph – Szemantikus elemzés
            </a>
          </div>
        </div>
      )}

      {(mode === 'manage' || mode === 'all') && (
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-5">
          <h3 className="font-semibold text-amber-800 mb-2">Az indexelés kezelése átkerült</h3>
          <p className="text-sm text-amber-700 mb-3">
            A Knowledge Graph és az e-Discovery indexelése a megfelelő oldalakon kezelhető.
            A Graph RAG chat az ott felépített tudásgráfot használja.
          </p>
          <a href="/knowledge-graph" className="inline-flex items-center gap-2 px-4 py-2 bg-white border border-amber-300 text-amber-700 text-sm font-medium rounded-lg hover:bg-amber-50 transition-colors">
            🕸 Knowledge Graph oldal megnyitása
          </a>
        </div>
      )}
    </div>
  );
}
