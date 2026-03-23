import { useState } from 'react';
import { api, type FileInfo } from '../lib/api';

export default function SynologyPanel() {
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [searched, setSearched] = useState(false);

  async function handleSearch() {
    setLoading(true);
    setMessage('');
    try {
      const data = await api.findSynology();
      setFiles(data);
      setSearched(true);
      setMessage(`${data.length} PST/OST fájl található a Synology NAS-on.`);
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleSearchAndSave() {
    setLoading(true);
    setMessage('');
    try {
      const result = await api.findSynologyToDb();
      setMessage(result);
      // Refresh the list
      const data = await api.findSynology();
      setFiles(data);
      setSearched(true);
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    } finally {
      setLoading(false);
    }
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
          <button
            onClick={handleSearch}
            disabled={loading}
            className="px-4 py-2.5 bg-gray-700 text-white text-sm rounded-lg hover:bg-gray-800 disabled:opacity-50 transition-colors"
          >
            {loading ? 'Keresés...' : 'PST fájlok keresése'}
          </button>
          <button
            onClick={handleSearchAndSave}
            disabled={loading}
            className="px-4 py-2.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            Keresés + mentés adatbázisba
          </button>
        </div>
      </div>

      {/* Message */}
      {message && (
        <div className={`rounded-lg px-4 py-3 text-sm ${
          message.startsWith('Hiba') ? 'bg-red-50 text-red-700 border border-red-200' : 'bg-blue-50 text-blue-700 border border-blue-200'
        }`}>
          {message}
        </div>
      )}

      {/* Results table */}
      {searched && (
        <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
          <div className="px-4 py-3 bg-gray-50 border-b border-gray-200">
            <h3 className="text-sm font-semibold text-gray-700">Találatok ({files.length})</h3>
          </div>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200">
                <th className="text-left px-4 py-3 font-medium text-gray-600">Fájlnév</th>
                <th className="text-left px-4 py-3 font-medium text-gray-600">Útvonal (lokális)</th>
                <th className="text-right px-4 py-3 font-medium text-gray-600">Méret</th>
                <th className="text-left px-4 py-3 font-medium text-gray-600">Módosítva</th>
              </tr>
            </thead>
            <tbody>
              {files.map((file, i) => (
                <tr key={i} className="border-b border-gray-100 hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium">{file.fileName}</td>
                  <td className="px-4 py-3 text-gray-500 text-xs font-mono max-w-md truncate">{file.path}</td>
                  <td className="px-4 py-3 text-right text-gray-600 whitespace-nowrap">{formatSize(file.size)}</td>
                  <td className="px-4 py-3 text-gray-600 whitespace-nowrap">{formatDate(file.lastModified)}</td>
                </tr>
              ))}
              {files.length === 0 && (
                <tr><td colSpan={4} className="px-4 py-8 text-center text-gray-400">Nem található PST/OST fájl</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
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
