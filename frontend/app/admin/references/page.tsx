import type { Metadata } from "next";
import Link from "next/link";
import { adminReferenceCounts } from "@/lib/api";
import { requireAdmin } from "@/lib/auth";
import { DICT_ORDER, DICT_TITLES } from "@/types/admin";

/**
 * Справочники раздела «Управление»: плитки с количеством записей.
 * CRUD конкретного справочника - на странице /admin/references/{dictCode}.
 */
export const metadata: Metadata = {
  title: "Справочники - Управление",
};

export default async function AdminReferencesPage() {
  await requireAdmin();

  let counts: Record<string, number> = {};
  let loadError: string | null = null;
  try {
    counts = await adminReferenceCounts();
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-2xl font-bold">Справочники</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Отрасли, процессы, производители, регионы, типы решений и
          характеристик платформы
        </p>
      </div>

      {loadError !== null && (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <p className="font-medium">Не удалось загрузить справочники</p>
          <p className="mt-1">{loadError}</p>
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        {DICT_ORDER.map((dictCode) => (
          <Link
            key={dictCode}
            href={`/admin/references/${dictCode}`}
            className="group flex flex-col gap-2 rounded-xl border border-slate-200 bg-white p-5 transition hover:border-emerald-400 hover:shadow-sm dark:border-slate-800 dark:bg-slate-900"
          >
            <span className="text-lg font-semibold text-slate-900 group-hover:text-emerald-700 dark:text-slate-100 dark:group-hover:text-emerald-400">
              {DICT_TITLES[dictCode]}
            </span>
            <span className="text-sm text-slate-500 dark:text-slate-400">
              Записей: {counts[dictCode] ?? "-"}
            </span>
          </Link>
        ))}
      </div>
    </div>
  );
}
