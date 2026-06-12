import { useState, useRef, useEffect, useCallback } from 'react';
import { api, type ChatMessage } from '../lib/api';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface Session {
  id: string;
  title: string;        // first user message, truncated
  model: string;
  createdAt: number;
  messages: ChatMessage[];
}

// ---------------------------------------------------------------------------
// localStorage helpers
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'rag_chat_sessions';

function loadSessions(): Session[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session[]) : [];
  } catch {
    return [];
  }
}

function saveSessions(sessions: Session[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions));
  } catch {
    // storage quota – fail silently
  }
}

function generateId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // Fallback for non-secure HTTP contexts
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

function newSession(model: string): Session {
  return {
    id: generateId(),
    title: 'Új beszélgetés',
    model,
    createdAt: Date.now(),
    messages: [],
  };
}

function truncate(text: string, max = 45) {
  return text.length <= max ? text : text.slice(0, max) + '…';
}

function formatDate(ts: number) {
  const d = new Date(ts);
  const now = new Date();
  const isToday = d.toDateString() === now.toDateString();
  if (isToday) return d.toLocaleTimeString('hu-HU', { hour: '2-digit', minute: '2-digit' });
  return d.toLocaleDateString('hu-HU', { month: 'short', day: 'numeric' });
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function RagChat() {
  const [sessions, setSessions] = useState<Session[]>(() => loadSessions());
  const [activeId, setActiveId] = useState<string>(() => {
    const saved = loadSessions();
    return saved.length > 0 ? saved[0].id : '';
  });

  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [expandedSources, setExpandedSources] = useState<Set<number>>(new Set());

  // Model selector
  const [models, setModels] = useState<string[]>([]);
  const [modelsLoading, setModelsLoading] = useState(true);

  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Load available models
  useEffect(() => {
    api.ragModels()
      .then(list => setModels(list))
      .catch(() => setModels([]))
      .finally(() => setModelsLoading(false));
  }, []);

  // Persist sessions on every change
  useEffect(() => {
    saveSessions(sessions);
  }, [sessions]);

  // Scroll to bottom when active session messages change
  const activeSession = sessions.find(s => s.id === activeId) ?? null;
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [activeSession?.messages.length, loading]);

  // ---------------------------------------------------------------------------
  // Session management
  // ---------------------------------------------------------------------------

  const createSession = useCallback(() => {
    const model = activeSession?.model ?? models[0] ?? 'llama3.2';
    const s = newSession(model);
    setSessions(prev => [s, ...prev]);
    setActiveId(s.id);
    setError('');
    setExpandedSources(new Set());
    setTimeout(() => inputRef.current?.focus(), 50);
  }, [activeSession, models]);

  function deleteSession(id: string) {
    setSessions(prev => {
      const next = prev.filter(s => s.id !== id);
      if (id === activeId) {
        setActiveId(next.length > 0 ? next[0].id : '');
      }
      return next;
    });
  }

  function updateSessionModel(model: string) {
    setSessions(prev => prev.map(s => s.id === activeId ? { ...s, model } : s));
  }

  // ---------------------------------------------------------------------------
  // Send message
  // ---------------------------------------------------------------------------

  async function handleSend(e: React.FormEvent) {
    e.preventDefault();
    const text = input.trim();
    if (!text || loading || !activeSession) return;

    const userMsg: ChatMessage = { role: 'user', content: text };
    const isFirst = activeSession.messages.length === 0;

    setSessions(prev => prev.map(s =>
      s.id === activeId
        ? {
            ...s,
            messages: [...s.messages, userMsg],
            title: isFirst ? truncate(text) : s.title,
          }
        : s
    ));
    setInput('');
    setError('');
    setLoading(true);

    try {
      // Send conversation history (without sources) so the LLM has context for follow-up questions
      const historyForApi = activeSession.messages.map(m => ({
        role: m.role,
        content: m.content,
      }));

      // Add a placeholder assistant message that will be updated progressively
      const placeholderMsg: ChatMessage = { role: 'assistant', content: '', sources: [] };
      setSessions(prev => prev.map(s =>
        s.id === activeId
          ? { ...s, messages: [...s.messages, placeholderMsg] }
          : s
      ));

      // Stream tokens progressively
      let accumulated = '';
      const sources = await api.ragChatStream(
        text, 8, activeSession.model || undefined, historyForApi,
        (token: string) => {
          accumulated += token;
          const current = accumulated;
          setSessions(prev => prev.map(s => {
            if (s.id !== activeId) return s;
            const msgs = [...s.messages];
            const lastIdx = msgs.length - 1;
            if (lastIdx >= 0 && msgs[lastIdx].role === 'assistant') {
              msgs[lastIdx] = { ...msgs[lastIdx], content: current };
            }
            return { ...s, messages: msgs };
          }));
        }
      );

      // Final update with sources
      setSessions(prev => prev.map(s => {
        if (s.id !== activeId) return s;
        const msgs = [...s.messages];
        const lastIdx = msgs.length - 1;
        if (lastIdx >= 0 && msgs[lastIdx].role === 'assistant') {
          msgs[lastIdx] = { ...msgs[lastIdx], content: accumulated, sources };
        }
        return { ...s, messages: msgs };
      }));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (err: any) {
      setError('Nem sikerült választ kapni: ' + err.message);
    } finally {
      setLoading(false);
    }
  }

  function toggleSources(idx: number) {
    setExpandedSources(prev => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx); else next.add(idx);
      return next;
    });
  }

  // ---------------------------------------------------------------------------
  // If no session exists yet, show a create-first screen
  // ---------------------------------------------------------------------------

  if (sessions.length === 0 || !activeSession) {
    return (
      <div className="flex flex-col items-center justify-center h-[calc(100vh-12rem)] min-h-[400px] text-center">
        <div className="text-5xl mb-4">💬</div>
        <p className="text-lg font-medium text-gray-700 mb-2">Nincs még beszélgetés</p>
        <p className="text-sm text-gray-400 mb-6">Indíts egy új chat sessiont az e-mail archívum kereséséhez.</p>
        <button
          onClick={createSession}
          className="px-5 py-2.5 bg-blue-600 text-white text-sm font-medium rounded-xl hover:bg-blue-700 transition-colors"
        >
          + Új beszélgetés
        </button>
      </div>
    );
  }

  // ---------------------------------------------------------------------------
  // Main layout: sidebar + chat area
  // ---------------------------------------------------------------------------

  return (
    <div className="flex gap-4 h-[calc(100vh-12rem)] min-h-[500px]">

      {/* ---- Sidebar ---- */}
      <aside className="w-56 flex-shrink-0 flex flex-col gap-2">
        <button
          onClick={createSession}
          className="w-full px-3 py-2 bg-blue-600 text-white text-sm font-medium rounded-xl hover:bg-blue-700 transition-colors flex items-center justify-center gap-1.5"
        >
          <span className="text-base leading-none">+</span> Új beszélgetés
        </button>

        <div className="flex-1 overflow-y-auto space-y-1 mt-1">
          {sessions.map(s => (
            <div
              key={s.id}
              className={`group relative flex flex-col px-3 py-2 rounded-xl cursor-pointer transition-colors ${
                s.id === activeId
                  ? 'bg-blue-50 border border-blue-200'
                  : 'hover:bg-gray-100 border border-transparent'
              }`}
              onClick={() => { setActiveId(s.id); setExpandedSources(new Set()); setError(''); }}
            >
              <span className={`text-xs font-medium truncate pr-5 ${s.id === activeId ? 'text-blue-800' : 'text-gray-700'}`}>
                {s.title}
              </span>
              <span className="text-[10px] text-gray-400 mt-0.5">{s.model} · {formatDate(s.createdAt)}</span>

              {/* Delete button */}
              <button
                onClick={e => { e.stopPropagation(); deleteSession(s.id); }}
                className="absolute right-2 top-2 opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-500 transition-all text-xs leading-none p-0.5 rounded"
                title="Törlés"
              >
                ✕
              </button>
            </div>
          ))}
        </div>
      </aside>

      {/* ---- Chat area ---- */}
      <div className="flex-1 flex flex-col min-w-0">

        {/* Model selector */}
        <div className="flex items-center gap-3 mb-4 pb-3 border-b border-gray-100">
          <span className="text-xs font-medium text-gray-500 whitespace-nowrap">Modell:</span>
          {modelsLoading ? (
            <span className="text-xs text-gray-400">Betöltés…</span>
          ) : models.length === 0 ? (
            <span className="text-xs text-red-400">Ollama nem elérhető</span>
          ) : (
            <select
              value={activeSession.model || models[0]}
              onChange={e => updateSessionModel(e.target.value)}
              disabled={loading}
              className="text-sm border border-gray-200 rounded-lg px-3 py-1.5 bg-white focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50 min-w-[200px]"
            >
              {models.map(m => <option key={m} value={m}>{m}</option>)}
            </select>
          )}
        </div>

        {/* Empty state */}
        {activeSession.messages.length === 0 && !loading && (
          <div className="flex-1 flex flex-col items-center justify-center text-center p-6 text-gray-400 select-none">
            <p className="text-base font-medium text-gray-600 mb-1">Kérdezz a levelezésről</p>
            <p className="text-xs text-gray-400 mb-5 max-w-xs">
              Az asszisztens megkeresi a releváns e-maileket és összefoglalt választ ad.
            </p>
            <div className="grid grid-cols-1 gap-2 text-sm w-full max-w-md">
              {[
                'Mikor volt az utolsó megbeszélés a közbeszerzési pályázatról?',
                'Ki küldte a szerződésmódosítással kapcsolatos leveleket?',
                'Milyen csatolmányok érkeztek a pénzügyi osztálytól?',
                'Összesítsd a Kovács Péterrel folytatott levelezést!',
              ].map((q, i) => (
                <button
                  key={i}
                  onClick={() => setInput(q)}
                  className="text-left px-3 py-2 rounded-lg border border-gray-200 bg-gray-50 hover:bg-blue-50 hover:border-blue-200 transition-colors text-gray-600 hover:text-blue-700 text-xs"
                >
                  {q}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Messages */}
        {activeSession.messages.length > 0 && (
          <div className="flex-1 overflow-y-auto space-y-4 pr-1">
            {activeSession.messages.map((msg, idx) => (
              <div key={idx} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[85%] rounded-2xl px-4 py-3 ${
                  msg.role === 'user'
                    ? 'bg-blue-600 text-white rounded-br-sm'
                    : 'bg-white border border-gray-200 text-gray-800 rounded-bl-sm shadow-sm'
                }`}>
                  <p className="whitespace-pre-wrap text-sm leading-relaxed">{msg.content}</p>

                  {msg.role === 'assistant' && msg.sources && msg.sources.length > 0 && (
                    <div className="mt-3 pt-3 border-t border-gray-100">
                      <button
                        onClick={() => toggleSources(idx)}
                        className="flex items-center gap-1.5 text-xs font-medium text-gray-500 hover:text-blue-600 transition-colors"
                      >
                        <span>{expandedSources.has(idx) ? '▾' : '▸'}</span>
                        Forrás e-mailek ({msg.sources.length} db)
                      </button>
                      {expandedSources.has(idx) && (
                        <div className="mt-2 space-y-1.5">
                          {msg.sources.map((src, si) => (
                            <a
                              key={si}
                              href={`/emails?id=${src.emailId}`}
                              className="flex items-start gap-2 p-2 rounded-lg bg-gray-50 hover:bg-blue-50 border border-gray-100 hover:border-blue-200 transition-colors group"
                            >
                              <span className="text-blue-500 mt-0.5 flex-shrink-0">✉</span>
                              <div className="min-w-0">
                                <p className="text-xs font-medium text-gray-800 group-hover:text-blue-700 truncate">
                                  {src.subject || '(nincs tárgy)'}
                                </p>
                                <p className="text-xs text-gray-500 truncate">{src.sender}</p>
                              </div>
                              <span className={`ml-auto flex-shrink-0 text-xs px-1.5 py-0.5 rounded-full font-medium ${
                                src.score >= 0.8 ? 'bg-green-100 text-green-700' :
                                src.score >= 0.6 ? 'bg-blue-100 text-blue-700' :
                                'bg-gray-100 text-gray-600'
                              }`}>
                                {(src.score * 100).toFixed(0)}%
                              </span>
                            </a>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}

            {loading && (
              <div className="flex justify-start">
                <div className="bg-white border border-gray-200 rounded-2xl rounded-bl-sm px-4 py-3 shadow-sm">
                  <div className="flex items-center gap-2 text-gray-400">
                    <div className="flex gap-1">
                      <span className="w-2 h-2 bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }} />
                      <span className="w-2 h-2 bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }} />
                      <span className="w-2 h-2 bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }} />
                    </div>
                    <span className="text-xs">Gondolkodás…</span>
                  </div>
                </div>
              </div>
            )}

            {error && (
              <div className="bg-red-50 border border-red-100 text-red-600 text-sm px-4 py-3 rounded-xl">
                {error}
              </div>
            )}
            <div ref={bottomRef} />
          </div>
        )}

        {/* Input */}
        <form onSubmit={handleSend} className="mt-4 flex gap-2">
          <input
            ref={inputRef}
            type="text"
            value={input}
            onChange={e => setInput(e.target.value)}
            placeholder="Kérdezz a levelezésről…"
            disabled={loading}
            className="flex-1 px-4 py-3 border border-gray-300 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent disabled:opacity-50 bg-white"
          />
          <button
            type="submit"
            disabled={loading || !input.trim()}
            className="px-5 py-3 bg-blue-600 text-white text-sm font-medium rounded-xl hover:bg-blue-700 disabled:opacity-50 transition-colors flex items-center gap-2 whitespace-nowrap"
          >
            {loading ? (
              <>
                <span className="w-4 h-4 border-2 border-white/40 border-t-white rounded-full animate-spin" />
                Küldés…
              </>
            ) : (
              <>↑ Küldés</>
            )}
          </button>
        </form>
      </div>
    </div>
  );
}
