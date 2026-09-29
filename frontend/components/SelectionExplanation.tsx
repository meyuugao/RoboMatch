"use client";

import type { CriterionContribution } from "@/types/selection";

/**
 * Объяснимость ранжирования: таблица вклада каждого критерия -
 * вес, нормированное значение, вклад числом и диаграммой (полоса
 * пропорциональна вкладу). «Критерий не оценён» - отдельная пометка
 * (selection_algorithm.md §6.2: NULL-критерий не понижает статус,
 * но снижает Score).
 */
export default function SelectionExplanation({
  contributions,
  score,
}: {
  contributions: CriterionContribution[];
  score: number | null;
}) {
  const maxContribution = Math.max(
    ...contributions.map((c) => c.contribution),
    0.0001,
  );
  return (
    <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs dark:border-slate-800 dark:bg-slate-800">
      <div className="mb-2 flex items-baseline justify-between">
        <span className="font-medium text-slate-700 dark:text-slate-300">
          Вклад критериев в оценку
        </span>
        {score !== null && (
          <span className="text-slate-500 dark:text-slate-400">
            Score: <span className="font-semibold text-slate-800 dark:text-slate-200">{score}</span>
          </span>
        )}
      </div>
      <table className="w-full border-collapse">
        <thead>
          <tr className="text-left text-slate-500 dark:text-slate-400">
            <th className="py-1 pr-2 font-medium">Критерий</th>
            <th className="py-1 pr-2 font-medium">Вес</th>
            <th className="py-1 pr-2 font-medium">Значение</th>
            <th className="py-1 pr-2 font-medium">Вклад</th>
            <th className="py-1 font-medium">Диаграмма</th>
          </tr>
        </thead>
        <tbody>
          {contributions.map((c) => (
            <tr key={c.code} className="border-t border-slate-200 dark:border-slate-800">
              <td className="py-1 pr-2 text-slate-700 dark:text-slate-300">{c.name}</td>
              <td className="py-1 pr-2 tabular-nums text-slate-600 dark:text-slate-300">{c.weight}</td>
              <td className="py-1 pr-2 tabular-nums text-slate-600 dark:text-slate-300">
                {c.evaluated ? c.normalizedValue : "-"}
              </td>
              <td className="py-1 pr-2 tabular-nums font-medium text-slate-800 dark:text-slate-200">
                {c.contribution}
              </td>
              <td className="py-1">
                <div className="h-2 w-full rounded bg-slate-200 dark:bg-slate-700">
                  <div
                    className="h-2 rounded bg-sky-600"
                    style={{
                      width: `${Math.max(
                        (c.contribution / maxContribution) * 100,
                        1,
                      )}%`,
                    }}
                    title={`${c.name}: вклад ${c.contribution}`}
                  />
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-slate-400 dark:text-slate-500">
        «-» - критерий не оценён (нет данных у решения): вклад 0, статус не
        понижается (правила сравнения - раздел 6.2 алгоритма подбора).
      </p>
    </div>
  );
}
