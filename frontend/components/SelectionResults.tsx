"use client";

import { useMemo, useState } from "react";
import type {
  ScenarioDto,
  SelectionResultDto,
  SelectionRunDto,
  SelectionStatus,
} from "@/types/selection";
import {
  SCENARIO_TYPE_LABELS,
  SELECTION_STATUS_LABELS,
} from "@/types/selection";
import ManualAddDialog from "@/components/ManualAddDialog";
import SelectionExplanation from "@/components/SelectionExplanation";

/**
 * Таблица результатов подбора: решение, статус,
 * Score, причины, недостающие данные, вклад критериев (число +
 * диаграмма). Фильтры по статусу, сортировка по Score. Кнопки:
 * «Запустить подбор», «Добавить в сценарий» (fit) и «Добавить
 * вручную» (needs_check/excluded — модалка с причиной).
 */
const STATUS_FILTERS: { value: SelectionStatus | "all"; label: string }[] = [
  { value: "all", label: "Все" },
  { value: "fit", label: "Подходят" },
  { value: "needs_check", label: "Требуют проверки" },
  { value: "excluded", label: "Исключены" },
];

const STATUS_BADGE: Record<SelectionStatus, string> = {
  fit: "border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-400",
  needs_check: "border-sky-200 bg-sky-50 text-sky-700 dark:border-sky-800 dark:bg-sky-950/40 dark:text-sky-400",
  excluded: "border-red-200 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950/40 dark:text-red-400",
};

