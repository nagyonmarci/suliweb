import { useState, useEffect, useRef } from 'react';
import { api, type ProgressState } from '../lib/api';

export default function PstProcessing() {
  const [progress, setProgress] = useState<ProgressState | null>(null);
  const [message, setMessage] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);
  const [saveAttachments, setSaveAttachments] = useState(true);
  const [directories, setDirectories] = useState('');
  const intervalRef = useRef<ReturnType<typeof setInterval>>(undefined);

  useEffect(() => {
    pollProgress();
    intervalRef.current = setInterval(pollProgress, 1500);
    api.getPstFinderSettings().then(s => {
      if (s.searchDirectories.length > 0) setDirectories(s.searchDirectories.join('\n'));
    }).catch(() => {});
    return () => clearInterval(intervalRef.current);
  }, []);

  async function pollProgress() {
    try {
      const p = await api.getProgress();
      setProgress(p);
      setIsProcessing(p.active);
    } catch {
      // backend not available
    }
  }

  async function handleFindPst() {
    if (!directories.trim()) {
      setMessage('Add meg a keresési könyvtárakat!');
      return;
    }
    setMessage('PST fájlok keresése...');
    try {
      const dirs = directories.split('\n').map(d => d.trim()).filter(Boolean);
      const result = await api.findPst(dirs);
      setMessage(result);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    }
  }

  async function handleProcessFromDb() {
    setMessage('Feldolgozás indítása...');
    setIsProcessing(true);
    try {
      const result = await api.processFromDb(saveAttachments);
      setMessage(result);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    } finally {
      setIsProcessing(false);
    }
  }

  async function handlePause() {
    try {
      await api.pauseProcessing();
      setMessage('Feldolgozás szüneteltetve.');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    }
  }

  async function handleResume() {
    try {
      await api.resumeProcessing();
      setMessage('Feldolgozás folytatva.');
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    }
  }

  async function handleSaveAttachmentsFromDb() {
    setMessage('Csatolmányok mentése indítása...');
    setIsProcessing(true);
    try {
      const result = await api.saveAttachmentsFromDb();
      setMessage(result);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage('Hiba: ' + e.message);
    } finally {
      setIsProcessing(false);
    }
  }

  return (
    <div className="space-y-6">
      {/* Progress section */}
      {progress?.active && (
        <div className="bg-white rounded-xl border border-blue-200 p-6">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-gray-700">Aktív feldolgozás</h3>
            <div className="flex gap-2">
              <button onClick={handlePause} className="px-3 py-1.5 text-sm bg-amber-500 text-white rounded-lg hover:bg-amber-600">
                Szünet
              </button>
              <button onClick={handleResume} className="px-3 py-1.5 text-sm bg-green-600 text-white rounded-lg hover:bg-green-700">
                Folytatás
              </button>
            </div>
          </div>
          <p className="text-sm text-gray-600 mb-1">{progress.currentOperation}</p>
          {progress.statusDetail && (
            <p className="text-xs text-blue-600 mb-2 font-medium italic">{progress.statusDetail}</p>
          )}
          <div className="w-full bg-gray-200 rounded-full h-4">
            <div
              className="bg-blue-600 h-4 rounded-full transition-all duration-500 flex items-center justify-center"
              style={{ width: `${Math.max(progress.percentage, 2)}%` }}
            >
              <span className="text-[10px] text-white font-medium">{progress.percentage}%</span>
            </div>
          </div>
          <p className="text-xs text-gray-500 mt-2">
            {progress.processedItems.toLocaleString('hu-HU')} / {progress.totalItems.toLocaleString('hu-HU')} elem
          </p>
        </div>
      )}

      {/* Find PST files */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">PST fájlok keresése</h3>
        <textarea
          value={directories}
          onChange={e => setDirectories(e.target.value)}
          placeholder="Könyvtárak (soronként egy)&#10;pl. /mnt/nas/share1&#10;/mnt/nas/share2"
          rows={3}
          className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 mb-3 font-mono"
        />
        <div className="flex gap-2">
          <button
            onClick={handleFindPst}
            className="px-4 py-2.5 bg-gray-700 text-white text-sm rounded-lg hover:bg-gray-800 transition-colors"
          >
            Keresés és mentés adatbázisba
          </button>
          <button
            onClick={async () => {
              try {
                const dirs = directories.split('\n').map(d => d.trim()).filter(Boolean);
                await api.savePstFinderSettings({ searchDirectories: dirs, excludedDirectories: [] });
                setMessage('Keresési könyvtárak elmentve alapértékként.');
              } catch (e: any) {
                setMessage('Hiba a mentésnél: ' + e.message);
              }
            }}
            className="px-4 py-2.5 bg-white border border-gray-300 text-gray-700 text-sm rounded-lg hover:bg-gray-50 transition-colors"
          >
            Mentés alapértékként
          </button>
        </div>
      </div>

      {/* Process from DB */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">Feldolgozás adatbázisból</h3>
        <p className="text-sm text-gray-500 mb-4">
          Az adatbázisban tárolt "New" és "Modified" státuszú PST fájlok feldolgozása.
        </p>
        <div className="flex items-center gap-4">
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={saveAttachments}
              onChange={e => setSaveAttachments(e.target.checked)}
              className="rounded border-gray-300"
            />
            Csatolmányok mentése
          </label>
          <button
            onClick={handleProcessFromDb}
            disabled={isProcessing}
            className="px-4 py-2.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {isProcessing ? 'Feldolgozás folyamatban...' : 'Feldolgozás indítása'}
          </button>
        </div>
      </div>

      {/* Save attachments for already processed files */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">Csatolmányok mentése (feldolgozott fájlok)</h3>
        <p className="text-sm text-gray-500 mb-4">
          A már "Processed" státuszú, de csatolmányokat még nem tartalmazó PST fájlok újraolvasása és csatolmányaik mentése.
        </p>
        <button
          onClick={handleSaveAttachmentsFromDb}
          disabled={isProcessing}
          className="px-4 py-2.5 bg-amber-600 text-white text-sm rounded-lg hover:bg-amber-700 disabled:opacity-50 transition-colors"
        >
          {isProcessing ? 'Feldolgozás folyamatban...' : 'Csatolmányok mentése'}
        </button>
      </div>

      {/* Message */}
      {message && (
        <div className={`rounded-lg px-4 py-3 text-sm ${
          message.startsWith('Hiba') ? 'bg-red-50 text-red-700 border border-red-200' : 'bg-green-50 text-green-700 border border-green-200'
        }`}>
          {message}
        </div>
      )}
    </div>
  );
}
