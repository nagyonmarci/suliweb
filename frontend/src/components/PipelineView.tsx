import '../lib/i18n';
import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { api, type StageProgress, type PipelineStatus } from '../lib/api';

function formatEta(seconds: number | null | undefined, t: TFunction): string {
  if (seconds == null || seconds <= 0) return '';
  if (seconds < 60) return t('pipeline.lessThanMinute');
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return t('pipeline.hoursMinutes', { h, m });
  return t('pipeline.minutes', { m });
}

function formatRate(rate: number | null | undefined, t: TFunction): string {
  if (rate == null || rate <= 0) return '';
  return t('pipeline.perMinute', { rate: rate.toFixed(1) });
}

type StageState = StageProgress['state'];

function StageIcon({ state }: { state: StageState }) {
  if (state === 'DONE') return <span className="text-green-500 text-lg">✓</span>;
  if (state === 'FAILED') return <span className="text-red-500 text-lg">✗</span>;
  if (state === 'SKIPPED') return <span className="text-gray-400 text-lg">─</span>;
  if (state === 'RUNNING') {
    return (
      <span className="inline-block w-4 h-4 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
    );
  }
  return <span className="text-gray-300 text-lg">○</span>;
}

function stageBg(state: StageState): string {
  if (state === 'DONE') return 'bg-green-50 border-green-200';
  if (state === 'FAILED') return 'bg-red-50 border-red-200';
  if (state === 'RUNNING') return 'bg-blue-50 border-blue-200';
  if (state === 'SKIPPED') return 'bg-gray-50 border-gray-200';
  return 'bg-white border-gray-200';
}

function progressBarColor(state: StageState): string {
  if (state === 'DONE') return 'bg-green-500';
  if (state === 'FAILED') return 'bg-red-500';
  if (state === 'RUNNING') return 'bg-blue-500';
  return 'bg-gray-300';
}

