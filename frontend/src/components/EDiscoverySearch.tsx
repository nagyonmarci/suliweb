import { useState, useEffect, useRef } from 'react';
import { api, type EDiscoveryResult, type EDiscoveryStatus } from '../lib/api';

function renderSnippet(html: string) {
  if (!html) return null;
  const parts = html.split(/(<em>|<\/em>)/);
  let inEm = false;
  return parts.map((part, i) => {
    if (part === '<em>') { inEm = true; return null; }
    if (part === '</em>') { inEm = false; return null; }
    return inEm
      ? <mark key={i} className="bg-yellow-200 text-yellow-900 rounded px-0.5">{part}</mark>
      : part;
  });
}

function ScoreBadge({ score }: { score: number }) {
  const color = score >= 8 ? 'bg-green-100 text-green-700' :
                score >= 3 ? 'bg-blue-100 text-blue-700' :
                'bg-gray-100 text-gray-600';
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${color}`}>
      {score.toFixed(1)}
    </span>
  );
}

function formatDate(dateStr: string | null | undefined) {
  if (!dateStr) return '-';
  try {
    return new Date(dateStr).toLocaleDateString('hu-HU', {
      year: 'numeric', month: '2-digit', day: '2-digit',
    });
  } catch { return dateStr; }
}

function SearchSkeleton() {
  return (
    <div className="space-y-4">
      {[1, 2, 3].map(i => (
        <div key={i} className="bg-white rounded-xl border border-gray-200 p-4 animate-pulse">
          <div className="h-4 bg-gray-200 rounded w-3/4 mb-2" />
          <div className="h-3 bg-gray-100 rounded w-1/2 mb-3" />
          <div className="h-12 bg-gray-100 rounded-lg" />
        </div>
      ))}
    </div>
  );
}

export default function EDiscoverySearch() {
  const [query, setQuery] = useState('');
  const [topK, setTopK] = useState(10);
  const [results, setResults] = useState<EDiscoveryResult[]>([]);
  const [status, setStatus] = useState<EDiscoveryStatus | null>(null);
  const [searching, setSearching] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [ingesting, setIngesting] = useState(false);
  const [message, setMessage] = useState('');
  const [showFilters, setShowFilters] = useState(false);
  const [filterSender, setFilterSender] = useState('');
  const [filterPstOwner, setFilterPstOwner] = useState('');
  const [filterPstFile, setFilterPstFile] = useState('');
  const [filterDateFrom, setFilterDateFrom] = useState('');
  const [filterDateTo, setFilterDateTo] = useState('');
  const searchInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    loadStatus();
    const interval = setInterval(loadStatus, 5000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault();
        searchInputRef.current?.focus();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, []);

  async function loadStatus() {
    try {
      setStatus(await api.ediscoveryStatus());
    } catch { /* backend may not be running */ }
  }

  async function handleIngest() {
    setIngesting(true);
    setMessage('');
    try {
      const msg = await api.ediscoveryIngest();
      setMessage(msg);
      loadStatus();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage('Indexelési hiba: ' + e.message);
    } finally {
      setIngesting(false);
    }
  }

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setMessage('');
    try {
      const r = await api.ediscoverySearch(query, topK, {
        sender: filterSender.trim() || undefined,
        pstOwner: filterPstOwner.trim() || undefined,
        pstFileName: filterPstFile.trim() || undefined,
        dateFrom: filterDateFrom || undefined,
        dateTo: filterDateTo || undefined,
      });
      setResults(r);
      setHasSearched(true);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage('Keresési hiba: ' + e.message);
    } finally {
      setSearching(false);
    }
  }

  function clearFilters() {
    setFilterSender('');
    setFilterPstOwner('');
    setFilterPstFile('');
    setFilterDateFrom('');
    setFilterDateTo('');
  }

  const activeFilterCount = [filterSender, filterPstOwner, filterPstFile, filterDateFrom, filterDateTo].filter(Boolean).length;
  const stats = status?.stats;

  return (
    <div className="space-y-6">
      {/* Stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className={`rounded-xl border p-4 ${status?.running ? 'bg-amber-50 border-amber-200' : 'bg-gray-50 border-gray-200'}`}>
          <p className="text-xs font-medium text-gray-500">Állapot</p>
          <p className={`text-base font-bold ${status?.running ? 'text-amber-700' : 'text-gray-700'}`}>
            {status?.running ? 'Indexelés...' : 'Kész'}
          </p>
        </div>
        <div className="rounded-xl border p-4 bg-blue-50 border-blue-200">
          <p className="text-xs font-medium text-blue-600">Indexelt</p>
          <p className="text-lg font-bold text-blue-700">{stats?.indexed?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-gray-50 border-gray-200">
          <p className="text-xs font-medium text-gray-600">Átugrott (dedup)</p>
          <p className="text-lg font-bold text-gray-700">{stats?.skipped?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-amber-50 border-amber-200">
          <p className="text-xs font-medium text-amber-600">Csatolmány hiba</p>
          <p className="text-lg font-bold text-amber-700">{stats?.attFailures?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
      </div>

      {/* Ingest */}
      <div className="bg-white rounded-xl border border-gray-200 p-5">
        <h3 className="text-sm font-semibold text-gray-700 mb-3">Elasticsearch Indexelés</h3>
        <div className="flex items-center gap-3 flex-wrap">
          <button
            onClick={handleIngest}
            disabled={ingesting || status?.running}
            className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {status?.running ? 'Indexelés folyamatban...' : ingesting ? 'Indítás...' : 'Emailek ES indexelése'}
          </button>
          <p className="text-xs text-gray-400">Teljes archiválás: PST → szöveg + csatolmányok → Elasticsearch</p>
        </div>
        {message && (
          <div className={`text-sm mt-3 p-3 rounded-lg flex items-start gap-2 ${
            message.toLowerCase().includes('hiba') || message.toLowerCase().includes('error')
              ? 'bg-red-50 text-red-700 border border-red-200'
              : 'bg-green-50 text-green-700 border border-green-200'
          }`}>
            <span className="flex-1">{message}</span>
            <button onClick={() => setMessage('')} className="text-current opacity-50 hover:opacity-100">&times;</button>
          </div>
        )}
      </div>

      {/* Search form */}
      <div className="bg-white rounded-xl border border-gray-200 p-5">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold text-gray-700">Teljes szöveges keresés</h3>
          <span className="text-xs text-gray-400">Ctrl+K</span>
        </div>
        <form onSubmit={handleSearch} className="space-y-3">
          <div className="flex gap-3">
            <input
              ref={searchInputRef}
              type="text"
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Kulcsszó, szerződés, projekt, személy neve..."
              className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
            <select
              value={topK}
              onChange={e => setTopK(Number(e.target.value))}
              className="text-sm border border-gray-300 rounded-lg px-2 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {[5, 10, 20, 50].map(n => <option key={n} value={n}>{n} találat</option>)}
            </select>
            <button
              type="submit"
              disabled={searching || !query.trim()}
              className="px-5 py-2.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors whitespace-nowrap"
            >
              {searching ? 'Keresés...' : 'Keresés'}
            </button>
          </div>
          <div className="flex items-center">
            <button
              type="button"
              onClick={() => setShowFilters(v => !v)}
              className={`text-xs px-2.5 py-1 rounded-lg border transition-colors ${
                showFilters || activeFilterCount > 0
                  ? 'bg-blue-50 border-blue-300 text-blue-700'
                  : 'border-gray-300 text-gray-500 hover:bg-gray-50'
              }`}
            >
              Szűrők{activeFilterCount > 0 ? ` (${activeFilterCount})` : ''} {showFilters ? '▴' : '▾'}
            </button>
          </div>
          {showFilters && (
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 pt-2 border-t border-gray-100">
              <div>
                <label className="text-xs text-gray-500 block mb-1">Feladó e-mail</label>
                <input type="text" value={filterSender} onChange={e => setFilterSender(e.target.value)}
                  placeholder="pl. kovacs@pelda.hu"
                  className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
              <div>
                <label className="text-xs text-gray-500 block mb-1">PST tulajdonos</label>
                <input type="text" value={filterPstOwner} onChange={e => setFilterPstOwner(e.target.value)}
                  placeholder="pl. Kovács Péter"
                  className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
              <div>
                <label className="text-xs text-gray-500 block mb-1">PST fájl</label>
                <input type="text" value={filterPstFile} onChange={e => setFilterPstFile(e.target.value)}
                  placeholder="pl. archive.pst"
                  className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
              <div>
                <label className="text-xs text-gray-500 block mb-1">Dátum (tól)</label>
                <input type="date" value={filterDateFrom} onChange={e => setFilterDateFrom(e.target.value)}
                  className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
              <div>
                <label className="text-xs text-gray-500 block mb-1">Dátum (ig)</label>
                <input type="date" value={filterDateTo} onChange={e => setFilterDateTo(e.target.value)}
                  className="w-full px-3 py-1.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
              </div>
              {activeFilterCount > 0 && (
                <div className="flex items-end">
                  <button type="button" onClick={clearFilters}
                    className="text-xs text-red-500 hover:text-red-700 underline pb-1.5">
                    Szűrők törlése
                  </button>
                </div>
              )}
            </div>
          )}
        </form>
      </div>

      {searching && <SearchSkeleton />}

      {!searching && results.length > 0 && (
        <div className="space-y-4">
          <h3 className="text-sm font-semibold text-gray-600">{results.length} találat</h3>
          {results.map((r, idx) => (
            <div key={idx} className="bg-white rounded-xl border border-gray-200 p-4">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0 flex-1">
                  <h4 className="font-medium text-gray-900 truncate">{r.subject || '(nincs tárgy)'}</h4>
                  <p className="text-sm text-gray-500 mt-0.5">
                    {r.senderName || r.sender}
                    {r.sender && r.senderName && (
                      <span className="text-gray-400"> &lt;{r.sender}&gt;</span>
                    )}
                    <span className="mx-2 text-gray-300">·</span>
                    {formatDate(r.date)}
                    <span className="mx-2 text-gray-300">·</span>
                    <span className="text-gray-400">{r.pstFileName}</span>
                    {r.pstOwner && <span className="text-gray-400"> ({r.pstOwner})</span>}
                  </p>
                </div>
                <ScoreBadge score={r.score} />
              </div>
              {r.snippet && (
                <p className="mt-3 text-sm text-gray-700 bg-gray-50 rounded-lg p-3 leading-relaxed">
                  {renderSnippet(r.snippet)}
                </p>
              )}
            </div>
          ))}
        </div>
      )}

      {!searching && hasSearched && results.length === 0 && (
        <div className="text-center py-12 text-gray-400">
          <p className="text-lg">Nincs találat</p>
          <p className="text-sm mt-1">Próbálj más kulcsszót, vagy lazítsd a szűrőket</p>
          {activeFilterCount > 0 && (
            <button onClick={clearFilters} className="mt-3 text-sm text-blue-600 hover:text-blue-800 underline">
              Szűrők törlése és újrapróbálás
            </button>
          )}
        </div>
      )}
    </div>
  );
}
