"use client";

import { useState } from "react";
import type { CalculationFull } from "@/types/economics";
import { METRIC_LABELS } from "@/types/economics";
import ManualAdjustDialog from "@/components/ManualAdjustDialog";

/**
 * Кнопка «Скорректировать значение» + модалка: выбирает
 * метрику последнего расчёта сценария, новое значение и причину.
 * Клиентский компонент (диалог и мутация).
 */
export default function AdjustButton({
  projectId,
  calculation,
  disabled,
}: {
  projectId: string;
  calculation: CalculationFull | null;
  disabled: boolean;
}) {
  const [open, setOpen] = useState(false);

  if (!calculation || disabled) {
    return (
      <button
        type="button"
        disabled
        title="Сначала рассчитайте сценарий покупки"
        className="cursor-not-allowed rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-400 dark:border-slate-800 dark:text-slate-500"
      >
        Скорректировать значение
      </button>
    );
  }

  const candidates = (
    [
      ["total_capex", calculation.totalCapex],
      ["total_opex", calculation.totalOpex],
      ["opex_delta_rub", calculation.opexDeltaRub],
      ["effect_year", calculation.effectYear],
      ["payback_years", calculation.paybackYears],
      ["roi_pct", calculation.roiPct],
      ["tco_rub", calculation.tcoRub],
    ] as const
  ).map(([metricName, value]) => ({
    metricName,
    currentValue: value,
  }));

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
      >
        Скорректировать значение
      </button>
      {open ? (
        <ManualAdjustDialog
          projectId={projectId}
          calculationId={calculation.id}
          metricCandidates={candidates}
          onClose={() => setOpen(false)}
        />
      ) : null}
    </>
  );
}
