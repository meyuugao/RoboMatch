import type { Metadata } from "next";
import Link from "next/link";
import SimulationView from "@/components/SimulationView";
import { fetchProject, fetchScenarios, getSimulation } from "@/lib/api";
import { requireUser } from "@/lib/auth";
import type { SimulationRun } from "@/types/simulation";

/**
 * Страница имитации (,
 * 4.3.3; user-flow.md шаг 7): 2D-схема склада, KPI-панель, управление
 * запуском. Гейт типа объекта - is_calc_enabled (поддерживается склад):
 * не-склад - понятное сообщение вместо ошибки. Серверный
 * компонент: интерактив (запуск, анимация, скорость) - в SimulationView
 * (клиент).
 */
export const metadata: Metadata = {
  title: "Имитация 2D и KPI",
};

export default async function SimulationPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireUser();
  const { id } = await params;

  let isWarehouse = true;
  let scenarios: Awaited<ReturnType<typeof fetchScenarios>> | null = null;
  let loadError: string | null = null;
  try {
    const project = await fetchProject(id);
    isWarehouse = project.objectTypeIsCalcEnabled;
    if (isWarehouse) {
      scenarios = await fetchScenarios(id);
    }
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  // base скрыт (заведомо 400 - инвариант base, data_model.md §10.5);
  // сначала непустые сценарии, порядок покупки → RaaS
  const robotized = (scenarios ?? [])
    .filter((s) => s.type !== "base")
    .map((s) => ({
      id: s.id,
      name: s.name,
      type: s.type,
      solutionCount: s.solutions.reduce((sum, sol) => sum + sol.quantity, 0),
    }))
    .sort((a, b) => {
      const empty = (a.solutionCount === 0 ? 1 : 0)
        - (b.solutionCount === 0 ? 1 : 0);
      if (empty !== 0) {
        return empty;
      }
      return a.type === "purchase" ? -1 : 1;
    });

  let initialSimulation: SimulationRun | null = null;
  const initialScenarioId =
    robotized.find((s) => s.solutionCount > 0)?.id ?? robotized[0]?.id ?? null;
  if (initialScenarioId !== null) {
    try {
      initialSimulation = await getSimulation(id, initialScenarioId);
    } catch {
      // последний результат необязателен - страница покажет «Запустить»
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="text-2xl font-bold">Имитация 2D и KPI</h1>
          <p className="mt-1 max-w-3xl text-sm text-slate-500 dark:text-slate-400">
            Проверка достижимости заявленной производительности, загрузки
            оборудования, простоев и узких мест на 2D-схеме склада.
            Модель использует параметры выбранного сценария и
            подтверждает расчёт экономики.
          </p>
        </div>
        <Link
          href={`/projects/${id}/economics`}
          className="shrink-0 rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Экономика и сценарии
        </Link>
      </div>

      {loadError !== null ? (
        <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-6 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300">
          <p className="font-medium">Не удалось загрузить данные проекта</p>
          <p className="mt-1">{loadError}</p>
        </div>
      ) : !isWarehouse ? (
        <p className="rounded-xl border border-dashed border-amber-300 bg-amber-50 p-6 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300">
          Имитация доступна для объектов типа «склад». Для аэропорта
          и медицины пользуйтесь каталогом и подбором решений.
        </p>
      ) : (
        <SimulationView
          projectId={id}
          scenarios={robotized}
          initialScenarioId={initialScenarioId}
          initialSimulation={initialSimulation}
        />
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
