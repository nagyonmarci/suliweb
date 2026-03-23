import { useState, useEffect } from 'react';
import { api, type ProgressState } from '../lib/api';

interface Stats {
  totalEmails: number;
  totalFiles: number;
  newFiles: number;
  processedFiles: number;
}

export default function Dashboard() {
  const [stats, setStats] = useState<Stats>({ totalEmails: 0, totalFiles: 0, newFiles: 0, processedFiles: 0 });
  const [progress, setProgress] = useState<ProgressState | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadStats();
    const interval = setInterval(loadProgress, 2000);
    return () => clearInterval(interval);
  }, []);

  async function loadStats() {
    try {
      const [emails, files] = await Promise.all([api.getEmails(), api.getFileInfos()]);
      setStats({
        totalEmails: emails.length,
        totalFiles: files.length,
        newFiles: files.filter(f => f.status === 'New' || f.status === 'Modified').length,
        processedFiles: files.filter(f => f.status === 'Processed').length,
      });
    } catch (e) {
      console.error('Stats betöltési hiba:', e);
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

  if (loading) {
    return <div className="text-gray-500">Betöltés...</div>;
  }

  return (
    <div className="space-y-6">
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

      {/* Quick actions */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">Gyors műveletek</h3>
        <div className="flex flex-wrap gap-3">
          <a href="/processing" className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 transition-colors">
            PST feldolgozás
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
