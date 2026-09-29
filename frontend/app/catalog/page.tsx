import type { Metadata } from "next";
import Link from "next/link";
import CompareBar from "@/components/compare/CompareBar";
import Pagination from "@/components/Pagination";
import SolutionCard from "@/components/SolutionCard";
import SolutionFilters from "@/components/SolutionFilters";
import SolutionSearch from "@/components/SolutionSearch";
import SolutionSort from "@/components/SolutionSort";
import { fetchFilters, fetchSolutions, type CatalogParams } from "@/lib/api";
import type { PageResponse, SolutionSummary } from "@/types/solution";

/**
 * Страница каталога: поиск, фильтры, сортировка,
 * пагинация, выбор решений для сравнения.
 *
 * СЕРВЕРНЫЙ компонент: состояние фильтров — в query-параметрах URL
 * (ссылку с фильтрами можно отправить коллеге — результат тот же).
 * Клиентские компоненты (SolutionSearch/SolutionFilters/SolutionSort)
 * только меняют URL; данные приходят с сервера через BFF.
 */
export const metadata: Metadata = {
  title: "Каталог решений",
};

/** Параметры, которые читает страница (остальное игнорируется). */
const CATALOG_KEYS = [
  "q",
  "typeId",
  "subtypeId",
  "industryId",
  "processId",
  "status",
  "trlMin",
  "trlMax",
  "priceMin",
  "priceMax",
  "payloadMin",
  "sortBy",
  "sortDir",
  "page",
  "size",
] as const;

export default async function CatalogPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const raw = await searchParams;
  const params: CatalogParams = {};
  for (const key of CATALOG_KEYS) {
    const value = raw[key];
    if (typeof value === "string" && value !== "") {
      params[key] = value;
    }
  }

  let solutions: PageResponse<SolutionSummary> | null = null;
  let filters = null;
  let loadError: string | null = null;

  const [solutionsResult, filtersResult] = await Promise.allSettled([
    fetchSolutions(params),
    fetchFilters(),
  ]);
  if (solutionsResult.status === "fulfilled") {
    solutions = solutionsResult.value;
  } else {
    loadError =
      solutionsResult.reason instanceof Error
        ? solutionsResult.reason.message
        : "Неизвестная ошибка загрузки";
  }
  if (filtersResult.status === "fulfilled") {
    filters = filtersResult.value;
  }

  // активные ФИЛЬТРЫ (поиск/значения) — сортировка и номер страницы
  // фильтрами не считаются (смена сортировки не должна показывать
  // «Сбросить все фильтры»)
  const FILTER_KEYS = ["q", "typeId", "subtypeId", "industryId", "processId",
    "status", "trlMin", "trlMax", "priceMin", "priceMax", "payloadMin"] as const;
  const hasActiveFilters = FILTER_KEYS.some((key) => params[key] !== undefined);
  // ключ пересоздаёт компоненты при смене URL-состояния: иначе локальный
  // useState поиска/фильтров показывал бы старые значения после «Сбросить
  // все фильтры» (рассинхрон URL и UI)
  const stateKey = JSON.stringify(params);

  return (
    <div className="flex flex-col gap-6 pb-20">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Каталог решений</h1>
          <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
            {loadError === null && solutions !== null
              ? `Найдено: ${solutions.totalElements} роботизированных решений
                 (БРС, БАС и ПО). Данные — каталог решений с подтверждёнными ТТХ.`
              : "Роботизированные решения: БРС, БАС и ПО."}
          </p>
        </div>
        {solutions !== null && filters !== null && (
          <SolutionSort params={params} />
        )}
      </div>

      {/* Ошибка загрузки: что случилось и как исправить */}
      {loadError !== null && (
        <div
          role="alert"
          className="rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-800
                     dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
        >
          <p className="font-semibold">Ошибка загрузки каталога</p>
          <p className="mt-1">{loadError}</p>
          <p className="mt-2 text-red-600 dark:text-red-400">
            Проверьте: запущен ли backend (порт 8080) и БД (docker compose up -d db).
          </p>
        </div>
      )}

      {filters !== null && (
        <div className="flex flex-col gap-3">
          <SolutionSearch key={`search-${stateKey}`} params={params} />
          <SolutionFilters key={`filters-${stateKey}`} filters={filters} params={params} />
        </div>
      )}

      {/* Ничего не найдено: подсказки, а не пустой экран */}
      {solutions !== null && solutions.content.length === 0 && (
        <div className="rounded-xl border border-slate-200 bg-white p-8 text-center text-sm text-slate-600
                    dark:border-slate-800 dark:bg-slate-900 dark:text-slate-300">
          <p className="font-medium text-slate-900 dark:text-slate-100">Ничего не найдено</p>
          <p className="mt-2">
            Попробуйте изменить или очистить поиск, ослабить фильтры
            (диапазон цены, УГТ, грузоподъёмность).
          </p>
          {hasActiveFilters && (
            <Link
              href="/catalog"
              className="mt-4 inline-block rounded-lg border border-slate-300 px-4 py-2
                         text-slate-700 transition hover:bg-slate-100
                         dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
            >
              Сбросить все фильтры
            </Link>
          )}
        </div>
      )}

      {/* Сетка карточек: 1 колонка на телефоне, 2 — планшет, 3 — десктоп */}
      {solutions !== null && solutions.content.length > 0 && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {solutions.content.map((solution) => (
              <SolutionCard key={solution.id} solution={solution} />
            ))}
          </div>
          <Pagination
            page={solutions.page}
            totalPages={solutions.totalPages}
            params={params}
          />
        </>
      )}

      {/* Панель сравнения — видима, когда выбрано хотя бы одно решение */}
      <CompareBar />
    </div>
  );
}
