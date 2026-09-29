"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { formatRub, type ScenarioSolution } from "@/types/economics";

/**
 * Состав оборудования сценария с точечным редактированием:
 * у каждой позиции - поле количества (PUT) и кнопка «Удалить» с
 * confirm. Удаление/правка касаются ТОЛЬКО строки scenario_solution -
 * исторические расчёты не трогаются (append-only, data_model.md §10.8);
 * страница сценариев после правки показывает бейдж «состав изменён с
 * момента расчёта». Для base состав не редактируется: base не
 * содержит решений по определению.
 */
export default function ScenarioComposition({
  projectId,
  scenarioId,
  scenarioType,
  solutions,
}: {
  projectId: string;
  scenarioId: number;
  scenarioType: "base" | "purchase" | "raas";
  solutions: ScenarioSolution[];
}) {
  const router = useRouter();
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<number, string>>({});

  const editable = scenarioType !== "base";

  async function onRemove(solution: ScenarioSolution) {
    const confirmed = window.confirm(
      `Убрать «${solution.solutionName}» из состава сценария?` +
        `\n\nИсторические расчёты не изменятся - новый расчёт пойдёт с новым составом.`,
    );
    if (!confirmed) {
      return;
    }
    setBusyId(solution.solutionId);
    setError(null);
    try {
      const response = await fetch(
        `/api/projects/${projectId}/scenarios/${scenarioId}/solutions/${solution.solutionId}`,
        { method: "DELETE" },
      );
      if (response.status === 204) {
        router.refresh();
        return;
      }
      setError(await readError(response));
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setBusyId(null);
    }
  }

  async function onQuantity(solution: ScenarioSolution) {
    const draft = (drafts[solution.solutionId] ?? "").trim();
    const quantity = Number(draft);
    if (draft === "" || !Number.isInteger(quantity) || quantity < 1 || quantity > 10_000) {
      setError(`Количество для «${solution.solutionName}»: целое число от 1 до 10 000.`);
      return;
    }
    if (quantity === solution.quantity) {
      delete drafts[solution.solutionId];
      setDrafts({ ...drafts });
      setError(null);
      return;
    }
    setBusyId(solution.solutionId);
    setError(null);
    try {
      const response = await fetch(
        `/api/projects/${projectId}/scenarios/${scenarioId}/solutions/${solution.solutionId}`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ quantity }),
        },
      );
      if (response.ok) {
        setDrafts((prev) => {
          const next = { ...prev };
          delete next[solution.solutionId];
          return next;
        });
        router.refresh();
        return;
      }
      setError(await readError(response));
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setBusyId(null);
    }
  }

  if (solutions.length === 0) {
    return (
      <p className="text-xs text-slate-400 dark:text-slate-500">
        {scenarioType === "base"
          ? "Базовый сценарий не требует оборудования."
          : "Добавьте решения через подбор или вручную."}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-1.5">
      <ul className="space-y-1.5 text-sm">
        {solutions.map((s) => {
          const busy = busyId === s.solutionId;
          const draft = drafts[s.solutionId];
          return (
            <li
              key={s.solutionId}
              className="rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-800"
            >
              <div className="flex flex-wrap items-baseline justify-between gap-1">
                <span className="font-medium text-slate-800 dark:text-slate-200">
                  {s.solutionName}
                </span>
                <span className="text-xs text-slate-500 dark:text-slate-400">
                  ×{s.quantity} = {formatRub(s.sumRub)}
                </span>
              </div>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                {s.vendorName ?? "Вендор не указан"}
                {s.manual ? " · добавлено вручную" : ""}
              </p>
              {s.manual && s.manualReason ? (
                <p className="mt-0.5 text-xs italic text-slate-400 dark:text-slate-500">
                  Причина: {s.manualReason}
                </p>
              ) : null}
              {editable && (
                <div className="mt-1.5 flex flex-wrap items-center gap-2">
                  <label className="flex items-center gap-1 text-xs text-slate-600 dark:text-slate-300">
                    Кол-во:
                    <input
                      type="number"
                      min={1}
                      max={10_000}
                      step={1}
                      value={draft ?? s.quantity}
                      onChange={(e) =>
                        setDrafts((prev) => ({
                          ...prev,
                          [s.solutionId]: e.target.value,
                        }))
                      }
                      disabled={busy}
                      aria-label={`Количество «${s.solutionName}»`}
                      className="w-20 rounded-md border border-slate-300 bg-white px-2 py-1 text-xs tabular-nums focus:border-slate-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
                    />
                    <button
                      type="button"
                      onClick={() => onQuantity(s)}
                      disabled={busy || draft === undefined}
                      className="rounded-md border border-slate-300 bg-white px-2 py-1 text-xs text-slate-700 transition hover:bg-slate-100 disabled:opacity-40 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
                    >
                      Сохранить
                    </button>
                  </label>
                  <button
                    type="button"
                    onClick={() => onRemove(s)}
                    disabled={busy}
                    className="rounded-md border border-red-200 bg-white px-2 py-1 text-xs text-red-700 transition hover:bg-red-50 disabled:opacity-40 dark:border-red-800 dark:bg-slate-900 dark:text-red-400 dark:hover:bg-red-950/40"
                  >
                    Удалить
                  </button>
                </div>
              )}
            </li>
          );
        })}
      </ul>
      {error !== null && (
        <p role="alert" className="text-xs text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}

async function readError(response: Response): Promise<string> {
  let message = `Ошибка (HTTP ${response.status})`;
  try {
    const body = (await response.json()) as { message?: string; error?: string };
    message = body.message ?? body.error ?? message;
  } catch {
    // тело не JSON - fallback
  }
  return message;
}
