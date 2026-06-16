import '../lib/i18n';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import LanguageSwitcher from './LanguageSwitcher.tsx';

interface Props {
  activePage: string;
}

const NAV_ITEMS = [
  { href: '/', key: 'nav.dashboard', icon: '⌂' },
  { href: '/emails', key: 'nav.emails', icon: '✉' },
  { href: '/rag', key: 'nav.rag', icon: '🔍' },
  { href: '/files', key: 'nav.files', icon: '📁' },
  { href: '/attachments', key: 'nav.attachments', icon: '📎' },
  { href: '/attachment-processing', key: 'nav.attachmentProcessing', icon: '📄' },
  { href: '/pipeline', key: 'nav.pipeline', icon: '▶' },
  { href: '/processing', key: 'nav.processing', icon: '⚙' },
  { href: '/synology', key: 'nav.synology', icon: '🖥' },
  { href: '/ediscovery', key: 'nav.ediscovery', icon: '⚖' },
  { href: '/knowledge-graph', key: 'nav.knowledgeGraph', icon: '🕸' },
  { href: '/logs', key: 'nav.logs', icon: '📋' },
  { href: '/users', key: 'nav.users', icon: '👥' },
  { href: '/settings', key: 'nav.settings', icon: '⚙' },
];

export default function Sidebar({ activePage }: Props) {
  const { t } = useTranslation();
  const [username, setUsername] = useState('');

  useEffect(() => {
    (async () => {
      try {
        const token = localStorage.getItem('accessToken');
        if (!token) return;
        const res = await fetch('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } });
        if (res.ok) {
          const user = await res.json();
          if (user.username) setUsername(user.username);
        }
      } catch { /* non-critical */ }
    })();
  }, []);

  function logout() {
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    window.location.href = '/login';
  }

  return (
    <aside id="sidebar" className="fixed lg:sticky top-0 left-0 z-50 w-64 bg-gray-900 text-white h-screen flex flex-col -translate-x-full lg:translate-x-0 transition-transform duration-200 ease-in-out">
      <div className="p-6 border-b border-gray-700 hidden lg:block">
        <h1 className="text-xl font-bold tracking-tight">SuliWeb</h1>
        <div className="flex items-center justify-between mt-1">
          <p className="text-xs text-gray-400">{t('app.subtitle')}</p>
          <LanguageSwitcher />
        </div>
      </div>
      <div className="p-6 border-b border-gray-700 lg:hidden">
        <div className="flex items-center justify-between">
          <p className="text-xs text-gray-400">{t('app.subtitle')}</p>
          <LanguageSwitcher />
        </div>
      </div>
      <nav className="flex-1 p-4 space-y-1 overflow-y-auto">
        {NAV_ITEMS.map(item => (
          <a
            key={item.href}
            href={item.href}
            className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
              activePage === item.href
                ? 'bg-blue-600 text-white'
                : 'text-gray-300 hover:bg-gray-800 hover:text-white'
            }`}
          >
            <span className="w-5 h-5 flex items-center justify-center text-xs">{item.icon}</span>
            {t(item.key)}
          </a>
        ))}
      </nav>

      <div className="p-4 border-t border-gray-700">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2 min-w-0">
            <span className="w-8 h-8 bg-blue-600 rounded-full flex items-center justify-center text-sm font-medium shrink-0">
              {username ? username.charAt(0).toUpperCase() : '?'}
            </span>
            <span className="text-sm text-gray-300 truncate">{username || '...'}</span>
          </div>
          <button
            onClick={logout}
            className="text-gray-400 hover:text-white transition-colors p-1 shrink-0"
            title={t('nav.logout')}
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
            </svg>
          </button>
        </div>
      </div>
    </aside>
  );
}
