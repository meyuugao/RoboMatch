"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import type { ExportFormat, ExportDto } from "@/types/export";
import { FORMAT_LABELS, formatFileSize } from "@/types/export";
import { useTheme } from "@/components/ThemeProvider";

/**
 * Панель экспорта: кнопки генерации
 * трёх форматов с индикатором (схема CalculateAllButton — POST к BFF
 * + автоматическое скачивание готового файла), история выгрузок
 * (дата, формат, размер, автор) со скачиванием и удалением.
 *
 * Тема PDF-отчёта: переключатель рядом с кнопками — по
 * умолчанию как в интерфейсе (светлая/тёмная), выбор пользователя
 * отправляется в запросе; содержимое отчёта не зависит от темы
 * браузера — только от этого явного выбора. Excel/CSV — машиночитаемые
 * форматы, тема не применяется. SVG-схема имитации сохраняется в
 * текущей теме интерфейса (страница имитации).
 *
 * Пометка «Предварительная оценка — требует верификации при обследовании
 * объекта» — в шапке страницы (page.tsx), не здесь.
 *
 * Тема 2D-схемы имитации в отчёте: отдельный выбор — «Как в UI»
 * (по умолчанию: подставляется текущая тема интерфейса), «Светлая»,
 * «Тёмная». Сервер сверяет тему сохранённой схемы с выбранной и при
 * отличии перерисовывает её — PDF всегда содержит схему выбранной
 * темы (светлая схема не попадает в тёмный отчёт и наоборот).
 */
