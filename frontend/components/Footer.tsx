/**
 * Подвал сайта: копирайт.
 * mt-auto + flex-колонка в layout - прилипает к низу на коротких страницах.
 * Нейтральная продуктовая строка: без статусов разработки и упоминания
 * контекста создания.
 */
export default function Footer() {
  return (
    <footer className="mt-auto border-t border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      <div className="mx-auto max-w-6xl px-4 py-4 text-sm text-slate-500 dark:text-slate-400">
        <p>RoboMatch - платформа подбора роботизированных решений · 2026</p>
      </div>
    </footer>
  );
}
