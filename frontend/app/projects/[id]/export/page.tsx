import type { Metadata } from "next";
import Link from "next/link";
import ExportPanel from "@/components/ExportPanel";
import { fetchComparison, fetchProject, listExports } from "@/lib/api";
import { requireUser } from "@/lib/auth";
import type { ExportDto } from "@/types/export";

/**
 * Страница экспорта отчётов ( шаг 8,
 * 3.7.1–3.7.5): генерация PDF (итоговый отчёт) и Excel/CSV (расчётные
 * таблицы), история выгрузок, пометка о предварительной оценке.
 * Серверный компонент; интерактив — ExportPanel.
 */
export const metadata: Metadata = {
  title: "Экспорт отчёта",
};

export default async function ExportPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireUser();
  const { id } = await params;

  let projectName = "";
  let exportsList: ExportDto[] = [];
  let calculated = false;
  let isWarehouse = true;
  let comparisonUnavailable = false;
  let loadError: string | null = null;
  try {
    const project = await fetchProject(id);
    projectName = project.name;
    // не-склад: экспорт недоступен по гейту (как экономика и имитация)
    // — честное сообщение вместо ложного «не рассчитана»
    isWarehouse = project.objectTypeIsCalcEnabled;
    const [comparison, history] = await Promise.all([
      fetchComparison(id).catch(() => null),
      listExports(id).catch(() => [] as ExportDto[]),
    ]);
    exportsList = history;
    // при недоступности сравнения не утверждаем «не рассчитана» —
    // сервер скажет точно при генерации
    comparisonUnavailable = isWarehouse && comparison === null;
    calculated =
      comparison?.scenarios?.some((scenario) => scenario.calculated)
      ?? false;
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  if (loadError !== null) {
    return (
      <div className="flex flex-col gap-4">
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 p-6 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <p className="font-medium">
            Не удалось загрузить страницу экспорта
          </p>
          <p className="mt-1">{loadError}</p>
        </div>
        <Link
          href={`/projects/${id}`}
          className="text-sm text-slate-600 hover:underline dark:text-slate-300"
        >
          ← К карточке проекта
        </Link>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <h1 className="text-2xl font-bold">Экспорт отчёта</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            Проект «{projectName}». Итоговый отчёт PDF и расчётные таблицы
            Excel/CSV для обсуждения с руководством, техническими
            специалистами или потенциальными поставщиками.
          </p>
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <Link
            href={`/projects/${id}/economics`}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800"
          >
            Экономика и сравнение
          </Link>
          <Link
            href={`/projects/${id}`}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800"
          >
            К карточке проекта
          </Link>
        </div>
      </div>

      {/* Пометка о статусе результата — видна сразу */}
      <div
        role="note"
        data-preliminary-note
        className="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-200"
      >
        <strong>Предварительная оценка — требует верификации при
        обследовании объекта.</strong>{" "}
        Результат расчёта не заменяет детальное проектирование; точность
        зависит от полноты входных данных. Та же пометка включена в каждый
        сгенерированный отчёт — на титуле и в конце.
      </div>

      <ExportPanel
        projectId={id}
        initialExports={exportsList}
        calculated={calculated}
        isWarehouse={isWarehouse}
        comparisonUnavailable={comparisonUnavailable}
      />
    </div>
  );
}