export default function ExportPanel({
  projectId,
  initialExports,
  calculated,
  isWarehouse = true,
  comparisonUnavailable = false,
}: {
  projectId: string;
  initialExports: ExportDto[];
  /** Есть ли хоть один расчёт экономики (иначе генерация недоступна). */
  calculated: boolean;
  /** Гейт склада (is_calc_enabled) — как у экономики и имитации. */
  isWarehouse?: boolean;
  /** Сравнение сценариев не загрузилось (не «не рассчитана»!). */
  comparisonUnavailable?: boolean;
}) {
  const router = useRouter();
  const [history, setHistory] = useState<ExportDto[]>(initialExports);
  const [busyFormat, setBusyFormat] = useState<ExportFormat | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // тема PDF-отчёта: по умолчанию — как в интерфейсе (устанавливается
  // после монтирования, чтобы не разойтись с SSR-разметкой), затем —
  // явный выбор пользователя в radio-группе
  const { resolvedTheme } = useTheme();
  const [reportTheme, setReportTheme] = useState<"light" | "dark">(
    "light",
  );
  const [reportThemeTouched, setReportThemeTouched] = useState(false);
  useEffect(() => {
    if (!reportThemeTouched) {
      setReportTheme(resolvedTheme);
    }
  }, [resolvedTheme, reportThemeTouched]);

  // тема 2D-схемы в отчёте: «ui» подставляет текущую тему интерфейса
  const [schemaThemeChoice, setSchemaThemeChoice] = useState<
    "ui" | "light" | "dark"
  >("ui");
  const schemaTheme =
    schemaThemeChoice === "ui"
      ? (resolvedTheme ?? "light")
      : schemaThemeChoice;

  async function onExport(format: ExportFormat) {
    setBusyFormat(format);
    setError(null);
    setNotice(null);
    try {
      const response = await fetch(`/api/projects/${projectId}/exports`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          format,
          theme: reportTheme,
          schemaTheme,
        }),
      });
      if (!response.ok) {
        let message = `Ошибка генерации (HTTP ${response.status})`;
        try {
          const body = (await response.json()) as {
            message?: string;
            error?: string;
          };
          message = body.message ?? body.error ?? message;
        } catch {
          // fallback уже задан
        }
        setError(message);
        return;
      }
      const created = (await response.json()) as ExportDto;
      // готовый файл скачивается сразу
      window.location.href = `/api/projects/${projectId}/exports/${created.id}`;
      setHistory((rows) => [created, ...rows]);
      setNotice(
        `Отчёт ${FORMAT_LABELS[format]} сформирован — файл скачивается.`,
      );
      router.refresh();
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setBusyFormat(null);
    }
  }

  async function onDelete(exportId: number) {
    // гонка двойного клика — кнопка гасится на время
    // запроса; «уже удалена» (404) — тихо убираем строку, не пугая
    setError(null);
    setNotice(null);
    setDeletingId(exportId);
    try {
      const response = await fetch(
        `/api/projects/${projectId}/exports/${exportId}`,
        { method: "DELETE" },
      );
      if (response.status === 204 || response.status === 404) {
        setHistory((rows) => rows.filter((row) => row.id !== exportId));
        setNotice("Выгрузка удалена.");
        router.refresh();
        return;
      }
      let message = `Не удалось удалить (HTTP ${response.status})`;
      try {
        const body = (await response.json()) as {
          message?: string;
          error?: string;
        };
        message = body.message ?? body.error ?? message;
      } catch {
        // fallback уже задан
      }
      setError(message);
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setDeletingId(null);
    }
  }

  const disabledAll =
    busyFormat !== null || !calculated || !isWarehouse;

  return (
    <div className="flex flex-col gap-6">
      {/* Генерация -------------------------------------- */}
      <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
        <h2 className="text-base font-semibold text-slate-800 dark:text-slate-100">
          Сформировать отчёт
        </h2>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Состав отчёта: параметры объекта, выбранные решения,
          состав оборудования, расчёт экономики трёх сценариев, ограничения,
          источники данных и дата расчёта. Формулы и допущения видны
          пользователю и в отчёте.
        </p>
        {/* Тема PDF-отчёта: явный выбор, не зависит от темы браузера */}
        <fieldset className="mt-4">
          <legend className="text-xs font-medium uppercase tracking-wide text-slate-400 dark:text-slate-500">
            Тема PDF-отчёта
          </legend>
          <div
            role="radiogroup"
            aria-label="Тема PDF-отчёта"
            data-testid="report-theme"
            className="mt-2 inline-flex items-center gap-0.5 rounded-lg border border-slate-200 bg-slate-50 p-0.5 dark:border-slate-700 dark:bg-slate-800"
          >
            {(["light", "dark"] as const).map((value) => {
              const active = reportTheme === value;
              return (
                <button
                  key={value}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  onClick={() => {
                    setReportTheme(value);
                    setReportThemeTouched(true);
                  }}
                  data-report-theme-option={value}
                  className={`rounded-md px-3 py-1.5 text-xs font-medium transition ${
                    active
                      ? "bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900"
                      : "text-slate-600 hover:bg-slate-200 dark:text-slate-300 dark:hover:bg-slate-700"
                  }`}
                >
                  {value === "light" ? "Светлая" : "Тёмная"}
                </button>
              );
            })}
          </div>
          <p className="mt-1.5 text-xs text-slate-400 dark:text-slate-500">
            Применяется к PDF; по умолчанию — как в интерфейсе. Excel и CSV —
            машиночитаемые форматы без темы.
          </p>
        </fieldset>
        {/* Тема 2D-схемы имитации: схема в PDF — строго выбранной
 темы (по умолчанию как в интерфейсе) */}
        <fieldset className="mt-4">
          <legend className="text-xs font-medium uppercase tracking-wide text-slate-400 dark:text-slate-500">
            Тема 2D-схемы в отчёте
          </legend>
          <div
            role="radiogroup"
            aria-label="Тема 2D-схемы в отчёте"
            data-testid="schema-theme"
            className="mt-2 inline-flex items-center gap-0.5 rounded-lg border border-slate-200 bg-slate-50 p-0.5 dark:border-slate-700 dark:bg-slate-800"
          >
            {(["ui", "light", "dark"] as const).map((value) => {
              const active = schemaThemeChoice === value;
              return (
                <button
                  key={value}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  onClick={() => setSchemaThemeChoice(value)}
                  data-schema-theme-option={value}
                  className={`rounded-md px-3 py-1.5 text-xs font-medium transition ${
                    active
                      ? "bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900"
                      : "text-slate-600 hover:bg-slate-200 dark:text-slate-300 dark:hover:bg-slate-700"
                  }`}
                >
                  {value === "ui" ? "Как в UI" : value === "light" ? "Светлая" : "Тёмная"}
                </button>
              );
            })}
          </div>
          <p className="mt-1.5 text-xs text-slate-400 dark:text-slate-500">
            Схема склада из раздела имитации встраивается в отчёт выбранной
            темы; при отличии сохранённой схемы она перерисовывается.
          </p>
        </fieldset>
        {!isWarehouse ? (
          <p
            role="alert"
            className="mt-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
          >
            Экспорт доступен только для склада — параметры, подбор и
            сравнение решений работают, а расчёт и отчёты — для типов
            объектов с поддержкой расчёта.
          </p>
        ) : !calculated ? (
          <p
            role="alert"
            className="mt-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
          >
            Экономика ещё не рассчитана — при генерации отчёта платформа
            рассчитает сценарии автоматически; если обязательных параметров
            не хватает, причина будет прямо в разделе экономики.
          </p>
        ) : comparisonUnavailable ? (
          <p
            role="status"
            className="mt-3 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300"
          >
            Не удалось проверить расчёты сценариев — обновите страницу;
            при генерации сервер сам проверит готовность проекта.
          </p>
        ) : null}
        <div className="mt-4 flex flex-wrap gap-3">
          {(["pdf", "xlsx", "csv"] as const).map((format) => (
            <button
              key={format}
              type="button"
              onClick={() => onExport(format)}
              disabled={disabledAll}
              data-export-format={format}
              className="inline-flex items-center gap-2 rounded-lg bg-sky-700 px-4 py-2 text-sm
                         font-medium text-white transition hover:bg-sky-800
                         disabled:cursor-wait disabled:opacity-60 dark:bg-sky-600 dark:hover:bg-sky-500"
            >
              {busyFormat === format && (
                <span
                  aria-hidden
                  className="inline-block h-3.5 w-3.5 animate-spin rounded-full
                             border-2 border-white/40 border-t-white"
                />
              )}
              {busyFormat === format
                ? `Формируется ${FORMAT_LABELS[format]}…`
                : `Скачать ${FORMAT_LABELS[format]}`}
            </button>
          ))}
        </div>
        {notice && (
          <p
            role="status"
            className="mt-3 rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300"
          >
            {notice}
          </p>
        )}
        {error && (
          <p
            role="alert"
            className="mt-3 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
          >
            {error}
          </p>
        )}
      </section>

      {/* История выгрузок ------------------------------------------- */}
      <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
        <h2 className="text-base font-semibold text-slate-800 dark:text-slate-100">
          История выгрузок
        </h2>
        {history.length === 0 ? (
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
            Выгрузок пока нет — сформируйте первый отчёт.
          </p>
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[560px] text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-xs uppercase
                             tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
                <th scope="col" className="py-2 pr-3 font-medium">Дата</th>
                <th scope="col" className="py-2 pr-3 font-medium">Формат</th>
                <th scope="col" className="py-2 pr-3 font-medium">Файл</th>
                <th scope="col" className="py-2 pr-3 font-medium">Размер</th>
                <th scope="col" className="py-2 pr-3 font-medium">Автор</th>
                <th scope="col" className="py-2 font-medium text-right">
                  Действия
                </th>
              </tr>
            </thead>
            <tbody>
              {history.map((row) => (
                <tr
                  key={row.id}
                  data-export-id={row.id}
                  className="border-b border-slate-100 last:border-0 dark:border-slate-800"
                >
                  <td className="py-2 pr-3 text-slate-600 dark:text-slate-300">
                    {new Date(row.createdAt).toLocaleString("ru-RU", {
                      day: "2-digit",
                      month: "2-digit",
                      year: "numeric",
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </td>
                  <td className="py-2 pr-3 font-medium text-slate-700 dark:text-slate-200">
                    {FORMAT_LABELS[row.format] ?? row.format}
                  </td>
                  <td className="py-2 pr-3 text-slate-600 dark:text-slate-300">{row.fileName}</td>
                  <td className="py-2 pr-3 text-slate-600 dark:text-slate-300">
                    {formatFileSize(row.sizeBytes)}
                  </td>
                  <td className="py-2 pr-3 text-slate-600 dark:text-slate-300">
                    {row.createdByLogin}
                  </td>
                  <td className="py-2 text-right">
                    <div className="inline-flex gap-2">
                      <a
                        href={`/api/projects/${projectId}/exports/${row.id}`}
                        className="rounded-lg border border-slate-300 px-3 py-1
                                   text-xs font-medium text-slate-700 transition
                                   hover:bg-slate-100 dark:border-slate-700 dark:text-slate-200
                                   dark:hover:bg-slate-800"
                        aria-label={`Скачать ${row.fileName}`}
                      >
                        Скачать
                      </a>
                      <button
                        type="button"
                        onClick={() => onDelete(row.id)}
                        disabled={deletingId === row.id}
                        className="rounded-lg border border-rose-200 px-3 py-1
                                   text-xs font-medium text-rose-700 transition
                                   hover:bg-rose-50 disabled:cursor-wait
                                   disabled:opacity-60 dark:border-rose-800
                                   dark:text-rose-400 dark:hover:bg-rose-950/40"
                        aria-label={`Удалить ${row.fileName}`}
                      >
                        {deletingId === row.id ? "Удаляется…" : "Удалить"}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
