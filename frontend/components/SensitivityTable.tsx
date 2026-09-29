import type { SensitivityRowDto } from "@/types/economics";
import {
  SENSITIVITY_PARAM_LABELS,
  formatPaybackShort,
  formatPct,
  formatRub,
} from "@/types/economics";

/**
 * Таблица чувствительности: «Δ параметра → Δ
 * показателя» для стоимости оборудования, объёма операций и стоимости
 * труда; шаги −20 / −10 / 0 / +10 / +20%. Серверный компонент.
 *
 * Объём операций: парк масштабируется пропорционально выбранному
 * составу (ceil(selected × (1+Δ))); поле note сохранено для исторических
 * расчётов (новые строки — всегда null).
 */
export default function SensitivityTable({
  rows,
  scenarioName,
}: {
  rows: SensitivityRowDto[];
  scenarioName: string | null;
}) {
  if (rows.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-slate-300 bg-white p-6 text-center text-sm text-slate-500 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-400">
        Чувствительность появится после расчёта роботизированного сценария.
      </div>
    );
  }

  const parameters = [...new Set(rows.map((r) => r.parameter))];
  // и для строк чувствительности: сверхвысокий ROI при большом
  // baseline ФОТ требует проверки допущений
  const highRoi = rows.some(
    (r) => r.roiPct !== null && r.roiPct !== undefined && r.roiPct > 500,
  );

  return (
    <div className="space-y-4">
      {scenarioName ? (
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Пересчёт {scenarioName} при изменении параметра (экономика
          пересчитывается целиком — доли секунды).
        </p>
      ) : null}
      {parameters.map((parameter) => {
        const paramRows = rows.filter((r) => r.parameter === parameter);
        const baseRow = paramRows.find((r) => r.deltaPct === 0);
        // примечание строк (линейный fallback без P_nominal)
        const note = paramRows.find((r) => r.note)?.note ?? null;
        return (
          <div
            key={parameter}
            className="overflow-x-auto rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900"
          >
            <div className="border-b border-slate-100 bg-slate-50 px-4 py-2 text-sm font-medium text-slate-700 dark:border-slate-800 dark:bg-slate-800 dark:text-slate-300">
              {SENSITIVITY_PARAM_LABELS[parameter] ?? parameter}
            </div>
            <table className="w-full min-w-[560px] text-sm">
              <thead>
                <tr className="border-b border-slate-100 text-left text-xs text-slate-500 dark:border-slate-800 dark:text-slate-400">
                  <th className="px-4 py-2 font-medium">Δ параметра</th>
                  <th className="px-4 py-2 font-medium">Годовой эффект</th>
                  <th className="px-4 py-2 font-medium">Δ эффекта</th>
                  <th className="px-4 py-2 font-medium">Окупаемость</th>
                  <th className="px-4 py-2 font-medium">ROI</th>
                </tr>
              </thead>
              <tbody>
                {paramRows.map((row) => (
                  <tr
                    key={row.deltaPct}
                    className={`border-b border-slate-50 last:border-0 dark:border-slate-800 ${
                      row.deltaPct === 0 ? "bg-sky-50/50 dark:bg-sky-950/40" : ""
                    }`}
                  >
                    <td className="px-4 py-2 font-medium text-slate-700 dark:text-slate-300">
                      {row.deltaPct > 0 ? "+" : ""}
                      {row.deltaPct}%
                    </td>
                    <td className="px-4 py-2 text-slate-900 dark:text-slate-100">
                      {formatRub(row.effectYear)}
                    </td>
                    <td
                      className={`px-4 py-2 ${
                        row.effectDelta === null || row.effectDelta === 0
                          ? "text-slate-400 dark:text-slate-500"
                          : row.effectDelta > 0
                            ? "text-emerald-700 dark:text-emerald-400"
                            : "text-rose-700 dark:text-rose-400"
                      }`}
                    >
                      {row.effectDelta === null || row.effectDelta === 0
                        ? "—"
                        : `${row.effectDelta > 0 ? "+" : ""}${Math.round(
                            row.effectDelta,
                          ).toLocaleString("ru-RU")} ₽`}
                    </td>
                    <td className="px-4 py-2 text-slate-900 dark:text-slate-100">
                      {/* срок < 0,5 года — в месяцах */}
                      {formatPaybackShort(row.paybackYears)}
                    </td>
                    <td className="px-4 py-2 text-slate-900 dark:text-slate-100">
                      {formatPct(row.roiPct)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {note ? (
              <p className="border-t border-amber-100 bg-amber-50 px-4 py-2 text-xs text-amber-800 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300">
                {note}
              </p>
            ) : null}
            {baseRow ? null : (
              <p className="px-4 py-2 text-xs text-amber-700 dark:text-amber-400">
                Базовая строка (Δ 0%) не найдена — пересчитайте сценарий.
              </p>
            )}
          </div>
        );
      })}
      {highRoi ? (
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Значения ROI зависят от большого baseline ФОТ — проверьте допущения.
        </p>
      ) : null}
    </div>
  );
}
