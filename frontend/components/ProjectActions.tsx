"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

/**
 * Действия над проектом: копировать / удалить. Клиентский
 * компонент - мутации через собственные BFF-роуты (cookie сессии
 * браузер прикладывает сам, same-origin). После мутации - router.refresh
 * для перезагрузки серверных данных.
 *
 * Удаление - с подтверждением (каскадное, вместе с параметрами,
 * сценариями и результатами -).
 */
export default function ProjectActions({
  projectId,
  projectName,
  redirectAfterDelete = null,
}: {
  projectId: number;
  projectName: string;
  /** Куда вернуться после удаления (например, /projects). */
  redirectAfterDelete?: string | null;
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onCopy() {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/projects/${projectId}/copy`, {
        method: "POST",
      });
      if (!response.ok) {
        throw new Error(await errorMessage(response));
      }
      const copy = (await response.json()) as { id: number };
      router.push(`/projects/${copy.id}`);
      router.refresh();
    } catch (copyError) {
      setError(copyError instanceof Error ? copyError.message : "Не удалось скопировать");
      setBusy(false);
    }
  }

  async function onDelete() {
    const confirmed = window.confirm(
      `Удалить проект «${projectName}»?\n\nВместе с ним удалятся параметры, ` +
        "сценарии, подборы, расчёты и выгрузки. Действие необратимо.",
    );
    if (!confirmed) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/projects/${projectId}`, {
        method: "DELETE",
      });
      if (!response.ok && response.status !== 204) {
        throw new Error(await errorMessage(response));
      }
      if (redirectAfterDelete) {
        router.push(redirectAfterDelete);
      }
      router.refresh();
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "Не удалось удалить");
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col items-start gap-2">
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onCopy}
          disabled={busy}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700
                     transition hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Копировать
        </button>
        <button
          type="button"
          onClick={onDelete}
          disabled={busy}
          className="rounded-lg border border-red-200 px-3 py-1.5 text-sm text-red-700
                     transition hover:bg-red-50 disabled:opacity-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950/40"
        >
          Удалить
        </button>
      </div>
      {error !== null && (
        <p role="alert" className="text-sm text-red-700 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    if (body?.message) {
      return body.message;
    }
  } catch {
    // не JSON - общий текст
  }
  return `Ошибка запроса (HTTP ${response.status})`;
}
