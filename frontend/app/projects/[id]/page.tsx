import type { Metadata } from "next";
import Link from "next/link";
import ProjectActions from "@/components/ProjectActions";
import { fetchProject } from "@/lib/api";
import { requireUser } from "@/lib/auth";
import {
  formatProjectDate,
  PROJECT_STATUS_LABELS,
  type ProjectStatus,
} from "@/types/project";

/**
 * Карточка проекта: метаданные, действия
 * (редактировать/копировать/удалить), входы к разделам - параметры
 * объекта, подбор, сценарии, имитация, экспорт.
 * Серверный компонент; 404 отдаёт понятную страницу (не пустой экран).
 */
export const metadata: Metadata = {
  title: "Карточка проекта",
};

export default async function ProjectCardPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireUser();
  const { id } = await params;

  let project: Awaited<ReturnType<typeof fetchProject>> | null = null;
  let loadError: string | null = null;
  try {
    project = await fetchProject(id);
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Неизвестная ошибка загрузки";
  }

  if (project === null) {
    return (
      <div className="flex flex-col gap-4">
        <div
          role="alert"
          className="rounded-xl border border-slate-200 bg-white p-8 text-center text-sm text-slate-600
                     dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300"
        >
          <p className="font-medium text-slate-900 dark:text-slate-100">Проект не найден</p>
          <p className="mt-2">
            Возможно, он удалён или принадлежит другому пользователю.
          </p>
          <Link
            href="/projects"
            className="mt-4 inline-block rounded-lg border border-slate-300 px-4 py-2 dark:border-slate-700
                       text-slate-700 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
          >
            К списку проектов
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-bold">{project.name}</h1>
            <span className="rounded-full border border-slate-300 px-2.5 py-0.5 text-xs text-slate-600 dark:border-slate-700 dark:text-slate-300">
              {PROJECT_STATUS_LABELS[project.status as ProjectStatus] ?? project.status}
            </span>
          </div>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            {project.objectTypeName}
            {project.objectTypeIsCalcEnabled
              ? " · расчёт экономического эффекта доступен"
              : " · расчёт экономического эффекта недоступен для этого типа объекта"}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Link
            href={`/projects/${project.id}/edit`}
            className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white
                       transition hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
          >
            Редактировать
          </Link>
          <ProjectActions
            projectId={project.id}
            projectName={project.name}
            redirectAfterDelete="/projects"
          />
        </div>
      </div>

      {project.description !== null && project.description !== "" && (
        <p className="rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300">
          {project.description}
        </p>
      )}

      <dl className="grid grid-cols-1 gap-3 rounded-xl border border-slate-200 bg-white p-4
                      text-sm sm:grid-cols-2 dark:border-slate-800 dark:bg-slate-900"
      >
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Создан</dt>
          <dd className="mt-0.5 text-slate-900 dark:text-slate-100">{formatProjectDate(project.createdAt)}</dd>
        </div>
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Последнее изменение</dt>
          <dd className="mt-0.5 text-slate-900 dark:text-slate-100">{formatProjectDate(project.updatedAt)}</dd>
        </div>
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Тип объекта</dt>
          <dd className="mt-0.5 text-slate-900 dark:text-slate-100">{project.objectTypeName}</dd>
        </div>
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Статус</dt>
          <dd className="mt-0.5 text-slate-900 dark:text-slate-100">
            {PROJECT_STATUS_LABELS[project.status as ProjectStatus] ?? project.status}
          </dd>
        </div>
      </dl>

      {/* Шаги пути: параметры, подбор, экономика и имитация */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <Link
          href={`/projects/${project.id}/parameters`}
          className="rounded-xl border border-slate-300 bg-white p-4 text-sm transition
                     hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900
                     dark:hover:border-slate-600 dark:hover:bg-slate-800"
        >
          <p className="font-medium text-slate-700 dark:text-slate-300">Параметры объекта</p>
          <p className="mt-1 text-slate-500 dark:text-slate-400">
            Характеристики и процессы объекта: ручной ввод или импорт
            Excel/CSV.
          </p>
          <p className="mt-2 text-xs font-medium text-sky-700 dark:text-sky-400">
            Заполнить параметры →
          </p>
        </Link>
        <Link
          href={`/projects/${project.id}/selection`}
          className="rounded-xl border border-slate-300 bg-white p-4 text-sm transition
                     hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900
                     dark:hover:border-slate-600 dark:hover:bg-slate-800"
        >
          <p className="font-medium text-slate-700 dark:text-slate-300">Подбор решений</p>
          <p className="mt-1 text-slate-500 dark:text-slate-400">
            Подходящие решения с объяснением включения и исключения каждого,
            ранжирование и добавление в сценарий.
          </p>
          <p className="mt-2 text-xs font-medium text-sky-700 dark:text-sky-400">
            Запустить подбор →
          </p>
        </Link>
        <Link
          href={`/projects/${project.id}/scenarios`}
          className="rounded-xl border border-slate-300 bg-white p-4 text-sm transition
                     hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900
                     dark:hover:border-slate-600 dark:hover:bg-slate-800"
        >
          <p className="font-medium text-slate-700 dark:text-slate-300">Экономика и сценарии</p>
          <p className="mt-1 text-slate-500 dark:text-slate-400">
            Базовый сценарий, покупка и RaaS: CAPEX, OPEX, TCO, окупаемость,
            ROI и чувствительность.
          </p>
          <p className="mt-2 text-xs font-medium text-sky-700 dark:text-sky-400">
            Рассчитать и сравнить →
          </p>
        </Link>
        <Link
          href={`/projects/${project.id}/simulation`}
          className="rounded-xl border border-slate-300 bg-white p-4 text-sm transition
                     hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900
                     dark:hover:border-slate-600 dark:hover:bg-slate-800"
        >
          <p className="font-medium text-slate-700 dark:text-slate-300">Имитация 2D и KPI</p>
          <p className="mt-1 text-slate-500 dark:text-slate-400">
            2D-схема склада с роботами: производительность, загрузка,
            простои и узкие места.
          </p>
          <p className="mt-2 text-xs font-medium text-sky-700 dark:text-sky-400">
            Открыть имитацию →
          </p>
        </Link>
        <Link
          href={`/projects/${project.id}/export`}
          className="rounded-xl border border-slate-300 bg-white p-4 text-sm transition
                     hover:border-slate-400 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900
                     dark:hover:border-slate-600 dark:hover:bg-slate-800"
        >
          <p className="font-medium text-slate-700 dark:text-slate-300">Экспорт отчёта</p>
          <p className="mt-1 text-slate-500 dark:text-slate-400">
            Итоговый отчёт PDF и расчётные таблицы Excel/CSV с параметрами,
            решениями, экономикой и источниками данных.
          </p>
          <p className="mt-2 text-xs font-medium text-sky-700 dark:text-sky-400">
            Сформировать отчёт →
          </p>
        </Link>
      </div>

      <div>
        <Link
          href="/projects"
          className="text-sm text-slate-600 hover:text-slate-900 hover:underline dark:text-slate-300 dark:hover:text-slate-100"
        >
          ← К списку проектов
        </Link>
      </div>
    </div>
  );
}
