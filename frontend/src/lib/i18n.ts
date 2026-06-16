import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import hu from '../locales/hu.json';
import en from '../locales/en.json';

export type Lang = 'hu' | 'en';

function storedLang(): Lang {
  if (typeof localStorage === 'undefined') return 'hu';
  const v = localStorage.getItem('lang');
  return v === 'en' ? 'en' : 'hu';
}

if (!i18next.isInitialized) {
  i18next
    .use(initReactI18next)
    .init({
      lng: storedLang(),
      fallbackLng: 'hu',
      resources: {
        hu: { translation: hu },
        en: { translation: en },
      },
      interpolation: { escapeValue: false },
      returnNull: false,
    });
}

export function setLang(lang: Lang) {
  localStorage.setItem('lang', lang);
  i18next.changeLanguage(lang);
  document.documentElement.lang = lang;
}

export function getLang(): Lang {
  return (i18next.language === 'en' ? 'en' : 'hu');
}

export default i18next;
