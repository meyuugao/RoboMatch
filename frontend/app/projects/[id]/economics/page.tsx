import type { Metadata } from "next";
import Link from "next/link";
import AdjustButton from "@/components/AdjustButton";
import AssumptionEditor from "@/components/AssumptionEditor";
import CalculationHistory from "@/components/CalculationHistory";
import EconomicsFormulas from "@/components/EconomicsFormulas";
import ScenarioComparison from "@/components/ScenarioComparison";
import SensitivityTable from "@/components/SensitivityTable";
import {
  fetchAssumptions,
  fetchCalculation,
  fetchComparison,
  fetchScenarios,
} from "@/lib/api";
import { requireUser } from "@/lib/auth";
import type {
  AssumptionDto,
  CalculationFull,
  ComparisonDto,
  ScenarioDetails,
} from "@/types/economics";
import { formatCalcDate, formatRub, METRIC_LABELS } from "@/types/economics";

/**
 * Дашборд экономики: таблица сравнения
 * трёх сценариев с Δ к базовому и интерпретацией окупаемости, блок
 * «Формулы и допущения», чувствительность, ручная
 * корректировка, история расчётов и редактор
 * допущений. Серверный компонент.
 */
export const metadata: Metadata = {
  title: "Экономика и сценарии",
};

export default async function EconomicsPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireUser();
  const { id } = await params;

  let comparison: ComparisonDto | null = null;
  let assumptions: AssumptionDto[] = [];
  let scenarios: ScenarioDetails[] = [];
  let loadError: string | null = null;
  try {
    [comparison, assumptions, scenarios] = await Promise.all([
      fetchComparison(id),
      fetchAssumptions(id),
      fetchScenarios(id),
    ]);
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  if (loadError !== null || comparison === null) {
    return (
      <div className="flex flex-col gap-4">
        <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-6 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300">
          <p className="font-medium">Не удалось загрузить экономику проекта</p>
          <p className="mt-1">{loadError}</p>
        </div>
        <Link href={`/projects/${id}`} className="text-sm text-slate-600 hover:underline dark:text-slate-300">
          ← К карточке проекта
        </Link>
      </div>
    );
  }

  const purchase = comparison.scenarios.find((s) => s.type === "purchase") ?? null;
  const raas = comparison.scenarios.find((s) => s.type === "raas") ?? null;
  // Чувствительность - для КАЖДОГО рассчитанного роботизированного
  // сценария
  const sensitivitySources = [purchase, raas].filter(
    (s): s is NonNullable<typeof s> => s !== null && s.calculated,
  );

  // Детальный расчёт покупки - для разбивки статей и корректировки
  let purchaseCalculation: CalculationFull | null = null;
  if (purchase?.calculationId) {
    try {
      purchaseCalculation = await fetchCalculation(id, purchase.calculationId);
    } catch {
      // детальная разбировка не критична для таблицы
    }
  }

  const anyCalculated = comparison.scenarios.some((s) => s.calculated);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        {/* Левый блок шапки: заголовок + основные действия сценария.
 Экспорт отчёта вынесен в правую группу: действие
 «забрать результат» отделено от действий «работать с расчётом». */}
        <div className="flex min-w-0 flex-col gap-3">
          <div>
            <h1 className="text-2xl font-bold">Экономика и сравнение сценариев</h1>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              Базовый сценарий, покупка и RaaS по единым показателям.
              Горизонт: {comparison.horizonYears ?? 5} лет.
              {comparison.versionModel
                ? ` Модель: ${comparison.versionModel}.`
                : ""}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <AdjustButton
              projectId={id}
              calculation={purchaseCalculation}
              disabled={!purchase?.calculated}
            />
            <Link
              href={`/projects/${id}/simulation`}
              className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
            >
              Имитация 2D и KPI
            </Link>
            <Link
              href={`/projects/${id}/scenarios`}
              className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
            >
              Сценарии и расчёт
            </Link>
          </div>
        </div>
        {/* Правая группа: экспорт на правом краю шапки; на узких экранах
 переносится на отдельную строку с выравниванием вправо (ml-auto) */}
        <div className="ml-auto flex shrink-0 items-center gap-2">
          <Link
            href={`/projects/${id}/export`}
            className="rounded-lg bg-sky-700 px-4 py-2 text-sm font-medium text-white transition hover:bg-sky-800"
          >
            Экспорт отчёта
          </Link>
        </div>
      </div>

      {!anyCalculated ? (
        <div className="rounded-xl border border-dashed border-amber-300 bg-amber-50 p-5 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300">
          <p className="font-medium">Расчётов пока нет</p>
          <p className="mt-1">
            Откройте «Сценарии и расчёт» и нажмите «Рассчитать и перейти к
            дашборду →» - все три сценария посчитаются подряд (до 10 секунд
            на сценарий).
          </p>
        </div>
      ) : (
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Последние расчёты:{" "}
          {comparison.scenarios
            .filter((s) => s.calculated)
            .map((s) => `${s.name} - ${formatCalcDate(s.calculatedAt)}`)
            .join("; ")}
          .
        </p>
      )}

      {/* состав изменён после последнего расчёта - данные на
 дашборде устарели; сверка текущего состава со снимком
 metrics_json последнего расчёта (ScenarioService) */}
      {scenarios
        .filter((s) => s.compositionChanged && s.lastCalculatedAt !== null)
        .map((s) => (
          <p
            key={s.id}
            role="status"
            className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900
                   dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
          >
            <span className="font-medium">Состав изменён с момента расчёта</span>{" "}
            (сценарий «{s.name}», расчёт от {formatCalcDate(s.lastCalculatedAt)}) -
            приведённые ниже показатели посчитаны для старого состава.
            Пересчитайте сценарий на странице «Сценарии и расчёт».
          </p>
        ))}

      <section aria-label="Сравнение сценариев">
        <h2 className="mb-3 text-lg font-semibold">Сравнение сценариев</h2>
        <ScenarioComparison
          scenarios={comparison.scenarios}
          horizonYears={comparison.horizonYears}
        />
      </section>

      <section aria-label="Чувствительность">
        <h2 className="mb-3 text-lg font-semibold">
          Чувствительность
        </h2>
        {sensitivitySources.length === 0 ? (
          <p className="text-sm text-slate-500 dark:text-slate-400">
            Рассчитайте покупку и/или RaaS - таблица чувствительности
            появится здесь.
          </p>
        ) : (
          <div className="flex flex-col gap-5">
            {sensitivitySources.map((source) => (
              <SensitivityTable
                key={source.scenarioId}
                rows={source.sensitivity}
                scenarioName={source.name}
              />
            ))}
          </div>
        )}
      </section>

      <section aria-label="Формулы и допущения">
        <h2 className="mb-3 text-lg font-semibold">
          Формулы и допущения
        </h2>
        <EconomicsFormulas
          purchase={purchase}
          calculation={purchaseCalculation}
        />
        {/* Журнал ручных корректировок (
 пользователю) */}
        {purchaseCalculation ? (
          <details
            className="mt-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
            open={
              (purchaseCalculation.adjustments?.length ?? 0) > 0 ||
              purchaseCalculation.details?.adjustedFrom != null
            }
          >
            <summary className="cursor-pointer text-sm font-medium text-slate-700 dark:text-slate-300">
              Журнал ручных корректировок
            </summary>
            <div className="mt-3 space-y-2 text-xs">
              {purchaseCalculation.details?.adjustedFrom ? (
                <p className="rounded-lg bg-amber-50 px-3 py-2 text-amber-900 dark:bg-amber-950/40 dark:text-amber-300">
                  Текущий расчёт №{purchaseCalculation.id} получен
                  корректировкой расчёта
                  №{purchaseCalculation.details.adjustedFrom.sourceCalculationId}:
                  метрика «{METRIC_LABELS[purchaseCalculation.details.adjustedFrom.metricName] ?? purchaseCalculation.details.adjustedFrom.metricName}»
                  - {formatRub(purchaseCalculation.details.adjustedFrom.originalValue)} → {formatRub(purchaseCalculation.details.adjustedFrom.newValue)}
                  ({purchaseCalculation.details.adjustedFrom.reason};
                  {" "}
                  {purchaseCalculation.details.adjustedFrom.authorLogin},{" "}
                  {formatCalcDate(purchaseCalculation.details.adjustedFrom.createdAt)})
                </p>
              ) : null}
              {(purchaseCalculation.adjustments?.length ?? 0) > 0 ? (
                purchaseCalculation.adjustments.map((a, i) => (
                  <p
                    key={i}
                    className="rounded-lg bg-slate-50 px-3 py-2 text-slate-700 dark:bg-slate-800 dark:text-slate-300"
                  >
                    {METRIC_LABELS[a.metricName] ?? a.metricName}: {" "}
                    {formatRub(a.originalValue)} → {formatRub(a.newValue)} -{" "}
                    {a.reason} ({a.authorLogin}, {formatCalcDate(a.createdAt)})
                  </p>
                ))
              ) : purchaseCalculation.details?.adjustedFrom == null ? (
                <p className="text-slate-500 dark:text-slate-400">
                  Корректировок нет. Измените метрику кнопкой
                  «Скорректировать метрику» - запись попадёт в этот журнал.
                </p>
              ) : null}
            </div>
          </details>
        ) : null}
        {purchaseCalculation && purchaseCalculation.assumptions.length > 0 ? (
          <details className="mt-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <summary className="cursor-pointer text-sm font-medium text-slate-700 dark:text-slate-300">
              Снимок допущений расчёта №{purchaseCalculation.id}
            </summary>
            <ul className="mt-3 space-y-1.5 text-xs">
              {purchaseCalculation.assumptions.map((a) => (
                <li key={a.name} className="rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-800">
                  <span className="font-medium text-slate-800 dark:text-slate-200">{a.title}</span>
                  {": "}
                  <span className="text-slate-700 dark:text-slate-300">
                    {a.value}
                    {a.unit ? ` ${a.unit}` : ""}
                  </span>
                  <span className="ml-1 text-slate-400 dark:text-slate-500">
                    ({a.sourceKind === "organizer_catalog" ? "каталог" : "проект"})
                  </span>
                  {a.impactNote ? (
                    <span className="mt-0.5 block text-slate-500 dark:text-slate-400">{a.impactNote}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          </details>
        ) : null}
      </section>

      <section aria-label="История расчётов">
        <CalculationHistory
          projectId={id}
          scenarios={scenarios.map((s) => ({
            id: s.id,
            name: s.name,
            type: s.type,
          }))}
        />
      </section>

      <section aria-label="Допущения">
        <h2 className="mb-3 text-lg font-semibold">
          Изменяемые допущения
        </h2>
        <AssumptionEditor projectId={id} assumptions={assumptions} />
      </section>

      <div>
        <Link
          href={`/projects/${id}`}
          className="text-sm text-slate-600 hover:text-slate-900 hover:underline dark:text-slate-300 dark:hover:text-slate-100"
        >
          ← К карточке проекта
        </Link>
      </div>
    </div>
  );
}
