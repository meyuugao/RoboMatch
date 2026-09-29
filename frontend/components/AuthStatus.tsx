import Link from "next/link";
import { getCurrentUser } from "@/lib/auth";
import { USER_ROLE_LABELS } from "@/types/user";
import LogoutButton from "./LogoutButton";

/**
 * Статус аутентификации в шапке (серверный компонент).
 *
 * «Гость» + ссылка «Войти» - без сессии; логин, роль и «Выйти» -
 * с сессией. Профиль берётся через getCurrentUser (cookie -> BFF
 * /api/auth/me -> backend): токен остаётся в httpOnly-cookie.
 * Если backend недоступен - корректно показываем гостя.
 */
export default async function AuthStatus() {
  const user = await getCurrentUser();

  if (!user) {
    return (
      <div className="flex items-center gap-3">
        <span className="text-sm text-slate-500 dark:text-slate-400">Гость</span>
        <Link
          href="/login"
          className="rounded-lg bg-emerald-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-emerald-700"
        >
          Войти
        </Link>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-3">
      <span className="text-sm font-medium text-slate-900 dark:text-slate-100">{user.login}</span>
      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600 dark:bg-slate-800 dark:text-slate-300">
        {USER_ROLE_LABELS[user.role] ?? user.role}
      </span>
      <LogoutButton />
    </div>
  );
}
