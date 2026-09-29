import type { Metadata } from "next";
import LoginForm from "@/components/LoginForm";

/**
 * Страница входа /login (группа маршрутов (auth) - без префикса в URL).
 * Серверный компонент-обёртка: метаданные + вёрстка; интерактив - в
 * клиентском LoginForm. Гость уже видит каталог - вход нужен для
 * проектов (requirements/roles.md).
 */
export const metadata: Metadata = {
  title: "Вход",
};

export default function LoginPage() {
  return (
    <div className="mx-auto max-w-md">
      <div>
        <h1 className="text-2xl font-bold">Вход</h1>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
          Проекты и расчёты - для зарегистрированных пользователей. Гость
          может свободно листать каталог и выполнять демо-расчёт.
        </p>
      </div>

      <div className="mt-6 rounded-xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
        <LoginForm />
      </div>

      <p className="mt-4 rounded-lg bg-slate-100 px-4 py-3 text-sm text-slate-600 dark:bg-slate-800 dark:text-slate-300">
        Демо-аккаунты: <code className="rounded bg-white px-1 dark:bg-slate-900">user</code>{" "}
        и <code className="rounded bg-white px-1 dark:bg-slate-900">admin</code> - пароли
        указаны в README (создаются seed-ом при первом запуске).
      </p>
    </div>
  );
}
