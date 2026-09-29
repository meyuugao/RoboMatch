import type { Metadata } from "next";
import Link from "next/link";
import CalculateAllButton from "@/components/CalculateAllButton";
import ScenarioComposition from "@/components/ScenarioComposition";
import { fetchScenarios } from "@/lib/api";
import { requireUser } from "@/lib/auth";
import {
  SCENARIO_TYPE_LABELS,
  formatCalcDate,
  formatRub,
  type ScenarioDetails,
} from "@/types/economics";

/**
 * Страница сценариев проекта:
 * список сценариев base/purchase/raas, состав оборудования каждого
 * (результат подбора + ручные добавления) с точечным редактированием
 * (количество + удаление - компонент ScenarioComposition).
 * Единая кнопка «Рассчитать и перейти к дашборду →» вместо отдельных
 * «Рассчитать X» - считает все три сценария подряд
 * (base → purchase → raas) и открывает дашборд сравнения. Базовый
 * сценарий без состава - по определению. Серверный компонент.
 */
export const metadata: Metadata = {
  title: "Сценарии расчёта",
};

export default async function ScenariosPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireUser();
  const { id } = await params;

  let scenarios: ScenarioDetails[] | null = null;
  let loadError: string | null = null;
  try {
    scenarios = await fetchScenarios(id);
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  if (scenarios === null) {
    return (
      <div className="flex flex-col gap-4">
        <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-6 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300">
          <p className="font-medium">Не удалось загрузить сценарии</p>
          <p className="mt-1">{loadError}</p>
        </div>
        <Link href={`/projects/${id}`} className="text-sm text-slate-600 hover:underline dark:text-slate-300">
          ← К карточке проекта
        </Link>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      {/* заголовок слева, кнопка расчёта -
 по правому краю ТОЙ ЖЕ строки. Блоку заголовка нужен
 min-w-0 + flex-1: длинный подзаголовок иначе растягивает его
 на всю ширину и при flex-wrap кнопка падает на вторую строку
 к левому краю (замер живого рендера: кнопка left=80px). */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="text-2xl font-bold">Сценарии и расчёт</h1>
          <p className="mt-1 max-w-3xl text-sm text-slate-500 dark:text-slate-400">
            Три сценария сравнения: базовый процесс, покупка
            оборудования и RaaS. Состав - из подбора и ручных добавлений.
          </p>
        </div>
        <CalculateAllButton
          projectId={id}
          scenarios={scenarios.map((s) => ({
            id: s.id,
            type: s.type,
            name: s.name,
            solutionCount: s.solutions.reduce(
              (sum, sol) => sum + sol.quantity,
              0,
            ),
          }))}
        />
      </div>

      {scenarios.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-6 text-center text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">
          <p className="font-medium text-slate-800 dark:text-slate-200">Сценариев пока нет</p>
          <p className="mt-1">
            Запустите подбор решений - он создаст три сценария автоматически.
          </p>
          <Link
            href={`/projects/${id}/selection`}
            className="mt-3 inline-block rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
          >
            К подбору решений
          </Link>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          {scenarios.map((scenario) => {
            const totalRobots = scenario.solutions.reduce(
              (sum, s) => sum + s.quantity,
              0,
            );
            const totalSum = scenario.solutions.reduce(
              (sum, s) => sum + (s.sumRub ?? 0),
              0,
            );
            return (
              <section
                key={scenario.id}
                className="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
              >
                <div>
                  <p className="text-xs font-medium uppercase tracking-wide text-slate-400 dark:text-slate-500">
                    {SCENARIO_TYPE_LABELS[scenario.type] ?? scenario.type}
                  </p>
                  <h2 className="mt-0.5 font-semibold text-slate-900 dark:text-slate-100">
                    {scenario.name}
                  </h2>
                </div>

                <div className="text-sm text-slate-600 dark:text-slate-300">
                  <p>
                    Состав:{" "}
                    {scenario.solutions.length === 0 ? (
                      <span className="text-slate-400 dark:text-slate-500">пусто</span>
                    ) : (
                      <span className="font-medium text-slate-900 dark:text-slate-100">
                        {scenario.solutions.length} позиц. / {totalRobots} ед.
                      </span>
                    )}
                  </p>
                  <p className="mt-0.5">
                    Стоимость состава:{" "}
                    <span className="font-medium text-slate-900 dark:text-slate-100">
                      {scenario.solutions.length === 0
                        ? "-"
                        : formatRub(totalSum)}
                    </span>
                  </p>
                  {scenario.lastCalculatedAt !== null && (
                    <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                      Последний расчёт: {formatCalcDate(scenario.lastCalculatedAt)}
                    </p>
                  )}
                </div>

                {scenario.compositionChanged && scenario.lastCalculatedAt !== null && (
                  <p
                    role="status"
                    className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800
                               dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
                  >
                    Состав изменён с момента расчёта
                    (от {formatCalcDate(scenario.lastCalculatedAt)}) - пересчитайте
                    сценарий, чтобы дашборд экономики отражал текущий состав.
                  </p>
                )}

                <ScenarioComposition
                  projectId={id}
                  scenarioId={scenario.id}
                  scenarioType={scenario.type}
                  solutions={scenario.solutions}
                />
              </section>
            );
          })}
        </div>
      )}

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
