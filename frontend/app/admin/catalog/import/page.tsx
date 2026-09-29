import type { Metadata } from "next";
import Link from "next/link";
import CatalogImportForm from "@/components/admin/CatalogImportForm";
import { adminImportHistory } from "@/lib/api";
import { requireAdmin } from "@/lib/auth";
import { IMPORT_STATUS_LABELS } from "@/types/admin";
import type { AdminImportDto } from "@/types/admin";

/**
 * Импорт каталога: загрузка таблицы каталога, обновление
 * по запросу и журнал загрузок с итогами.
 */
export const metadata: Metadata = {
  title: "Импорт каталога — Управление",
};

export default async function ImportPage() {
  await requireAdmin();

  let history: AdminImportDto[] = [];
  let loadError: string | null = null;
  try {
    history = await adminImportHistory(20);
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link href="/admin" className="text-sm text-slate-500 hover:underline dark:text-slate-400">
          ← К разделу «Управление»
        </Link>
        <h1 className="mt-2 text-2xl font-bold">Импорт каталога</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Таблица каталога в формате выгрузки: 15 колонок,
          разделитель «;». Правила обработки — как при первичной загрузке:
          дедупликация по id, объединение дублей, автоправки опечаток.
        </p>
      </div>

      {loadError !== null && (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <p className="font-medium">Не удалось загрузить историю</p>
          <p className="mt-1">{loadError}</p>
        </div>
      )}

      <CatalogImportForm />

      <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
        <h2 className="font-semibold text-slate-900 dark:text-slate-100">Журнал загрузок</h2>
        {history.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">
            Загрузок пока не было.
          </p>
        ) : (
          <table className="mt-3 w-full text-left text-sm" data-testid="import-history">
            <thead>
              <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
                <th className="py-2 pr-4 font-medium">Файл</th>
                <th className="py-2 pr-4 font-medium">Размер</th>
                <th className="py-2 pr-4 font-medium">Начало</th>
                <th className="py-2 pr-4 font-medium">Завершение</th>
                <th className="py-2 font-medium">Статус</th>
              </tr>
            </thead>
            <tbody>
              {history.map((row) => (
                <tr key={row.id} className="border-b border-slate-100 dark:border-slate-800">
                  <td className="py-2 pr-4">{row.fileName}</td>
                  <td className="py-2 pr-4 text-slate-500 dark:text-slate-400">
                    {(row.sizeBytes / 1024).toFixed(0)} КБ
                  </td>
                  <td className="py-2 pr-4 text-slate-500 dark:text-slate-400">
                    {new Date(row.startedAt).toLocaleString("ru-RU")}
                  </td>
                  <td className="py-2 pr-4 text-slate-500 dark:text-slate-400">
                    {row.finishedAt
                      ? new Date(row.finishedAt).toLocaleString("ru-RU")
                      : "—"}
                  </td>
                  <td className="py-2">
                    <span
                      className={
                        row.status === "completed"
                          ? "rounded-full bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-400"
                          : row.status === "failed"
                            ? "rounded-full bg-rose-50 px-2 py-0.5 text-xs text-rose-700 dark:bg-rose-950/40 dark:text-rose-400"
                            : "rounded-full bg-amber-50 px-2 py-0.5 text-xs text-amber-700 dark:bg-amber-950/40 dark:text-amber-400"
                      }
                    >
                      {IMPORT_STATUS_LABELS[row.status]}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
