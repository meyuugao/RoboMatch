"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

/**
 * Тема оформления: контекст + управление классом .dark на <html>.
 *
 * Хранение выбора пользователя — localStorage («theme» =
 * «light» | «dark» | «system», по умолчанию «system»). Класс .dark
 * выставляет ещё ДО гидратации inline-скрипт в <head> (см.
 * app/layout.tsx) — первый кадр уже в правильной теме, без мигания
 * (FOUC). Этот провайдер синхронизирует состояние с DOM после
 * монтирования и реагирует на смену системной темы в режиме «system».
 *
 * resolvedTheme — фактическая тема («light» | «dark»): нужна
 * компонентам с не-CSS цветами (SVG-схема имитации, выбор темы
 * PDF-отчёта по умолчанию).
 */

export type ThemeChoice = "light" | "dark" | "system";
export type ResolvedTheme = "light" | "dark";

type ThemeContextValue = {
  /** Выбор пользователя (light/dark/system). */
  theme: ThemeChoice;
  /** Фактическая тема после разрешения «system» через настройки ОС. */
  resolvedTheme: ResolvedTheme;
  /** Сменить тему (сохраняется в localStorage, применяется мгновенно). */
  setTheme: (theme: ThemeChoice) => void;
};

const STORAGE_KEY = "theme";
const ThemeContext = createContext<ThemeContextValue | null>(null);

/** Инлайн-скрипт установки темы до первой отрисовки (без FOUC). */
export const THEME_INIT_SCRIPT = `(function(){try{var t=localStorage.getItem("${STORAGE_KEY}");var d=t==="dark"||(t!=="light"&&window.matchMedia("(prefers-color-scheme: dark)").matches);var c=document.documentElement.classList;d?c.add("dark"):c.remove("dark");}catch(e){}})();`;

function systemPrefersDark(): boolean {
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

function resolve(choice: ThemeChoice): ResolvedTheme {
  if (choice === "system") {
    return systemPrefersDark() ? "dark" : "light";
  }
  return choice;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeChoice>("system");
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>("light");

  // После монтирования: читаем сохранённый выбор и синхронизируем
  // состояние с классом, который уже выставил inline-скрипт.
  useEffect(() => {
    let stored: ThemeChoice = "system";
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw === "light" || raw === "dark" || raw === "system") {
        stored = raw;
      }
    } catch {
      // localStorage недоступен — остаётся «system»
    }
    setThemeState(stored);
    setResolvedTheme(resolve(stored));
  }, []);

  // Реакция на смену системной темы, пока выбрано «system».
  useEffect(() => {
    if (theme !== "system") {
      return;
    }
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => setResolvedTheme(resolve("system"));
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, [theme]);

  const setTheme = useCallback((next: ThemeChoice) => {
    setThemeState(next);
    setResolvedTheme(resolve(next));
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // localStorage недоступен — тема живёт до перезагрузки
    }
    document.documentElement.classList.toggle("dark", resolve(next) === "dark");
  }, []);

  return (
    <ThemeContext.Provider value={{ theme, resolvedTheme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

/** Доступ к теме; вне провайдера — безопасные значения по умолчанию. */
export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (context !== null) {
    return context;
  }
  return {
    theme: "system",
    resolvedTheme: "light",
    setTheme: () => undefined,
  };
}