function StageCard({ stage }: { stage: StageProgress }) {
  const { t } = useTranslation();
  const pct = Math.min(100, Math.max(0, stage.percentage));
  return (
    <div className={`rounded-lg border p-4 ${stageBg(stage.state)}`}>
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-3">
          <StageIcon state={stage.state} />
          <span className="font-medium text-gray-800">{stage.name}</span>
        </div>
        <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
          stage.state === 'DONE'    ? 'bg-green-100 text-green-700' :
          stage.state === 'FAILED'  ? 'bg-red-100 text-red-700' :
          stage.state === 'RUNNING' ? 'bg-blue-100 text-blue-700' :
          stage.state === 'SKIPPED' ? 'bg-gray-100 text-gray-500' :
          'bg-gray-100 text-gray-400'
        }`}>
          {stage.state === 'PENDING'  ? t('pipeline.statePending') :
           stage.state === 'RUNNING'  ? t('pipeline.stateRunning') :
           stage.state === 'DONE'     ? t('common.done') :
           stage.state === 'FAILED'   ? t('common.failed') :
           t('pipeline.stateSkipped')}
        </span>
      </div>

      {/* Progress bar */}
      <div className="w-full bg-gray-200 rounded-full h-2 mb-2">
        <div
          className={`h-2 rounded-full transition-all duration-500 ${progressBarColor(stage.state)}`}
          style={{ width: `${pct}%` }}
        />
      </div>

      {/* Stats row */}
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-500">
        {(stage.state === 'RUNNING' || stage.state === 'DONE') && (
          <>
            <span>{pct}%</span>
            {stage.total > 0 && (
              <span>{stage.processed.toLocaleString()} / {stage.total.toLocaleString()}</span>
            )}
            {stage.ratePerMin != null && stage.ratePerMin > 0 && (
              <span>{formatRate(stage.ratePerMin, t)}</span>
            )}
            {stage.etaSeconds != null && stage.etaSeconds > 0 && (
              <span>ETA: {formatEta(stage.etaSeconds, t)}</span>
            )}
          </>
        )}
        {stage.detail && (
          <span className="truncate max-w-xs" title={stage.detail}>{stage.detail}</span>
        )}
      </div>
    </div>
  );
}

const DEFAULT_REQUEST = {
  directories: '',
  excludedDirectories: '',
  saveAttachments: true,
  skipPstDiscovery: false,
  skipPstProcessing: false,
  skipEsIndexing: false,
  skipKgIngestion: false,
};

export default function PipelineView() {
  const { t } = useTranslation();
  const [status, setStatus] = useState<PipelineStatus | null>(null);
  const [config, setConfig] = useState(DEFAULT_REQUEST);
  const [showConfig, setShowConfig] = useState(true);
  const [message, setMessage] = useState('');
  const intervalRef = useRef<ReturnType<typeof setInterval>>(undefined);

  useEffect(() => {
    pollStatus();
    intervalRef.current = setInterval(pollStatus, 2000);
    return () => clearInterval(intervalRef.current);
  }, []);

  async function pollStatus() {
    try {
      const s = await api.getPipelineStatus();
      setStatus(s);
      if (s.running) setShowConfig(false);
    } catch {
      // backend not available
    }
  }

  async function handleStart() {
    setMessage('');
    const dirs = config.directories.split('\n').map(d => d.trim()).filter(Boolean);
    const excl = config.excludedDirectories.split('\n').map(d => d.trim()).filter(Boolean);

    if (!config.skipPstDiscovery && dirs.length === 0) {
      setMessage(t('pipeline.enterDirectoryOrSkip'));
      return;
    }

    try {
      await api.startPipeline({
        directories: dirs,
        excludedDirectories: excl,
        saveAttachments: config.saveAttachments,
        skipPstDiscovery: config.skipPstDiscovery,
        skipPstProcessing: config.skipPstProcessing,
        skipEsIndexing: config.skipEsIndexing,
        skipKgIngestion: config.skipKgIngestion,
      });
      setShowConfig(false);
      setMessage('');
    } catch (e: unknown) {
      setMessage(t('common.error') + ': ' + (e instanceof Error ? e.message : String(e)));
    }
  }

  const isRunning = status?.running ?? false;
  const stages = status?.stages ?? [];

  const allDone = stages.length > 0 && stages.every(
    s => s.state === 'DONE' || s.state === 'SKIPPED' || s.state === 'FAILED'
  );

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-semibold text-gray-800">{t('pipeline.fullPipeline')}</h2>
          <p className="text-sm text-gray-500 mt-0.5">
            {t('pipeline.pipelineDescription')}
          </p>
        </div>
        {!isRunning && (
          <button
            onClick={() => setShowConfig(v => !v)}
            className="text-sm text-blue-600 hover:underline"
          >
            {showConfig ? t('pipeline.hideSettings') : t('nav.settings')}
          </button>
        )}
      </div>

      {/* Config panel */}
      {showConfig && !isRunning && (
        <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              {t('pipeline.directoriesLabel')}
            </label>
            <textarea
              className="w-full border border-gray-300 rounded px-3 py-2 text-sm font-mono h-24 focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="/mnt/nas/pst&#10;/data/archive"
              value={config.directories}
              onChange={e => setConfig(c => ({ ...c, directories: e.target.value }))}
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              {t('pipeline.excludedDirectoriesLabel')}
            </label>
            <textarea
              className="w-full border border-gray-300 rounded px-3 py-2 text-sm font-mono h-16 focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="/mnt/nas/pst/tmp"
              value={config.excludedDirectories}
              onChange={e => setConfig(c => ({ ...c, excludedDirectories: e.target.value }))}
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <label className="flex items-center gap-2 text-sm text-gray-700 cursor-pointer">
              <input
                type="checkbox"
                checked={config.saveAttachments}
                onChange={e => setConfig(c => ({ ...c, saveAttachments: e.target.checked }))}
                className="rounded"
              />
              {t('fileList.saveAttachments')}
            </label>
          </div>

          <div className="border-t border-gray-100 pt-3">
            <p className="text-xs text-gray-500 mb-2 font-medium">{t('pipeline.skipStages')}</p>
            <div className="grid grid-cols-2 gap-2">
              {[
                { key: 'skipPstDiscovery',  label: t('pipeline.skipPstDiscovery') },
                { key: 'skipPstProcessing', label: t('pipeline.skipPstProcessing') },
                { key: 'skipEsIndexing',    label: t('pipeline.skipEsIndexing') },
                { key: 'skipKgIngestion',   label: t('pipeline.skipKgIngestion') },
              ].map(({ key, label }) => (
                <label key={key} className="flex items-center gap-2 text-sm text-gray-600 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={config[key as keyof typeof config] as boolean}
                    onChange={e => setConfig(c => ({ ...c, [key]: e.target.checked }))}
                    className="rounded"
                  />
                  {label}
                </label>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Action buttons */}
      {!isRunning && (
        <div className="flex items-center gap-3">
          <button
            onClick={handleStart}
            className="px-5 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700 transition-colors"
          >
            {t('pipeline.startPipeline')}
          </button>
          {allDone && stages.length > 0 && (
            <span className="text-sm text-green-600 font-medium">{t('pipeline.allStagesDone')}</span>
          )}
        </div>
      )}

      {message && (
        <p className="text-sm text-red-600">{message}</p>
      )}

      {/* Stage cards */}
      {stages.length > 0 && (
        <div className="space-y-3">
          {stages.map(stage => (
            <StageCard key={stage.id} stage={stage} />
          ))}
        </div>
      )}

      {stages.length === 0 && !isRunning && (
        <div className="text-center py-12 text-gray-400 text-sm">
          {t('pipeline.configureAndStart')}
        </div>
      )}
    </div>
  );
}
