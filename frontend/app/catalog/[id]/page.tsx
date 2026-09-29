import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import CompareCheckbox from "@/components/compare/CompareCheckbox";
import SolutionCharacteristics from "@/components/SolutionCharacteristics";
import { fetchSolutionById } from "@/lib/api";
import {
  PRODUCT_CLASS_LABELS,
  SOLUTION_STATUS_LABELS,
  SOURCE_KIND_LABELS,
} from "@/types/solution";

/**
 * Детальная карточка решения: полные данные -
 * производитель, тип, регион, статус, УГТ, цена, ТТХ (зеркальные + EAV
 * с провенансом), кейсы, применения, источник записи.
 *
 * Серверный компонент; интерактивна только кнопка сравнения.
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  try {
    const solution = await fetchSolutionById(id);
    return { title: `${solution.name} - каталог` };
  } catch {
    return { title: "Решение - каталог" };
  }
}

const priceFormatter = new Intl.NumberFormat("ru-RU", {
  style: "currency",
  currency: "RUB",
  maximumFractionDigits: 0,
});

const STATUS_STYLES: Record<string, string> = {
  operation: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300",
  piloting: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  rnd: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300",
};

export default async function SolutionPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  const solution = await fetchSolutionById(id).catch(() => null);
  if (solution === null) {
    notFound();
  }

  return (
    <div className="flex flex-col gap-6">
      <Link
        href="/catalog"
        className="text-sm text-slate-500 transition hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100"
      >
        ← Каталог решений
      </Link>

      {/* Заголовок: название, производитель, бейджи */}
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded-md bg-slate-900 px-2 py-1 text-xs font-medium text-white dark:bg-slate-100 dark:text-slate-900">
            {PRODUCT_CLASS_LABELS[solution.productClass] ?? solution.productClass}
          </span>
          <span
            className={`rounded-md px-2 py-1 text-xs font-medium ${
              STATUS_STYLES[solution.status] ?? "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300"
            }`}
          >
            {SOLUTION_STATUS_LABELS[solution.status] ?? solution.status}
          </span>
          {solution.trl !== null && (
            <span className="rounded-md bg-slate-100 px-2 py-1 text-xs font-medium text-slate-700 dark:bg-slate-800 dark:text-slate-300">
              УГТ: {solution.trl}
            </span>
          )}
          {solution.completenessPct !== null && (
            <span className="rounded-md bg-slate-100 px-2 py-1 text-xs font-medium text-slate-700 dark:bg-slate-800 dark:text-slate-300">
              Заполненность: {solution.completenessPct}%
            </span>
          )}
        </div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">{solution.name}</h1>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          {solution.vendorName}
          {solution.solutionTypeName ? ` · ${solution.solutionTypeName}` : ""}
          {solution.solutionSubtypeName ? ` · ${solution.solutionSubtypeName}` : ""}
          {solution.regionName ? ` · ${solution.regionName}` : ""}
        </p>
      </header>

      {/* Цена + сравнение */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white p-5
                  dark:border-slate-800 dark:bg-slate-900">
        <p className="text-xl font-bold text-slate-900 dark:text-slate-100">
          {priceFormatter.format(solution.priceRub)}
          <span className="ml-1 text-xs font-normal text-slate-500 dark:text-slate-400">
            с НДС, без доставки и пусконаладки
          </span>
        </p>
        <CompareCheckbox solutionId={solution.id} />
      </div>

      {/* Описание */}
      {solution.description && (
        <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
          <h2 className="mb-2 text-base font-semibold text-slate-900 dark:text-slate-100">Описание</h2>
          <p className="text-sm leading-relaxed text-slate-700 dark:text-slate-300">{solution.description}</p>
        </section>
      )}

      {/* ТТХ: зеркальные + EAV с провенансом */}
      <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
        <h2 className="mb-4 text-base font-semibold text-slate-900 dark:text-slate-100">
          Технические характеристики
        </h2>
        <SolutionCharacteristics solution={solution} />
      </section>

      {/* Применения: отрасль + процесс */}
      {solution.applications.length > 0 && (
        <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
          <h2 className="mb-3 text-base font-semibold text-slate-900 dark:text-slate-100">Применение</h2>
          <ul className="flex flex-wrap gap-2">
            {solution.applications.map((application) => (
              <li
                key={`${application.industryId}-${application.processId}`}
                className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-sm text-slate-700
                           dark:border-slate-800 dark:bg-slate-800 dark:text-slate-300"
              >
                {application.industryName} - {application.processName}
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* Кейсы внедрения */}
      {solution.cases.length > 0 && (
        <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
          <h2 className="mb-3 text-base font-semibold text-slate-900 dark:text-slate-100">
            Реализованные кейсы
          </h2>
          <ul className="flex flex-col gap-3">
            {solution.cases.map((item) => (
              <li key={item.id} className="rounded-lg bg-slate-50 p-4 dark:bg-slate-800">
                <p className="text-sm font-medium text-slate-900 dark:text-slate-100">{item.name}</p>
                {item.description && (
                  <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">{item.description}</p>
                )}
                {item.sourceUrl && (
                  <a
                    href={item.sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="mt-1 inline-block text-xs text-blue-600 hover:underline dark:text-blue-400"
                  >
                    Источник{item.sourceDate ? `, ${item.sourceDate}` : ""}
                  </a>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* Источник записи карточки */}
      <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900 text-sm text-slate-600 dark:text-slate-300">
        <h2 className="mb-2 text-base font-semibold text-slate-900 dark:text-slate-100">
          Источник данных
        </h2>
        <p>
          {SOURCE_KIND_LABELS[solution.sourceKind] ?? solution.sourceKind}
          {solution.sourceUrl ? (
            <>
              {" - "}
              <span className="break-all text-slate-500 dark:text-slate-400">{solution.sourceUrl}</span>
            </>
          ) : null}
          {solution.sourceDate ? `, актуально на ${solution.sourceDate}` : ""}
        </p>
      </section>
    </div>
  );
}
