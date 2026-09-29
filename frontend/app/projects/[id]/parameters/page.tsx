import type { Metadata } from "next";
import Link from "next/link";
import ParameterForm from "@/components/ParameterForm";
import ParameterImport from "@/components/ParameterImport";
import { fetchAttachments, fetchParameters, fetchProject } from "@/lib/api";
import { requireUser } from "@/lib/auth";
import type { Attachment, ParameterDto } from "@/types/parameter";

/**
 * Страница параметров объекта (;
 * user-flow.md шаг 2): форма ручного ввода по метаданным + импорт
 * Excel/CSV по шаблону + история вложений. Серверный компонент собирает
 * начальные данные; интерактив - в ParameterForm/ParameterImport.
 */
export const metadata: Metadata = {
  title: "Параметры объекта",
};

export default async function ProjectParametersPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireUser();
  const { id } = await params;

  let parameters: ParameterDto[] = [];
  let attachments: Attachment[] = [];
  let projectName: string | null = null;
  let objectTypeName: string | null = null;
  let loadError: string | null = null;

  try {
    const [project, paramsResult, attachmentsResult] = await Promise.all([
      fetchProject(id),
      fetchParameters(id),
      fetchAttachments(id),
    ]);
    projectName = project.name;
    objectTypeName = project.objectTypeName;
    parameters = paramsResult;
    attachments = attachmentsResult;
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Неизвестная ошибка загрузки";
  }

  if (loadError !== null) {
    return (
      <div className="flex flex-col gap-4">
        <div
          role="alert"
          className="rounded-xl border border-slate-200 bg-white p-8 text-center text-sm text-slate-600
                     dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300"
        >
          <p className="font-medium text-slate-900 dark:text-slate-100">Не удалось открыть параметры</p>
          <p className="mt-2">{loadError}</p>
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

  const filledCount = parameters.filter((p) => p.currentValue !== null).length;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href={`/projects/${id}`}
          className="text-sm text-slate-600 hover:text-slate-900 hover:underline dark:text-slate-300 dark:hover:text-slate-100"
        >
          ← {projectName}
        </Link>
        <div className="mt-1 flex flex-wrap items-baseline gap-2">
          <h1 className="text-2xl font-bold">Параметры объекта</h1>
          <span className="text-sm text-slate-500 dark:text-slate-400">
            {objectTypeName} · заполнено {filledCount} из {parameters.length}
          </span>
        </div>
        <p className="mt-2 max-w-3xl text-sm text-slate-600 dark:text-slate-300">
          Заполните характеристики объекта вручную или загрузите Excel/CSV
          по шаблону. Значения по умолчанию и источники нормативов указаны
          в подсказках полей. Обязательные параметры отмечены звёздочкой -
          без них расчёт экономического эффекта не запустится.
        </p>
      </div>

      <ParameterForm projectId={id} parameters={parameters} />

      <ParameterImport projectId={id} initialAttachments={attachments} />
    </div>
  );
}
