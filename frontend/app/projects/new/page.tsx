import type { Metadata } from "next";
import ProjectForm from "@/components/ProjectForm";
import { fetchFilters } from "@/lib/api";
import { requireUser } from "@/lib/auth";

/**
 * Создание проекта (user-flow.md шаг 1): имя, описание, выбор типа
 * объекта. Типы - из /api/filters (objectTypes, справочник БД - без
 * хардкода,. Серверный компонент: сессия обязательна,
 * форма - клиентская (ProjectForm).
 */
export const metadata: Metadata = {
  title: "Новый проект",
};

export default async function NewProjectPage() {
  await requireUser();

  let objectTypes: Awaited<ReturnType<typeof fetchFilters>>["objectTypes"] = [];
  let loadError: string | null = null;
  try {
    objectTypes = (await fetchFilters()).objectTypes;
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Неизвестная ошибка загрузки";
  }

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">Новый проект</h1>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
          Шаг 1 из 8: выберите тип объекта и назовите проект. Параметры
          объекта и подбор решений - следующие шаги.
        </p>
      </div>

      {loadError !== null ? (
        <div
          role="alert"
          className="rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-800
                     dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
        >
          <p className="font-semibold">Не удалось загрузить типы объектов</p>
          <p className="mt-1">{loadError}</p>
        </div>
      ) : (
        <ProjectForm objectTypes={objectTypes} />
      )}
    </div>
  );
}
