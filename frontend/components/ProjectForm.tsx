"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { DictItem } from "@/types/solution";
import type { ProjectFull } from "@/types/project";

/**
 * Форма проекта (создание/редактирование,; user-flow.md шаг 1).
 * Клиентский компонент: валидация на клиенте (границы совпадают с
 * backend-DTO: имя 1-128, описание до 1024), ошибки сервера (409
 * «имя занято», 400) - из тела ответа, на русском.
 *
 * Редактирование: тип объекта НЕ меняется (задаёт набор параметров
 * проекта - object_type_parameter); для смены типа служит копия.
 */
export default function ProjectForm({
  objectTypes,
  project,
}: {
  objectTypes: DictItem[];
  project?: ProjectFull;
}) {
  const router = useRouter();
  const isEdit = project !== undefined;

  const [name, setName] = useState(project?.name ?? "");
  const [description, setDescription] = useState(project?.description ?? "");
  const [objectTypeId, setObjectTypeId] = useState<number | null>(
    project?.objectTypeId ?? null,
  );
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function validate(): string | null {
    if (name.trim().length < 1 || name.trim().length > 128) {
      return "Имя проекта: от 1 до 128 символов";
    }
    if (description.length > 1024) {
      return "Описание: до 1024 символов";
    }
    if (!isEdit && objectTypeId === null) {
      return "Выберите тип объекта";
    }
    return null;
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    const validationError = validate();
    if (validationError !== null) {
      setError(validationError);
      return;
    }
    setError(null);
    setSaving(true);

    try {
      let saved: ProjectFull;
      if (isEdit) {
        const response = await fetch(`/api/projects/${project.id}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            name: name.trim(),
            description: description.trim() === "" ? undefined : description.trim(),
          }),
        });
        if (!response.ok) {
          throw new Error(await errorMessage(response));
        }
        saved = (await response.json()) as ProjectFull;
      } else {
        const response = await fetch("/api/projects", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            name: name.trim(),
            description: description.trim() === "" ? undefined : description.trim(),
            objectTypeId,
          }),
        });
        if (!response.ok) {
          throw new Error(await errorMessage(response));
        }
        saved = (await response.json()) as ProjectFull;
      }
      // после создания/сохранения - на карточку проекта
      router.push(`/projects/${saved.id}`);
      router.refresh();
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : "Ошибка сохранения");
      setSaving(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex max-w-xl flex-col gap-4" noValidate>
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Имя проекта</span>
        <input
          type="text"
          value={name}
          onChange={(event) => setName(event.target.value)}
          maxLength={128}
          required
          placeholder="Например: Склад в Химках"
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm dark:border-slate-700 dark:text-slate-100"
        />
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Тип объекта</span>
        {isEdit ? (
          <p className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300">
            {project.objectTypeName}
            <span className="mt-1 block text-xs text-slate-500 dark:text-slate-400">
              Тип объекта нельзя изменить после создания: он задаёт набор
              параметров проекта. Нужен другой тип - скопируйте проект.
            </span>
          </p>
        ) : (
          <select
            value={objectTypeId ?? ""}
            onChange={(event) =>
              setObjectTypeId(event.target.value === "" ? null : Number(event.target.value))
            }
            required
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="" disabled>
              Выберите тип объекта
            </option>
            {objectTypes.map((type) => (
              <option key={type.id} value={type.id}>
                {type.name}
              </option>
            ))}
          </select>
        )}
      </label>

      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">
          Описание <span className="font-normal text-slate-400 dark:text-slate-500">(необязательно)</span>
        </span>
        <textarea
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          maxLength={1024}
          rows={4}
          placeholder="Назначение объекта, зоны, особенности процессов"
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm dark:border-slate-700 dark:text-slate-100"
        />
      </label>

      {error !== null && (
        <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800 dark:border-red-800 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </p>
      )}

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={saving}
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white
                     transition hover:bg-slate-700 disabled:opacity-50 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
        >
          {saving ? "Сохранение…" : isEdit ? "Сохранить" : "Создать проект"}
        </button>
        <button
          type="button"
          onClick={() => router.back()}
          disabled={saving}
          className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700
                     transition hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Отмена
        </button>
      </div>
    </form>
  );
}

/** Сообщение об ошибке из тела ответа (ErrorResponse.message). */
async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    if (body?.message) {
      return body.message;
    }
  } catch {
    // не JSON - общий текст
  }
  return `Ошибка запроса (HTTP ${response.status})`;
}
