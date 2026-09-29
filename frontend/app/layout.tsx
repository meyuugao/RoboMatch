import type { Metadata } from "next";
import "./globals.css";
import Header from "@/components/Header";
import Footer from "@/components/Footer";
import { ThemeProvider, THEME_INIT_SCRIPT } from "@/components/ThemeProvider";

/**
 * Корневой layout: обёртка ВСЕХ страниц (в App Router layout обязательный
 * и «живёт» вокруг каждой страницы без перезагрузки при переходах).
 *
 * Здесь подключаются глобальные стили, шапка и подвал.
 * min-h-screen + flex flex-col: шапка сверху, контент растягивается,
 * подвал прилипает к низу (footer mt-auto) даже на коротких страницах.
 *
 * Тёмная тема: inline-скрипт в <head> ставит класс .dark на <html> ДО
 * первой отрисовки (localStorage «theme» + системная настройка) — без
 * мигания; suppressHydrationWarning — класс может появиться до гидратации.
 */
export const metadata: Metadata = {
  title: {
    default: "RoboMatch — подбор роботизированных решений",
    template: "%s · RoboMatch",
  },
  description:
    "Платформа предынвестиционной оценки роботизации: каталог решений, " +
    "подбор по параметрам объекта, расчёт экономического эффекта.",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ru" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
      <body className="flex min-h-screen flex-col bg-slate-50 text-slate-900 antialiased dark:bg-slate-950 dark:text-slate-100">
        <ThemeProvider>
          <Header />
          <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
            {children}
          </main>
          <Footer />
        </ThemeProvider>
      </body>
    </html>
  );
}
