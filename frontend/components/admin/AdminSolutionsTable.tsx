"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import AdminBulkDeleteBar from "@/components/admin/AdminBulkDeleteBar";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import type { BulkDeleteResultDto } from "@/types/admin";
import {
  PRODUCT_CLASS_LABELS,
  SOLUTION_STATUS_LABELS,
  type SolutionSummary,
} from "@/types/solution";

/**
 * Таблица решений «Управления» с массовым выбором: чекбокс в шапке
 * выбирает всю страницу, чекбокс слева — строку. «Удалить выбранные»
 * открывает диалог с числом позиций, далее batch-эндпоинт
 * /api/admin/solutions/bulk-delete: удалённые исчезают из списка,
 * отказы (ссылки из сценариев) остаются с причиной.
 */
export default function AdminSolutionsTable({
  items,
}: {
  items: SolutionSummary[];
}) {
  const router = useRouter();

  const [selected, setSelected] = useState<number[]>([]);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<BulkDeleteResultDto | null>(null);

  const allChecked =
    items.length > 0 && items.every((item) => selected.includes(item.id));

  function toggleRow(id: number) {
    setSelected((current) =>
      current.includes(id)
        ? current.filter((value) => value !== id)
        : [...current, id],
    );
  }

  function toggleAll() {
    setSelected(allChecked ? [] : items.map((item) => item.id));
  }

  async function onDeleteConfirmed() {
    setBusy(true);
    setError(null);
    try {
      // мутация — прямой fetch через BFF (lib/api.ts тянет серверный
      // next/headers и в клиентский бандл не попадает)
      const response = await fetch("/api/admin/solutions/bulk-delete", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids: selected }),
      });
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      setResult((await response.json()) as BulkDeleteResultDto);
      setSelected([]);
      setConfirmOpen(false);
      // серверный компонент перечитает список и вернёт свежие строки
      router.refresh();
    } catch (deleteError) {
      setError(
        deleteError instanceof Error
          ? deleteError.message
          : "Ошибка удаления",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      {error !== null && (
        <div
          role="alert"
          data-testid="bulk-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      <AdminBulkDeleteBar
        selectedCount={selected.length}
        busy={busy}
        onRequestDelete={() => setConfirmOpen(true)}
        onClearSelection={() => setSelected([])}
        result={result}
        onDismissResult={() => setResult(null)}
        itemLabel="Решения"
      />

      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
              <th className="px-4 py-3 font-medium">
                <input
                  type="checkbox"
                  checked={allChecked}
                  onChange={toggleAll}
                  disabled={busy || items.length === 0}
                  aria-label="Выбрать все решения на странице"
                  data-testid="bulk-select-all"
                  className="h-4 w-4"
                />
              </th>
              <th className="px-4 py-3 font-medium">Название</th>
              <th className="px-4 py-3 font-medium">Производитель</th>
              <th className="px-4 py-3 font-medium">Класс</th>
              <th className="px-4 py-3 font-medium">Статус</th>
              <th className="px-4 py-3 font-medium">Цена, руб.</th>
              <th className="px-4 py-3 font-medium">Источник</th>
              <th className="px-4 py-3 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 && (
              <tr>
                <td
                  colSpan={8}
                  className="px-4 py-6 text-center text-slate-500 dark:text-slate-400"
                >
                  Ничего не найдено
                </td>
              </tr>
            )}
            {items.map((solution) => (
              <tr
                key={solution.id}
                className={
                  "border-b border-slate-100 dark:border-slate-800 " +
                  (selected.includes(solution.id)
                    ? "bg-rose-50/50 dark:bg-rose-950/20"
                    : "")
                }
              >
                <td className="px-4 py-3">
                  <input
                    type="checkbox"
                    checked={selected.includes(solution.id)}
                    onChange={() => toggleRow(solution.id)}
                    disabled={busy}
                    aria-label={`Выбрать решение ${solution.name}`}
                    data-testid="bulk-row-checkbox"
                    className="h-4 w-4"
                  />
                </td>
                <td className="px-4 py-3 font-medium text-slate-900 dark:text-slate-100">
                  {solution.name}
                </td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                  {solution.vendorName}
                </td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                  {PRODUCT_CLASS_LABELS[solution.productClass]
                    ?? solution.productClass}
                </td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                  {SOLUTION_STATUS_LABELS[solution.status] ?? solution.status}
                </td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                  {solution.priceRub == null
                    ? "—"
                    : Number(solution.priceRub).toLocaleString("ru-RU")}
                </td>
                <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                  {solution.sourceKind === "manual" ? (
                    <span className="rounded-full bg-amber-50 px-2 py-0.5 text-xs text-amber-700 dark:bg-amber-950/40 dark:text-amber-400">
                      вручную — требует проверки
                    </span>
                  ) : solution.sourceKind === "organizer_catalog" ? (
                    "таблица каталога"
                  ) : solution.sourceKind === "open_source" ? (
                    "открытый источник"
                  ) : (
                    solution.sourceKind
                  )}
                </td>
                <td className="px-4 py-3 text-right">
                  <Link
                    href={`/admin/solutions/${solution.id}`}
                    className="text-sm text-emerald-700 hover:underline dark:text-emerald-400"
                  >
                    Открыть
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {confirmOpen && (
        <ConfirmDialog
          title="Удалить выбранные решения?"
          message={`Будет удалено ${selected.length} ${plural(
            selected.length, "решение", "решения", "решений",
          )}. Решения, добавленные в сценарии проектов, не удалятся — причина отказа появится в сводке.`}
          confirmLabel="Удалить"
          busy={busy}
          onConfirm={onDeleteConfirmed}
          onCancel={() => setConfirmOpen(false)}
        />
      )}
    </div>
  );
}

/** Русское склонение: 1 решение / 2-4 решения / 5+ решений. */
function plural(n: number, one: string, few: string, many: string): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) {
    return one;
  }
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
    return few;
  }
  return many;
}

async function messageOf(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string; error?: string };
    return body.message ?? body.error ?? `Ошибка HTTP ${response.status}`;
  } catch {
    return `Ошибка HTTP ${response.status}`;
  }
}
