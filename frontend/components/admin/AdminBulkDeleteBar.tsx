"use client";

import type { BulkDeleteResultDto } from "@/types/admin";

/**
 * Панель массовых действий таблиц «Управления»: счётчик «Выбрано: N»,
 * кнопка «Удалить выбранные» (неактивна при пустом выборе) и снятие
 * выделения. Под панелью - сводка последнего массового удаления:
 * сколько удалено и по каким позициям отказ (с причиной).
 */
export default function AdminBulkDeleteBar({
  selectedCount,
  busy,
  onRequestDelete,
  onClearSelection,
  result,
  onDismissResult,
  itemLabel,
}: {
  selectedCount: number;
  busy: boolean;
  onRequestDelete: () => void;
  onClearSelection: () => void;
  result: BulkDeleteResultDto | null;
  onDismissResult: () => void;
  itemLabel: string;
}) {
  return (
    <div className="flex flex-col gap-3" data-testid="bulk-panel">
      <div className="flex flex-wrap items-center gap-3 rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 dark:border-slate-800 dark:bg-slate-900">
        <span
          data-testid="bulk-selected-count"
          className={
            "text-sm font-medium " +
            (selectedCount > 0
              ? "text-slate-900 dark:text-slate-100"
              : "text-slate-500 dark:text-slate-400")
          }
        >
          Выбрано: {selectedCount}
        </span>
        <button
          type="button"
          disabled={busy || selectedCount === 0}
          onClick={onRequestDelete}
          data-testid="bulk-delete-button"
          className="rounded-lg bg-rose-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-rose-700 disabled:cursor-not-allowed disabled:opacity-40"
        >
          Удалить выбранные
        </button>
        {selectedCount > 0 && (
          <button
            type="button"
            disabled={busy}
            onClick={onClearSelection}
            className="text-sm text-slate-600 hover:underline disabled:opacity-50 dark:text-slate-300"
          >
            Снять выделение
          </button>
        )}
        <span className="text-xs text-slate-500 dark:text-slate-400">
          {itemLabel} со ссылками не удалятся - будет показана причина
        </span>
      </div>

      {result !== null && (
        <div
          role="status"
          data-testid="bulk-result"
          className={
            "rounded-xl border p-4 text-sm " +
            (result.failed.length === 0
              ? "border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300"
              : "border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300")
          }
        >
          <div className="flex items-start justify-between gap-3">
            <p className="font-medium" data-testid="bulk-result-summary">
              Удалено: {result.deleted.length} из{" "}
              {result.deleted.length + result.failed.length}
            </p>
            <button
              type="button"
              onClick={onDismissResult}
              aria-label="Закрыть сводку"
              className="text-sm text-slate-500 hover:underline dark:text-slate-400"
            >
              Скрыть
            </button>
          </div>
          {result.failed.length > 0 && (
            <div className="mt-2">
              <p className="font-medium">Не удалены:</p>
              <ul
                data-testid="bulk-failed-list"
                className="mt-1 flex max-h-48 flex-col gap-1 overflow-y-auto"
              >
                {result.failed.map((failure) => (
                  <li
                    key={failure.id}
                    className="flex flex-col gap-0.5 rounded-lg bg-white/60 px-3 py-1.5 dark:bg-slate-900/60"
                  >
                    <span className="font-medium">ID {failure.id}</span>
                    <span className="text-xs leading-relaxed opacity-90">
                      {failure.reason}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