export default function SelectionResults({
  projectId,
  initial,
}: {
  projectId: string;
  initial: SelectionRunDto;
}) {
  const [data, setData] = useState<SelectionRunDto>(initial);
  const [statusFilter, setStatusFilter] = useState<SelectionStatus | "all">("all");
  const [sortAsc, setSortAsc] = useState(false);
  const [running, setRunning] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dialogFor, setDialogFor] = useState<SelectionResultDto | null>(null);

  const counts = useMemo(() => {
    const byStatus: Record<string, number> = { fit: 0, needs_check: 0, excluded: 0 };
    for (const r of data.results) {
      byStatus[r.status] = (byStatus[r.status] ?? 0) + 1;
    }
    return byStatus;
  }, [data.results]);

  const visible = useMemo(() => {
    const filtered = statusFilter === "all"
      ? data.results
      : data.results.filter((r) => r.status === statusFilter);
    // сортировка по Score (убывание по умолчанию), затем ранг, затем имя
    const sorted = [...filtered].sort((a, b) => {
      const scoreA = a.score ?? -1;
      const scoreB = b.score ?? -1;
      if (scoreA !== scoreB) {
        return sortAsc ? scoreA - scoreB : scoreB - scoreA;
      }
      const rankA = a.rank ?? Number.MAX_SAFE_INTEGER;
      const rankB = b.rank ?? Number.MAX_SAFE_INTEGER;
      if (rankA !== rankB) {
        return sortAsc ? rankA - rankB : rankB - rankA;
      }
      // fallback имени гарантирует ненулевую строку:
      // решение могло покинуть каталог между запусками
      return (a.solutionName ?? "").localeCompare(b.solutionName ?? "", "ru");
    });
    return sorted;
  }, [data.results, statusFilter, sortAsc]);

  async function onRun() {
    setRunning(true);
    setError(null);
    setMessage(null);
    try {
      const response = await fetch(`/api/projects/${projectId}/selection/run`, {
        method: "POST",
      });
      if (response.ok) {
        setData((await response.json()) as SelectionRunDto);
        setMessage("Подбор выполнен — результаты ниже");
      } else {
        let text = `Ошибка (HTTP ${response.status})`;
        try {
          const body = (await response.json()) as { message?: string; error?: string };
          text = body.message ?? body.error ?? text;
        } catch {
          // тело не JSON — fallback
        }
        setError(text);
      }
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={onRun}
            disabled={running}
            className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white
                       transition hover:bg-slate-700 disabled:opacity-50 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
          >
            {running ? "Подбор..." : "Запустить подбор"}
          </button>
          <span className="text-xs text-slate-500 dark:text-slate-400">
            Фильтрация по 8 обязательным ТТХ и инфраструктурным ограничениям,
            ранжирование fit-решений с объяснением
          </span>
        </div>
        {data.scenarios.length > 0 && (
          <span className="text-xs text-slate-500 dark:text-slate-400">
            Сценарии:{" "}
            {data.scenarios
              .map((s) => `${s.name} (${SCENARIO_TYPE_LABELS[s.type] ?? s.type})`)
              .join(" · ")}
          </span>
        )}
      </div>

      {error !== null && (
        <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      )}
      {message !== null && error === null && (
        <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-400">
          {message}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2">
        {STATUS_FILTERS.map((filter) => {
          const count = filter.value === "all"
            ? data.results.length
            : counts[filter.value] ?? 0;
          const active = statusFilter === filter.value;
          return (
            <button
              key={filter.value}
              type="button"
              onClick={() => setStatusFilter(filter.value)}
              className={
                active
                  ? "rounded-full border border-slate-900 bg-slate-900 px-3 py-1 text-xs font-medium text-white dark:border-slate-100 dark:bg-slate-100 dark:text-slate-900"
                  : "rounded-full border border-slate-300 bg-white px-3 py-1 text-xs text-slate-600 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
              }
            >
              {filter.label} ({count})
            </button>
          );
        })}
        <button
          type="button"
          onClick={() => setSortAsc((v) => !v)}
          className="rounded-full border border-slate-300 bg-white px-3 py-1 text-xs text-slate-600 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Score: {sortAsc ? "по возрастанию ↑" : "по убыванию ↓"}
        </button>
      </div>

      {data.results.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">
          Подбор ещё не запускался. Нажмите «Запустить подбор» — платформа
          определит применимые решения и объяснит причины включения и
          исключения каждого.
        </div>
      ) : visible.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">
          В этом статусе решений нет.
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {visible.map((r) => (
            <div
              key={r.solutionId}
              className="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
            >
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold text-slate-900 dark:text-slate-100">
                      {r.solutionName}
                    </span>
                    {r.solutionTypeName && (
                      <span className="rounded-full border border-slate-200 bg-slate-50 px-2 py-0.5 text-[11px] text-slate-500 dark:border-slate-800 dark:bg-slate-800 dark:text-slate-400">
                        {r.solutionTypeName}
                      </span>
                    )}
                    {r.vendorName && (
                      <span className="text-xs text-slate-500 dark:text-slate-400">{r.vendorName}</span>
                    )}
                    <span
                      className={`rounded-full border px-2 py-0.5 text-[11px] font-medium ${STATUS_BADGE[r.status]}`}
                    >
                      {SELECTION_STATUS_LABELS[r.status] ?? r.status}
                    </span>
                    {r.rank !== null && (
                      <span className="rounded-full border border-slate-300 px-2 py-0.5 text-[11px] text-slate-600 dark:border-slate-700 dark:text-slate-300">
                        место {r.rank}
                      </span>
                    )}
                  </div>
                  {r.reason && (
                    <p className="mt-1 max-w-3xl text-xs text-slate-600 dark:text-slate-300">{r.reason}</p>
                  )}
                  {r.missingData !== null && r.missingData.length > 0 && (
                    <ul className="mt-1 list-inside list-disc text-xs text-slate-500 dark:text-slate-400">
                      {r.missingData.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  {r.score !== null && (
                    <span
                      className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-sm
                                 font-semibold tabular-nums text-slate-800 dark:border-slate-800
                                 dark:bg-slate-800 dark:text-slate-200"
                      title="Итоговая оценка ранжирования [0..1]"
                    >
                      Score {r.score}
                    </span>
                  )}
                  <button
                    type="button"
                    onClick={() => setDialogFor(r)}
                    className={
                      r.status === "fit"
                        ? "rounded-lg bg-slate-900 px-3 py-1.5 text-xs font-medium text-white transition hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
                        : "rounded-lg border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-medium text-amber-800 transition hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300 dark:hover:bg-amber-900/40"
                    }
                  >
                    {r.status === "fit" ? "Добавить в сценарий" : "Добавить вручную"}
                  </button>
                </div>
              </div>

              {r.status === "fit" && r.criteriaContribution !== null && (
                <SelectionExplanation
                  contributions={r.criteriaContribution}
                  score={r.score}
                />
              )}
            </div>
          ))}
        </div>
      )}

      {dialogFor !== null && (
        <ManualAddDialog
          projectId={projectId}
          scenarios={data.scenarios}
          solution={dialogFor}
          onClose={() => setDialogFor(null)}
          onAdded={(text) => {
            setDialogFor(null);
            setMessage(text);
          }}
        />
      )}
    </div>
  );
}
