"use client";

import { useTheme, type ThemeChoice } from "@/components/ThemeProvider";

/**
 * Переключатель темы в шапке: светлая / тёмная / системная.
 * Выбор сохраняется в localStorage и применяется мгновенно
 * (класс .dark на <html> — SVG-схема и все dark:-стили меняются
 * на лету, без перезапуска имитации). Иконки — встроенные SVG,
 * без внешних зависимостей.
 */

const OPTIONS: Array<{
  value: ThemeChoice;
  label: string;
  title: string;
  icon: React.ReactNode;
}> = [
  {
    value: "light",
    label: "Светлая",
    title: "Светлая тема",
    icon: (
      <svg viewBox="0 0 24 24" className="h-4 w-4" aria-hidden="true">
        <circle cx="12" cy="12" r="4.5" fill="currentColor" />
        <g stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M12 2.5v2.5" />
          <path d="M12 19v2.5" />
          <path d="M2.5 12h2.5" />
          <path d="M19 12h2.5" />
          <path d="M5.3 5.3l1.8 1.8" />
          <path d="M16.9 16.9l1.8 1.8" />
          <path d="M18.7 5.3l-1.8 1.8" />
          <path d="M7.1 16.9l-1.8 1.8" />
        </g>
      </svg>
    ),
  },
  {
    value: "dark",
    label: "Тёмная",
    title: "Тёмная тема",
    icon: (
      <svg viewBox="0 0 24 24" className="h-4 w-4" aria-hidden="true">
        <path
          d="M20.5 14.2A8.5 8.5 0 0 1 9.8 3.5a8.5 8.5 0 1 0 10.7 10.7z"
          fill="currentColor"
        />
      </svg>
    ),
  },
  {
    value: "system",
    label: "Системная",
    title: "Как в системе",
    icon: (
      <svg viewBox="0 0 24 24" className="h-4 w-4" aria-hidden="true">
        <rect
          x="3"
          y="4.5"
          width="18"
          height="13"
          rx="2"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
        />
        <path d="M9 20.5h6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
    ),
  },
];

export default function ThemeToggle() {
  const { theme, setTheme } = useTheme();

  return (
    <div
      role="group"
      aria-label="Тема оформления"
      data-testid="theme-toggle"
      className="flex items-center gap-0.5 rounded-lg border border-slate-200 bg-slate-50 p-0.5 dark:border-slate-700 dark:bg-slate-800"
    >
      {OPTIONS.map((option) => {
        const active = theme === option.value;
        return (
          <button
            key={option.value}
            type="button"
            onClick={() => setTheme(option.value)}
            aria-pressed={active}
            title={option.title}
            data-theme-option={option.value}
            className={`flex h-7 w-7 items-center justify-center rounded-md transition ${
              active
                ? "bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900"
                : "text-slate-500 hover:bg-slate-200 hover:text-slate-800 dark:text-slate-400 dark:hover:bg-slate-700 dark:hover:text-slate-200"
            }`}
          >
            <span className="sr-only">{option.label}</span>
            {option.icon}
          </button>
        );
      })}
    </div>
  );
}
