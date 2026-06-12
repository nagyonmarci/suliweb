import { useState, useEffect, useRef } from 'react';
import { api, type KgPersonNode, type KgEmailNode, type KgStatus, type ChatMessage, type ChatSource } from '../lib/api';

type KgTab = 'status' | 'network' | 'thread' | 'concept' | 'chat';

function formatDate(dateStr: string | null | undefined) {
  if (!dateStr) return '-';
  try {
    return new Date(dateStr).toLocaleDateString('hu-HU', {
      year: 'numeric', month: '2-digit', day: '2-digit',
    });
  } catch { return dateStr; }
}

function formatEta(seconds: number | null | undefined): string {
  if (seconds == null || seconds < 0) return '...';
  if (seconds < 60) return `${Math.round(seconds)} mp`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} perc`;
  return `${Math.floor(seconds / 3600)} ó ${Math.round((seconds % 3600) / 60)} perc`;
}

function EmailList({ items }: { items: KgEmailNode[] }) {
  return (
    <div className="space-y-3">
      {items.map((e, i) => (
        <div key={i} className="bg-white rounded-xl border border-gray-200 p-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0 flex-1">
              <p className="font-medium text-gray-900 truncate">{e.subject || '(nincs tárgy)'}</p>
              <p className="text-xs text-gray-500 mt-0.5">
                {formatDate(e.date)}
                {e.pstFileName && <span className="ml-2 text-gray-400">{e.pstFileName}</span>}
                {e.pstOwner && <span className="ml-2 text-gray-400">({e.pstOwner})</span>}
              </p>
            </div>
            <span className="text-xs text-gray-400 shrink-0">#{i + 1}</span>
          </div>
          {e.bodyDelta && (
            <p className="mt-2 text-sm text-gray-600 bg-gray-50 rounded-lg p-2 line-clamp-2">{e.bodyDelta}</p>
          )}
        </div>
      ))}
    </div>
  );
}

export default function KnowledgeGraph() {
  const [tab, setTab] = useState<KgTab>('status');
  const [kgStatus, setKgStatus] = useState<KgStatus | null>(null);
  const [ingesting, setIngesting] = useState(false);
  const [ingestMsg, setIngestMsg] = useState('');

  const [networkEmail, setNetworkEmail] = useState('');
  const [networkResults, setNetworkResults] = useState<KgPersonNode[]>([]);
  const [networkLoading, setNetworkLoading] = useState(false);
  const [networkSearched, setNetworkSearched] = useState(false);
  const [networkError, setNetworkError] = useState('');

  const [threadId, setThreadId] = useState('');
  const [threadResults, setThreadResults] = useState<KgEmailNode[]>([]);
  const [threadLoading, setThreadLoading] = useState(false);
  const [threadSearched, setThreadSearched] = useState(false);
  const [threadError, setThreadError] = useState('');

  const [conceptName, setConceptName] = useState('');
  const [conceptTopK, setConceptTopK] = useState(10);
  const [conceptResults, setConceptResults] = useState<KgEmailNode[]>([]);
  const [conceptLoading, setConceptLoading] = useState(false);
  const [conceptSearched, setConceptSearched] = useState(false);
  const [conceptError, setConceptError] = useState('');

  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [chatLoading, setChatLoading] = useState(false);
  const [chatError, setChatError] = useState('');
  const [chatModels, setChatModels] = useState<string[]>([]);
  const [chatModel, setChatModel] = useState('');
  const chatBottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    loadStatus();
    const interval = setInterval(loadStatus, 5000);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    api.ragModels()
      .then(list => { setChatModels(list); if (list.length > 0) setChatModel(list[0]); })
      .catch(() => {});
  }, []);

  useEffect(() => {
    chatBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatMessages.length, chatLoading]);

  async function loadStatus() {
    try {
      setKgStatus(await api.kgStatus());
    } catch { /* backend may not be running */ }
  }

  async function handleIngest() {
    setIngesting(true);
    setIngestMsg('');
    try {
      const msg = await api.kgIngest();
      setIngestMsg(msg);
      loadStatus();
    } catch (e: any) {
      setIngestMsg('Hiba: ' + e.message);
    } finally {
      setIngesting(false);
    }
  }

  async function handleNetworkSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!networkEmail.trim()) return;
    setNetworkLoading(true);
    setNetworkError('');
    try {
      setNetworkResults(await api.kgPersonNetwork(networkEmail.trim()));
      setNetworkSearched(true);
    } catch (e: any) {
      setNetworkError('Hiba: ' + e.message);
    } finally {
      setNetworkLoading(false);
    }
  }

  async function handleThreadSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!threadId.trim()) return;
    setThreadLoading(true);
    setThreadError('');
    try {
      setThreadResults(await api.kgThread(threadId.trim()));
      setThreadSearched(true);
    } catch (e: any) {
      setThreadError('Hiba: ' + e.message);
    } finally {
      setThreadLoading(false);
    }
  }

  async function handleConceptSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!conceptName.trim()) return;
    setConceptLoading(true);
    setConceptError('');
    try {
      setConceptResults(await api.kgConcept(conceptName.trim(), conceptTopK));
      setConceptSearched(true);
    } catch (e: any) {
      setConceptError('Hiba: ' + e.message);
    } finally {
      setConceptLoading(false);
    }
  }

  async function handleKgChat(e: React.FormEvent) {
    e.preventDefault();
    const text = chatInput.trim();
    if (!text || chatLoading) return;
    const userMsg: ChatMessage = { role: 'user', content: text };
    const history = chatMessages.map(m => ({ role: m.role, content: m.content }));
    setChatMessages(prev => [...prev, userMsg]);
    setChatInput('');
    setChatError('');
    setChatLoading(true);
    const placeholder: ChatMessage = { role: 'assistant', content: '', sources: [] };
    setChatMessages(prev => [...prev, placeholder]);
    let accumulated = '';
    try {
      const sources = await api.kgChatStream(
        text, 8, chatModel || undefined, history,
        (token: string) => {
          accumulated += token;
          const cur = accumulated;
          setChatMessages(prev => {
            const msgs = [...prev];
            const last = msgs[msgs.length - 1];
            if (last?.role === 'assistant') msgs[msgs.length - 1] = { ...last, content: cur };
            return msgs;
          });
        }
      );
      setChatMessages(prev => {
        const msgs = [...prev];
        const last = msgs[msgs.length - 1];
        if (last?.role === 'assistant') msgs[msgs.length - 1] = { ...last, content: accumulated, sources };
        return msgs;
      });
    } catch (err: any) {
      setChatError('Nem sikerült választ kapni: ' + err.message);
      setChatMessages(prev => prev.slice(0, -1));
    } finally {
      setChatLoading(false);
    }
  }

  const stats = kgStatus?.stats;

  const tabs: { id: KgTab; label: string }[] = [
    { id: 'status', label: 'Állapot & Építés' },
    { id: 'network', label: 'Hálózat' },
    { id: 'thread', label: 'Szál bejárás' },
    { id: 'concept', label: 'Fogalom közelség' },
    { id: 'chat', label: '💬 Chat' },
  ];

  return (
    <div className="space-y-6">
      {/* Stats overview */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className={`rounded-xl border p-4 ${kgStatus?.running ? 'bg-amber-50 border-amber-200' : 'bg-gray-50 border-gray-200'}`}>
          <p className="text-xs font-medium text-gray-500">Állapot</p>
          <p className={`text-base font-bold ${kgStatus?.running ? 'text-amber-700' : 'text-gray-700'}`}>
            {kgStatus?.running ? 'Épül...' : 'Kész'}
          </p>
        </div>
        <div className="rounded-xl border p-4 bg-gray-50 border-gray-200">
          <p className="text-xs font-medium text-gray-500">Összes email</p>
          <p className="text-lg font-bold text-gray-700">{stats?.totalEmails?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-indigo-50 border-indigo-200">
          <p className="text-xs font-medium text-indigo-600">Feldolgozva</p>
          <p className="text-lg font-bold text-indigo-700">{stats?.processed?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-red-50 border-red-200">
          <p className="text-xs font-medium text-red-600">Hiba</p>
          <p className="text-lg font-bold text-red-700">{stats?.failed?.toLocaleString('hu-HU') ?? '-'}</p>
        </div>
      </div>

      {/* Progress bar — csak futás közben */}
      {kgStatus?.running && stats && stats.totalEmails > 0 && (
        <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 space-y-2">
          <div className="flex justify-between text-xs text-amber-700 font-medium">
            <span>{stats.processed.toLocaleString('hu-HU')} / {stats.totalEmails.toLocaleString('hu-HU')} email</span>
            <span>{Math.round(stats.processed / stats.totalEmails * 100)}%</span>
          </div>
          <div className="w-full bg-amber-200 rounded-full h-2">
            <div
              className="bg-amber-500 h-2 rounded-full transition-all duration-500"
              style={{ width: `${Math.min(100, stats.processed / stats.totalEmails * 100)}%` }}
            />
          </div>
          <div className="flex gap-4 text-xs text-amber-600">
            {stats.ratePerMin > 0 && (
              <span>{stats.ratePerMin.toFixed(1)} email/perc</span>
            )}
            {stats.etaSeconds != null && (
              <span>Várható befejezés: {formatEta(stats.etaSeconds)}</span>
            )}
          </div>
        </div>
      )}

      {/* Tab bar */}
      <div className="flex gap-1 p-1 bg-gray-100 rounded-xl w-fit flex-wrap">
        {tabs.map(t => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`px-4 py-2 text-sm font-medium rounded-lg transition-all ${
              tab === t.id
                ? 'bg-white shadow text-indigo-700'
                : 'text-gray-600 hover:text-gray-900'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Status & Ingest */}
      {tab === 'status' && (
        <div className="bg-white rounded-xl border border-gray-200 p-5">
          <h3 className="text-sm font-semibold text-gray-700 mb-2">Knowledge Graph Építés</h3>
          <p className="text-xs text-gray-500 mb-4">
            Kommunikációs hálózat, entitás-kapcsolatok, szálgráf és dokumentum-tudásgráf Neo4j-ban.
            Entitáskinyerés Ollama (NER) segítségével.
          </p>
          <button
            onClick={handleIngest}
            disabled={ingesting || kgStatus?.running}
            className="px-4 py-2 bg-indigo-600 text-white text-sm rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition-colors"
          >
            {kgStatus?.running ? 'Gráf építése folyamatban...' : ingesting ? 'Indítás...' : 'Knowledge Graph építése'}
          </button>
          {ingestMsg && (
            <div className={`text-sm mt-3 p-3 rounded-lg flex items-start gap-2 ${
              ingestMsg.toLowerCase().includes('hiba') || ingestMsg.toLowerCase().includes('error')
                ? 'bg-red-50 text-red-700 border border-red-200'
                : 'bg-green-50 text-green-700 border border-green-200'
            }`}>
              <span className="flex-1">{ingestMsg}</span>
              <button onClick={() => setIngestMsg('')} className="text-current opacity-50 hover:opacity-100">&times;</button>
            </div>
          )}
        </div>
      )}

      {/* Network search */}
      {tab === 'network' && (
        <div className="space-y-4">
          <div className="bg-white rounded-xl border border-gray-200 p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3">Kommunikációs Hálózat</h3>
            <form onSubmit={handleNetworkSearch} className="flex gap-3">
              <input
                type="text"
                value={networkEmail}
                onChange={e => setNetworkEmail(e.target.value)}
                placeholder="E-mail cím, pl. kovacs@pelda.hu"
                className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
              <button
                type="submit"
                disabled={networkLoading || !networkEmail.trim()}
                className="px-5 py-2.5 bg-indigo-600 text-white text-sm rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition-colors"
              >
                {networkLoading ? 'Keresés...' : 'Keresés'}
              </button>
            </form>
          </div>
          {networkError && <p className="text-sm text-red-600 bg-red-50 p-3 rounded-lg border border-red-200">{networkError}</p>}
          {networkResults.length > 0 && (
            <div className="space-y-2">
              <h4 className="text-sm font-semibold text-gray-600">{networkResults.length} kommunikációs partner</h4>
              {networkResults.map((p, i) => (
                <div key={i} className="bg-white rounded-xl border border-gray-200 p-4 flex items-center gap-4">
                  <span className="w-9 h-9 bg-indigo-100 text-indigo-700 rounded-full flex items-center justify-center text-sm font-medium shrink-0">
                    {(p.name || p.email).charAt(0).toUpperCase()}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="font-medium text-gray-900 truncate">{p.name || p.email}</p>
                    {p.name && <p className="text-xs text-gray-500 truncate">{p.email}</p>}
                    {p.organization && <p className="text-xs text-gray-400">{p.organization}</p>}
                  </div>
                </div>
              ))}
            </div>
          )}
          {!networkLoading && networkSearched && networkResults.length === 0 && (
            <p className="text-center py-8 text-gray-400 text-sm">Nincs találat</p>
          )}
        </div>
      )}

      {/* Thread search */}
      {tab === 'thread' && (
        <div className="space-y-4">
          <div className="bg-white rounded-xl border border-gray-200 p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3">Szál Bejárás</h3>
            <form onSubmit={handleThreadSearch} className="flex gap-3">
              <input
                type="text"
                value={threadId}
                onChange={e => setThreadId(e.target.value)}
                placeholder="Szál azonosítója (threadId / conversationTopic)"
                className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
              <button
                type="submit"
                disabled={threadLoading || !threadId.trim()}
                className="px-5 py-2.5 bg-indigo-600 text-white text-sm rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition-colors"
              >
                {threadLoading ? 'Betöltés...' : 'Betöltés'}
              </button>
            </form>
          </div>
          {threadError && <p className="text-sm text-red-600 bg-red-50 p-3 rounded-lg border border-red-200">{threadError}</p>}
          {threadResults.length > 0 && (
            <>
              <h4 className="text-sm font-semibold text-gray-600">{threadResults.length} email a szálban</h4>
              <EmailList items={threadResults} />
            </>
          )}
          {!threadLoading && threadSearched && threadResults.length === 0 && (
            <p className="text-center py-8 text-gray-400 text-sm">Nincs találat</p>
          )}
        </div>
      )}

      {/* Concept search */}
      {tab === 'concept' && (
        <div className="space-y-4">
          <div className="bg-white rounded-xl border border-gray-200 p-5">
            <h3 className="text-sm font-semibold text-gray-700 mb-3">Fogalom Közelség</h3>
            <form onSubmit={handleConceptSearch} className="flex gap-3">
              <input
                type="text"
                value={conceptName}
                onChange={e => setConceptName(e.target.value)}
                placeholder="Fogalom neve, pl. szállítási szerződés"
                className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
              <select
                value={conceptTopK}
                onChange={e => setConceptTopK(Number(e.target.value))}
                className="text-sm border border-gray-300 rounded-lg px-2 py-2 focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                {[5, 10, 20].map(n => <option key={n} value={n}>{n}</option>)}
              </select>
              <button
                type="submit"
                disabled={conceptLoading || !conceptName.trim()}
                className="px-5 py-2.5 bg-indigo-600 text-white text-sm rounded-lg hover:bg-indigo-700 disabled:opacity-50 transition-colors"
              >
                {conceptLoading ? 'Keresés...' : 'Keresés'}
              </button>
            </form>
          </div>
          {conceptError && <p className="text-sm text-red-600 bg-red-50 p-3 rounded-lg border border-red-200">{conceptError}</p>}
          {conceptResults.length > 0 && (
            <>
              <h4 className="text-sm font-semibold text-gray-600">{conceptResults.length} kapcsolódó email</h4>
              <EmailList items={conceptResults} />
            </>
          )}
          {!conceptLoading && conceptSearched && conceptResults.length === 0 && (
            <p className="text-center py-8 text-gray-400 text-sm">Nincs találat</p>
          )}
        </div>
      )}

      {/* Chat */}
      {tab === 'chat' && (
        <div className="flex flex-col h-[60vh] min-h-[400px]">
          <div className="flex items-center gap-3 mb-4 pb-3 border-b border-gray-100">
            <span className="text-xs font-medium text-gray-500">Modell:</span>
            {chatModels.length === 0 ? (
              <span className="text-xs text-red-400">Ollama nem elérhető</span>
            ) : (
              <select
                value={chatModel}
                onChange={e => setChatModel(e.target.value)}
                disabled={chatLoading}
                className="text-sm border border-gray-200 rounded-lg px-3 py-1.5 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:opacity-50"
              >
                {chatModels.map(m => <option key={m} value={m}>{m}</option>)}
              </select>
            )}
            {chatMessages.length > 0 && (
              <button onClick={() => setChatMessages([])} className="ml-auto text-xs text-gray-400 hover:text-red-500 transition-colors">
                Törlés
              </button>
            )}
          </div>

          <div className="flex-1 overflow-y-auto space-y-4 pr-1">
            {chatMessages.length === 0 && !chatLoading && (
              <div className="flex items-center justify-center h-full text-gray-400 text-sm">
                Kérdezz a levelezési hálózatról…
              </div>
            )}
            {chatMessages.map((msg, idx) => (
              <div key={idx} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm ${
                  msg.role === 'user'
                    ? 'bg-indigo-600 text-white rounded-br-sm'
                    : 'bg-white border border-gray-200 text-gray-800 rounded-bl-sm shadow-sm'
                }`}>
                  <p className="whitespace-pre-wrap leading-relaxed">{msg.content}</p>
                  {msg.role === 'assistant' && msg.sources && msg.sources.length > 0 && (
                    <p className="text-xs mt-2 pt-2 border-t border-gray-100 text-gray-400">
                      {msg.sources.length} forrás e-mail
                    </p>
                  )}
                </div>
              </div>
            ))}
            {chatLoading && (
              <div className="flex justify-start">
                <div className="bg-white border border-gray-200 rounded-2xl rounded-bl-sm px-4 py-3 shadow-sm">
                  <div className="flex gap-1">
                    {[0, 150, 300].map(d => (
                      <span key={d} className="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style={{ animationDelay: `${d}ms` }} />
                    ))}
                  </div>
                </div>
              </div>
            )}
            {chatError && (
              <div className="bg-red-50 border border-red-100 text-red-600 text-sm px-4 py-3 rounded-xl">{chatError}</div>
            )}
            <div ref={chatBottomRef} />
          </div>

          <form onSubmit={handleKgChat} className="mt-4 flex gap-2">
            <input
              type="text"
              value={chatInput}
              onChange={e => setChatInput(e.target.value)}
              placeholder="Kérdezz a levelezési hálózatról…"
              disabled={chatLoading}
              className="flex-1 px-4 py-3 border border-gray-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 disabled:opacity-50 bg-white"
            />
            <button
              type="submit"
              disabled={chatLoading || !chatInput.trim()}
              className="px-5 py-3 bg-indigo-600 text-white text-sm font-medium rounded-xl hover:bg-indigo-700 disabled:opacity-50 transition-colors whitespace-nowrap"
            >
              {chatLoading ? 'Küldés…' : '↑ Küldés'}
            </button>
          </form>
        </div>
      )}
    </div>
  );
}
