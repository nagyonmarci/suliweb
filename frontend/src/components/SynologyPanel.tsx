import { useState, useMemo } from 'react';
import { api, type FileInfo } from '../lib/api';

type SortField = 'fileName' | 'path' | 'size' | 'lastModified';

export default function SynologyPanel() {
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [searched, setSearched] = useState(false);

  // Filters
  const [search, setSearch] = useState('');
  const [colFilters, setColFilters] = useState<Record<string, string>>({});

  // Sort
  const [sortField, setSortField] = useState<SortField>('lastModified');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');

  async function handleSearch() {
    setLoading(true); setMessage('');
    try {
      const data = await api.findSynology();
      setFiles(data); setSearched(true);
      setMessage(`${data.length} PST/OST fájl található a Synology NAS-on.`);
    } catch (e: any) { setMessage('Hiba: ' + e.message); }
    finally { setLoading(false); }
  }

  async function handleSearchAndSave() {
    setLoading(true); setMessage('');
    try {
      const result = await api.findSynologyToDb();
      setMessage(result);
      const data = await api.findSynology();
      setFiles(data); setSearched(true);
    } catch (e: any) { setMessage('Hiba: ' + e.message); }
    finally { setLoading(false); }
  }

  const visible = useMemo(() => {
    let result = [...files];

    if (search.trim()) {
      const q = search.toLowerCase();
      result = result.filter(f =>
        f.fileName?.toLowerCase().includes(q) ||
        f.path?.toLowerCase().includes(q)
      );
    }
    if (colFilters['fileName']) result = result.filter(f => f.fileName?.toLowerCase().includes(colFilters['fileName'].toLowerCase()));
    if (colFilters['path']) result = result.filter(f => f.path?.toLowerCase().includes(colFilters['path'].toLowerCase()));

    result.sort((a, b) => {
      if (sortField === 'size') {
        const diff = Number(a.size) - Number(b.size);
        return sortDir === 'asc' ? diff : -diff;
      }
      const cmp = String(a[sortField] ?? '').localeCompare(String(b[sortField] ?? ''), 'hu');
      return sortDir === 'asc' ? cmp : -cmp;
    });

    return result;
  }, [files, search, colFilters, sortField, sortDir]);

  function toggleSort(field: SortField) {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('asc'); }
  }

  function SortIcon({ field }: { field: SortField }) {
    if (sortField !== field) return <span className="text-gray-300 ml-1">↕</span>;
    return <span className="text-blue-500 ml-1">{sortDir === 'asc' ? '↑' : '↓'}</span>;
  }

  return (
    <div className="space-y-6">
      {/* Info card */}
      <div className="bg-gradient-to-r from-gray-800 to-gray-900 text-white rounded-xl p-6">
        <h3 className="text-lg font-semibold mb-2">Synology NAS integráció</h3>
        <p className="text-sm text-gray-300">
          A Synology Universal Search API-n keresztül megtalálja a NAS-on tárolt PST és OST fájlokat,
          majd az útvonalakat leképezi a lokális mount pontra a feldolgozáshoz.
        </p>
      </div>

      {/* Actions */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">Műveletek</h3>
        <div className="flex flex-wrap gap-3">
          <button onClick={handleSearch} disabled={loading} className="px-4 py-2.5 bg-gray-700 text-white text-sm rounded-lg hover:bg-gray-800 disabled:opacity-50 transition-colors">
            {loading ? 'Keresés...' : 'PST fájlok keresése'}
          </button>
          <button onClick={handleSearchAndSave} disabled={loading} className="px-4 py-2.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors">
            Keresés + mentés adatbázisba
          </button>
        </div>
      </div>

      {/* Message */}
      {message && (
        <div className={`rounded-lg px-4 py-3 text-sm ${message.startsWith('Hiba') ? 'bg-red-50 text-red-700 border border-red-200' : 'bg-blue-50 text-blue-700 border border-blue-200'}`}>
          {message}
        </div>
      )}

      {/* Search + results */}
      {searched && (
        <div className="space-y-3">
          {/* Search bar */}
          <div className="flex items-center gap-3 bg-white p-3 rounded-xl border border-gray-200">
            <div className="relative flex-1">
              <input
                type="text"
                placeholder="Globális keresés (fájlnév, útvonal)..."
                value={search}
                onChange={e => setSearch(e.target.value)}
                className="w-full pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
              <svg className="absolute left-2.5 top-2.5 h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
            </div>
            <div className="bg-blue-50 text-blue-700 px-3 py-1.5 rounded-lg text-sm font-medium border border-blue-100 whitespace-nowrap">
              {visible.length} / {files.length} fájl
            </div>
          </div>

          {/* Table */}
          <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-sm min-w-[640px]">
                <thead className="bg-gray-50 border-b border-gray-200">
                  <tr>
                    <th className="text-left px-4 py-3">
                      <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('fileName')}>
                        Fájlnév <SortIcon field="fileName" />
                      </button>
                      <input type="text" placeholder="Szűrés..." className="w-full text-xs border border-gray-300 rounded px-2 py-1 font-normal" value={colFilters['fileName'] || ''} onChange={e => setColFilters(p => ({ ...p, fileName: e.target.value }))} />
                    </th>
                    <th className="text-left px-4 py-3">
                      <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('path')}>
                        Útvonal (lokális) <SortIcon field="path" />
                      </button>
                      <input type="text" placeholder="Szűrés..." className="w-full text-xs border border-gray-300 rounded px-2 py-1 font-normal" value={colFilters['path'] || ''} onChange={e => setColFilters(p => ({ ...p, path: e.target.value }))} />
                    </th>
                    <th className="text-right px-4 py-3">
                      <button className="flex items-center justify-end font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5 w-full" onClick={() => toggleSort('size')}>
                        Méret <SortIcon field="size" />
                      </button>
                      <div className="h-[26px]" />
                    </th>
                    <th className="text-left px-4 py-3">
                      <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('lastModified')}>
                        Módosítva <SortIcon field="lastModified" />
                      </button>
                      <div className="h-[26px]" />
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((file, i) => (
                    <tr key={i} className="border-b border-gray-100 hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 font-medium text-gray-900">{file.fileName}</td>
                      <td className="px-4 py-3 text-gray-500 text-xs font-mono max-w-md truncate" title={file.path}>{file.path}</td>
                      <td className="px-4 py-3 text-right text-gray-600 whitespace-nowrap">{formatSize(file.size)}</td>
                      <td className="px-4 py-3 text-gray-600 whitespace-nowrap">{formatDate(file.lastModified)}</td>
                    </tr>
                  ))}
                  {visible.length === 0 && (
                    <tr><td colSpan={4} className="px-4 py-8 text-center text-gray-400 italic">Nincs a szűrésnek megfelelő fájl.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
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
