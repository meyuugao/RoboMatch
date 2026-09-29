import type { Metadata } from "next";
import Link from "next/link";
import AdminSolutionForm from "@/components/admin/AdminSolutionForm";
import { adminListReferences, fetchFilters } from "@/lib/api";
import { requireAdmin } from "@/lib/auth";
import type { FiltersData } from "@/types/solution";

/**
 * Создание решения вручную: провенанс будет «вручную —
 * требует проверки» (source_kind=manual).
 */
export const metadata: Metadata = {
  title: "Новое решение — Управление",
};

export default async function NewSolutionPage() {
  await requireAdmin();

  const [filters, vendorsPage, regionsPage] = await Promise.all([
    fetchFilters(),
    adminListReferences("vendor", undefined, 0, 200),
    adminListReferences("region", undefined, 0, 200),
  ]);
  const filtersData = filters as FiltersData;

  return (
    <div className="flex flex-col gap-5">
      <div>
        <Link href="/admin/solutions" className="text-sm text-slate-500 hover:underline dark:text-slate-400">
          ← К списку решений
        </Link>
        <h1 className="mt-2 text-2xl font-bold">Новое решение</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Карточка будет помечена как внесённая вручную — до подтверждения
          источником она видна в фильтре «Требуют проверки».
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
        <AdminSolutionForm
          vendors={vendorsPage.content.map((v) => ({
            id: v.id,
            code: v.code ?? "",
            name: v.name,
          }))}
          regions={regionsPage.content.map((r) => ({
            id: r.id,
            code: r.code ?? "",
            name: r.name,
          }))}
          types={filtersData.types}
          subtypes={filtersData.subtypes}
          typeSubtypeMapping={filtersData.typeSubtypeMapping}
        />
      </div>
    </div>
  );
}
