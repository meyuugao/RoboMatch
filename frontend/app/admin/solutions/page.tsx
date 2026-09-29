import type { Metadata } from "next";
import Link from "next/link";
import AdminSolutionsTable from "@/components/admin/AdminSolutionsTable";
import { adminListSolutions } from "@/lib/api";
import { requireAdmin } from "@/lib/auth";
import type { PageResponse } from "@/types/solution";
import type { SolutionSummary } from "@/types/solution";

/**
 * Список решений в разделе «Управление»: поиск, фильтр «требуют проверки»
 * (внесены вручную — source_kind=manual, не подтверждены источником,
 *), пагинация. Серверный компонент; массовое выделение и
 * удаление — клиентской таблицей (AdminSolutionsTable).
 */
export const metadata: Metadata = {
  title: "Решения — Управление",
};

const PAGE_SIZE = 20;

export default async function AdminSolutionsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await requireAdmin();
  const raw = await searchParams;
  const q = typeof raw.q === "string" ? raw.q : "";
  const needsCheck = raw.needsCheck === "true";
  const page = Math.max(0, Number(raw.page ?? 0) || 0);

  let list: PageResponse<SolutionSummary> | null = null;
  let loadError: string | null = null;
  try {
    list = await adminListSolutions({ q, needsCheck, page, size: PAGE_SIZE });
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  function pageHref(target: number): string {
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (needsCheck) params.set("needsCheck", "true");
    params.set("page", String(target));
    return `/admin/solutions?${params}`;
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Решения каталога</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            {list
              ? `Всего: ${list.totalElements}${needsCheck ? " (только требующие проверки)" : ""}`
              : "\u00a0"}
          </p>
        </div>
        <Link
          href="/admin/solutions/new"
          className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-emerald-700"
        >
          Добавить решение
        </Link>
      </div>

      <form method="get" action="/admin/solutions" className="flex flex-wrap gap-2">
        <input
          type="search"
          name="q"
          defaultValue={q}
          placeholder="Поиск по названию…"
          className="w-64 rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
        />
        <label className="flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-700 dark:border-slate-700 dark:text-slate-300">
          <input
            type="checkbox"
            name="needsCheck"
            value="true"
            defaultChecked={needsCheck}
            className="h-4 w-4"
          />
          Требуют проверки
        </label>
        <button
          type="submit"
          className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Найти
        </button>
      </form>

      {loadError !== null && (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <p className="font-medium">Не удалось загрузить список</p>
          <p className="mt-1">{loadError}</p>
        </div>
      )}

      {list !== null && <AdminSolutionsTable items={list.content} />}

      {list !== null && list.totalPages > 1 && (
        <div className="flex items-center justify-between text-sm">
          {page > 0 ? (
            <Link href={pageHref(page - 1)} className="text-emerald-700 hover:underline dark:text-emerald-400">
              ← Назад
            </Link>
          ) : (
            <span className="text-slate-300 dark:text-slate-600">← Назад</span>
          )}
          <span className="text-slate-500 dark:text-slate-400">
            Страница {page + 1} из {list.totalPages}
          </span>
          {page + 1 < list.totalPages ? (
            <Link href={pageHref(page + 1)} className="text-emerald-700 hover:underline dark:text-emerald-400">
              Вперёд →
            </Link>
          ) : (
            <span className="text-slate-300 dark:text-slate-600">Вперёд →</span>
          )}
        </div>
      )}
    </div>
  );
}

