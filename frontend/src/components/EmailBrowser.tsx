import { useState, useEffect, useMemo } from 'react';
import { api, type Email, type Attachment } from '../lib/api';

const AVAILABLE_COLUMNS: { key: keyof Email | 'attachments'; label: string }[] = [
  { key: 'receivedTime', label: 'Dátum' },
  { key: 'senderName', label: 'Feladó neve' },
  { key: 'senderEmailAddress', label: 'Feladó címe' },
  { key: 'subject', label: 'Tárgy' },
  { key: 'pstFileName', label: 'PST fájl' },
  { key: 'folderPath', label: 'Mappa' },
  { key: 'importance', label: 'Fontosság' },
  { key: 'isRead', label: 'Olvasott-e' },
  { key: 'attachments', label: 'Csatol.' }
];

const DEFAULT_COLUMNS = ['receivedTime', 'senderName', 'subject', 'folderPath', 'pstFileName', 'attachments'];

export default function EmailBrowser() {
  const [emails, setEmails] = useState<Email[]>([]);
  const [loading, setLoading] = useState(true);
  
  // Search & Filters
  const [search, setSearch] = useState('');
  const [columnFilters, setColumnFilters] = useState<Record<string, string>>({});
  const [dateFilter, setDateFilter] = useState({ start: '', end: '' });

  // Debounced Search & Filters
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [debouncedColFilters, setDebouncedColFilters] = useState<Record<string, string>>({});
  const [debouncedDateFilter, setDebouncedDateFilter] = useState({ start: '', end: '' });

  // UI State
  const [selectedEmail, setSelectedEmail] = useState<Email | null>(null);
  const [modalAttachments, setModalAttachments] = useState<Attachment[]>([]);
  const [modalAttLoading, setModalAttLoading] = useState(false);
  const [downloadingAttId, setDownloadingAttId] = useState<string | null>(null);
  
  const [sortField, setSortField] = useState<keyof Email>('receivedTime');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(0);
  const pageSize = 50;

  const [visibleColumns, setVisibleColumns] = useState<string[]>(() => {
    try {
      const saved = localStorage.getItem('suliweb_email_columns');
      return saved ? JSON.parse(saved) : DEFAULT_COLUMNS;
    } catch {
      return DEFAULT_COLUMNS;
    }
  });

  const [showColumnPicker, setShowColumnPicker] = useState(false);

  // Debouncing effect
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(search);
      setDebouncedColFilters(columnFilters);
      setDebouncedDateFilter(dateFilter);
      setPage(0);
    }, 600);
    return () => clearTimeout(timer);
  }, [search, columnFilters, dateFilter]);

  // Fetch Emails
  useEffect(() => {
    async function fetchEmails() {
      setLoading(true);
      try {
        const params: Record<string, string> = { limit: '1000' };
        
        if (debouncedSearch.trim()) params.q = debouncedSearch.trim();
        
        if (debouncedColFilters['senderName']) params.sender = debouncedColFilters['senderName'];
        if (debouncedColFilters['senderEmailAddress']) params.sender = debouncedColFilters['senderEmailAddress']; // Might overwrite if both set, but usually one is enough
        if (debouncedColFilters['subject']) params.subject = debouncedColFilters['subject'];
        if (debouncedColFilters['pstFileName']) params.pstFile = debouncedColFilters['pstFileName'];
        if (debouncedColFilters['folderPath']) params.folder = debouncedColFilters['folderPath'];

        if (debouncedDateFilter.start) params.startDate = debouncedDateFilter.start + 'T00:00:00';
        if (debouncedDateFilter.end) params.endDate = debouncedDateFilter.end + 'T23:59:59';

        const data = await api.searchEmails(params);
        setEmails(data);
      } catch (e) {
        console.error('Email betöltési hiba:', e);
      } finally {
        setLoading(false);
      }
    }
    fetchEmails();
  }, [debouncedSearch, debouncedColFilters, debouncedDateFilter]);

  // Handle URL ID parameter for deep linking
  useEffect(() => {
    async function checkUrlParams() {
      const params = new URLSearchParams(window.location.search);
      const emailId = params.get('id');
      if (emailId) {
        const localEmail = emails.find(e => e.id === emailId);
        if (localEmail) {
          setSelectedEmail(localEmail);
        } else {
          try {
            const remoteEmail = await api.getEmailById(emailId);
            if (remoteEmail) {
              setSelectedEmail(remoteEmail);
            }
          } catch (e) {
            console.error('Hiba az e-mail lekérésekor:', e);
          }
        }
      }
    }
    checkUrlParams();
  }, [emails]);

  // Handle fetching attachments when modal opens
  useEffect(() => {
    if (selectedEmail) {
      setModalAttLoading(true);
      api.getAttachmentsByEmail(selectedEmail.id)
        .then(setModalAttachments)
        .catch(e => console.error('Attachment fetch error:', e))
        .finally(() => setModalAttLoading(false));
    } else {
      setModalAttachments([]);
    }
  }, [selectedEmail]);

  useEffect(() => {
    localStorage.setItem('suliweb_email_columns', JSON.stringify(visibleColumns));
  }, [visibleColumns]);

  const paged = useMemo(() => {
    const result = [...emails];
    result.sort((a, b) => {
      const aVal = a[sortField] ?? '';
      const bVal = b[sortField] ?? '';
      const cmp = String(aVal).localeCompare(String(bVal), 'hu');
      return sortDir === 'asc' ? cmp : -cmp;
    });
    return result.slice(page * pageSize, (page + 1) * pageSize);
  }, [emails, sortField, sortDir, page]);

  const totalPages = Math.ceil(emails.length / pageSize);

  function toggleSort(field: keyof Email | 'attachments') {
    if (field === 'attachments') return;
    if (sortField === field) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field as keyof Email);
      setSortDir('asc');
    }
    setPage(0);
  }

  function toggleColumn(key: string) {
    setVisibleColumns(prev => 
      prev.includes(key) ? prev.filter(k => k !== key) : [...prev, key]
    );
  }

  function handleColFilterChange(key: string, value: string) {
    setColumnFilters(prev => ({ ...prev, [key]: value }));
  }

  async function handleDownloadAttachment(att: Attachment) {
    try {
      setDownloadingAttId(att.id);
      await api.downloadAttachment(att.id, att.filename || 'Ismeretlen_fájl');
    } catch (e) {
      alert('Sikertelen letöltés.');
      console.error(e);
    } finally {
      setDownloadingAttId(null);
    }
  }

  function renderCell(email: Email, col: string) {
    switch (col) {
      case 'receivedTime': return formatDate(email.receivedTime);
      case 'senderName': return email.senderName || '(üres)';
      case 'senderEmailAddress': return email.senderEmailAddress || '(üres)';
      case 'subject': return email.subject || '(nincs tárgy)';
      case 'pstFileName': return email.pstFileName?.split('/').pop();
      case 'folderPath': return email.folderPath;
      case 'importance': return translateImportance(email.importance);
      case 'isRead': return email.isRead ? 'Igen' : 'Nem';
      case 'attachments': return email.attachmentPaths?.length || 0;
      default: return String(email[col as keyof Email] ?? '');
    }
  }

  if (loading && emails.length === 0) return <div className="text-gray-500">Betöltés...</div>;

  return (
    <div className="space-y-4">
      {/* Top Search and Columns bar */}
      <div className="flex items-center gap-4 relative bg-white p-4 rounded-xl shadow-sm border border-gray-100">
        <input
          type="text"
          placeholder="Globális keresés (törzs, formázott szöveg, csatolmány nevek is)..."
          value={search}
          onChange={e => setSearch(e.target.value)}
          className="flex-1 px-4 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
        />
        <div className="relative">
          <button 
            onClick={() => setShowColumnPicker(!showColumnPicker)}
            className="px-4 py-2 border border-gray-300 rounded-lg text-sm hover:bg-gray-50 bg-white transition-colors flex items-center gap-2"
          >
            Oszlopok ⚙️
          </button>
          
          {showColumnPicker && (
            <div className="absolute right-0 mt-2 w-56 bg-white border border-gray-200 rounded-lg shadow-xl z-20 p-2">
              <div className="text-xs font-semibold text-gray-500 mb-2 px-2 uppercase">Megjelenítendő oszlopok</div>
              {AVAILABLE_COLUMNS.map(col => (
                <label key={col.key} className="flex items-center gap-2 px-2 py-1.5 hover:bg-gray-50 rounded cursor-pointer transition-colors">
                  <input 
                    type="checkbox" 
                    checked={visibleColumns.includes(col.key)} 
                    onChange={() => toggleColumn(col.key)} 
                    className="rounded text-blue-500 focus:ring-blue-500"
                  />
                  <span className="text-sm text-gray-700">{col.label}</span>
                </label>
              ))}
            </div>
          )}
        </div>
        <span className="text-sm text-gray-500 whitespace-nowrap hidden sm:inline">{emails.length} találat</span>
      </div>

      {/* Email detail modal */}
      {selectedEmail && (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4" onClick={() => setSelectedEmail(null)}>
          <div className="bg-white rounded-xl max-w-5xl w-full max-h-[90vh] flex flex-col overflow-hidden shadow-2xl ring-1 ring-black/5" onClick={e => e.stopPropagation()}>
            <div className="flex justify-between items-start p-5 border-b border-gray-100 bg-gray-50">
              <div>
                <h3 className="text-xl font-bold text-gray-900 mb-1">{selectedEmail.subject || '(nincs tárgy)'}</h3>
                <p className="text-sm text-gray-600">
                  <span className="font-semibold text-gray-800">{selectedEmail.senderName}</span>
                  {' '}
                  <span className="text-gray-500">&lt;{selectedEmail.senderEmailAddress}&gt;</span>
                  <span className="mx-2 text-gray-300">•</span>
                  {formatDate(selectedEmail.receivedTime)}
                </p>
              </div>
              <button 
                onClick={() => setSelectedEmail(null)} 
                className="text-gray-400 hover:text-gray-900 hover:bg-gray-200 rounded-full w-8 h-8 flex items-center justify-center transition-colors"
              >
                &times;
              </button>
            </div>
            
            <div className="flex-1 overflow-auto p-0 flex flex-col bg-white">
              {/* Email Content Area */}
              {selectedEmail.htmlContent ? (
                  <iframe 
                    title="email-content"
                    className="w-full h-full min-h-[500px] border-none"
                    srcDoc={selectedEmail.htmlContent}
                    sandbox="allow-same-origin allow-popups"
                  />
              ) : (
                <div className="whitespace-pre-wrap text-gray-800 p-6 min-h-[400px] font-sans">
                  {selectedEmail.body || '(üres levéltörzs)'}
                </div>
              )}
            </div>
            
            <div className="bg-gray-50 p-5 border-t border-gray-200 text-sm">
              <div className="grid grid-cols-2 gap-x-6 gap-y-3">
                <p><span className="font-semibold text-gray-600 block text-xs uppercase tracking-wider mb-0.5">Mappa</span> {selectedEmail.folderPath}</p>
                <p><span className="font-semibold text-gray-600 block text-xs uppercase tracking-wider mb-0.5">Adatfájl</span> {selectedEmail.pstFileName?.split('/').pop()}</p>
                {selectedEmail.recipients?.length > 0 && (
                  <p className="col-span-2"><span className="font-semibold text-gray-600 block text-xs uppercase tracking-wider mb-0.5">Címzettek</span> {selectedEmail.recipients.join(', ')}</p>
                )}
                {selectedEmail.cc?.length > 0 && (
                  <p className="col-span-2"><span className="font-semibold text-gray-600 block text-xs uppercase tracking-wider mb-0.5">CC</span> {selectedEmail.cc.join(', ')}</p>
                )}
              </div>

              {selectedEmail.attachmentPaths?.length > 0 && (
                <div className="mt-5 pt-4 border-t border-gray-200/60">
                  <span className="font-semibold text-gray-600 block text-xs uppercase tracking-wider mb-3">
                    Csatolmányok ({selectedEmail.attachmentPaths.length})
                  </span>
                  {modalAttLoading ? (
                    <div className="text-gray-500 italic text-sm py-2">Betöltés folyamatban...</div>
                  ) : (
                    <div className="flex flex-wrap gap-2">
                      {modalAttachments.map(att => (
                        <button 
                          key={att.id}
                          onClick={() => handleDownloadAttachment(att)}
                          disabled={downloadingAttId === att.id}
                          className="bg-white border border-gray-200 hover:border-blue-400 hover:bg-blue-50 text-gray-700 font-medium rounded-lg px-3 py-2 text-sm flex items-center gap-2 shadow-sm transition-all disabled:opacity-50 disabled:cursor-not-allowed group"
                        >
                          <span className="text-blue-500 group-hover:scale-110 transition-transform">
                            {downloadingAttId === att.id ? '⏳' : '📎'}
                          </span>
                          <span className="max-w-[250px] truncate" title={att.filename || att.localPath}>
                            {att.filename || att.localPath?.split('/').pop() || 'Ismeretlen_fájl'}
                          </span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Table */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden shadow-sm relative">
        {loading && <div className="absolute inset-0 bg-white/60 backdrop-blur-[1px] z-10 flex items-center justify-center font-medium text-blue-600">Betöltés...</div>}
        <div className="overflow-x-auto">
          <table className="w-full text-sm min-w-[1000px]">
            <thead>
              <tr className="bg-gray-50 border-b border-gray-200 text-left text-gray-700">
                {AVAILABLE_COLUMNS.filter(c => visibleColumns.includes(c.key as string)).map(col => (
                  <th key={col.key} className="p-3 align-top font-medium w-auto group">
                    <div 
                      className="flex items-center justify-between cursor-pointer select-none hover:text-blue-600 transition-colors"
                      onClick={() => toggleSort(col.key)}
                    >
                      <span className="py-1">{col.label}</span>
                      <span className="text-gray-400 group-hover:text-blue-500">
                        {sortField === col.key ? (sortDir === 'asc' ? '▲' : '▼') : '↕️'}
                      </span>
                    </div>
                    
                    {/* Filter Inputs directly below the header label */}
                    <div className="mt-2" onClick={e => e.stopPropagation()}>
                      {col.key === 'receivedTime' ? (
                        <div className="flex flex-col gap-1.5 min-w-[130px]">
                          <input 
                            type="date" 
                            className="w-full text-xs px-2 py-1.5 border border-gray-300 rounded text-gray-700 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 bg-white"
                            value={dateFilter.start}
                            onChange={e => setDateFilter(p => ({ ...p, start: e.target.value }))}
                            title="Tól"
                          />
                          <input 
                            type="date" 
                            className="w-full text-xs px-2 py-1.5 border border-gray-300 rounded text-gray-700 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 bg-white"
                            value={dateFilter.end}
                            onChange={e => setDateFilter(p => ({ ...p, end: e.target.value }))}
                            title="Ig"
                          />
                        </div>
                      ) : col.key === 'attachments' || col.key === 'isRead' || col.key === 'importance' ? (
                        <div className="h-7"></div> // Empty space for non-text filterable cols
                      ) : (
                        <input 
                          type="text" 
                          placeholder="Szűrés..."
                          className="w-full min-w-[120px] text-xs px-2 py-1.5 border border-gray-300 rounded text-gray-700 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 bg-white font-normal"
                          value={columnFilters[col.key] || ''}
                          onChange={e => handleColFilterChange(col.key as string, e.target.value)}
                        />
                      )}
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {paged.map(email => (
                <tr
                  key={email.id}
                  onClick={() => setSelectedEmail(email)}
                  className="hover:bg-blue-50/50 cursor-pointer transition-colors"
                >
                  {AVAILABLE_COLUMNS.filter(c => visibleColumns.includes(c.key as string)).map(col => (
                    <td key={col.key} className={`px-4 py-3 align-top ${col.key === 'subject' ? 'font-medium max-w-sm truncate' : 'whitespace-nowrap text-gray-600'}`}>
                      {col.key === 'folderPath' ? (
                        <div className="max-w-[180px] break-words whitespace-normal text-xs leading-tight">
                           {renderCell(email, col.key)}
                        </div>
                      ) : renderCell(email, col.key)}
                    </td>
                  ))}
                </tr>
              ))}
              {paged.length === 0 && !loading && (
                <tr><td colSpan={visibleColumns.length || 1} className="px-4 py-12 text-center text-gray-400">Nincs találat a jelenlegi szűrőkkel.</td></tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-6 py-4 border-t border-gray-200 bg-gray-50">
            <button
              onClick={() => setPage(p => Math.max(0, p - 1))}
              disabled={page === 0}
              className="px-4 py-2 text-sm font-medium border border-gray-300 rounded-lg disabled:opacity-40 hover:bg-white bg-white shadow-sm transition-colors"
            >
              Előző
            </button>
            <span className="text-sm text-gray-600 font-medium">{page + 1} / {totalPages} (Összesen {emails.length})</span>
            <button
              onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
              disabled={page >= totalPages - 1}
              className="px-4 py-2 text-sm font-medium border border-gray-300 rounded-lg disabled:opacity-40 hover:bg-white bg-white shadow-sm transition-colors"
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

function translateImportance(val: string | number | undefined) {
  if (val === undefined || val === null) return '-';
  const str = String(val);
  if (str === '2') return 'Magas';
  if (str === '1') return 'Normál';
  if (str === '0') return 'Alacsony';
  return str;
}
