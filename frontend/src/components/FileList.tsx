import { useState, useEffect } from 'react';
import { api, type FileInfo } from '../lib/api';

export default function FileList() {
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<string>('all');

  useEffect(() => {
    loadFiles();
  }, []);

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

  const filtered = statusFilter === 'all'
    ? files
    : files.filter(f => f.status === statusFilter);

  const statusCounts = files.reduce((acc, f) => {
    acc[f.status] = (acc[f.status] || 0) + 1;
    return acc;
  }, {} as Record<string, number>);

  if (loading) return <div className="text-gray-500">Betöltés...</div>;

  return (
    <div className="space-y-4">
      {/* Status filter chips */}
      <div className="flex items-center gap-2 flex-wrap">
        <FilterChip
          label={`Összes (${files.length})`}
          active={statusFilter === 'all'}
          onClick={() => setStatusFilter('all')}
        />
        {Object.entries(statusCounts).map(([status, count]) => (
          <FilterChip
            key={status}
            label={`${status} (${count})`}
            active={statusFilter === status}
            onClick={() => setStatusFilter(status)}
            color={statusColor(status)}
          />
        ))}
        <button
          onClick={loadFiles}
          className="ml-auto px-3 py-1.5 text-sm border border-gray-300 rounded-lg hover:bg-white"
        >
          Frissítés
        </button>
      </div>

      {/* Table */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        <div className="overflow-x-auto">
        <table className="w-full text-sm min-w-[640px]">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-200">
              <th className="text-left px-4 py-3 font-medium text-gray-600">Fájlnév</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">Útvonal</th>
              <th className="text-right px-4 py-3 font-medium text-gray-600">Méret</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">Módosítva</th>
              <th className="text-left px-4 py-3 font-medium text-gray-600">Státusz</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map(file => (
              <tr key={file.id} className="border-b border-gray-100 hover:bg-gray-50">
                <td className="px-4 py-3 font-medium">{file.fileName}</td>
                <td className="px-4 py-3 text-gray-500 text-xs max-w-xs truncate">{file.path}</td>
                <td className="px-4 py-3 text-right text-gray-600 whitespace-nowrap">{formatSize(file.size)}</td>
                <td className="px-4 py-3 text-gray-600 whitespace-nowrap">{formatDate(file.lastModified)}</td>
                <td className="px-4 py-3">
                  <StatusBadge status={file.status} />
                </td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-8 text-center text-gray-400">Nincs fájl</td></tr>
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
  return (
    <span className={`px-2 py-1 rounded-full text-xs font-medium ${colors[status] || 'bg-gray-100 text-gray-700'}`}>
      {status}
    </span>
  );
}

function FilterChip({ label, active, onClick, color }: { label: string; active: boolean; onClick: () => void; color?: string }) {
  return (
    <button
      onClick={onClick}
      className={`px-3 py-1.5 text-sm rounded-full border transition-colors ${
        active
          ? 'bg-gray-900 text-white border-gray-900'
          : `bg-white ${color || 'text-gray-700'} border-gray-300 hover:bg-gray-50`
      }`}
    >
      {label}
    </button>
  );
}

function statusColor(status: string): string {
  const map: Record<string, string> = {
    New: 'text-blue-600',
    Modified: 'text-amber-600',
    Processed: 'text-green-600',
    Deleted: 'text-red-600',
  };
  return map[status] || 'text-gray-600';
}

function formatSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
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
