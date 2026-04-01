import { useState, useEffect, useMemo } from 'react';
import { api, type Attachment } from '../lib/api';

type ActiveTab = 'list' | 'duplicates';

const AVAILABLE_COLUMNS: { key: keyof Attachment; label: string }[] = [
  { key: 'filename', label: 'Fájlnév' },
  { key: 'size', label: 'Méret' },
  { key: 'contentType', label: 'Típus' },
  { key: 'emailSubject', label: 'E-mail tárgya' },
  { key: 'senderName', label: 'Küldő' },
  { key: 'receivedTime', label: 'Dátum' },
  { key: 'pstFileName', label: 'PST fájl' }
];

const DEFAULT_COLUMNS = ['filename', 'size', 'contentType', 'emailSubject', 'senderName', 'receivedTime', 'pstFileName'];

export default function AttachmentList() {
  const [activeTab, setActiveTab] = useState<ActiveTab>('list');

  // Duplikátumok tab állapot
  const [dupStats, setDupStats] = useState<{
    totalRecords: number; uniqueFiles: number;
    sameEmailDuplicates: number; crossEmailShared: number;
  } | null>(null);
  const [dupLoading, setDupLoading] = useState(false);
  const [dupMessage, setDupMessage] = useState('');

  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [loading, setLoading] = useState(true);
  
  // Search & Filters
  const [search, setSearch] = useState('');
  const [columnFilters, setColumnFilters] = useState<Record<string, string>>({});
  const [dateFilter, setDateFilter] = useState({ start: '', end: '' });
  const [sizeFilter, setSizeFilter] = useState({ min: '', max: '' });

  // Debounced Search & Filters
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [debouncedColFilters, setDebouncedColFilters] = useState<Record<string, string>>({});
  const [debouncedDateFilter, setDebouncedDateFilter] = useState({ start: '', end: '' });
  const [debouncedSizeFilter, setDebouncedSizeFilter] = useState({ min: '', max: '' });

  // UI State
  const [downloadingAttId, setDownloadingAttId] = useState<string | null>(null);
  
  const [sortField, setSortField] = useState<keyof Attachment>('creationTime');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(0);
  const pageSize = 50;

  const [visibleColumns, setVisibleColumns] = useState<string[]>(() => {
    try {
      const saved = localStorage.getItem('suliweb_att_columns');
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
      setDebouncedSizeFilter(sizeFilter);
      setPage(0);
    }, 600);
    return () => clearTimeout(timer);
  }, [search, columnFilters, dateFilter, sizeFilter]);

  // Fetch Attachments
  useEffect(() => {
    async function fetchAttachments() {
      setLoading(true);
      try {
        const params: Record<string, string> = { limit: '1000' };
        
        if (debouncedSearch.trim()) params.q = debouncedSearch.trim();
        
        if (debouncedColFilters['filename']) params.filename = debouncedColFilters['filename'];
        if (debouncedColFilters['contentType']) params.extension = debouncedColFilters['contentType'];
        if (debouncedColFilters['emailSubject']) params.emailSubject = debouncedColFilters['emailSubject'];
        if (debouncedColFilters['senderName']) params.sender = debouncedColFilters['senderName'];
        if (debouncedColFilters['pstFileName']) params.pstFile = debouncedColFilters['pstFileName'];

        if (debouncedDateFilter.start) params.startDate = debouncedDateFilter.start + 'T00:00:00';
        if (debouncedDateFilter.end) params.endDate = debouncedDateFilter.end + 'T23:59:59';
        
        if (debouncedSizeFilter.min) params.minSize = String(Number(debouncedSizeFilter.min) * 1024 * 1024);
        if (debouncedSizeFilter.max) params.maxSize = String(Number(debouncedSizeFilter.max) * 1024 * 1024);

        const data = await api.searchAttachments(params);
        setAttachments(data);
      } catch (e) {
        console.error('Csatolmány betöltési hiba:', e);
      } finally {
        setLoading(false);
      }
    }
    fetchAttachments();
  }, [debouncedSearch, debouncedColFilters, debouncedDateFilter, debouncedSizeFilter]);

  useEffect(() => {
    localStorage.setItem('suliweb_att_columns', JSON.stringify(visibleColumns));
  }, [visibleColumns]);

  const paged = useMemo(() => {
    let result = [...attachments];
    result.sort((a, b) => {
      const aVal = a[sortField] ?? '';
      const bVal = b[sortField] ?? '';
      
      if (sortField === 'size' || sortField === 'creationTime' || sortField === 'receivedTime') {
        const aNum = sortField === 'size' ? Number(aVal) : new Date(aVal as string).getTime();
        const bNum = sortField === 'size' ? Number(bVal) : new Date(bVal as string).getTime();
        return sortDir === 'asc' ? aNum - bNum : bNum - aNum;
      }
      
      const cmp = String(aVal).localeCompare(String(bVal), 'hu');
      return sortDir === 'asc' ? cmp : -cmp;
    });
    return result.slice(page * pageSize, (page + 1) * pageSize);
  }, [attachments, sortField, sortDir, page]);

  const totalPages = Math.ceil(attachments.length / pageSize);

  function toggleSort(field: keyof Attachment) {
    if (sortField === field) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDir('asc');
    }
    setPage(0);
  }

  function toggleColumn(key: string) {
    setVisibleColumns(prev => 
      prev.includes(key) ? prev.filter(c => c !== key) : [...prev, key]
    );
  }

  function handleColFilterChange(key: string, value: string) {
    setColumnFilters(prev => ({ ...prev, [key]: value }));
  }

  async function loadDupStats() {
    setDupLoading(true); setDupMessage('');
    try {
      const data = await api.getAttachmentDuplicateStats();
      setDupStats(data);
    } catch (e: any) {
      setDupMessage('Hiba: ' + e.message);
    } finally {
      setDupLoading(false);
    }
  }

  async function handleDeduplicate() {
    setDupLoading(true); setDupMessage('');
    try {
      const msg = await api.deduplicateAttachments();
      setDupMessage(msg);
      await loadDupStats();
    } catch (e: any) {
      setDupMessage('Hiba: ' + e.message);
    } finally {
      setDupLoading(false);
    }
  }

  function formatBytes(bytes: number, decimals = 2) {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
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

  return (
    <div className="space-y-4 relative">
      {/* Tab strip */}
      <div className="border-b border-gray-200">
        <nav className="flex gap-1">
          {([['list', 'Csatolmányok'], ['duplicates', 'Duplikátumok']] as [ActiveTab, string][]).map(([tab, label]) => (
            <button
              key={tab}
              onClick={() => { setActiveTab(tab); if (tab === 'duplicates' && dupStats === null) loadDupStats(); }}
              className={`px-4 py-2.5 text-sm font-medium rounded-t-lg transition-colors ${
                activeTab === tab
                  ? 'bg-white border border-b-white border-gray-200 text-blue-600 -mb-px'
                  : 'text-gray-500 hover:text-gray-700'
              }`}
            >{label}</button>
          ))}
        </nav>
      </div>

      {/* Duplikátumok tab */}
      {activeTab === 'duplicates' && (
        <div className="space-y-4">
          <div className="bg-white rounded-xl border border-gray-200 p-4 flex flex-wrap items-center gap-4">
            <button onClick={loadDupStats} disabled={dupLoading} className="px-4 py-2 border border-gray-300 text-sm rounded-lg hover:bg-gray-50 disabled:opacity-50 transition-colors">
              {dupLoading ? 'Betöltés...' : 'Frissítés'}
            </button>
            {dupStats && dupStats.sameEmailDuplicates > 0 && (
              <button onClick={handleDeduplicate} disabled={dupLoading} className="px-4 py-2 bg-red-600 text-white text-sm rounded-lg hover:bg-red-700 disabled:opacity-50 transition-colors">
                Duplikátumok törlése az adatbázisból
              </button>
            )}
            {dupMessage && <span className={`text-sm ${dupMessage.startsWith('Hiba') ? 'text-red-600' : 'text-gray-600'}`}>{dupMessage}</span>}
          </div>

          {dupStats && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
              <div className="bg-white rounded-xl border border-gray-200 p-4">
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">Összes rekord</p>
                <p className="text-2xl font-bold text-gray-800">{dupStats.totalRecords}</p>
              </div>
              <div className="bg-white rounded-xl border border-gray-200 p-4">
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">Egyedi fájl (hash)</p>
                <p className="text-2xl font-bold text-blue-600">{dupStats.uniqueFiles}</p>
              </div>
              <div className="bg-white rounded-xl border border-amber-100 p-4">
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">Megosztott fájl</p>
                <p className="text-2xl font-bold text-amber-600">{dupStats.crossEmailShared}</p>
                <p className="text-xs text-gray-400 mt-1">azonos fájl, több e-mail</p>
              </div>
              <div className="bg-white rounded-xl border border-red-100 p-4">
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">Törlendő rekord</p>
                <p className="text-2xl font-bold text-red-600">{dupStats.sameEmailDuplicates}</p>
                <p className="text-xs text-gray-400 mt-1">azonos fájl, azonos e-mail</p>
              </div>
            </div>
          )}

          {dupStats && dupStats.sameEmailDuplicates === 0 && !dupMessage && (
            <div className="bg-white rounded-xl border border-gray-200 px-4 py-10 text-center text-gray-400 italic">
              Nincs duplikált csatolmány rekord.
            </div>
          )}
        </div>
      )}

      {activeTab === 'list' && <>
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 bg-white p-4 rounded-xl shadow-sm border border-gray-200">
        <div className="w-full md:w-96">
          <div className="relative">
            <input
              type="text"
              placeholder="Globális keresés (fájlnév, e-mail tárgy, kiterjesztés)..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            />
            <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <svg className="h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3 w-full md:w-auto">
          <div className="relative">
            <button
              onClick={() => setShowColumnPicker(!showColumnPicker)}
              className="px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm text-gray-700 hover:bg-gray-50 flex items-center gap-2 transition-colors"
            >
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 17V7m0 10a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h2a2 2 0 012 2m0 10a2 2 0 002 2h2a2 2 0 002-2M9 7a2 2 0 012-2h2a2 2 0 012 2m0 10V7m0 10a2 2 0 002 2h2a2 2 0 002-2V7a2 2 0 00-2-2h-2a2 2 0 00-2 2" />
              </svg>
              Oszlopok
            </button>
            
            {showColumnPicker && (
              <div className="absolute right-0 mt-2 w-56 bg-white rounded-lg shadow-lg border border-gray-200 z-50 p-2">
                <div className="text-xs font-semibold text-gray-500 mb-2 px-2 uppercase tracking-wider">Megjelenített oszlopok</div>
                <div className="max-h-64 overflow-y-auto">
                  {AVAILABLE_COLUMNS.map(col => (
                    <label key={col.key} className="flex items-center px-2 py-1.5 hover:bg-gray-50 rounded cursor-pointer">
                      <input
                        type="checkbox"
                        checked={visibleColumns.includes(col.key)}
                        onChange={() => toggleColumn(col.key)}
                        className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                        disabled={visibleColumns.length === 1 && visibleColumns.includes(col.key)}
                      />
                      <span className="ml-2 text-sm text-gray-700">{col.label}</span>
                    </label>
                  ))}
                </div>
              </div>
            )}
          </div>
          
          <div className="bg-blue-50 text-blue-700 px-3 py-1.5 rounded-lg text-sm font-medium border border-blue-100 whitespace-nowrap">
            {loading ? 'Betöltés...' : `${attachments.length} csatolmány`}
          </div>
        </div>
      </div>

      <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                {AVAILABLE_COLUMNS.filter(col => visibleColumns.includes(col.key)).map(col => (
                  <th key={col.key} className="px-4 py-3 text-left">
                    <button
                      className="group flex items-center gap-1 text-xs font-semibold text-gray-600 uppercase tracking-wider hover:text-gray-900 w-full mb-2"
                      onClick={() => toggleSort(col.key)}
                    >
                      {col.label}
                      <span className={`text-gray-400 ${sortField === col.key ? 'text-blue-500' : 'group-hover:text-gray-600'}`}>
                        {sortField === col.key ? (sortDir === 'asc' ? '↑' : '↓') : '↕'}
                      </span>
                    </button>
                    
                    {col.key === 'receivedTime' ? (
                      <div className="flex flex-col gap-1 w-32">
                        <input type="date" className="text-xs border px-1 py-1 rounded" title="Dátumtól" value={dateFilter.start} onChange={e => setDateFilter(prev => ({ ...prev, start: e.target.value }))} />
                        <input type="date" className="text-xs border px-1 py-1 rounded" title="Dátumig" value={dateFilter.end} onChange={e => setDateFilter(prev => ({ ...prev, end: e.target.value }))} />
                      </div>
                    ) : col.key === 'size' ? (
                      <div className="flex flex-col gap-1 w-24">
                        <input type="number" placeholder="Min MB" className="text-xs border px-1 py-1 rounded" title="Min méret (MB)" value={sizeFilter.min} onChange={e => setSizeFilter(prev => ({ ...prev, min: e.target.value }))} />
                        <input type="number" placeholder="Max MB" className="text-xs border px-1 py-1 rounded" title="Max méret (MB)" value={sizeFilter.max} onChange={e => setSizeFilter(prev => ({ ...prev, max: e.target.value }))} />
                      </div>
                    ) : col.key === 'contentType' ? (
                        <input 
                          type="text" 
                          placeholder="pl. pdf, jpg"
                          className="w-20 text-xs border border-gray-300 rounded px-2 py-1 focus:ring-1 focus:ring-blue-500 outline-none font-normal"
                          value={columnFilters[col.key] || ''}
                          onChange={e => handleColFilterChange(col.key as string, e.target.value)}
                        />
                    ) : (
                        <input 
                          type="text" 
                          placeholder="Szűrés..."
                          className="w-full text-xs border border-gray-300 rounded px-2 py-1 focus:ring-1 focus:ring-blue-500 outline-none font-normal"
                          value={columnFilters[col.key] || ''}
                          onChange={e => handleColFilterChange(col.key as string, e.target.value)}
                        />
                    )}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-100">
              {loading && attachments.length === 0 ? (
                <tr>
                  <td colSpan={visibleColumns.length} className="px-6 py-12 text-center text-gray-500">
                    <div className="flex justify-center items-center gap-3">
                      <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-blue-600"></div>
                      Kis türelmet, adatok betöltése...
                    </div>
                  </td>
                </tr>
              ) : paged.length === 0 ? (
                <tr>
                  <td colSpan={visibleColumns.length} className="px-6 py-12 text-center text-gray-500 italic">
                    Nincs a keresésnek megfelelő csatolmány.
                  </td>
                </tr>
              ) : (
                paged.map((att) => (
                  <tr key={att.id} className="hover:bg-blue-50/50 transition-colors cursor-default">
                    {AVAILABLE_COLUMNS.filter(c => visibleColumns.includes(c.key)).map(col => (
                      <td key={col.key} className="px-4 py-3 whitespace-nowrap text-sm text-gray-700">
                        {col.key === 'filename' ? (
                          <div className="font-medium text-gray-900 group">
                            <button 
                                onClick={() => handleDownloadAttachment(att)}
                                className="flex items-center gap-1.5 px-2 py-1 bg-white border border-gray-200 rounded text-sm hover:bg-gray-50 transition-colors group-hover:bg-blue-50 group-hover:border-blue-200 group-hover:text-blue-700 text-left truncate max-w-[200px]"
                                title={att.filename || 'Letöltés'}
                            >
                                <span className="text-blue-500">
                                {downloadingAttId === att.id ? '⏳' : '📎'}
                                </span>
                                <span className="truncate">{att.filename || att.localPath?.split('/').pop() || 'Ismeretlen'}</span>
                            </button>
                          </div>
                        ) : col.key === 'size' ? (
                          formatBytes(att.size)
                        ) : col.key === 'receivedTime' ? (
                          att.receivedTime ? new Date(att.receivedTime).toLocaleString('hu-HU', {
                            year: 'numeric', month: '2-digit', day: '2-digit', 
                            hour: '2-digit', minute: '2-digit'
                          }) : '-'
                        ) : col.key === 'emailSubject' ? (
                          <a 
                            href={`/emails/?id=${att.emailId}`}
                            className="text-blue-600 hover:text-blue-800 hover:underline truncate max-w-[250px] inline-block align-bottom" 
                            title={att.emailSubject}
                          >
                            {att.emailSubject}
                          </a>
                        ) : (
                          <div className="truncate max-w-[150px] lg:max-w-[250px]" title={String(att[col.key] || '')}>
                            {String(att[col.key] || '')}
                          </div>
                        )}
                      </td>
                    ))}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        
        {/* Pagination Footer */}
        <div className="bg-gray-50 px-4 py-3 border-t border-gray-200 flex items-center justify-between sm:px-6">
          <div className="hidden sm:flex-1 sm:flex sm:items-center sm:justify-between">
            <div>
              <p className="text-sm text-gray-700">
                Mutatva <span className="font-medium">{attachments.length === 0 ? 0 : page * pageSize + 1}</span> - <span className="font-medium">{Math.min((page + 1) * pageSize, attachments.length)}</span> / <span className="font-medium">{attachments.length}</span> összesen
              </p>
            </div>
            <div>
              <nav className="relative z-0 inline-flex rounded-md shadow-sm -space-x-px" aria-label="Pagination">
                <button
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="relative inline-flex items-center px-2 py-2 rounded-l-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  Előző
                </button>
                <div className="px-4 py-2 border-t border-b border-gray-300 bg-white text-sm font-medium text-gray-700">
                  {page + 1} / {Math.max(1, totalPages)}
                </div>
                <button
                  onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                  disabled={page >= totalPages - 1}
                  className="relative inline-flex items-center px-2 py-2 rounded-r-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  Következő
                </button>
              </nav>
            </div>
          </div>
        </div>
      </div>
      </>}
    </div>
  );
}
