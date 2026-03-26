import { useState, useEffect } from 'react';
import { api, type SearchResult, type EmailSearchResult, type RagStats, type RagHealth, type Email } from '../lib/api';

type ViewMode = 'chunks' | 'emails';

export default function RagSearch() {
  const [query, setQuery] = useState('');
  const [topK, setTopK] = useState(10);
  const [viewMode, setViewMode] = useState<ViewMode>('emails');
  const [chunkResults, setChunkResults] = useState<SearchResult[]>([]);
  const [emailResults, setEmailResults] = useState<EmailSearchResult[]>([]);
  const [health, setHealth] = useState<RagHealth | null>(null);
  const [searching, setSearching] = useState(false);
  const [ingesting, setIngesting] = useState(false);
  const [message, setMessage] = useState('');
  const [selectedEmail, setSelectedEmail] = useState<Email | null>(null);
  const [expandedChunks, setExpandedChunks] = useState<Set<number>>(new Set());
  const [includeAttachments, setIncludeAttachments] = useState(false);

  useEffect(() => {
    loadHealth();
    const interval = setInterval(loadHealth, 5000);
    return () => clearInterval(interval);
  }, []);

  async function loadHealth() {
    try {
      const h = await api.ragHealth();
      setHealth(h);
    } catch {
      // backend may not be running
    }
  }

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setMessage('');
    try {
      if (viewMode === 'emails') {
        const results = await api.ragSearchEmails(query, topK);
        setEmailResults(results);
        setChunkResults([]);
      } else {
        const results = await api.ragSearch(query, topK);
        setChunkResults(results);
        setEmailResults([]);
      }
    } catch (e: any) {
      setMessage('Keresési hiba: ' + e.message);
    } finally {
      setSearching(false);
    }
  }

  async function handleIngest() {
    setIngesting(true);
    setMessage('');
    try {
      const result = await api.ragIngest(includeAttachments);
      setMessage(result);
      loadHealth();
    } catch (e: any) {
      setMessage('Indexelési hiba: ' + e.message);
    } finally {
      setIngesting(false);
    }
  }

  async function handleEmbed() {
    setMessage('');
    try {
      const result = await api.ragEmbed();
      setMessage(result);
      loadHealth();
    } catch (e: any) {
      setMessage('Embedding hiba: ' + e.message);
    }
  }

  async function handleResetFailed() {
    setMessage('');
    try {
      const result = await api.ragResetFailed();
      setMessage(result);
      loadHealth();
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    }
  }

  async function handleResetAll() {
    if (!confirm('Biztosan törölni szeretnél minden eddigi RAG indexelést és elölről kezdeni?')) return;
    setMessage('');
    try {
      const result = await api.ragResetAll();
      setMessage(result);
      loadHealth();
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    }
  }

  function toggleChunkExpand(index: number) {
    setExpandedChunks(prev => {
      const next = new Set(prev);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });
  }

  const stats = health?.stats;
  const hasResults = emailResults.length > 0 || chunkResults.length > 0;

  return (
    <div className="space-y-6">
      {/* Health & Stats */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className={`rounded-xl border p-4 ${health?.ollamaAvailable ? 'bg-green-50 border-green-200' : 'bg-red-50 border-red-200'}`}>
          <p className="text-xs font-medium opacity-75">Ollama</p>
          <p className={`text-lg font-bold ${health?.ollamaAvailable ? 'text-green-700' : 'text-red-700'}`}>
            {health?.ollamaAvailable ? 'Elérhető' : 'Nem elérhető'}
          </p>
        </div>
        <div className="rounded-xl border p-4 bg-blue-50 border-blue-200">
          <p className="text-xs font-medium text-blue-600">Indexelt chunkek</p>
          <p className="text-lg font-bold text-blue-700">{stats?.embeddedChunks?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-amber-50 border-amber-200">
          <p className="text-xs font-medium text-amber-600">Függőben</p>
          <p className="text-lg font-bold text-amber-700">{stats?.pendingChunks?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-gray-50 border-gray-200">
          <p className="text-xs font-medium text-gray-600">Összes chunk</p>
          <p className="text-lg font-bold text-gray-700">{stats?.totalChunks?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
      </div>

      {/* Ingestion controls */}
      <div className="bg-white rounded-xl border border-gray-200 p-5">
        <div className="flex justify-between items-start mb-4">
          <h3 className="text-sm font-semibold text-gray-700">Indexelés kezelése</h3>
          <button
            onClick={handleResetAll}
            className="text-xs text-red-500 hover:text-red-700 underline"
          >
            Indexelés törlése és újrakezdés
          </button>
        </div>
        
        <div className="flex flex-wrap gap-3">
          <div className="flex flex-col gap-2">
            <button
              onClick={handleIngest}
              disabled={ingesting || health?.ingestionRunning}
              className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {health?.ingestionRunning ? 'Indexelés folyamatban...' : ingesting ? 'Indítás...' : 'Emailek indexelése'}
            </button>
            {/* Attachment toggle */}
            <label className={`flex items-center gap-2 cursor-pointer select-none ${
              (ingesting || health?.ingestionRunning) ? 'opacity-50 pointer-events-none' : ''
            }`}>
              <div
                onClick={() => setIncludeAttachments(v => !v)}
                className={`relative inline-flex items-center w-9 h-5 rounded-full transition-colors ${
                  includeAttachments ? 'bg-blue-600' : 'bg-gray-300'
                }`}
              >
                <span className={`absolute left-0.5 w-4 h-4 bg-white rounded-full shadow transition-transform ${
                  includeAttachments ? 'translate-x-4' : 'translate-x-0'
                }`} />
              </div>
              <span className="text-xs text-gray-600">
                Csatolányok szövegkinyerese (PDF, DOCX…) – lassabb
              </span>
            </label>
          </div>
          
          <button
            onClick={handleEmbed}
            disabled={!stats?.pendingChunks || health?.ingestionRunning}
            className="px-4 py-2 bg-gray-700 text-white text-sm rounded-lg hover:bg-gray-800 disabled:opacity-50 transition-colors"
          >
            Embedding generálás ({stats?.pendingChunks ?? 0} függő)
          </button>

          {stats?.failedChunks && stats.failedChunks > 0 ? (
            <button
              onClick={handleResetFailed}
              className="px-4 py-2 bg-amber-100 text-amber-700 text-sm rounded-lg hover:bg-amber-200 transition-colors border border-amber-200"
            >
              {stats.failedChunks} hiba visszaállítása
            </button>
          ) : null}
        </div>
        
        {message && (
          <p className={`text-sm mt-3 p-2 rounded ${message.includes('hiba') || message.includes('Hiba') ? 'bg-red-50 text-red-600 border border-red-100' : 'bg-green-50 text-green-600 border border-green-100'}`}>
            {message}
          </p>
        )}
      </div>

      {/* Search form */}
      <div className="bg-white rounded-xl border border-gray-200 p-5">
        <h3 className="text-sm font-semibold text-gray-700 mb-3">Szemantikus keresés</h3>
        <form onSubmit={handleSearch} className="space-y-3">
          <div className="flex gap-3">
            <input
              type="text"
              value={query}
              onChange={e => setQuery(e.target.value)}
              placeholder="Írj be egy kérdést vagy keresőkifejezést..."
              className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
            <button
              type="submit"
              disabled={searching || !query.trim()}
              className="px-5 py-2.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors whitespace-nowrap"
            >
              {searching ? 'Keresés...' : 'Keresés'}
            </button>
          </div>
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <label className="text-xs text-gray-500">Nézet:</label>
              <select
                value={viewMode}
                onChange={e => setViewMode(e.target.value as ViewMode)}
                className="text-sm border border-gray-300 rounded-lg px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option value="emails">Email csoportosítás</option>
                <option value="chunks">Chunk részletek</option>
              </select>
            </div>
            <div className="flex items-center gap-2">
              <label className="text-xs text-gray-500">Max találat:</label>
              <select
                value={topK}
                onChange={e => setTopK(Number(e.target.value))}
                className="text-sm border border-gray-300 rounded-lg px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                {[5, 10, 20, 50].map(n => <option key={n} value={n}>{n}</option>)}
              </select>
            </div>
          </div>
        </form>
      </div>

      {/* Email detail modal */}
      {selectedEmail && (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4" onClick={() => setSelectedEmail(null)}>
          <div className="bg-white rounded-xl max-w-3xl w-full max-h-[80vh] overflow-auto p-6" onClick={e => e.stopPropagation()}>
            <div className="flex justify-between items-start mb-4">
              <h3 className="text-lg font-semibold">{selectedEmail.subject || '(nincs tárgy)'}</h3>
              <button onClick={() => setSelectedEmail(null)} className="text-gray-400 hover:text-gray-600 text-xl">&times;</button>
            </div>
            <div className="space-y-2 text-sm">
              <p><span className="font-medium text-gray-600">Feladó:</span> {selectedEmail.senderName} &lt;{selectedEmail.senderEmailAddress}&gt;</p>
              <p><span className="font-medium text-gray-600">Címzettek:</span> {selectedEmail.recipients?.join(', ')}</p>
              {selectedEmail.cc?.length > 0 && <p><span className="font-medium text-gray-600">CC:</span> {selectedEmail.cc.join(', ')}</p>}
              <p><span className="font-medium text-gray-600">Dátum:</span> {formatDate(selectedEmail.receivedTime)}</p>
              <p><span className="font-medium text-gray-600">PST fájl:</span> {selectedEmail.pstFileName}</p>
              <p><span className="font-medium text-gray-600">Mappa:</span> {selectedEmail.folderPath}</p>
              {selectedEmail.attachmentPaths?.length > 0 && (
                <div>
                  <span className="font-medium text-gray-600">Csatolmányok:</span>
                  <ul className="list-disc list-inside mt-1">
                    {selectedEmail.attachmentPaths.map((a, i) => <li key={i} className="text-gray-700">{a.split('/').pop()}</li>)}
                  </ul>
                </div>
              )}
              <hr className="my-3" />
              <div className="whitespace-pre-wrap text-gray-700 max-h-64 overflow-auto bg-gray-50 p-3 rounded">
                {selectedEmail.body || '(üres levéltörzs)'}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Results: Email view */}
      {viewMode === 'emails' && emailResults.length > 0 && (
        <div className="space-y-4">
          <h3 className="text-sm font-semibold text-gray-600">{emailResults.length} email találat</h3>
          {emailResults.map((result, idx) => (
            <div key={idx} className="bg-white rounded-xl border border-gray-200 overflow-hidden">
              <div
                className="p-4 cursor-pointer hover:bg-gray-50 transition-colors"
                onClick={() => setSelectedEmail(result.email)}
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0 flex-1">
                    <h4 className="font-medium text-gray-900 truncate">
                      {result.email.subject || '(nincs tárgy)'}
                    </h4>
                    <p className="text-sm text-gray-500 mt-0.5">
                      {result.email.senderName || result.email.senderEmailAddress}
                      <span className="mx-2">·</span>
                      {formatDate(result.email.receivedTime)}
                    </p>
                  </div>
                  <div className="flex-shrink-0">
                    <ScoreBadge score={result.bestScore} />
                  </div>
                </div>
                {/* Matched chunks preview */}
                <div className="mt-3 space-y-2">
                  {result.matchedChunks.slice(0, 3).map((chunk, ci) => (
                    <div key={ci} className="text-sm bg-gray-50 rounded-lg p-3 border border-gray-100">
                      <div className="flex items-center gap-2 mb-1">
                        <SourceBadge sourceType={chunk.sourceType} fileName={chunk.attachmentFileName} />
                        <span className="text-xs text-gray-400">{(chunk.score * 100).toFixed(0)}%</span>
                      </div>
                      <p className="text-gray-700 line-clamp-2">{chunk.content}</p>
                    </div>
                  ))}
                  {result.matchedChunks.length > 3 && (
                    <p className="text-xs text-gray-400 pl-1">
                      + {result.matchedChunks.length - 3} további találat
                    </p>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Results: Chunk view */}
      {viewMode === 'chunks' && chunkResults.length > 0 && (
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-gray-600">{chunkResults.length} chunk találat</h3>
          {chunkResults.map((chunk, idx) => (
            <div key={idx} className="bg-white rounded-xl border border-gray-200 p-4">
              <div className="flex items-start justify-between gap-3 mb-2">
                <div className="flex items-center gap-2 min-w-0">
                  <SourceBadge sourceType={chunk.sourceType} fileName={chunk.attachmentFileName} />
                  <span className="text-sm font-medium text-gray-900 truncate">{chunk.emailSubject}</span>
                </div>
                <ScoreBadge score={chunk.score} />
              </div>
              <p className="text-xs text-gray-500 mb-2">
                {chunk.senderName} · {chunk.pstFileName}
              </p>
              <div
                className={`text-sm text-gray-700 bg-gray-50 rounded-lg p-3 cursor-pointer ${expandedChunks.has(idx) ? '' : 'line-clamp-3'}`}
                onClick={() => toggleChunkExpand(idx)}
              >
                {chunk.content}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* No results */}
      {!searching && query && !hasResults && (
        <div className="text-center py-12 text-gray-400">
          <p className="text-lg">Nincs találat</p>
          <p className="text-sm mt-1">Próbálj más keresőkifejezést vagy növeld a max találatot</p>
        </div>
      )}
    </div>
  );
}

function ScoreBadge({ score }: { score: number }) {
  const pct = (score * 100).toFixed(0);
  const color = score >= 0.8 ? 'bg-green-100 text-green-700' :
                score >= 0.6 ? 'bg-blue-100 text-blue-700' :
                'bg-gray-100 text-gray-600';
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${color}`}>
      {pct}%
    </span>
  );
}

function SourceBadge({ sourceType, fileName }: { sourceType: string; fileName: string | null }) {
  const labels: Record<string, { label: string; color: string }> = {
    email_body: { label: 'Levéltörzs', color: 'bg-blue-100 text-blue-700' },
    email_subject: { label: 'Tárgy', color: 'bg-purple-100 text-purple-700' },
    attachment: { label: fileName ?? 'Csatolmány', color: 'bg-amber-100 text-amber-700' },
  };
  const info = labels[sourceType] ?? { label: sourceType, color: 'bg-gray-100 text-gray-600' };
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${info.color} max-w-[200px] truncate`}>
      {info.label}
    </span>
  );
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-';
  try {
    return new Date(dateStr).toLocaleString('hu-HU', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return dateStr;
  }
}
