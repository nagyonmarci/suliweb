import '../lib/i18n';
import { useState, useMemo, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { api, type FileInfo, type SynologySettingsResponse } from '../lib/api';

type SortField = 'fileName' | 'path' | 'size' | 'lastModified';
type ActiveTab = 'actions' | 'duplicates' | 'settings';

function isErrorMessage(msg: string) {
  const lower = msg.toLowerCase();
  return lower.startsWith('hiba') || lower.startsWith('error');
}

export default function SynologyPanel() {
  const { t, i18n } = useTranslation();
  const locale = i18n.language === 'en' ? 'en-US' : 'hu-HU';
  const [activeTab, setActiveTab] = useState<ActiveTab>('actions');

  // --- Actions tab state ---
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

  // --- Settings tab state ---
  const [settings, setSettings] = useState<SynologySettingsResponse | null>(null);
  const [settingsLoading, setSettingsLoading] = useState(false);
  const [settingsSaving, setSettingsSaving] = useState(false);
  const [settingsMessage, setSettingsMessage] = useState('');

  const [hostInput, setHostInput] = useState('');
  const [usernameInput, setUsernameInput] = useState('');
  const [passwordInput, setPasswordInput] = useState('');
  const [pathPrefixInput, setPathPrefixInput] = useState('');
  const [localMountPrefixInput, setLocalMountPrefixInput] = useState('');
  const [searchExtensionsInput, setSearchExtensionsInput] = useState('');
  const [batchSizeInput, setBatchSizeInput] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  useEffect(() => {
    setSettingsLoading(true);
    api.getSynologySettings()
      .then(s => {
        setSettings(s);
        setHostInput(s.host ?? '');
        setUsernameInput(s.username ?? '');
        setPathPrefixInput(s.pathPrefix ?? '');
        setLocalMountPrefixInput(s.localMountPrefix ?? '');
        setSearchExtensionsInput(s.searchExtensions ?? '');
        setBatchSizeInput(s.batchSize != null ? String(s.batchSize) : '');
      })
      .catch(() => {})
      .finally(() => setSettingsLoading(false));
  }, []);

  async function handleSearch() {
    setLoading(true); setMessage('');
    try {
      const data = await api.findSynology();
      setFiles(data); setSearched(true);
      const dupCount = getDuplicateGroups(data).length;
      setMessage(t('synology.filesFound', { count: data.length }) +
        (dupCount > 0 ? ' ' + t('synology.duplicateGroupsFound', { count: dupCount }) : ''));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) { setMessage(t('common.error') + ': ' + e.message); }
    finally { setLoading(false); }
  }

  async function handleSearchAndSave() {
    setLoading(true); setMessage('');
    try {
      const result = await api.findSynologyToDb();
      setMessage(result);
      const data = await api.findSynology();
      setFiles(data); setSearched(true);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) { setMessage(t('common.error') + ': ' + e.message); }
    finally { setLoading(false); }
  }

  async function handleSaveSettings() {
    setSettingsSaving(true); setSettingsMessage('');
    try {
      const saved = await api.saveSynologySettings({
        host: hostInput || undefined,
        username: usernameInput || undefined,
        password: passwordInput || undefined,
        pathPrefix: pathPrefixInput || undefined,
        localMountPrefix: localMountPrefixInput || undefined,
        searchExtensions: searchExtensionsInput || undefined,
        batchSize: batchSizeInput ? parseInt(batchSizeInput) : undefined,
      });
      setSettings(saved);
      setPasswordInput('');
      setSettingsMessage(t('appSettings.saved'));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } catch (e: any) {
      setSettingsMessage(t('common.error') + ': ' + e.message);
    } finally {
      setSettingsSaving(false);
    }
  }

  const duplicateGroups = useMemo(() => getDuplicateGroups(files), [files]);

  const totalSizeGB = useMemo(() => {
    const bytes = files.reduce((sum, f) => sum + (Number(f.size) || 0), 0);
    return (bytes / (1024 ** 3)).toFixed(2);
  }, [files]);

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
      const cmp = String(a[sortField] ?? '').localeCompare(String(b[sortField] ?? ''), locale);
      return sortDir === 'asc' ? cmp : -cmp;
    });

    return result;
  }, [files, search, colFilters, sortField, sortDir, locale]);

  const visibleSizeGB = useMemo(() => {
    const bytes = visible.reduce((sum, f) => sum + (Number(f.size) || 0), 0);
    return (bytes / (1024 ** 3)).toFixed(2);
  }, [visible]);

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
        <h3 className="text-lg font-semibold mb-2">{t('synology.integrationTitle')}</h3>
        <p className="text-sm text-gray-300">
          {t('synology.integrationDescription')}
        </p>
      </div>

      {/* Tab strip */}
      <div className="border-b border-gray-200">
        <nav className="flex gap-1">
          {([
            ['actions', t('synology.tabActions')],
            ['duplicates', t('synology.tabDuplicates') + (duplicateGroups.length > 0 ? ` (${duplicateGroups.length})` : '')],
            ['settings', t('nav.settings')]
          ] as [ActiveTab, string][]).map(([tab, label]) => (
            <button
              key={tab}
              onClick={() => setActiveTab(tab)}
              className={`px-4 py-2.5 text-sm font-medium rounded-t-lg transition-colors ${
                activeTab === tab
                  ? 'bg-white border border-b-white border-gray-200 text-blue-600 -mb-px'
                  : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              {label}
            </button>
          ))}
        </nav>
      </div>

      {/* === Actions tab === */}
      {activeTab === 'actions' && (
        <>
          {/* Actions */}
          <div className="bg-white rounded-xl border border-gray-200 p-6">
            <h3 className="text-sm font-semibold text-gray-700 mb-4">{t('synology.tabActions')}</h3>
            <div className="flex flex-wrap gap-3">
              <button onClick={handleSearch} disabled={loading} className="px-4 py-2.5 bg-gray-700 text-white text-sm rounded-lg hover:bg-gray-800 disabled:opacity-50 transition-colors">
                {loading ? t('common.search') + '...' : t('synology.findPstFiles')}
              </button>
              <button onClick={handleSearchAndSave} disabled={loading} className="px-4 py-2.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors">
                {t('synology.findAndSave')}
              </button>
            </div>
          </div>

          {/* Message */}
          {message && (
            <div className={`rounded-lg px-4 py-3 text-sm ${isErrorMessage(message) ? 'bg-red-50 text-red-700 border border-red-200' : 'bg-blue-50 text-blue-700 border border-blue-200'}`}>
              {message}
            </div>
          )}

          {/* Search + results */}
          {searched && (
            <div className="space-y-3">
              {/* Summary */}
              <div className="grid grid-cols-3 gap-3">
                <div className="bg-white rounded-xl border border-gray-200 p-4">
                  <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">{t('dashboard.totalFiles')}</p>
                  <p className="text-2xl font-bold text-gray-900">{t('fileList.groupCount', { count: files.length })}</p>
                </div>
                <div className="bg-white rounded-xl border border-blue-100 p-4">
                  <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">{t('synology.totalSize')}</p>
                  <p className="text-2xl font-bold text-blue-600">{totalSizeGB} GB</p>
                </div>
                <div className="bg-white rounded-xl border border-gray-200 p-4">
                  <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">{t('synology.filteredSize')}</p>
                  <p className="text-2xl font-bold text-gray-700">{visibleSizeGB} GB</p>
                </div>
              </div>

              {/* Search bar */}
              <div className="flex items-center gap-3 bg-white p-3 rounded-xl border border-gray-200">
                <div className="relative flex-1">
                  <input
                    type="text"
                    placeholder={t('synology.globalSearchPlaceholder')}
                    value={search}
                    onChange={e => setSearch(e.target.value)}
                    className="w-full pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                  <svg className="absolute left-2.5 top-2.5 h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                  </svg>
                </div>
                <div className="bg-blue-50 text-blue-700 px-3 py-1.5 rounded-lg text-sm font-medium border border-blue-100 whitespace-nowrap">
                  {t('synology.fileCountOf', { visible: visible.length, total: files.length })}
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
                            {t('fileList.fileName')} <SortIcon field="fileName" />
                          </button>
                          <input type="text" placeholder={t('emailBrowser.filterPlaceholder')} className="w-full text-xs border border-gray-300 rounded px-2 py-1 font-normal" value={colFilters['fileName'] || ''} onChange={e => setColFilters(p => ({ ...p, fileName: e.target.value }))} />
                        </th>
                        <th className="text-left px-4 py-3">
                          <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('path')}>
                            {t('synology.localPath')} <SortIcon field="path" />
                          </button>
                          <input type="text" placeholder={t('emailBrowser.filterPlaceholder')} className="w-full text-xs border border-gray-300 rounded px-2 py-1 font-normal" value={colFilters['path'] || ''} onChange={e => setColFilters(p => ({ ...p, path: e.target.value }))} />
                        </th>
                        <th className="text-right px-4 py-3">
                          <button className="flex items-center justify-end font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5 w-full" onClick={() => toggleSort('size')}>
                            {t('fileList.size')} <SortIcon field="size" />
                          </button>
                          <div className="h-[26px]" />
                        </th>
                        <th className="text-left px-4 py-3">
                          <button className="flex items-center font-semibold text-xs text-gray-600 uppercase tracking-wider mb-1.5" onClick={() => toggleSort('lastModified')}>
                            {t('fileList.modified')} <SortIcon field="lastModified" />
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
                          <td className="px-4 py-3 text-gray-600 whitespace-nowrap">{formatDate(file.lastModified, locale)}</td>
                        </tr>
                      ))}
                      {visible.length === 0 && (
                        <tr><td colSpan={4} className="px-4 py-8 text-center text-gray-400 italic">{t('fileList.noFilesForFilter')}</td></tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}
        </>
      )}

      {/* === Duplicates tab === */}
      {activeTab === 'duplicates' && (
        <div className="space-y-4">
          {!searched ? (
            <div className="bg-white rounded-xl border border-gray-200 px-4 py-10 text-center text-gray-400 italic">
              {t('synology.runSearchFirst')}
            </div>
          ) : duplicateGroups.length === 0 ? (
            <div className="bg-white rounded-xl border border-gray-200 px-4 py-10 text-center text-gray-400 italic">
              {t('synology.noDuplicatesInResults')}
            </div>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3">
                <div className="bg-white rounded-xl border border-amber-100 p-4">
                  <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">{t('fileList.duplicateGroups')}</p>
                  <p className="text-2xl font-bold text-amber-600">{t('fileList.groupCount', { count: duplicateGroups.length })}</p>
                </div>
                <div className="bg-white rounded-xl border border-red-100 p-4">
                  <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">{t('fileList.wastedSpace')}</p>
                  <p className="text-2xl font-bold text-red-600">
                    {(duplicateGroups.reduce((s, g) => s + g[0].size * (g.length - 1), 0) / 1024 ** 3).toFixed(2)} GB
                  </p>
                </div>
              </div>
              <p className="text-xs text-gray-500 px-1">
                {t('synology.saveSkipsDuplicatesHint')}
              </p>
              {duplicateGroups.map((group, gi) => (
                <div key={gi} className="bg-white rounded-xl border border-amber-200 overflow-hidden">
                  <div className="bg-amber-50 px-4 py-2.5 flex items-center justify-between border-b border-amber-200">
                    <span className="text-sm font-semibold text-amber-800">
                      {t('fileList.identicalFiles', { count: group.length, size: formatSize(group[0].size) })}
                    </span>
                    {group[0].contentHash && (
                      <span className="text-xs text-amber-600 font-mono" title={t('synology.contentHashHint')}>
                        {group[0].contentHash.slice(0, 12)}…
                      </span>
                    )}
                  </div>
                  <table className="w-full text-sm">
                    <tbody>
                      {group.map((file, fi) => (
                        <tr key={fi} className={`border-b border-gray-100 ${fi === 0 ? 'bg-green-50' : 'hover:bg-gray-50'}`}>
                          <td className="px-4 py-2.5 font-medium text-gray-900 whitespace-nowrap">
                            {fi === 0 && <span className="mr-2 text-xs bg-green-100 text-green-700 px-1.5 py-0.5 rounded">{t('synology.willBeSaved')}</span>}
                            {fi > 0 && <span className="mr-2 text-xs bg-red-100 text-red-600 px-1.5 py-0.5 rounded">{t('synology.skipped')}</span>}
                            {file.fileName}
                          </td>
                          <td className="px-4 py-2.5 text-gray-500 text-xs font-mono max-w-xs truncate" title={file.path}>{file.path}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ))}
            </>
          )}
        </div>
      )}

      {/* === Settings tab === */}
      {activeTab === 'settings' && (
        <div className="bg-white rounded-xl border border-gray-200 p-6">
          <h3 className="text-sm font-semibold text-gray-700 mb-6">{t('synology.connectionSettings')}</h3>

          {settingsLoading ? (
            <p className="text-sm text-gray-400">{t('common.loading')}</p>
          ) : (
            <div className="space-y-4 max-w-lg">
              {/* Host */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">{t('synology.nasAddress')}</label>
                <input
                  type="text"
                  placeholder="e.g. http://192.168.1.100:5000"
                  value={hostInput}
                  onChange={e => setHostInput(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              {/* Username */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">{t('login.username')}</label>
                <input
                  type="text"
                  placeholder="admin"
                  value={usernameInput}
                  onChange={e => setUsernameInput(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              {/* Password */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">
                  {t('login.password')}
                  {settings?.passwordConfigured && (
                    <span className="ml-2 text-green-600 font-normal">({t('synology.configured')})</span>
                  )}
                </label>
                <div className="relative">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    placeholder={settings?.passwordConfigured ? '••••••••' : t('synology.enterPassword')}
                    value={passwordInput}
                    onChange={e => setPasswordInput(e.target.value)}
                    className="w-full border border-gray-300 rounded-lg px-3 py-2 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(v => !v)}
                    className="absolute right-2.5 top-2 text-gray-400 hover:text-gray-600"
                    tabIndex={-1}
                  >
                    {showPassword ? (
                      <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21" /></svg>
                    ) : (
                      <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></svg>
                    )}
                  </button>
                </div>
                <p className="text-xs text-gray-400 mt-1">{t('synology.leaveBlankHint')}</p>
              </div>

              {/* Divider */}
              <div className="border-t border-gray-100 pt-4">
                <p className="text-xs font-medium text-gray-500 mb-3 uppercase tracking-wider">{t('synology.pathSettings')}</p>
              </div>

              {/* Path prefix */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">{t('synology.synologyPathPrefix')}</label>
                <input
                  type="text"
                  placeholder="/volume1"
                  value={pathPrefixInput}
                  onChange={e => setPathPrefixInput(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              {/* Local mount prefix */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">{t('synology.localMountPrefix')}</label>
                <input
                  type="text"
                  placeholder="/mnt/nas"
                  value={localMountPrefixInput}
                  onChange={e => setLocalMountPrefixInput(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              {/* Divider */}
              <div className="border-t border-gray-100 pt-4">
                <p className="text-xs font-medium text-gray-500 mb-3 uppercase tracking-wider">{t('synology.searchSettings')}</p>
              </div>

              {/* Extensions */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">{t('synology.searchExtensions')}</label>
                <input
                  type="text"
                  placeholder="pst,ost"
                  value={searchExtensionsInput}
                  onChange={e => setSearchExtensionsInput(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                <p className="text-xs text-gray-400 mt-1">{t('synology.commaSeparatedHint')}</p>
              </div>

              {/* Batch size */}
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">{t('synology.batchSize')}</label>
                <input
                  type="number"
                  min="1"
                  placeholder="100"
                  value={batchSizeInput}
                  onChange={e => setBatchSizeInput(e.target.value)}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              {/* Save */}
              <div className="flex items-center gap-3 pt-2">
                <button
                  onClick={handleSaveSettings}
                  disabled={settingsSaving}
                  className="px-5 py-2.5 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
                >
                  {settingsSaving ? t('common.starting') : t('common.save')}
                </button>
                {settingsMessage && (
                  <span className={`text-sm ${isErrorMessage(settingsMessage) ? 'text-red-600' : 'text-green-600'}`}>
                    {settingsMessage}
                  </span>
                )}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function getDuplicateGroups(files: FileInfo[]): FileInfo[][] {
  const groups = new Map<string, FileInfo[]>();
  for (const f of files) {
    const key = f.contentHash ? `hash:${f.contentHash}` : `name-size:${f.fileName}:${f.size}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(f);
  }
  return Array.from(groups.values()).filter(g => g.length > 1);
}

function formatSize(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
}

function formatDate(dateStr: string | null, locale: string) {
  if (!dateStr) return '-';
  try {
    return new Date(dateStr).toLocaleString(locale, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  } catch { return dateStr; }
}
