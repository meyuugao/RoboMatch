"use client";

import { useRouter } from "next/navigation";
import type { CatalogParams } from "@/lib/api";
import { buildCatalogUrl } from "@/lib/catalog-url";

/** Ключи сортировки — те же, что допускает API (белый список в сервисе). */
const SORT_OPTIONS = [
  { value: "name", label: "Название" },
  { value: "price", label: "Цена" },
  { value: "trl", label: "УГТ" },
  { value: "payload_kg", label: "Грузоподъёмность" },
  { value: "created_at", label: "Добавлено" },
] as const;

/** Сортировка каталога: поле + направление, применяется сразу. */
export default function SolutionSort({ params }: { params: CatalogParams }) {
  const router = useRouter();
  const sortBy = params.sortBy ?? "name";
  const sortDir = params.sortDir ?? "asc";

  function push(changes: { sortBy?: string; sortDir?: string }) {
    // смена сортировки не сбрасывает фильтры и страницу — только порядок
    router.push(buildCatalogUrl(params, changes));
  }

  return (
    // flex-wrap + min-w-0: на узких экранах селекты не выезжают за
    // вьюпорт (блок был ~386px при 375px)
    <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
      <span className="text-xs text-slate-500 dark:text-slate-400">Сортировка:</span>
      <select
        value={sortBy}
        onChange={(event) => push({ sortBy: event.target.value })}
        aria-label="Поле сортировки"
        className="min-w-0 max-w-[45%] rounded-lg border border-slate-300 bg-white px-2 py-1.5
                   dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
      >
        {SORT_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <select
        value={sortDir}
        onChange={(event) => push({ sortDir: event.target.value })}
        aria-label="Направление сортировки"
        className="min-w-0 rounded-lg border border-slate-300 bg-white px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
      >
        <option value="asc">по возрастанию</option>
        <option value="desc">по убыванию</option>
      </select>
    </div>
  );
}
