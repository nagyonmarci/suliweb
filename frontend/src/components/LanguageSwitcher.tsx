import '../lib/i18n';
import { useTranslation } from 'react-i18next';
import { setLang, getLang } from '../lib/i18n';

export default function LanguageSwitcher() {
  const { i18n } = useTranslation();
  const current = getLang();

  return (
    <div className="flex items-center gap-1 text-xs">
      {(['hu', 'en'] as const).map(lang => (
        <button
          key={lang}
          onClick={() => setLang(lang)}
          className={`px-2 py-1 rounded-md font-medium transition-colors ${
            current === lang
              ? 'bg-blue-600 text-white'
              : 'text-gray-400 hover:text-white hover:bg-gray-800'
          }`}
        >
          {lang.toUpperCase()}
        </button>
      ))}
      {/* re-render on language change even though i18n isn't read directly above */}
      <span className="hidden">{i18n.language}</span>
    </div>
  );
}
