import '../lib/i18n';
import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { api, type ProgressState } from '../lib/api';

function isErrorMessage(msg: string) {
  const lower = msg.toLowerCase();
  return lower.startsWith('hiba') || lower.startsWith('error');
}

export default function PstProcessing() {
  const { t, i18n } = useTranslation();
  const locale = i18n.language === 'en' ? 'en-US' : 'hu-HU';
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
      setMessage(t('pstProcessing.enterDirectories'));
      return;
    }
    setMessage(t('pstProcessing.searchingPst'));
    try {
      const dirs = directories.split('\n').map(d => d.trim()).filter(Boolean);
      const result = await api.findPst(dirs);
      setMessage(result);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage(t('common.error') + ': ' + e.message);
    }
  }

  async function handleProcessFromDb() {
    setMessage(t('fileList.startingProcessing'));
    setIsProcessing(true);
    try {
      const result = await api.processFromDb(saveAttachments);
      setMessage(result);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage(t('common.error') + ': ' + e.message);
    } finally {
      setIsProcessing(false);
    }
  }

  async function handlePause() {
    try {
      await api.pauseProcessing();
      setMessage(t('pstProcessing.paused'));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage(t('common.error') + ': ' + e.message);
    }
  }

  async function handleResume() {
    try {
      await api.resumeProcessing();
      setMessage(t('pstProcessing.resumed'));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage(t('common.error') + ': ' + e.message);
    }
  }

  async function handleSaveAttachmentsFromDb() {
    setMessage(t('pstProcessing.startingAttachmentSave'));
    setIsProcessing(true);
    try {
      const result = await api.saveAttachmentsFromDb();
      setMessage(result);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage(t('common.error') + ': ' + e.message);
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
            <h3 className="text-sm font-semibold text-gray-700">{t('dashboard.activeProcessing')}</h3>
            <div className="flex gap-2">
              <button onClick={handlePause} className="px-3 py-1.5 text-sm bg-amber-500 text-white rounded-lg hover:bg-amber-600">
                {t('pstProcessing.pause')}
              </button>
              <button onClick={handleResume} className="px-3 py-1.5 text-sm bg-green-600 text-white rounded-lg hover:bg-green-700">
                {t('pstProcessing.resume')}
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
            {t('pstProcessing.itemsProgress', {
              processed: progress.processedItems.toLocaleString(locale),
              total: progress.totalItems.toLocaleString(locale),
            })}
          </p>
        </div>
      )}

      {/* Find PST files */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">{t('pstProcessing.findPstFiles')}</h3>
        <textarea
          value={directories}
          onChange={e => setDirectories(e.target.value)}
          placeholder={t('pstProcessing.directoriesPlaceholder')}
          rows={3}
          className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 mb-3 font-mono"
        />
        <div className="flex gap-2">
          <button
            onClick={handleFindPst}
            className="px-4 py-2.5 bg-gray-700 text-white text-sm rounded-lg hover:bg-gray-800 transition-colors"
          >
            {t('pstProcessing.searchAndSave')}
          </button>
          <button
            onClick={async () => {
              try {
                const dirs = directories.split('\n').map(d => d.trim()).filter(Boolean);
                await api.savePstFinderSettings({ searchDirectories: dirs, excludedDirectories: [] });
                setMessage(t('pstProcessing.directoriesSaved'));
              } catch (e) {
                setMessage(t('pstProcessing.saveError') + ': ' + (e instanceof Error ? e.message : String(e)));
              }
            }}
            className="px-4 py-2.5 bg-white border border-gray-300 text-gray-700 text-sm rounded-lg hover:bg-gray-50 transition-colors"
          >
            {t('pstProcessing.saveAsDefault')}
          </button>
        </div>
      </div>

      {/* Process from DB */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">{t('pstProcessing.processFromDb')}</h3>
        <p className="text-sm text-gray-500 mb-4">
          {t('pstProcessing.processFromDbHint')}
        </p>
        <div className="flex items-center gap-4">
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={saveAttachments}
              onChange={e => setSaveAttachments(e.target.checked)}
              className="rounded border-gray-300"
            />
            {t('fileList.saveAttachments')}
          </label>
          <button
            onClick={handleProcessFromDb}
            disabled={isProcessing}
            className="px-4 py-2.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {isProcessing ? t('attachmentProcessing.inProgress') : t('pstProcessing.startProcessing')}
          </button>
        </div>
      </div>

      {/* Save attachments for already processed files */}
      <div className="bg-white rounded-xl border border-gray-200 p-6">
        <h3 className="text-sm font-semibold text-gray-700 mb-4">{t('pstProcessing.saveAttachmentsTitle')}</h3>
        <p className="text-sm text-gray-500 mb-4">
          {t('pstProcessing.saveAttachmentsHint')}
        </p>
        <button
          onClick={handleSaveAttachmentsFromDb}
          disabled={isProcessing}
          className="px-4 py-2.5 bg-amber-600 text-white text-sm rounded-lg hover:bg-amber-700 disabled:opacity-50 transition-colors"
        >
          {isProcessing ? t('attachmentProcessing.inProgress') : t('fileList.saveAttachments')}
        </button>
      </div>

      {/* Message */}
      {message && (
        <div className={`rounded-lg px-4 py-3 text-sm ${
          isErrorMessage(message) ? 'bg-red-50 text-red-700 border border-red-200' : 'bg-green-50 text-green-700 border border-green-200'
        }`}>
          {message}
        </div>
      )}
    </div>
  );
}
