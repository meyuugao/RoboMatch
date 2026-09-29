import type { ComparisonScenario } from "@/types/economics";
import {
  formatPaybackShort,
  formatPct,
  formatRub,
} from "@/types/economics";

/**
 * Таблица сравнения сценариев (; §2.13
 * economic_model.md): строки - показатели, колонки - базовый / покупка /
 * RaaS, с Δ к базовому и интерпретацией окупаемости.
 * Серверный компонент: данные приходят пропсом со страницы.
 *
 * Правила отображения:
 * - выбранный состав и требуемое количество - раздельно (вся экономика
 * по выбранному составу);
 * - недобор парка: бейдж «Парк меньше требуемого», Payback/ROI не
 * выводятся числами, причина - единообразная формулировка «Н/Д - парк
 * меньше требуемого по пиковой нагрузке»;
 * - превышение парка: нейтральная плашка «парк превышает рекомендуемый -
 * возможна переплата»;
 * - окупаемость < 0,5 года - в месяцах, срок - человекочитаемый
 * (paybackHuman с backend);
 * - ROI у RaaS без выкупа - «Н/П»; под ROI > 500% - подпись про большой
 * baseline ФОТ;
 * - подписи: OPEX базового сценария, платежи RaaS за выбранный состав.
 */
export default function ScenarioComparison({
  scenarios,
  horizonYears,
}: {
  scenarios: ComparisonScenario[];
  horizonYears: number | null;
}) {
  const base = scenarios.find((s) => s.type === "base") ?? null;
  const robotized = scenarios.filter((s) => s.type !== "base");

  function deltaCell(value: number | null | undefined, baseValue: number | null | undefined): string {
    if (value === null || value === undefined || baseValue === null || baseValue === undefined) {
      return "-";
    }
    const delta = value - baseValue;
    if (Math.abs(delta) < 0.5) {
      return "0";
    }
    return `${delta > 0 ? "+" : ""}${Math.round(delta).toLocaleString("ru-RU")}`;
  }

  function paybackCell(column: ComparisonScenario) {
    if (!column.calculated) {
      return <span className="text-slate-400 dark:text-slate-500">Не рассчитан</span>;
    }
    // недобор парка - окупаемость не
    // выводится числом, причина - в единообразной формулировке
    if (column.underpowered) {
      return (
        <span className="text-xs font-medium text-amber-700 dark:text-amber-400">
          Н/Д - парк меньше требуемого по пиковой нагрузке
        </span>
      );
    }
    if (!column.payback) {
      return <span className="text-slate-400 dark:text-slate-500">-</span>;
    }
    if (column.payback.paybackYears !== null) {
      // человекочитаемый срок - paybackHuman с backend
      // (зеркальное форматирование на клиенте - для чувствительности
      // и старых данных); рядом подпись категории остаётся
      return (
        <span>
          <span className="font-medium">
            {column.payback.paybackHuman
              ?? formatPaybackShort(column.payback.paybackYears)}
          </span>
          <span className="ml-1 block text-xs text-slate-500 dark:text-slate-400">
            {column.payback.text}
          </span>
        </span>
      );
    }
    return (
      <span className="text-xs text-slate-500 dark:text-slate-400">{column.payback.text}</span>
    );
  }

  /** ROI у RaaS без выкупа (CAPEX = 0) - «Н/П» с пояснением. */
  function roiCell(column: ComparisonScenario) {
    if (!column.calculated) {
      return <span className="text-slate-400 dark:text-slate-500">-</span>;
    }
    if (column.underpowered) {
      // та же формулировка причины, что и у окупаемости
      return (
        <span className="text-xs font-medium text-amber-700 dark:text-amber-400">
          Н/Д - парк меньше требуемого по пиковой нагрузке
        </span>
      );
    }
    if (column.roiPct === null || column.roiPct === undefined) {
      if (column.type === "raas" && column.capex === 0) {
        return (
          <span className="text-xs text-slate-500 dark:text-slate-400">
            Н/П - CAPEX = 0 (RaaS без выкупа): сравнивайте по годовому
            эффекту и TCO
          </span>
        );
      }
      return <span className="text-slate-400 dark:text-slate-500">-</span>;
    }
    return (
      <span>
        <span>{formatPct(column.roiPct)}</span>
        {/* сверхвысокий ROI при большом baseline ФОТ математически
 корректен, но требует проверки допущений (пример расчёта:
 ROI 3780% при ФОТ 203 млн) */}
        {column.roiPct > 500 ? (
          <span className="mt-0.5 block text-xs text-slate-500 dark:text-slate-400">
            Значение зависит от большого baseline ФОТ - проверьте допущения
          </span>
        ) : null}
      </span>
    );
  }

  const rows: Array<{
    label: string;
    hint?: string;
    cell: (c: ComparisonScenario) => React.ReactNode;
    delta?: boolean;
    deltaValue?: (c: ComparisonScenario) => number | null | undefined;
    deltaBase?: () => number | null | undefined;
    afterCell?: (c: ComparisonScenario) => React.ReactNode;
  }> = [
    {
      label: "Роботов (выбрано)",
      hint: "Фактический состав сценария - вся экономика считается по нему",
      cell: (c) => (c.selectedRobots === null ? "-" : `${c.selectedRobots} ед.`),
    },
    {
      label: "Требуется по пиковой нагрузке",
      hint: "Рекомендация по пиковой нагрузке (с резервом)",
      cell: (c) => (c.requiredRobots === null ? "-" : `${c.requiredRobots} ед.`),
    },
    {
      label: "Вспомогательное оборудование",
      hint: "Зарядные станции",
      cell: (c) => (c.nInfra === null ? "-" : `${c.nInfra} ед.`),
    },
    {
      label: "CAPEX",
      hint: "7 статей + резерв 10%",
      cell: (c) => formatRub(c.capex),
      delta: true,
      deltaValue: (c) => c.capex,
      deltaBase: () => base?.capex,
    },
    {
      label: "OPEX годовой",
      hint: "Роботизированные: 7 статей; базовый: ФОТ целевых групп × K_начислений - прочие текущие расходы в baseline не учтены",
      cell: (c) => formatRub(c.opexYear),
      delta: true,
      deltaValue: (c) => c.opexYear,
      deltaBase: () => base?.opexYear,
      // подпись платежей RaaS за выбранный состав
      afterCell: (c) =>
        c.type === "raas" && c.calculated && c.selectedRobots !== null ? (
          <span className="block text-xs text-slate-500 dark:text-slate-400">
            вкл. платежи RaaS за выбранный состав ({c.selectedRobots} ед.)
          </span>
        ) : null,
    },
    {
      label: "ΔOPEX к базовому",
      hint: "Отрицательное - экономия",
      cell: (c) => formatRub(c.opexDelta),
    },
    {
      label: "ΔFOT (экономия ФОТ)",
      hint: "Годовой OPEX минус базовый",
      cell: (c) => formatRub(c.deltaFot),
    },
    {
      label: "Годовой эффект после амортизации",
      hint: "Effect_gross − Амортизация (− аннуитет, если кредит); не является денежным потоком",
      cell: (c) => formatRub(c.effectYear),
      delta: true,
      deltaValue: (c) => c.effectYear,
      deltaBase: () => base?.effectYear,
    },
    {
      label: "Окупаемость",
      hint: "Интерпретация: до 3 / 3–5 / более 5 лет",
      cell: paybackCell,
    },
    {
      label: `ROI за ${horizonYears ?? 5} лет`,
      hint: "Сравнение по TCO на горизонте",
      cell: roiCell,
    },
    {
      label: `TCO за ${horizonYears ?? 5} лет`,
      hint: "RaaS: платежи × контракт + электроэнергия/связь/персонал × горизонт",
      cell: (c) => formatRub(c.tco),
      delta: true,
      deltaValue: (c) => c.tco,
      deltaBase: () => base?.tco,
    },
  ];

  // плашка «экономика - по выбранному составу, рекомендуем -
  // по формуле» для рассчитанных роботизированных сценариев
  const compositionNotes = scenarios.filter(
    (s) =>
      s.calculated &&
      s.type !== "base" &&
      s.selectedRobots !== null &&
      s.requiredRobots !== null &&
      s.requiredRobots !== undefined,
  );

  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      {compositionNotes.length > 0 ? (
        <div className="border-b border-slate-200 bg-slate-50 px-4 py-3 dark:border-slate-800 dark:bg-slate-800">
          {compositionNotes.map((s) => (
            <p
              key={s.type}
              className="text-xs text-slate-600 dark:text-slate-300"
            >
              <span className="font-medium">{s.name}:</span> экономика
              считается для выбранного состава ({s.selectedRobots} ед.). По
              пиковой нагрузке рекомендуется {s.requiredRobots} ед
              {s.underpowered
                ? " - парк меньше требуемого, расчёт не отражает достижение заявленной производительности."
                : "."}
            </p>
          ))}
        </div>
      ) : null}
      <table className="w-full min-w-[720px] text-sm">
        <thead>
          <tr className="border-b border-slate-200 bg-slate-50 text-left dark:border-slate-800 dark:bg-slate-800">
            <th className="px-4 py-3 font-medium text-slate-600 dark:text-slate-300">Показатель</th>
            {scenarios.map((s) => (
              <th key={s.type} className="px-4 py-3 font-semibold text-slate-800 dark:text-slate-200">
                {s.name}
                {s.calculated ? null : (
                  <span className="ml-1 block text-xs font-normal text-amber-600 dark:text-amber-400">
                    не рассчитан
                  </span>
                )}
                {/* бейдж «Парк меньше требуемого» */}
                {s.calculated && s.underpowered ? (
                  <span className="ml-1 inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">
                    Парк меньше требуемого
                  </span>
                ) : null}
                {/* нейтральная плашка превышения - не ошибка,
 предупреждений в warnings нет (возможна переплата) */}
                {s.calculated &&
                s.overpowered &&
                s.selectedRobots !== null &&
                s.requiredRobots !== null ? (
                  <span className="ml-1 inline-flex items-center rounded-full bg-sky-100 px-2 py-0.5 text-xs font-medium text-sky-800 dark:bg-sky-900/40 dark:text-sky-300">
                    Парк превышает рекомендуемый на{" "}
                    {s.selectedRobots - s.requiredRobots} ед. - возможна
                    переплата
                  </span>
                ) : null}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.label} className="border-b border-slate-100 last:border-0 dark:border-slate-800">
              <td className="px-4 py-2.5 text-slate-600 dark:text-slate-300">
                {row.label}
                {row.hint ? (
                  <span className="block text-xs text-slate-400 dark:text-slate-500">{row.hint}</span>
                ) : null}
              </td>
              {scenarios.map((column) => (
                <td key={column.type} className="px-4 py-2.5 text-slate-900 dark:text-slate-100">
                  {column.calculated ? (
                    <span>
                      {row.cell(column)}
                      {row.afterCell ? row.afterCell(column) : null}
                      {row.delta && column.type !== "base" && base?.calculated ? (
                        <span className="block text-xs text-slate-500 dark:text-slate-400">
                          Δ к базовому:{" "}
                          {deltaCell(
                            row.deltaValue ? row.deltaValue(column) : column.tco,
                            row.deltaBase ? row.deltaBase() : base?.tco,
                          )}{" "}
                          ₽
                        </span>
                      ) : null}
                    </span>
                  ) : (
                    <span className="text-slate-400 dark:text-slate-500">-</span>
                  )}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {robotized.some((s) => s.warnings.length > 0) ? (
        <div className="border-t border-amber-200 bg-amber-50 px-4 py-3 dark:border-amber-800 dark:bg-amber-950/40">
          <p className="text-xs font-medium text-amber-800 dark:text-amber-300">Предупреждения расчёта</p>
          <ul className="mt-1 list-disc space-y-0.5 pl-4 text-xs text-amber-800 dark:text-amber-300">
            {robotized.flatMap((s) =>
              s.warnings.map((w, i) => (
                <li key={`${s.type}-${i}`}>
                  <span className="font-medium">{s.name}:</span> {w}
                </li>
              )),
            )}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
