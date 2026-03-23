import { useState, useEffect, useMemo } from 'react';
import { api, type Email } from '../lib/api';

export default function EmailBrowser() {
  const [emails, setEmails] = useState<Email[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [selectedEmail, setSelectedEmail] = useState<Email | null>(null);
  const [sortField, setSortField] = useState<keyof Email>('receivedTime');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(0);
  const pageSize = 50;

  useEffect(() => {
    loadEmails();
  }, []);

  async function loadEmails() {
    try {
      const data = await api.getEmails();
      setEmails(data);
    } catch (e) {
      console.error('Email betöltési hiba:', e);
    } finally {
      setLoading(false);
    }
  }

  const filtered = useMemo(() => {
    const q = search.toLowerCase();
    let result = emails;
    if (q) {
      result = emails.filter(e =>
        e.subject?.toLowerCase().includes(q) ||
        e.senderName?.toLowerCase().includes(q) ||
        e.senderEmailAddress?.toLowerCase().includes(q) ||
        e.pstFileName?.toLowerCase().includes(q)
      );
    }
    result.sort((a, b) => {
      const aVal = a[sortField] ?? '';
      const bVal = b[sortField] ?? '';
      const cmp = String(aVal).localeCompare(String(bVal), 'hu');
      return sortDir === 'asc' ? cmp : -cmp;
    });
    return result;
  }, [emails, search, sortField, sortDir]);

  const paged = filtered.slice(page * pageSize, (page + 1) * pageSize);
  const totalPages = Math.ceil(filtered.length / pageSize);

  function toggleSort(field: keyof Email) {
    if (sortField === field) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDir('asc');
    }
    setPage(0);
  }

  if (loading) return <div className="text-gray-500">Betöltés...</div>;

  return (
    <div className="space-y-4">
      {/* Search bar */}
      <div className="flex items-center gap-4">
        <input
          type="text"
          placeholder="Keresés tárgy, feladó, fájlnév alapján..."
          value={search}
          onChange={e => { setSearch(e.target.value); setPage(0); }}
          className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
        />
        <span className="text-sm text-gray-500">{filtered.length} találat</span>
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
              <p><span className="font-medium text-gray-600">Dátum:</span> {selectedEmail.receivedTime}</p>
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

      {/* Table */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm min-w-[700px]">
            <thead>
              <tr className="bg-gray-50 border-b border-gray-200">
                {([
                  ['receivedTime', 'Dátum'],
                  ['senderName', 'Feladó'],
                  ['subject', 'Tárgy'],
                  ['pstFileName', 'PST fájl'],
                ] as [keyof Email, string][]).map(([field, label]) => (
                  <th
                    key={field}
                    onClick={() => toggleSort(field)}
                    className="text-left px-4 py-3 font-medium text-gray-600 cursor-pointer hover:text-gray-900 select-none"
                  >
                    {label} {sortField === field && (sortDir === 'asc' ? '▲' : '▼')}
                  </th>
                ))}
                <th className="text-left px-4 py-3 font-medium text-gray-600">Csatol.</th>
              </tr>
            </thead>
            <tbody>
              {paged.map(email => (
                <tr
                  key={email.id}
                  onClick={() => setSelectedEmail(email)}
                  className="border-b border-gray-100 hover:bg-blue-50 cursor-pointer transition-colors"
                >
                  <td className="px-4 py-3 whitespace-nowrap text-gray-600">{formatDate(email.receivedTime)}</td>
                  <td className="px-4 py-3 whitespace-nowrap">{email.senderName || email.senderEmailAddress}</td>
                  <td className="px-4 py-3 max-w-md truncate font-medium">{email.subject || '(nincs tárgy)'}</td>
                  <td className="px-4 py-3 whitespace-nowrap text-gray-500 text-xs">{email.pstFileName}</td>
                  <td className="px-4 py-3 whitespace-nowrap text-center">{email.attachmentPaths?.length || 0}</td>
                </tr>
              ))}
              {paged.length === 0 && (
                <tr><td colSpan={5} className="px-4 py-8 text-center text-gray-400">Nincs találat</td></tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-4 py-3 border-t border-gray-200 bg-gray-50">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg disabled:opacity-40 hover:bg-white"
            >
              Előző
            </button>
            <span className="text-sm text-gray-600">{page + 1} / {totalPages}</span>
            <button
              onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
              disabled={page >= totalPages - 1}
              className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg disabled:opacity-40 hover:bg-white"
            >
              Következő
            </button>
          </div>
        )}
      </div>
    </div>
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
