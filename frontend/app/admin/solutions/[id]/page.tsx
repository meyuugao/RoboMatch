import type { Metadata } from "next";
import Link from "next/link";
import AdminCharacteristicsEditor from "@/components/admin/AdminCharacteristicsEditor";
import AdminSolutionForm from "@/components/admin/AdminSolutionForm";
import {
  adminGetSolution,
  adminListReferences,
  fetchFilters,
} from "@/lib/api";
import { requireAdmin } from "@/lib/auth";
import type { FiltersData } from "@/types/solution";

/**
 * Карточка решения в разделе «Управление»: правка основных полей,
 * управление ТТХ с провенансом, удаление (409, пока
 * решение используется в проектах).
 */
export const metadata: Metadata = {
  title: "Решение — Управление",
};

export default async function EditSolutionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireAdmin();
  const { id } = await params;
  const numericId = Number(id);
  const validId = /^\d+$/.test(id) ? numericId : NaN;

  let loadError: string | null = null;
  if (Number.isNaN(validId)) {
    loadError = "Некорректный идентификатор решения";
  }

  const [solution, filters, vendorsPage, regionsPage, characteristicTypes] =
    await Promise.all([
      Number.isNaN(validId)
        ? Promise.resolve(null)
        : adminGetSolution(validId).catch(() => null),
      fetchFilters().catch(() => null),
      adminListReferences("vendor", undefined, 0, 200).catch(() => null),
      adminListReferences("region", undefined, 0, 200).catch(() => null),
      adminListReferences("characteristic_type", undefined, 0, 200).catch(
        () => null,
      ),
    ]);

  if (solution === null || filters === null) {
    return (
      <div className="flex flex-col gap-4">
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 p-6 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <p className="font-medium">Решение не найдено</p>
          <p className="mt-1">
            {loadError ?? "Возможно, оно было удалено или недоступно."}
          </p>
        </div>
        <Link href="/admin/solutions" className="text-sm text-slate-600 hover:underline dark:text-slate-300">
          ← К списку решений
        </Link>
      </div>
    );
  }

  const filtersData = filters as FiltersData;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link href="/admin/solutions" className="text-sm text-slate-500 hover:underline dark:text-slate-400">
          ← К списку решений
        </Link>
        <h1 className="mt-2 text-2xl font-bold">{solution.name}</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Источник данных: {sourceLabel(solution.sourceKind)}
          {solution.sourceUrl ? ` (${solution.sourceUrl})` : ""} · провенанс
          правок через «Управление» — «вручную».
        </p>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
        <AdminSolutionForm
          vendors={(vendorsPage?.content ?? []).map((v) => ({
            id: v.id,
            code: v.code ?? "",
            name: v.name,
          }))}
          regions={(regionsPage?.content ?? []).map((r) => ({
            id: r.id,
            code: r.code ?? "",
            name: r.name,
          }))}
          types={filtersData.types}
          subtypes={filtersData.subtypes}
          typeSubtypeMapping={filtersData.typeSubtypeMapping}
          solution={solution}
        />
      </div>

      {characteristicTypes !== null && (
        <AdminCharacteristicsEditor
          solutionId={solution.id}
          characteristics={solution.characteristics ?? []}
          types={characteristicTypes.content.map((type) => ({
            id: type.id,
            code: type.code ?? "",
            name: type.name,
            dataType: type.dataType,
            unit: type.unit,
          }))}
        />
      )}
    </div>
  );
}

function sourceLabel(sourceKind: string): string {
  switch (sourceKind) {
    case "organizer_catalog":
      return "таблица каталога";
    case "open_source":
      return "открытый источник";
    default:
      return "внесено вручную";
  }
}
