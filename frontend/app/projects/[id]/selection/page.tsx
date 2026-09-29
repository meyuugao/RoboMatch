import type { Metadata } from "next";
import Link from "next/link";
import SelectionResults from "@/components/SelectionResults";
import { fetchProject, fetchSelection } from "@/lib/api";
import { requireUser } from "@/lib/auth";
import type { SelectionRunDto } from "@/types/selection";

/**
 * Страница подбора: список
 * применимых решений с причинами включения/исключения, ранжирование
 * с вкладом критериев, ручное добавление в сценарий.
 * Серверный компонент грузит проект и текущие результаты; интерактив
 * (запуск, фильтры, модалка) — клиентский SelectionResults.
 */
export const metadata: Metadata = {
  title: "Подбор решений",
};

export default async function SelectionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireUser();
  const { id } = await params;

  let project: Awaited<ReturnType<typeof fetchProject>> | null = null;
  let initial: SelectionRunDto = { scenarios: [], results: [] };
  let loadError: string | null = null;
  try {
    project = await fetchProject(id);
    initial = await fetchSelection(id);
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
          <h1 className="text-2xl font-bold">Подбор решений</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            Проект «{project.name}» · {project.objectTypeName}
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <Link
            href={`/projects/${project.id}/parameters`}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm
                       text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900
                       dark:text-slate-300 dark:hover:bg-slate-800"
          >
            Параметры объекта
          </Link>
          <Link
            href={`/projects/${project.id}`}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm
                       text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900
                       dark:text-slate-300 dark:hover:bg-slate-800"
          >
            К проекту
          </Link>
        </div>
      </div>

      {loadError !== null && (
        <p className="text-xs text-slate-400 dark:text-slate-500">
          Не удалось загрузить сохранённые результаты: {loadError}. Нажмите
          «Запустить подбор» — они пересоздадутся.
        </p>
      )}

      <SelectionResults projectId={String(project.id)} initial={initial} />

      <div>
        <Link
          href={`/projects/${project.id}`}
          className="text-sm text-slate-600 hover:text-slate-900 hover:underline dark:text-slate-300 dark:hover:text-slate-100"
        >
          ← К карточке проекта
        </Link>
      </div>
    </div>
  );
}
