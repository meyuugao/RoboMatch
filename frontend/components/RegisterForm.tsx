"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";

/**
 * Форма регистрации (клиентский компонент).
 *
 * POST /api/auth/register (BFF): при успехе сессия открывается сразу -
 * JWT в httpOnly-cookie, редирект в «Мои проекты». Минимумы полей -
 * те же, что в backend: логин 3–64, пароль 8–72.
 */
export default function RegisterForm() {
  const router = useRouter();
  const [login, setLogin] = useState("");
  const [password, setPassword] = useState("");
  const [passwordRepeat, setPasswordRepeat] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);

    if (login.trim().length < 3) {
      setError("Логин: от 3 символов");
      return;
    }
    if (password.length < 8) {
      setError("Пароль: от 8 символов");
      return;
    }
    if (password.length > 72) {
      setError("Пароль: не длиннее 72 символов");
      return;
    }
    if (password !== passwordRepeat) {
      setError("Пароли не совпадают");
      return;
    }

    setPending(true);
    try {
      const response = await fetch("/api/auth/register", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ login: login.trim(), password }),
      });
      if (response.ok) {
        router.push("/projects");
        router.refresh();
        return;
      }
      const data = await response.json().catch(() => null);
      setError(data?.error ?? `Ошибка регистрации (HTTP ${response.status})`);
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
          pattern="[A-Za-z0-9_.\-]+"
          title="Латинские буквы, цифры и символы . _ -"
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          placeholder="от 3 символов: латиница, цифры, . _ -"
        />
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Пароль</span>
        <input
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="new-password"
          required
          minLength={8}
          maxLength={72}
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          placeholder="от 8 до 72 символов"
        />
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Повторите пароль</span>
        <input
          type="password"
          value={passwordRepeat}
          onChange={(event) => setPasswordRepeat(event.target.value)}
          autoComplete="new-password"
          required
          minLength={8}
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          placeholder="ещё раз"
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
        {pending ? "Создаём аккаунт…" : "Зарегистрироваться"}
      </button>

      <p className="text-center text-sm text-slate-600 dark:text-slate-300">
        Уже есть аккаунт?{" "}
        <Link href="/login" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
          Войдите
        </Link>
      </p>
    </form>
  );
}
