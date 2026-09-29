"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

/**
 * Кнопка выхода (клиентский компонент): POST /api/auth/logout (BFF)
 * удаляет httpOnly-cookie с JWT, затем обновляем страницу - шапка
 * снова показывает гостя.
 */
export default function LogoutButton() {
  const router = useRouter();
  const [pending, setPending] = useState(false);

  async function handleLogout() {
    setPending(true);
    try {
      await fetch("/api/auth/logout", { method: "POST" });
      router.push("/");
      router.refresh();
    } finally {
      setPending(false);
    }
  }

  return (
    <button
      type="button"
      onClick={handleLogout}
      disabled={pending}
      className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
    >
      {pending ? "Выходим…" : "Выйти"}
    </button>
  );
}
