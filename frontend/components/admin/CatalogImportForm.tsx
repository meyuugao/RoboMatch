"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import type { CatalogImportSummaryDto } from "@/types/admin";

/**
 * Загрузка таблицы каталога и обновление по
 * запросу. Клиентский компонент: файл уходит через BFF
 * (multipart), результат - сводка «добавлено/обновлено/пропущено».
 */
export default function CatalogImportForm() {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [summary, setSummary] = useState<CatalogImportSummaryDto | null>(null);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (file === null) {
      setError("Выберите файл CSV или XLSX");
      return;
    }
    setBusy(true);
    setError(null);
    setSummary(null);
    try {
      const formData = new FormData();
      formData.append("file", file);
      const response = await fetch("/api/admin/catalog/import", {
        method: "POST",
        body: formData,
      });
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      setSummary((await response.json()) as CatalogImportSummaryDto);
      setFile(null);
      if (inputRef.current) {
        inputRef.current.value = "";
      }
      router.refresh();
    } catch (importError) {
      setError(
        importError instanceof Error ? importError.message : "Ошибка импорта",
      );
    } finally {
      setBusy(false);
    }
  }

  async function onRefresh() {
    setBusy(true);
    setError(null);
    setSummary(null);
    try {
      const response = await fetch("/api/admin/catalog/refresh", {
        method: "POST",
      });
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      setSummary((await response.json()) as CatalogImportSummaryDto);
      router.refresh();
    } catch (refreshError) {
      setError(
        refreshError instanceof Error ? refreshError.message : "Ошибка обновления",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="flex flex-col gap-4">
      <form
        onSubmit={onSubmit}
        className="flex flex-col gap-4 rounded-xl border border-dashed border-slate-300 bg-slate-50 p-6 dark:border-slate-700 dark:bg-slate-900"
        data-testid="import-form"
      >
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">
            Таблица каталога (CSV или XLSX, до 50 МБ)
          </span>
          <input
            ref={inputRef}
            type="file"
            accept=".csv,.xlsx,.xls"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
            data-testid="import-file"
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          />
        </label>
        <div className="flex flex-wrap gap-3">
          <button
            type="submit"
            disabled={busy}
            data-testid="import-submit"
            className="rounded-lg bg-emerald-600 px-5 py-2 text-sm font-medium text-white transition hover:bg-emerald-700 disabled:opacity-50"
          >
            {busy ? "Импорт выполняется…" : "Загрузить и импортировать"}
          </button>
          <button
            type="button"
            onClick={onRefresh}
            disabled={busy}
            data-testid="import-refresh"
            className="rounded-lg border border-slate-300 bg-white px-5 py-2 text-sm text-slate-700 transition hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
          >
            Обновить по последнему файлу
          </button>
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Повторная загрузка того же файла ничего не меняет (пропущено) -
          можно сверять актуальность без риска задвоить каталог.
        </p>
      </form>

      {error !== null && (
        <div
          role="alert"
          data-testid="import-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      {summary !== null && (
        <div
          className="rounded-xl border border-emerald-200 bg-emerald-50 p-5 dark:border-emerald-800 dark:bg-emerald-950/40"
          data-testid="import-summary"
        >
          <p className="font-medium text-emerald-900 dark:text-emerald-300">
            Импорт завершён: {summary.solutions} решений в файле (строк:{" "}
            {summary.csvRows}, групп дублей: {summary.dupGroups})
          </p>
          <table className="mt-3 w-full max-w-xl text-left text-sm">
            <thead>
              <tr className="border-b border-emerald-200 text-xs uppercase tracking-wide text-emerald-800 dark:border-emerald-800 dark:text-emerald-300">
                <th className="py-1 pr-4 font-medium">Сущность</th>
                <th className="py-1 pr-4 font-medium">Добавлено</th>
                <th className="py-1 pr-4 font-medium">Обновлено</th>
                <th className="py-1 font-medium">Пропущено</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(summary.entities).map(([entity, counters]) => (
                <tr key={entity} className="border-b border-emerald-100 dark:border-emerald-800">
                  <td className="py-1 pr-4">{ENTITY_LABELS[entity] ?? entity}</td>
                  <td className="py-1 pr-4">{counters.added}</td>
                  <td className="py-1 pr-4">{counters.updated}</td>
                  <td className="py-1">{counters.skipped}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {summary.warnings && summary.warnings.length > 0 && (
            <details className="mt-3 text-sm text-emerald-900 dark:text-emerald-300">
              <summary className="cursor-pointer">
                Предупреждения ({summary.warnings.length})
              </summary>
              <ul className="mt-2 list-disc space-y-1 pl-5 text-xs">
                {summary.warnings.slice(0, 20).map((warning, index) => (
                  <li key={index}>{warning}</li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
    </section>
  );
}

const ENTITY_LABELS: Record<string, string> = {
  solutions: "Решения",
  applications: "Применения",
  cases: "Кейсы",
  case_links: "Связи кейсов",
  vendors: "Производители",
  industries: "Отрасли",
  regions: "Регионы",
  solution_types: "Типы решений",
  solution_subtypes: "Подтипы решений",
  processes: "Процессы",
};

async function messageOf(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as {
      message?: string;
      errors?: { message: string }[];
    };
    if (body.errors && body.errors.length > 0) {
      return body.message + ": " + body.errors
        .slice(0, 5)
        .map((item) => item.message)
        .join("; ");
    }
    return body.message ?? `Ошибка HTTP ${response.status}`;
  } catch {
    return `Ошибка HTTP ${response.status}`;
  }
}
