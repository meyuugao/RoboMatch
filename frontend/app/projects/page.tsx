import type { Metadata } from "next";
import Link from "next/link";
import ProjectActions from "@/components/ProjectActions";
import { fetchProjects } from "@/lib/api";
import { requireUser } from "@/lib/auth";
import {
  formatProjectDate,
  PROJECT_STATUS_LABELS,
  type ProjectStatus,
} from "@/types/project";

/**
 * Список проектов пользователя (;
 * user-flow.md шаги 1–2). Серверный компонент: requireUser —
 * server-side redirect на /login без сессии; данные — через BFF
 * с httpOnly-сессией. Сортировку задаёт backend (обновлённые сверху),
 * здесь — только пагинация.
 */
export const metadata: Metadata = {
  title: "Мои проекты",
};

export default async function ProjectsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await requireUser();
  const raw = await searchParams;
  const pageParam = typeof raw.page === "string" ? Number(raw.page) : 0;
  const page = Number.isInteger(pageParam) && pageParam > 0 ? pageParam : 0;
  // redirect из requireAdmin: понятное сообщение,
  // почему пользователя вернули к его проектам
  const forbiddenAdmin = raw.forbidden === "admin";

  let projects: Awaited<ReturnType<typeof fetchProjects>> | null = null;
  let loadError: string | null = null;
  try {
    projects = await fetchProjects(page);
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Неизвестная ошибка загрузки";
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Мои проекты</h1>
          <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
            Объекты роботизации: параметры, подбор решений и расчёт
            экономического эффекта.
          </p>
        </div>
        <Link
          href="/projects/new"
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white dark:bg-slate-100 dark:text-slate-900
                     transition hover:bg-slate-700 dark:hover:bg-slate-200"
        >
          Создать проект
        </Link>
      </div>

      {forbiddenAdmin && (
        <div
          role="note"
          className="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900
                     dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
        >
          Раздел «Управление» доступен только роли «Администратор».
        </div>
      )}

      {loadError !== null && (
        <div
          role="alert"
          className="rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-800
                     dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
        >
          <p className="font-semibold">Ошибка загрузки проектов</p>
          <p className="mt-1">{loadError}</p>
          <p className="mt-2 text-red-600 dark:text-red-400">
            Проверьте: запущен ли backend (порт 8080) и БД (docker compose up -d db).
          </p>
        </div>
      )}

      {projects !== null && projects.content.length === 0 && page === 0 && (
        <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center
                     dark:border-slate-700 dark:bg-slate-900">
          <p className="font-semibold text-slate-700 dark:text-slate-300">У вас пока нет проектов</p>
          <p className="mx-auto mt-2 max-w-md text-sm text-slate-500 dark:text-slate-400">
            Создайте первый проект: выберите тип объекта (склад, аэропорт,
            медицинское учреждение), введите параметры — и платформа
            подберёт подходящие роботизированные решения.
          </p>
          <Link
            href="/projects/new"
            className="mt-4 inline-block rounded-lg bg-slate-900 px-4 py-2 text-sm
                       font-medium text-white dark:bg-slate-100 dark:text-slate-900
                       transition hover:bg-slate-700 dark:hover:bg-slate-200"
          >
            Создать проект
          </Link>
        </div>
      )}

      {/* страница за пределами диапазона (например, ?page=999 после
 удалений): не «нет проектов», а честная пустая страница */}
      {projects !== null && projects.content.length === 0 && page > 0 && (
        <div className="rounded-xl border border-slate-200 bg-white p-8 text-center text-sm
                     text-slate-600 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300">
          <p className="font-medium text-slate-900 dark:text-slate-100">На этой странице проектов нет</p>
          <p className="mt-2">
            Возможно, последние проекты были удалены.
          </p>
          <Link
            href="/projects"
            className="mt-4 inline-block rounded-lg border border-slate-300 px-4 py-2 dark:border-slate-700
                       text-slate-700 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
          >
            К первой странице
          </Link>
        </div>
      )}

      {projects !== null && projects.content.length > 0 && (
        <div className="flex flex-col gap-3">
          {projects.content.map((project) => (
            <div
              key={project.id}
              className="flex flex-wrap items-center justify-between gap-3
                         rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
            >
              <div className="min-w-0">
                <Link
                  href={`/projects/${project.id}`}
                  className="font-medium text-slate-900 hover:underline dark:text-slate-100"
                >
                  {project.name}
                </Link>
                <p className="mt-0.5 text-sm text-slate-500 dark:text-slate-400">
                  {project.objectTypeName}
                  {" · "}
                  {PROJECT_STATUS_LABELS[project.status as ProjectStatus] ?? project.status}
                  {" · изменён "}
                  {formatProjectDate(project.updatedAt)}
                </p>
                {project.description !== null && project.description !== "" && (
                  <p className="mt-1 line-clamp-2 max-w-2xl text-sm text-slate-600 dark:text-slate-300">
                    {project.description}
                  </p>
                )}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {/* «Управление» — на карточку проекта
 (параметры/подбор/сценарии/экономика), а не на /edit —
 редактирование параметров доступно внутри карточки */}
                <Link
                  href={`/projects/${project.id}`}
                  className="rounded-lg border border-slate-300 px-3 py-1.5 dark:border-slate-700 text-sm
                             text-slate-700 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
                >
                  Управление
                </Link>
                <ProjectActions projectId={project.id} projectName={project.name} />
              </div>
            </div>
          ))}

          {/* пагинация: единственный параметр списка — номер страницы */}
          {projects.totalPages > 1 && (
            <nav
              className="flex flex-wrap items-center justify-center gap-2"
              aria-label="Пагинация проектов"
            >
              {page > 0 && (
                <Link
                  href={page - 1 === 0 ? "/projects" : `/projects?page=${page - 1}`}
                  className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 dark:border-slate-700 dark:bg-slate-900
                             text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
                >
                  Назад
                </Link>
              )}
              <span className="px-2 text-sm text-slate-600 dark:text-slate-300">
                Страница {page + 1} из {projects.totalPages}
              </span>
              {page + 1 < projects.totalPages && (
                <Link
                  href={`/projects?page=${page + 1}`}
                  className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 dark:border-slate-700 dark:bg-slate-900
                             text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
                >
                  Вперёд
                </Link>
              )}
            </nav>
          )}
        </div>
      )}
    </div>
  );
}
