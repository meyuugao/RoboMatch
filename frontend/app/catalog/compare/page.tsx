import type { Metadata } from "next";
import Link from "next/link";
import SolutionCompareTable from "@/components/SolutionCompareTable";
import { fetchCompare } from "@/lib/api";

/**
 * Страница сравнения решений:
 * /catalog/compare?ids=1,2 - колонки-решения, строки-характеристики.
 *
 * Набор для сравнения передаётся в URL (шарящаяся ссылка) и дублируется
 * в localStorage (lib/compare.ts) - страница работает и по прямой
 * ссылке, и из «корзины сравнения» каталога.
 */
export const metadata: Metadata = {
  title: "Сравнение решений",
};

function parseIds(raw: string | string[] | undefined): number[] {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (!value) return [];
  return value
    .split(",")
    .map((part) => Number(part.trim()))
    .filter((id) => Number.isInteger(id) && id > 0);
}

export default async function ComparePage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const raw = await searchParams;
  const ids = parseIds(raw.ids);

  // Пустое состояние: минимум 2 решения (смысл сравнения)
  if (ids.length < 2) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-2xl font-bold">Сравнение решений</h1>
        <div className="rounded-xl border border-slate-200 bg-white p-8 text-center text-sm text-slate-600
                    dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300">
          <p className="font-medium text-slate-900 dark:text-slate-100">Выберите минимум 2 решения</p>
          <p className="mt-2">
            В каталоге отметьте решения чекбоксом «Добавить к сравнению»
            и нажмите «Сравнить» в панели снизу.
          </p>
          <Link
            href="/catalog"
            className="mt-4 inline-block rounded-lg bg-slate-900 px-4 py-2
                       text-sm font-medium text-white transition hover:bg-slate-700
                       dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
          >
            Перейти в каталог
          </Link>
        </div>
      </div>
    );
  }

  let solutions = null;
  let loadError: string | null = null;
  try {
    solutions = await fetchCompare(ids.slice(0, 10));
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Неизвестная ошибка";
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Сравнение решений</h1>
          <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
            {solutions !== null
              ? `${solutions.length} решения: зелёным выделены лучшие значения
                 (цена - меньше, УГТ и грузоподъёмность - больше).
                 Клик по характеристике сортирует решения.`
              : "Сопоставление по унифицированным характеристикам."}
          </p>
        </div>
        <Link
          href="/catalog"
          className="text-sm text-slate-500 transition hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100"
        >
          ← Каталог решений
        </Link>
      </div>

      {loadError !== null && (
        <div
          role="alert"
          className="rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-800
                     dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
        >
          <p className="font-semibold">Ошибка загрузки сравнения</p>
          <p className="mt-1">{loadError}</p>
          <p className="mt-2 text-red-600 dark:text-red-400">
            Проверьте: запущен ли backend (порт 8080); возможно, одно из решений
            удалено из каталога.
          </p>
        </div>
      )}

      {solutions !== null && <SolutionCompareTable solutions={solutions} />}
    </div>
  );
}
