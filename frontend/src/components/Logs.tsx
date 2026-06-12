import { useState, useEffect, useRef, Fragment } from 'react';
import { api, type LogEntry } from '../lib/api';

type LevelFilter = 'ALL' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';

const BADGE: Record<string, string> = {
  ERROR: 'bg-red-100 text-red-800',
  WARN:  'bg-yellow-100 text-yellow-800',
  INFO:  'bg-gray-100 text-gray-600',
  DEBUG: 'bg-blue-100 text-blue-700',
};

const ROW_BG: Record<string, string> = {
  ERROR: 'bg-red-50',
  WARN:  'bg-yellow-50',
  INFO:  '',
  DEBUG: 'bg-blue-50',
};

const TZ = Intl.DateTimeFormat().resolvedOptions().timeZone;

function fmt(ts: string) {
  return new Date(ts).toLocaleString('hu-HU', { hour12: false });
}

function toUtcIso(localDt: string): string | undefined {
  if (!localDt) return undefined;
  return new Date(localDt).toISOString();
}

export default function Logs() {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [filter, setFilter] = useState<LevelFilter>('ALL');
  const [fromVal, setFromVal] = useState('');
  const [toVal, setToVal] = useState('');
  const [sortDir, setSortDir] = useState<'desc' | 'asc'>('desc');
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  async function load(f: LevelFilter, from: string, to: string, sort: string) {
    try {
      const data = await api.getLogs(
        f === 'ALL' ? undefined : f,
        toUtcIso(from),
        toUtcIso(to),
        sort,
      );
      setLogs(data);
    } catch { /* intentional */ }
  }

  useEffect(() => {
    setLoading(true);
    load(filter, fromVal, toVal, sortDir).finally(() => setLoading(false));
  }, [filter, fromVal, toVal, sortDir]);

  useEffect(() => {
    if (intervalRef.current) clearInterval(intervalRef.current);
    if (autoRefresh) {
      intervalRef.current = setInterval(
        () => load(filter, fromVal, toVal, sortDir), 3000,
      );
    }
    return () => { if (intervalRef.current) clearInterval(intervalRef.current); };
  }, [autoRefresh, filter, fromVal, toVal, sortDir]);

  function toggleExpand(id: string) {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  const levels: LevelFilter[] = ['ALL', 'ERROR', 'WARN', 'INFO', 'DEBUG'];

  return (
    <div className="space-y-4">
      {/* Toolbar row 1: level filters + refresh toggle */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex gap-1 flex-wrap">
          {levels.map(l => (
            <button
              key={l}
              onClick={() => setFilter(l)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
                filter === l
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >{l}</button>
          ))}
        </div>
        <button
          onClick={() => setAutoRefresh(r => !r)}
          className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors ${
            autoRefresh ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
          }`}
        >
          <span className={`w-2 h-2 rounded-full ${autoRefresh ? 'bg-green-500 animate-pulse' : 'bg-gray-400'}`} />
          {autoRefresh ? 'Élő frissítés' : 'Szünetel'}
        </button>
      </div>

      {/* Toolbar row 2: date range + sort */}
      <div className="flex items-center gap-3 flex-wrap">
        <label className="flex items-center gap-2 text-sm text-gray-600">
          Tól:
          <input
            type="datetime-local"
            value={fromVal}
            onChange={e => setFromVal(e.target.value)}
            className="border border-gray-200 rounded-lg px-2 py-1 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
        </label>
        <label className="flex items-center gap-2 text-sm text-gray-600">
          Ig:
          <input
            type="datetime-local"
            value={toVal}
            onChange={e => setToVal(e.target.value)}
            className="border border-gray-200 rounded-lg px-2 py-1 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-400"
          />
        </label>
        {(fromVal || toVal) && (
          <button
            onClick={() => { setFromVal(''); setToVal(''); }}
            className="text-xs text-gray-400 hover:text-gray-600 underline"
          >törlés</button>
        )}
        <button
          onClick={() => setSortDir(d => d === 'desc' ? 'asc' : 'desc')}
          className="ml-auto flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium bg-gray-100 text-gray-600 hover:bg-gray-200 transition-colors"
        >
          Időpont {sortDir === 'desc' ? '↓' : '↑'}
        </button>
      </div>

      {/* Log table */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        {loading && logs.length === 0 ? (
          <div className="p-8 text-center text-gray-400 text-sm">Betöltés...</div>
        ) : logs.length === 0 ? (
          <div className="p-8 text-center text-gray-400 text-sm">Nincs log bejegyzés.</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-200 bg-gray-50 text-xs text-gray-500 uppercase tracking-wide">
                  <th className="text-left px-4 py-2 font-medium whitespace-nowrap">Időpont</th>
                  <th className="text-left px-4 py-2 font-medium">Szint</th>
                  <th className="text-left px-4 py-2 font-medium w-full">Üzenet</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {logs.map(log => (
                  <Fragment key={log.id}>
                    <tr
                      className={`${ROW_BG[log.level] ?? ''} ${log.stackTrace ? 'cursor-pointer' : ''}`}
                      onClick={() => log.stackTrace && toggleExpand(log.id)}
                    >
                      <td className="px-4 py-2 text-gray-400 whitespace-nowrap font-mono text-xs">
                        {fmt(log.timestamp)}
                      </td>
                      <td className="px-4 py-2">
                        <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${BADGE[log.level] ?? 'bg-gray-100 text-gray-600'}`}>
                          {log.level}
                        </span>
                      </td>
                      <td className="px-4 py-2 text-gray-800 break-all">
                        {log.message}
                        {log.stackTrace && (
                          <span className="ml-2 text-gray-400 text-xs">
                            {expanded.has(log.id) ? '▲' : '▶'}
                          </span>
                        )}
                      </td>
                    </tr>
                    {log.stackTrace && expanded.has(log.id) && (
                      <tr>
                        <td colSpan={3} className="px-4 pb-3 bg-red-50">
                          <pre className="text-xs text-red-700 whitespace-pre-wrap font-mono bg-red-100 rounded p-2 max-h-64 overflow-y-auto">
                            {log.stackTrace}
                          </pre>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <p className="text-xs text-gray-400 text-right">
        {logs.length} bejegyzés · helyi idő: {TZ}
      </p>
    </div>
  );
}
