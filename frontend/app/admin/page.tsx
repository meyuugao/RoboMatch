import type { Metadata } from "next";
import Link from "next/link";
import { adminImportHistory, adminReferenceCounts } from "@/lib/api";
import { requireAdmin } from "@/lib/auth";
import { IMPORT_STATUS_LABELS } from "@/types/admin";
import type { AdminImportDto } from "@/types/admin";

/**
 * Дашборд администратора: управление каталогом
 * решений, справочниками и загрузкой таблицы каталога.
 *
 * Доступ — только роль admin (requireAdmin: гость -> /login, пользователь
 * -> /projects); те же правила повторяет backend на /api/admin/**.
 */
export const metadata: Metadata = {
  title: "Управление",
};

export default async function AdminDashboardPage() {
  await requireAdmin();

  let counts: Record<string, number> = {};
  let history: AdminImportDto[] = [];
  let loadError: string | null = null;
  try {
    [counts, history] = await Promise.all([
      adminReferenceCounts(),
      adminImportHistory(5),
    ]);
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  const totalReferences = Object.values(counts).reduce(
    (sum, n) => sum + n,
    0,
  );

  const tiles = [
    {
      href: "/admin/solutions",
      title: "Решения",
      description:
        "Каталог решений: создание, правка, удаление, проверка карточек",
    },
    {
      href: "/admin/references",
      title: "Справочники",
      description: `Отрасли, процессы, производители, регионы, типы — записей: ${
        totalReferences > 0 ? totalReferences : "—"
      }`,
    },
    {
      href: "/admin/catalog/import",
      title: "Импорт каталога",
      description:
        "Загрузка таблицы каталога (CSV/XLSX) и обновление по запросу",
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">Управление</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Управление каталогом решений и справочниками платформы
        </p>
      </div>

      {loadError !== null && (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <p className="font-medium">Не удалось загрузить данные</p>
          <p className="mt-1">{loadError}</p>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-3">
        {tiles.map((tile) => (
          <Link
            key={tile.href}
            href={tile.href}
            className="group flex flex-col gap-2 rounded-xl border border-slate-200 bg-white p-5 transition hover:border-emerald-400 hover:shadow-sm dark:border-slate-800 dark:bg-slate-900"
          >
            <span className="text-lg font-semibold text-slate-900 group-hover:text-emerald-700 dark:text-slate-100 dark:group-hover:text-emerald-400">
              {tile.title}
            </span>
            <span className="text-sm text-slate-500 dark:text-slate-400">{tile.description}</span>
          </Link>
        ))}
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-semibold text-slate-900 dark:text-slate-100">
            Последние загрузки каталога
          </h2>
          <Link
            href="/admin/catalog/import"
            className="text-sm text-emerald-700 hover:underline dark:text-emerald-400"
          >
            Весь журнал →
          </Link>
        </div>
        {history.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500 dark:text-slate-400">
            Таблица каталога ещё не загружалась.
          </p>
        ) : (
          <table className="mt-3 w-full text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
                <th className="py-2 pr-4 font-medium">Файл</th>
                <th className="py-2 pr-4 font-medium">Начало</th>
                <th className="py-2 pr-4 font-medium">Статус</th>
                <th className="py-2 font-medium">Итог</th>
              </tr>
            </thead>
            <tbody>
              {history.map((row) => (
                <tr key={row.id} className="border-b border-slate-100 dark:border-slate-800">
                  <td className="py-2 pr-4">{row.fileName}</td>
                  <td className="py-2 pr-4 text-slate-500 dark:text-slate-400">
                    {new Date(row.startedAt).toLocaleString("ru-RU")}
                  </td>
                  <td className="py-2 pr-4">
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
                  <td className="py-2 text-slate-500 dark:text-slate-400">{summaryLine(row)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

/** Короткая строка итога импорта для таблицы. */
function summaryLine(row: AdminImportDto): string {
  const summary = row.summary as
    | { solutions?: number; entities?: Record<string, { added: number }> }
    | null;
  if (!summary) {
    return row.status === "running" ? "Выполняется…" : "—";
  }
  const added = summary.entities?.solutions?.added ?? 0;
  const count = summary.solutions ?? 0;
  const noun = count % 10 === 1 && count % 100 !== 11
    ? "решение"
    : [2, 3, 4].includes(count % 10) && ![12, 13, 14].includes(count % 100)
      ? "решения"
      : "решений";
  return `${count} ${noun}, добавлено ${added}`;
}
