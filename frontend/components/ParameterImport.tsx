"use client";

import { useState } from "react";
import type {
  Attachment,
  ImportFailure,
  ImportResult,
} from "@/types/parameter";

/**
 * Импорт параметров из Excel/CSV + история вложений:
 * шаблон на скачивание (xlsx с расшифровкой колонок / csv), загрузка
 * файла с результатом (записано N значений или построчные ошибки),
 * удаление вложений. Ошибки атомарны: при ошибке в файле
 * данные не меняются - форма остаётся как есть.
 */
export default function ParameterImport({
  projectId,
  initialAttachments,
}: {
  projectId: string;
  initialAttachments: Attachment[];
}) {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [warnings, setWarnings] = useState<string[] | null>(null);
  const [failure, setFailure] = useState<ImportFailure | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attachments, setAttachments] = useState<Attachment[]>(initialAttachments);
  const [deletingId, setDeletingId] = useState<number | null>(null);

  async function onUpload(event: React.FormEvent) {
    event.preventDefault();
    if (file === null) {
      setError("Выберите файл Excel или CSV");
      return;
    }
    setUploading(true);
    setResult(null);
    setFailure(null);
    setWarnings(null);
    setError(null);
    try {
      const form = new FormData();
      form.append("file", file);
      const response = await fetch(
        `/api/projects/${projectId}/parameters/import`,
        { method: "POST", body: form },
      );
      if (response.ok) {
        const success = (await response.json()) as ImportResult;
        setResult(success);
        setWarnings(success.warnings);
        // значения могли измениться массово - перезагрузка страницы
        // гарантирует согласованность формы и сервера
        if (success.importedCount > 0) {
          setTimeout(() => window.location.reload(), 1200);
        }
      } else if (response.status === 400) {
        const body = (await response.json()) as ImportFailure;
        if (Array.isArray(body.errors) && body.errors.length > 0) {
          setFailure(body);
        } else {
          setError(body.message ?? "Не удалось загрузить файл");
        }
      } else {
        setError(await plainError(response));
      }
    } catch {
      setError("Сервер недоступен. Повторите позже");
    } finally {
      setUploading(false);
      setFile(null);
    }
  }

  async function onDeleteAttachment(attachmentId: number) {
    setDeletingId(attachmentId);
    setError(null);
    try {
      const response = await fetch(
        `/api/projects/${projectId}/parameters/attachments/${attachmentId}`,
        { method: "DELETE" },
      );
      if (response.ok) {
        setAttachments((prev) => prev.filter((a) => a.id !== attachmentId));
      } else {
        setError(await plainError(response));
      }
    } catch {
      setError("Сервер недоступен. Повторите позже");
    } finally {
      setDeletingId(null);
    }
  }

  return (
    <section className="flex flex-col gap-4 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div>
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">
          Импорт из Excel или CSV
        </h2>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Скачайте шаблон со списком параметров вашего типа объекта,
          заполните строку значений и загрузите файл. Колонки шаблона -
          коды параметров; расшифровка с единицами и диапазонами - на
          втором листе XLSX-шаблона и в подсказках полей выше.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3 text-sm">
        <a
          href={`/api/projects/${projectId}/parameters/template?format=xlsx`}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-slate-700
                     transition hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Скачать шаблон XLSX
        </a>
        <a
          href={`/api/projects/${projectId}/parameters/template?format=csv`}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-slate-700
                     transition hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          CSV
        </a>
      </div>

      <form onSubmit={onUpload} className="flex flex-wrap items-center gap-3">
        <input
          type="file"
          accept=".xlsx,.xls,.csv"
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null);
            setResult(null);
            setFailure(null);
            setError(null);
          }}
          className="text-sm text-slate-600 file:mr-3 file:rounded-lg
                     file:border-0 file:bg-slate-100 file:px-3 file:py-1.5
                     file:text-sm file:text-slate-700 hover:file:bg-slate-200
                     dark:text-slate-300 dark:file:bg-slate-800
                     dark:file:text-slate-300 dark:hover:file:bg-slate-700"
        />
        <button
          type="submit"
          disabled={uploading || file === null}
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white
                     transition hover:bg-slate-700 disabled:opacity-50 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
        >
          {uploading ? "Загрузка..." : "Загрузить файл"}
        </button>
      </form>

      {error !== null && (
        <div
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
        >
          {error}
        </div>
      )}

      {result !== null && (
        <div
          role="status"
          className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-400"
        >
          Импортировано значений: {result.importedCount}. Страница обновится...
        </div>
      )}

      {warnings !== null && warnings.length > 0 && (
        <div
          role="status"
          className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
        >
          <p className="font-medium">Предупреждения</p>
          <ul className="mt-1 list-inside list-disc space-y-0.5">
            {warnings.map((warning, index) => (
              <li key={index}>{warning}</li>
            ))}
          </ul>
        </div>
      )}

      {failure !== null && (
        <div
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
        >
          <p className="font-medium">
            Файл не загружен: {failure.errors.length} ошибок. Ничего не
            сохранено - данные проекта не изменились.
          </p>
          <ul className="mt-2 space-y-1">
            {failure.errors.map((e, index) => (
              <li key={index} className="text-xs">
                <span className="font-medium">
                  Строка {e.row}, колонка {e.column}
                  {e.parameterCode ? ` (${e.parameterCode})` : ""}:
                </span>{" "}
                {e.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300">
          Загруженные файлы
        </h3>
        {attachments.length === 0 ? (
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            Пока ничего не загружено.
          </p>
        ) : (
          <ul className="mt-2 flex flex-col gap-2">
            {attachments.map((attachment) => (
              <li
                key={attachment.id}
                className="flex flex-wrap items-center justify-between gap-2 rounded-lg
                           border border-slate-200 px-3 py-2 text-sm dark:border-slate-800"
              >
                <div className="min-w-0">
                  <p className="truncate font-medium text-slate-800 dark:text-slate-200">
                    {attachment.fileName}
                  </p>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    {formatSize(attachment.sizeBytes)} ·{" "}
                    {new Date(attachment.uploadedAt).toLocaleString("ru-RU")}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => onDeleteAttachment(attachment.id)}
                  disabled={deletingId === attachment.id}
                  className="shrink-0 rounded-lg border border-slate-300 px-2.5 py-1
                             text-xs text-slate-600 transition hover:bg-slate-100
                             disabled:opacity-50 dark:border-slate-700 dark:text-slate-300
                             dark:hover:bg-slate-800"
                >
                  {deletingId === attachment.id ? "Удаление..." : "Удалить"}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

function formatSize(sizeBytes: number | null): string {
  if (sizeBytes === null) {
    return "-";
  }
  if (sizeBytes < 1024) {
    return `${sizeBytes} Б`;
  }
  if (sizeBytes < 1024 * 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} КБ`;
  }
  return `${(sizeBytes / 1024 / 1024).toFixed(1)} МБ`;
}

async function plainError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string; error?: string };
    return body.message ?? body.error ?? `Ошибка (HTTP ${response.status})`;
  } catch {
    return `Ошибка (HTTP ${response.status})`;
  }
}
