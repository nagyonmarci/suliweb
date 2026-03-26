import { useState, useEffect, useMemo } from 'react';
import { api, type FileInfo } from '../lib/api';

type SortField = keyof FileInfo;

export default function FileList() {
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [loading, setLoading] = useState(true);

  // Filters
  const [search, setSearch] = useState('');
  const [colFilters, setColFilters] = useState<Record<string, string>>({});
  const [statusFilter, setStatusFilter] = useState('all');

  // Sort
  const [sortField, setSortField] = useState<SortField>('lastModified');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');

  useEffect(() => { loadFiles(); }, []);

  async function loadFiles() {
    setLoading(true);
    try {
      const data = await api.getFileInfos();
      setFiles(data);
    } catch (e) {
      console.error('Fájl betöltési hiba:', e);
    } finally {
      setLoading(false);
    }
  }

  const statusCounts = useMemo(() =>
    files.reduce((acc, f) => {
      acc[f.status] = (acc[f.status] || 0) + 1;
      return acc;
    }, {} as Record<string, number>), [files]);

  const visible = useMemo(() => {
    let result = [...files];

    // Global search
    if (search.trim()) {
      const q = search.toLowerCase();
      result = result.filter(f =>
        f.fileName?.toLowerCase().includes(q) ||
        f.path?.toLowerCase().includes(q) ||
        f.status?.toLowerCase().includes(q)
      );
    }

    // Status chip filter
    if (statusFilter !== 'all') {
      result = result.filter(f => f.status === statusFilter);
    }

    // Column filters
    if (colFilters['fileName']) result = result.filter(f => f.fileName?.toLowerCase().includes(colFilters['fileName'].toLowerCase()));
    if (colFilters['path']) result = result.filter(f => f.path?.toLowerCase().includes(colFilters['path'].toLowerCase()));
    if (colFilters['status']) result = result.filter(f => f.status?.toLowerCase().includes(colFilters['status'].toLowerCase()));

    // Sort
    result.sort((a, b) => {
      const aVal = String(a[sortField] ?? '');
      const bVal = String(b[sortField] ?? '');
      if (sortField === 'size') {
        const diff = Number(a.size) - Number(b.size);
        return sortDir === 'asc' ? diff : -diff;
      }
      const cmp = aVal.localeCompare(bVal, 'hu');
      return sortDir === 'asc' ? cmp : -cmp;
    });

    return result;
  }, [files, search, colFilters, statusFilter, sortField, sortDir]);

  function toggleSort(field: SortField) {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('asc'); }
  }

  function SortIcon({ field }: { field: SortField }) {
    if (sortField !== field) return <span className="text-gray-300 ml-1">↕</span>;
    return <span className="text-blue-500 ml-1">{sortDir === 'asc' ? '↑' : '↓'}</span>;
  }

  if (loading) return <div className="text-gray-500 p-8">Betöltés...</div>;

  return (
    <div className="space-y-4">
      {/* Top bar */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 bg-white p-4 rounded-xl shadow-sm border border-gray-200">
        <div className="relative w-full md:w-96">
          <input
            type="text"
            placeholder="Globális keresés (fájlnév, útvonal, státusz)..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <svg className="absolute left-2.5 top-2.5 h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <StatusChip label={`Összes (${files.length})`} active={statusFilter === 'all'} onClick={() => setStatusFilter('all')} />
          {Object.entries(statusCounts).map(([st, cnt]) => (
            <StatusChip key={st} label={`${st} (${cnt})`} active={statusFilter === st} onClick={() => setStatusFilter(st)} color={statusColor(st)} />
          ))}
          <button onClick={loadFiles} className="px-3 py-1.5 text-sm border border-gray-300 rounded-lg hover:bg-gray-50">Frissítés</button>
          <div className="bg-blue-50 text-blue-700 px-3 py-1.5 rounded-lg text-sm font-medium border border-blue-100">{visible.length} fájl</div>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm min-w-[640px]">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {/* Fájlnév */}
                <th className="text-left px-4 py-3">
                  <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('fileName')}>
                    Fájlnév <SortIcon field="fileName" />
                  </button>
                  <input type="text" placeholder="Szűrés..." className="w-full text-xs border border-gray-300 rounded px-2 py-1 font-normal" value={colFilters['fileName'] || ''} onChange={e => setColFilters(p => ({ ...p, fileName: e.target.value }))} />
                </th>
                {/* Útvonal */}
                <th className="text-left px-4 py-3">
                  <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('path')}>
                    Útvonal <SortIcon field="path" />
                  </button>
                  <input type="text" placeholder="Szűrés..." className="w-full text-xs border border-gray-300 rounded px-2 py-1 font-normal" value={colFilters['path'] || ''} onChange={e => setColFilters(p => ({ ...p, path: e.target.value }))} />
                </th>
                {/* Méret */}
                <th className="text-right px-4 py-3">
                  <button className="flex items-center justify-end font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5 w-full" onClick={() => toggleSort('size')}>
                    Méret <SortIcon field="size" />
                  </button>
                  <div className="h-[26px]" /> {/* spacer so headers align */}
                </th>
                {/* Módosítva */}
                <th className="text-left px-4 py-3">
                  <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('lastModified')}>
                    Módosítva <SortIcon field="lastModified" />
                  </button>
                  <div className="h-[26px]" />
                </th>
                {/* Státusz */}
                <th className="text-left px-4 py-3">
                  <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('status')}>
                    Státusz <SortIcon field="status" />
                  </button>
                  <input type="text" placeholder="Szűrés..." className="w-28 text-xs border border-gray-300 rounded px-2 py-1 font-normal" value={colFilters['status'] || ''} onChange={e => setColFilters(p => ({ ...p, status: e.target.value }))} />
                </th>
              </tr>
            </thead>
            <tbody>
              {visible.map(file => (
                <tr key={file.id} className="border-b border-gray-100 hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3 font-medium text-gray-900">{file.fileName}</td>
                  <td className="px-4 py-3 text-gray-500 text-xs max-w-xs truncate" title={file.path}>{file.path}</td>
                  <td className="px-4 py-3 text-right text-gray-600 whitespace-nowrap">{formatSize(file.size)}</td>
                  <td className="px-4 py-3 text-gray-600 whitespace-nowrap">{formatDate(file.lastModified)}</td>
                  <td className="px-4 py-3"><StatusBadge status={file.status} /></td>
                </tr>
              ))}
              {visible.length === 0 && (
                <tr><td colSpan={5} className="px-4 py-8 text-center text-gray-400 italic">Nincs a szűrésnek megfelelő fájl.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const colors: Record<string, string> = {
    New: 'bg-blue-100 text-blue-700',
    Modified: 'bg-amber-100 text-amber-700',
    Processed: 'bg-green-100 text-green-700',
    Deleted: 'bg-red-100 text-red-700',
  };
  return <span className={`px-2 py-1 rounded-full text-xs font-medium ${colors[status] || 'bg-gray-100 text-gray-700'}`}>{status}</span>;
}

function StatusChip({ label, active, onClick, color }: { label: string; active: boolean; onClick: () => void; color?: string }) {
  return (
    <button onClick={onClick} className={`px-3 py-1.5 text-xs rounded-full border transition-colors ${active ? 'bg-gray-900 text-white border-gray-900' : `bg-white ${color || 'text-gray-700'} border-gray-300 hover:bg-gray-50`}`}>
      {label}
    </button>
  );
}

function statusColor(status: string): string {
  const map: Record<string, string> = { New: 'text-blue-600', Modified: 'text-amber-600', Processed: 'text-green-600', Deleted: 'text-red-600' };
  return map[status] || 'text-gray-600';
}

function formatSize(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-';
  try {
    return new Date(dateStr).toLocaleString('hu-HU', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  } catch { return dateStr; }
}
