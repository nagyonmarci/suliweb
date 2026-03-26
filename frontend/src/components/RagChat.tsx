import { useState, useRef, useEffect } from 'react';
import { api, type ChatMessage, type ChatSource } from '../lib/api';

export default function RagChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [expandedSources, setExpandedSources] = useState<Set<number>>(new Set());
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, loading]);

  async function handleSend(e: React.FormEvent) {
    e.preventDefault();
    const text = input.trim();
    if (!text || loading) return;

    const userMsg: ChatMessage = { role: 'user', content: text };
    setMessages(prev => [...prev, userMsg]);
    setInput('');
    setError('');
    setLoading(true);

    try {
      const res = await api.ragChat(text);
      const assistantMsg: ChatMessage = {
        role: 'assistant',
        content: res.answer,
        sources: res.sources,
      };
      setMessages(prev => [...prev, assistantMsg]);
    } catch (e: any) {
      setError('Nem sikerült választ kapni: ' + e.message);
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

  return (
    <div className="flex flex-col h-[calc(100vh-12rem)] min-h-[500px]">

      {/* Empty state */}
      {messages.length === 0 && !loading && (
        <div className="flex-1 flex flex-col items-center justify-center text-center p-8 text-gray-400 select-none">
          <div className="text-5xl mb-4">💬</div>
          <p className="text-lg font-medium text-gray-600">Kérdezz a levelezésről</p>
          <p className="text-sm mt-2 max-w-md">
            Írj be egy kérdést és az asszisztens megkeresi a releváns e-maileket,
            majd összefoglalt választ ad belőlük.
          </p>
          <div className="mt-6 grid grid-cols-1 sm:grid-cols-2 gap-2 text-sm max-w-lg">
            {[
              'Mikor volt az utolsó megbeszélés a közbeszerzési pályázatról?',
              'Ki küldte a szerződésmódosítással kapcsolatos leveleket?',
              'Milyen csatolmányok érkeztek a pénzügyi osztálytól?',
              'Összesítsd a Kovács Péterrel folytatott levelezést!',
            ].map((q, i) => (
              <button
                key={i}
                onClick={() => setInput(q)}
                className="text-left px-3 py-2 rounded-lg border border-gray-200 bg-gray-50 hover:bg-blue-50 hover:border-blue-200 transition-colors text-gray-600 hover:text-blue-700"
              >
                {q}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Message history */}
      {messages.length > 0 && (
        <div className="flex-1 overflow-y-auto space-y-4 pr-1">
          {messages.map((msg, idx) => (
            <div key={idx} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
              <div className={`max-w-[85%] rounded-2xl px-4 py-3 ${
                msg.role === 'user'
                  ? 'bg-blue-600 text-white rounded-br-sm'
                  : 'bg-white border border-gray-200 text-gray-800 rounded-bl-sm shadow-sm'
              }`}>
                <p className="whitespace-pre-wrap text-sm leading-relaxed">{msg.content}</p>

                {/* Sources for assistant messages */}
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

          {/* Thinking indicator */}
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

          {/* Error */}
          {error && (
            <div className="bg-red-50 border border-red-100 text-red-600 text-sm px-4 py-3 rounded-xl">
              {error}
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      )}

      {/* Input form */}
      <form onSubmit={handleSend} className="mt-4 flex gap-2">
        <input
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
  );
}
