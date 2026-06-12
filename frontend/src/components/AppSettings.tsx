import { useState, useEffect } from 'react';
import { api, type AppSettingsDto } from '../lib/api';

export default function AppSettings() {
  const [settings, setSettings] = useState<AppSettingsDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [isError, setIsError] = useState(false);

  const [form, setForm] = useState({
    chatModel: '',
    nerModel: '',
    chatMaxHistoryTurns: '',
    kgBatchSize: '',
    kgMaxConcurrentWrites: '',
  });

  useEffect(() => {
    api.getSettings()
      .then(data => {
        setSettings(data);
        setForm({
          chatModel: data.chatModel,
          nerModel: data.nerModel,
          chatMaxHistoryTurns: String(data.chatMaxHistoryTurns),
          kgBatchSize: String(data.kgBatchSize),
          kgMaxConcurrentWrites: String(data.kgMaxConcurrentWrites),
        });
      })
      .catch(() => {
        setMessage('Beállítások betöltése sikertelen.');
        setIsError(true);
      })
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    setSaving(true);
    setMessage('');
    try {
      const payload: Partial<AppSettingsDto> = {
        chatModel: form.chatModel || undefined,
        nerModel: form.nerModel || undefined,
        chatMaxHistoryTurns: form.chatMaxHistoryTurns ? Number(form.chatMaxHistoryTurns) : undefined,
        kgBatchSize: form.kgBatchSize ? Number(form.kgBatchSize) : undefined,
        kgMaxConcurrentWrites: form.kgMaxConcurrentWrites ? Number(form.kgMaxConcurrentWrites) : undefined,
      };
      const saved = await api.saveSettings(payload);
      setSettings(saved);
      setMessage('Beállítások mentve.');
      setIsError(false);
    } catch {
      setMessage('Mentés sikertelen.');
      setIsError(true);
    } finally {
      setSaving(false);
    }
  };

  const field = (label: string, key: keyof typeof form, hint?: string) => (
    <div>
      <label className="block text-sm font-medium text-gray-700 mb-1">{label}</label>
      {hint && <p className="text-xs text-gray-500 mb-1">{hint}</p>}
      <input
        type="text"
        className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        value={form[key]}
        onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
      />
    </div>
  );

  if (loading) {
    return <div className="text-gray-500 text-sm">Betöltés...</div>;
  }

  return (
    <div className="space-y-8 max-w-2xl">
      {/* Ollama info */}
      <section className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
        <h2 className="text-base font-semibold text-gray-800">Ollama</h2>
        <div>
          <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-1">Base URL</p>
          <p className="text-sm font-mono bg-gray-50 rounded px-3 py-2 text-gray-700 border border-gray-200">
            {settings?.ollamaBaseUrl ?? '—'}
          </p>
          <p className="text-xs text-gray-400 mt-1">Az Ollama URL az indítási konfigurációban állítható (application.properties).</p>
        </div>
      </section>

      {/* LLM settings */}
      <section className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
        <h2 className="text-base font-semibold text-gray-800">LLM modellek</h2>
        {field('Chat modell', 'chatModel', 'RAG chat és GraphRAG válaszgeneráláshoz (pl. llama3.1:8b, qwen2.5:14b)')}
        {field('NER modell', 'nerModel', 'Entitáskinyeréshez — elhagyható, alapértelmezetten a chat modell')}
      </section>

      {/* RAG settings */}
      <section className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
        <h2 className="text-base font-semibold text-gray-800">RAG beállítások</h2>
        {field('Előzménykörök (chatMaxHistoryTurns)', 'chatMaxHistoryTurns', 'Hány user+assistant pár kerül a kontextusba (alapért.: 6)')}
      </section>

      {/* KG settings */}
      <section className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
        <h2 className="text-base font-semibold text-gray-800">Knowledge Graph ingestion</h2>
        {field('Kötegméret (kgBatchSize)', 'kgBatchSize', 'Emailek száma egy batch-ben (alapért.: 100)')}
        {field('Párhuzamos írók (kgMaxConcurrentWrites)', 'kgMaxConcurrentWrites', 'Neo4j írási szálak száma (alapért.: 4)')}
      </section>

      {/* Actions */}
      <div className="flex items-center gap-4">
        <button
          onClick={handleSave}
          disabled={saving}
          className="bg-blue-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {saving ? 'Mentés...' : 'Mentés'}
        </button>
        {message && (
          <span className={`text-sm ${isError ? 'text-red-600' : 'text-green-600'}`}>
            {message}
          </span>
        )}
      </div>
    </div>
  );
}
