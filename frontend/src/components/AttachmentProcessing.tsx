import '../lib/i18n';
import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { api, type AttachmentProcessingStatus, type FailedConversion } from '../lib/api';

export default function AttachmentProcessing() {
  const { t, i18n } = useTranslation();
  const locale = i18n.language === 'en' ? 'en-US' : 'hu-HU';
  const [status, setStatus] = useState<AttachmentProcessingStatus | null>(null);
  const [failed, setFailed] = useState<FailedConversion[]>([]);
  const [starting, setStarting] = useState(false);
  const [message, setMessage] = useState('');

  useEffect(() => {
    loadStatus();
    loadFailed();
    const interval = setInterval(() => { loadStatus(); loadFailed(); }, 5000);
    return () => clearInterval(interval);
  }, []);

  async function loadStatus() {
    try {
      setStatus(await api.attachmentProcessingStatus());
    } catch { /* backend may not be running */ }
  }

  async function loadFailed() {
    try {
      setFailed(await api.attachmentProcessingFailed());
    } catch { /* backend may not be running */ }
  }

  async function handleStart() {
    setStarting(true);
    setMessage('');
    try {
      const msg = await api.attachmentProcessingStart();
      setMessage(msg);
      loadStatus();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage(t('common.error') + ': ' + e.message);
    } finally {
      setStarting(false);
    }
  }

  async function handleRetryAll() {
    try {
      const msg = await api.attachmentProcessingRetryAll();
      setMessage(msg);
      loadStatus();
      loadFailed();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setMessage(t('common.error') + ': ' + e.message);
    }
  }

  async function handleRetryOne(id: string) {
    try {
      await api.attachmentProcessingRetry(id);
      loadStatus();
      loadFailed();
    } catch { /* ignore */ }
  }

  const stats = status?.stats;
  const isErrorMessage = message.toLowerCase().includes('hiba') || message.toLowerCase().includes('error');

  return (
    <div className="space-y-6">
      {/* Stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className={`rounded-xl border p-4 ${status?.running ? 'bg-amber-50 border-amber-200' : 'bg-gray-50 border-gray-200'}`}>
          <p className="text-xs font-medium text-gray-500">{t('attachmentProcessing.status')}</p>
          <p className={`text-base font-bold ${status?.running ? 'text-amber-700' : 'text-gray-700'}`}>
            {status?.running ? t('attachmentProcessing.processing') : t('common.done')}
          </p>
        </div>
        <div className="rounded-xl border p-4 bg-blue-50 border-blue-200">
          <p className="text-xs font-medium text-blue-600">{t('attachmentProcessing.indexed')}</p>
          <p className="text-lg font-bold text-blue-700">{stats?.indexed?.toLocaleString(locale) ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-gray-50 border-gray-200">
          <p className="text-xs font-medium text-gray-600">{t('attachmentProcessing.skipped')}</p>
          <p className="text-lg font-bold text-gray-700">{stats?.skipped?.toLocaleString(locale) ?? '-'}</p>
        </div>
        <div className="rounded-xl border p-4 bg-amber-50 border-amber-200">
          <p className="text-xs font-medium text-amber-600">{t('common.failed')}</p>
          <p className="text-lg font-bold text-amber-700">{stats?.failed?.toLocaleString(locale) ?? '-'}</p>
        </div>
      </div>

      {/* Start */}
      <div className="bg-white rounded-xl border border-gray-200 p-5">
        <h3 className="text-sm font-semibold text-gray-700 mb-3">{t('attachmentProcessing.title')}</h3>
        <div className="flex items-center gap-3 flex-wrap">
          <button
            onClick={handleStart}
            disabled={starting || status?.running}
            className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {status?.running ? t('attachmentProcessing.inProgress') : starting ? t('common.starting') : t('attachmentProcessing.title')}
          </button>
          <p className="text-xs text-gray-400">{t('attachmentProcessing.description')}</p>
        </div>
        {message && (
          <div className={`text-sm mt-3 p-3 rounded-lg flex items-start gap-2 ${
            isErrorMessage
              ? 'bg-red-50 text-red-700 border border-red-200'
              : 'bg-green-50 text-green-700 border border-green-200'
          }`}>
            <span className="flex-1">{message}</span>
            <button onClick={() => setMessage('')} className="text-current opacity-50 hover:opacity-100">&times;</button>
          </div>
        )}
      </div>

      {/* Failed list */}
      {failed.length > 0 && (
        <div className="bg-white rounded-xl border border-gray-200 p-5">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-gray-700">{t('attachmentProcessing.failedConversions', { count: failed.length })}</h3>
            <button
              onClick={handleRetryAll}
              className="text-xs px-3 py-1.5 bg-amber-600 text-white rounded-lg hover:bg-amber-700 transition-colors"
            >
              {t('common.retryAll')}
            </button>
          </div>
          <div className="space-y-2">
            {failed.map(fc => (
              <div key={fc.id} className="flex items-center justify-between text-sm border-b border-gray-100 pb-2 last:border-0 last:pb-0">
                <div className="min-w-0 flex-1">
                  <p className="font-medium text-gray-800 truncate">{fc.attachmentFilename || t('common.unnamed')}</p>
                  <p className="text-xs text-gray-400 truncate">{fc.errorMessage}</p>
                </div>
                <button
                  onClick={() => handleRetryOne(fc.id)}
                  className="text-xs px-2.5 py-1 border border-gray-300 rounded-lg text-gray-600 hover:bg-gray-50 transition-colors ml-3 shrink-0"
                >
                  {t('common.retry')}
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
