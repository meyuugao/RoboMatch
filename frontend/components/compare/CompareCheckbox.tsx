"use client";

import { useEffect, useState } from "react";
import { COMPARE_CHANGED_EVENT, COMPARE_LIMIT, getCompareIds, toggleCompareId } from "@/lib/compare";

/**
 * Чекбокс «Добавить к сравнению» на карточке решения.
 * Состояние живёт в localStorage (lib/compare.ts) и синхронизируется
 * со всеми остальными чекбоксами и панелью «Сравнить (N)» через
 * window-событие. Лимит - 10 решений, как в API.
 */
export default function CompareCheckbox({
  solutionId,
  compact = false,
}: {
  solutionId: number;
  compact?: boolean;
}) {
  const [checked, setChecked] = useState(false);
  const [limitReached, setLimitReached] = useState(false);

  useEffect(() => {
    // начальное состояние - после монтирования (SSR безопасно)
    setChecked(getCompareIds().includes(solutionId));
    setLimitReached(getCompareIds().length >= COMPARE_LIMIT);
    const sync = () => {
      const ids = getCompareIds();
      setChecked(ids.includes(solutionId));
      setLimitReached(ids.length >= COMPARE_LIMIT);
    };
    window.addEventListener(COMPARE_CHANGED_EVENT, sync);
    window.addEventListener("storage", sync);
    return () => {
      window.removeEventListener(COMPARE_CHANGED_EVENT, sync);
      window.removeEventListener("storage", sync);
    };
  }, [solutionId]);

  function onChange() {
    const nowChecked = toggleCompareId(solutionId);
    setChecked(nowChecked);
    setLimitReached(!nowChecked && getCompareIds().length >= COMPARE_LIMIT);
  }

  if (limitReached && !checked) {
    // лимит достигнут: объясняем, почему чекбокс не ставится
    // (иначе попытка добавить 11-е решение молча игнорируется)
    return (
      <span className="text-xs text-slate-400 dark:text-slate-500" title="Доступно не более 10 решений">
        Сравнение заполнено (10)
      </span>
    );
  }

  return (
    <label
      className={`flex cursor-pointer select-none items-center gap-1.5 text-xs
                  ${checked ? "font-medium text-blue-700 dark:text-blue-400" : "text-slate-600 dark:text-slate-300"}`}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={onChange}
        aria-label="Добавить к сравнению"
        className="h-4 w-4 cursor-pointer rounded border-slate-300 accent-blue-600 dark:border-slate-700"
      />
      {compact ? "Сравнить" : "Добавить к сравнению"}
    </label>
  );
}
