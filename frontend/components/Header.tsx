import Link from "next/link";
import AuthStatus from "@/components/AuthStatus";
import ThemeToggle from "@/components/ThemeToggle";
import { getCurrentUser } from "@/lib/auth";

/**
 * Шапка сайта: логотип + навигация + статус аутентификации.
 * Компонент серверный (нет 'use client') - статичный HTML, без JS на клиенте.
 * Link из next/link - клиентская навигация без полной перезагрузки страницы.
 * AuthStatus (гость/логин/выйти) тоже серверный: берёт профиль из cookie-сессии.
 *
 * «Управление» - только для роли admin: ссылка видна администратору,
 * сами страницы и API дополнительно закрыты проверкой роли.
 */
export default async function Header() {
  const user = await getCurrentUser();

  return (
    <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      {/* flex-wrap: на мобильных навигация + статус переносятся на вторую
 строку вместо горизонтального скролла */}
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-x-6 gap-y-2 px-4 py-4">
        <Link href="/" className="flex items-center gap-2">
          {/* стилизованный «робот»-логотип без внешних картинок */}
          <span
            aria-hidden="true"
            className="flex h-8 w-8 items-center justify-center rounded-lg bg-slate-900 text-lg font-bold text-emerald-400 dark:bg-slate-100"
          >
            R
          </span>
          <span className="text-lg font-semibold text-slate-900 dark:text-slate-100">
            RoboMatch
          </span>
        </Link>

        {/* flex-wrap: навигация + статус переносятся по узким экранам -
 иначе блок шире вьюпорта и появляется горизонтальный скролл
 (особенно со ссылкой «Управление») */}
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
          <nav aria-label="Основная навигация" className="flex gap-6">
            <Link
              href="/"
              className="text-sm font-medium text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-slate-100"
            >
              Главная
            </Link>
            <Link
              href="/catalog"
              className="text-sm font-medium text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-slate-100"
            >
              Каталог
            </Link>
            {!user && (
              <Link
                href="/demo"
                data-testid="nav-demo"
                className="text-sm font-medium text-emerald-700 hover:text-emerald-800 dark:text-emerald-400 dark:hover:text-emerald-300"
              >
                Демо-расчёт
              </Link>
            )}
            <Link
              href="/projects"
              className="text-sm font-medium text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-slate-100"
            >
              Проекты
            </Link>
            {user?.role === "admin" && (
              <Link
                href="/admin"
                data-testid="nav-admin"
                className="text-sm font-medium text-emerald-700 hover:text-emerald-800 dark:text-emerald-400 dark:hover:text-emerald-300"
              >
                Управление
              </Link>
            )}
          </nav>
          <ThemeToggle />
          <AuthStatus />
        </div>
      </div>
    </header>
  );
}
