"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { buildCatalogUrl } from "@/lib/catalog-url";
import type { CatalogParams } from "@/lib/api";

/**
 * Строка поиска каталога: подстрока в названии, без учёта
 * регистра. Применяется по Enter или кнопке; сброс - пустое значение.
 * Состояние - в query-параметре q (шарящаяся ссылка).
 */
export default function SolutionSearch({ params }: { params: CatalogParams }) {
  const router = useRouter();
  const [value, setValue] = useState(params.q ?? "");

  function apply(event: React.FormEvent) {
    event.preventDefault();
    // новый поиск - всегда первая страница
    router.push(buildCatalogUrl(params, { q: value.trim() || undefined, page: undefined }));
  }

  return (
    <form onSubmit={apply} className="flex flex-1 gap-2" role="search">
      <input
        type="search"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        placeholder="Поиск по названию: например, Ronavi"
        aria-label="Поиск по названию"
        className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm
                   text-slate-900 placeholder:text-slate-400 focus:border-slate-500
                   focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100
                   dark:placeholder:text-slate-500 dark:focus:border-slate-400"
      />
      <button
        type="submit"
        className="shrink-0 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white
                   transition hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900
                   dark:hover:bg-slate-200"
      >
        Найти
      </button>
    </form>
  );
}
