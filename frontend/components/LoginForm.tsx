"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";

/**
 * Форма входа (клиентский компонент — нужен state полей и fetch).
 *
 * Общается только с СОИМ BFF-роутом /api/auth/login (не с backend):
 * токен приходит в httpOnly-cookie, код страницы его не видит.
 * Ошибки — на русском, понятным языком: либо сообщение
 * backend (неверный логин/пароль), либо состояние сети.
 */
export default function LoginForm() {
  const router = useRouter();
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);

    // те же минимумы, что в backend (@Valid): быстрая обратная связь
    if (login.trim().length < 3) {
      setError("Логин: от 3 символов");
      return;
    }
    if (password.length < 8) {
      setError("Пароль: от 8 символов");
      return;
    }

    setPending(true);
    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ login: login.trim(), password }),
      });
      if (response.ok) {
        router.push("/projects");
        router.refresh(); // обновить AuthStatus в шапке
        return;
      }
      const data = await response.json().catch(() => null);
      setError(data?.error ?? `Ошибка входа (HTTP ${response.status})`);
    } catch {
      setError("Сервер недоступен. Попробуйте позже.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Логин</span>
        <input
          type="text"
          value={login}
          onChange={(event) => setLogin(event.target.value)}
          autoComplete="username"
          required
          minLength={3}
          maxLength={64}
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          placeholder="например, user"
        />
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Пароль</span>
        <input
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          required
          minLength={8}
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          placeholder="от 8 символов"
        />
      </label>

      {error && (
        <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </p>
      )}

      <button
        type="submit"
        disabled={pending}
        className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-60"
      >
        {pending ? "Проверяем…" : "Войти"}
      </button>

      <p className="text-center text-sm text-slate-600 dark:text-slate-300">
        Нет аккаунта?{" "}
        <Link href="/register" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
          Зарегистрируйтесь
        </Link>
      </p>
    </form>
  );
}
