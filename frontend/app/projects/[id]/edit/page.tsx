import type { Metadata } from "next";
import Link from "next/link";
import ProjectForm from "@/components/ProjectForm";
import { fetchProject } from "@/lib/api";
import { requireUser } from "@/lib/auth";

/**
 * Редактирование проекта: имя и описание; тип объекта
 * зафиксирован при создании (ProjectForm показывает его как текст).
 * 404 - понятная страница вместо пустого экрана.
 */
export const metadata: Metadata = {
  title: "Редактирование проекта",
};

export default async function EditProjectPage({
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
      <div
        role="alert"
        className="rounded-xl border border-slate-200 bg-white p-8 text-center text-sm text-slate-600
                     dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300"
      >
        <p className="font-medium text-slate-900 dark:text-slate-100">
          {loadError ?? "Проект не найден"}
        </p>
        <Link
          href="/projects"
          className="mt-4 inline-block rounded-lg border border-slate-300 px-4 py-2 dark:border-slate-700
                     text-slate-700 transition hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          К списку проектов
        </Link>
      </div>
    );
  }

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">Редактирование проекта</h1>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
          Имя и описание. Тип объекта неизменен - он задаёт набор параметров.
        </p>
      </div>
      <ProjectForm objectTypes={[]} project={project} />
    </div>
  );
}
