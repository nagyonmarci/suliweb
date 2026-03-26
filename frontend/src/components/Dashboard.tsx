import { useState, useEffect } from 'react';
import { api, type ProgressState, type RagHealth } from '../lib/api';

interface Stats {
  totalEmails: number;
  totalFiles: number;
  newFiles: number;
  processedFiles: number;
}

export default function Dashboard() {
  const [stats, setStats] = useState<Stats>({ totalEmails: 0, totalFiles: 0, newFiles: 0, processedFiles: 0 });
  const [progress, setProgress] = useState<ProgressState | null>(null);
  const [ragHealth, setRagHealth] = useState<RagHealth | null>(null);
  const [loading, setLoading] = useState(true);
  const [statsError, setStatsError] = useState<string | null>(null);

  useEffect(() => {
    loadStats();
    loadRagHealth();
    const interval = setInterval(loadProgress, 2000);
    return () => clearInterval(interval);
  }, []);

  async function loadStats() {
    try {
      setStatsError(null);
      const [emailCount, fileCounts] = await Promise.all([
        api.getEmailCount(),
        api.getFileInfoCounts(),
      ]);
      setStats({
        totalEmails: emailCount,
        totalFiles: fileCounts.total,
        newFiles: fileCounts.pending,
        processedFiles: fileCounts.processed,
      });
    } catch (e) {
      console.error('Stats betöltési hiba:', e);
      setStatsError('A statisztikák betöltése sikertelen. Ellenőrizd, hogy a backend fut-e.');
    } finally {
      setLoading(false);
    }
  }

  async function loadProgress() {
    try {
      const p = await api.getProgress();
      setProgress(p);
    } catch {
      // backend may not be running
    }
  }

  async function loadRagHealth() {
    try {
      const h = await api.ragHealth();
      setRagHealth(h);
    } catch {
      // RAG may not be configured
    }
  }

  if (loading) {
    return <div className="text-gray-500">Betöltés...</div>;
  }

  return (
    <div className="space-y-6">
      {/* Error banner */}
      {statsError && (
        <div className="bg-red-50 border border-red-200 rounded-xl p-4 flex items-start gap-3">
          <svg className="w-5 h-5 text-red-500 mt-0.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
          </svg>
          <div className="flex-1">
            <p className="text-sm font-medium text-red-700">{statsError}</p>
          </div>
          <button
            onClick={loadStats}
            className="text-sm text-red-600 hover:text-red-800 font-medium underline shrink-0"
          >
            Újrapróbál
          </button>
        </div>
      )}

      {/* Stat cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        <StatCard label="Összes email" value={stats.totalEmails} color="blue" />
        <StatCard label="Összes fájl" value={stats.totalFiles} color="gray" />
        <StatCard label="Feldolgozandó" value={stats.newFiles} color="amber" />
        <StatCard label="Feldolgozott" value={stats.processedFiles} color="green" />
      </div>

      {/* Active progress */}
      {progress?.active && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h3 className="text-sm font-semibold text-gray-700 mb-3">Aktív feldolgozás</h3>
          <p className="text-sm text-gray-600 mb-2">{progress.currentOperation}</p>
          <div className="w-full bg-gray-200 rounded-full h-3">
            <div
              className="bg-blue-600 h-3 rounded-full transition-all duration-300"
              style={{ width: `${progress.percentage}%` }}
            />
          </div>
          <p className="text-xs text-gray-500 mt-2">
            {progress.processedItems} / {progress.totalItems} ({progress.percentage}%)
          </p>
        </div>
      )}

      {/* RAG Status */}
      {ragHealth && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-semibold text-gray-700">RAG Index</h3>
            <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${ragHealth.ollamaAvailable ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'}`}>
              Ollama: {ragHealth.ollamaAvailable ? 'Online' : 'Offline'}
            </span>
          </div>
          <div className="grid grid-cols-3 gap-4">
            <div>
              <p className="text-xs text-gray-500">Indexelt chunkek</p>
              <p className="text-xl font-bold text-blue-700">{ragHealth.stats.embeddedChunks.toLocaleString('hu-HU')}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Függőben</p>
              <p className="text-xl font-bold text-amber-600">{ragHealth.stats.pendingChunks.toLocaleString('hu-HU')}</p>
            </div>
            <div>
              <p className="text-xs text-gray-500">Sikertelen</p>
              <p className="text-xl font-bold text-red-600">{ragHealth.stats.failedChunks.toLocaleString('hu-HU')}</p>
            </div>
          </div>
          {ragHealth.ingestionRunning && (
            <p className="text-xs text-blue-600 mt-3 animate-pulse">Indexelés folyamatban...</p>
          )}
        </div>
      )}

      {/* Quick actions */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">Gyors műveletek</h3>
        <div className="flex flex-wrap gap-3">
          <a href="/processing" className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 transition-colors">
            PST feldolgozás
          </a>
          <a href="/rag" className="px-4 py-2 bg-purple-600 text-white text-sm rounded-lg hover:bg-purple-700 transition-colors">
            RAG keresés
          </a>
          <a href="/synology" className="px-4 py-2 bg-gray-700 text-white text-sm rounded-lg hover:bg-gray-800 transition-colors">
            Synology keresés
          </a>
          <a href="/emails" className="px-4 py-2 bg-white border border-gray-300 text-sm rounded-lg hover:bg-gray-50 transition-colors">
            Email böngésző
          </a>
        </div>
      </div>
    </div>
  );
}

function StatCard({ label, value, color }: { label: string; value: number; color: string }) {
  const colorMap: Record<string, string> = {
    blue: 'bg-blue-50 border-blue-200 text-blue-700',
    gray: 'bg-gray-50 border-gray-200 text-gray-700',
    amber: 'bg-amber-50 border-amber-200 text-amber-700',
    green: 'bg-green-50 border-green-200 text-green-700',
  };

  return (
    <div className={`rounded-xl border p-6 ${colorMap[color]}`}>
      <p className="text-sm font-medium opacity-75">{label}</p>
      <p className="text-3xl font-bold mt-1">{value.toLocaleString('hu-HU')}</p>
    </div>
  );
}
