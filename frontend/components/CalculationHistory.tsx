"use client";

import { useEffect, useState } from "react";
import type { CalculationDto } from "@/types/economics";
import {
  METRIC_LABELS,
  formatCalcDate,
  formatRub,
} from "@/types/economics";

/**
 * История расчётов: список с версиями данных (SHA-256 снимка
 * входов) и версии модели, свежие сверху; пометка скорректированных.
 * Клиентский компонент: подгружает историю выбранного сценария по кнопке.
 */
export default function CalculationHistory({
  projectId,
  scenarios,
}: {
  projectId: string;
  scenarios: Array<{ id: number; name: string; type: string }>;
}) {
  const [open, setOpen] = useState(false);
  const [scenarioId, setScenarioId] = useState<number | null>(
    scenarios[0]?.id ?? null,
  );
  const [rows, setRows] = useState<CalculationDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open || scenarioId === null) {
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetch(`/api/projects/${projectId}/scenarios/${scenarioId}/calculations`, {
      cache: "no-store",
    })
      .then(async (response) => {
        if (!response.ok) {
          const body = (await response.json().catch(() => ({}))) as {
            message?: string;
            error?: string;
          };
          throw new Error(
            body.message ?? body.error ?? `Ошибка загрузки (HTTP ${response.status})`,
          );
        }
        return response.json() as Promise<CalculationDto[]>;
      })
      .then((data) => {
        if (!cancelled) {
          setRows(data);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : "Не удалось загрузить историю");
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [open, projectId, scenarioId]);

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="font-semibold text-slate-800 dark:text-slate-200">История расчётов</h3>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Каждый расчёт — новая строка с версией данных и модели
            (воспроизведение расчёта).
          </p>
        </div>
        <div className="flex items-center gap-2">
          {scenarios.length > 1 ? (
            <select
              value={scenarioId ?? ""}
              onChange={(e) => setScenarioId(Number(e.target.value))}
              className="rounded-lg border border-slate-300 px-2 py-1.5 text-sm dark:border-slate-700 dark:text-slate-100"
              aria-label="Сценарий"
            >
              {scenarios.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          ) : null}
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
          >
            {open ? "Скрыть" : "Показать"}
          </button>
        </div>
      </div>

      {open ? (
        <div className="mt-4">
          {loading ? (
            <p className="text-sm text-slate-500 dark:text-slate-400">Загрузка…</p>
          ) : error ? (
            <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
              {error}
            </p>
          ) : rows === null ? null : rows.length === 0 ? (
            <p className="text-sm text-slate-500 dark:text-slate-400">
              Расчётов пока нет — запустите расчёт на странице сценариев.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[680px] text-sm">
                <thead>
                  <tr className="border-b border-slate-100 text-left text-xs text-slate-500 dark:border-slate-800 dark:text-slate-400">
                    <th className="py-2 pr-3 font-medium">№</th>
                    <th className="py-2 pr-3 font-medium">Дата</th>
                    <th className="py-2 pr-3 font-medium">Версия данных</th>
                    <th className="py-2 pr-3 font-medium">Модель</th>
                    <th className="py-2 pr-3 font-medium">
                      {METRIC_LABELS.effect_year}
                    </th>
                    <th className="py-2 pr-3 font-medium">
                      {METRIC_LABELS.tco_rub}
                    </th>
                    <th className="py-2 font-medium">Отметки</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-b border-slate-50 last:border-0 dark:border-slate-800">
                      <td className="py-2 pr-3 text-slate-500 dark:text-slate-400">{row.id}</td>
                      <td className="py-2 pr-3 text-slate-700 dark:text-slate-300">
                        {formatCalcDate(row.calculatedAt)}
                      </td>
                      <td className="py-2 pr-3 font-mono text-xs text-slate-600 dark:text-slate-300">
                        {row.versionData}
                      </td>
                      <td className="py-2 pr-3 font-mono text-xs text-slate-600 dark:text-slate-300">
                        {row.versionModel}
                      </td>
                      <td className="py-2 pr-3 text-slate-900 dark:text-slate-100">
                        {formatRub(row.effectYear)}
                      </td>
                      <td className="py-2 pr-3 text-slate-900 dark:text-slate-100">
                        {formatRub(row.tcoRub)}
                      </td>
                      <td className="py-2">
                        {row.adjusted ? (
                          <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">
                            скорректирован
                          </span>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : null}
    </div>
  );
}
