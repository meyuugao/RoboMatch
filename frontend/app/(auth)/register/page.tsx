import type { Metadata } from "next";
import RegisterForm from "@/components/RegisterForm";

/**
 * Страница регистрации /register (группа маршрутов (auth)).
 * Регистрация создаёт роль «пользователь» и сразу открывает сессию
 * (JWT в httpOnly-cookie через BFF). Роль администратора выдаёт
 * только seed - повысить себя через форму нельзя.
 */
export const metadata: Metadata = {
  title: "Регистрация",
};

export default function RegisterPage() {
  return (
    <div className="mx-auto max-w-md">
      <div>
        <h1 className="text-2xl font-bold">Регистрация</h1>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
          Аккаунт нужен, чтобы сохранять проекты, запускать подбор решений
          и расчёт экономического эффекта.
        </p>
      </div>

      <div className="mt-6 rounded-xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
        <RegisterForm />
      </div>
    </div>
  );
}
