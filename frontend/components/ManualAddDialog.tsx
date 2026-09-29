"use client";

import { useMemo, useState } from "react";
import type { ScenarioDto, SelectionResultDto } from "@/types/selection";
import { SCENARIO_TYPE_LABELS } from "@/types/selection";

/**
 * Модалка ручного добавления решения в сценарий: выбор
 * сценария + ОБЯЗАТЕЛЬНАЯ причина
 * предупреждением и причиной; заполняемость контролирует и фронт, и
 * backend). Для исключённых решений причина исключения показывается
 * как предупреждение.
 *
 * Базовый сценарий НЕ содержит решений по определению
 * (data_model.md §10.5: «текущий процесс без роботизации») -
 * из выбора исключён и фронт (здесь), и backend (400). Добавление -
 * только в сценарии «покупка» и «RaaS».
 */
export default function ManualAddDialog({
  projectId,
  scenarios,
  solution,
  onClose,
  onAdded,
}: {
  projectId: string;
  scenarios: ScenarioDto[];
  solution: SelectionResultDto;
  onClose: () => void;
  onAdded: (message: string) => void;
}) {
  const selectable = useMemo(
    // base - без решений по определению; в списке не предлагаем
    () => scenarios.filter((s) => s.type !== "base"),
    [scenarios],
  );
  const [scenarioId, setScenarioId] = useState<number | null>(
    selectable.find((s) => s.type === "purchase")?.id ?? selectable[0]?.id ?? null,
  );
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (scenarioId === null) {
      setError("Сначала запустите подбор - сценарии «покупка» и «RaaS» создаются первым запуском.");
      return;
    }
    if (reason.trim().length < 5) {
      setError("Укажите причину добавления (не короче 5 символов) - она попадёт в отчёт.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/projects/${projectId}/scenarios/${scenarioId}/solutions`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            solutionId: solution.solutionId,
            manualReason: reason.trim(),
          }),
        },
      );
      if (response.status === 201) {
        const scenarioName =
          selectable.find((s) => s.id === scenarioId)?.name ?? "сценарий";
        onAdded(`Решение «${solution.solutionName}» добавлено в «${scenarioName}»`);
        return;
      }
      let message = `Ошибка (HTTP ${response.status})`;
      try {
        const body = (await response.json()) as { message?: string; error?: string };
        message = body.message ?? body.error ?? message;
      } catch {
        // тело не JSON - остаётся fallback
      }
      setError(message);
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Добавление решения в сценарий"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
    >
      <div className="w-full max-w-lg rounded-xl bg-white p-5 shadow-xl dark:bg-slate-900">
        <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">
          Добавить «{solution.solutionName}» в сценарий
        </h3>

        {solution.status === "excluded" && (
          <p
            role="alert"
            className="mt-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
          >
            <span className="font-medium">Предупреждение: </span>
            решение исключено автоматическим подбором. {solution.reason}
          </p>
        )}
        {solution.status === "needs_check" && (
          <p className="mt-3 rounded-lg border border-sky-200 bg-sky-50 px-3 py-2 text-sm text-sky-800 dark:border-sky-800 dark:bg-sky-950/40 dark:text-sky-300">
            Решение «требует проверки»: {(solution.missingData ?? []).join("; ")}.
            Проверьте данные перед добавлением.
          </p>
        )}

        <form onSubmit={onSubmit} className="mt-4 flex flex-col gap-3">
          <label className="flex flex-col gap-1 text-sm text-slate-700 dark:text-slate-300">
            Сценарий
            <select
              value={scenarioId ?? ""}
              onChange={(e) => setScenarioId(Number(e.target.value) || null)}
              disabled={selectable.length === 0}
              className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none dark:border-slate-700 dark:text-slate-100"
            >
              {selectable.length === 0 && <option value="">- нет сценариев -</option>}
              {selectable.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name} ({SCENARIO_TYPE_LABELS[s.type] ?? s.type})
                </option>
              ))}
            </select>
            <span className="text-xs text-slate-400 dark:text-slate-500">
              Базовый сценарий не содержит решений по определению - выбор
              ограничен сценариями «покупка» и «RaaS».
            </span>
          </label>

          <label className="flex flex-col gap-1 text-sm text-slate-700 dark:text-slate-300">
            Причина добавления <span className="text-red-600 dark:text-red-400">*</span>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
              maxLength={1024}
              placeholder={
                solution.status === "excluded"
                  ? "Например: планируем расширить проходы до 3 м"
                  : "Например: основной кандидат для пилотной зоны"
              }
              className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none dark:border-slate-700 dark:text-slate-100"
            />
          </label>

          {error !== null && (
            <p role="alert" className="text-sm text-red-600 dark:text-red-400">
              {error}
            </p>
          )}

          <div className="mt-1 flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
            >
              Отмена
            </button>
            <button
              type="submit"
              disabled={saving}
              className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:opacity-50 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
            >
              {saving ? "Добавление..." : "Добавить в сценарий"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
