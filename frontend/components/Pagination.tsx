import Link from "next/link";
import type { CatalogParams } from "@/lib/api";
import { buildCatalogUrl } from "@/lib/catalog-url";

/**
 * Пагинация каталога: ссылки с сохранением всех фильтров.
 * Серверный компонент - чистые ссылки, без состояния.
 */
export default function Pagination({
  page,
  totalPages,
  params,
}: {
  page: number;
  totalPages: number;
  params: CatalogParams;
}) {
  if (totalPages <= 1) {
    return null;
  }

  // окно страниц вокруг текущей: до 2 слева и справа
  const windowPages: number[] = [];
  for (
    let index = Math.max(0, page - 2);
    index <= Math.min(totalPages - 1, page + 2);
    index++
  ) {
    windowPages.push(index);
  }

  const linkClass = (active: boolean) =>
    `rounded-lg px-3 py-1.5 text-sm transition
     ${active
        ? "bg-slate-900 font-medium text-white dark:bg-slate-100 dark:text-slate-900"
        : "border border-slate-300 bg-white text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"}`;

  return (
    <nav className="flex flex-wrap items-center justify-center gap-2" aria-label="Пагинация">
      {page > 0 && (
        <Link href={buildCatalogUrl(params, { page: String(page - 1) })} className={linkClass(false)}>
          ← Назад
        </Link>
      )}
      {windowPages[0] > 0 && (
        <>
          <Link href={buildCatalogUrl(params, { page: "0" })} className={linkClass(false)}>
            1
          </Link>
          {windowPages[0] > 1 && <span className="px-1 text-slate-400 dark:text-slate-500">…</span>}
        </>
      )}
      {windowPages.map((index) => (
        <Link
          key={index}
          href={buildCatalogUrl(params, { page: String(index) })}
          className={linkClass(index === page)}
          aria-current={index === page ? "page" : undefined}
        >
          {index + 1}
        </Link>
      ))}
      {windowPages[windowPages.length - 1] < totalPages - 1 && (
        <>
          {windowPages[windowPages.length - 1] < totalPages - 2 && (
            <span className="px-1 text-slate-400 dark:text-slate-500">…</span>
          )}
          <Link
            href={buildCatalogUrl(params, { page: String(totalPages - 1) })}
            className={linkClass(false)}
          >
            {totalPages}
          </Link>
        </>
      )}
      {page < totalPages - 1 && (
        <Link
          href={buildCatalogUrl(params, { page: String(page + 1) })}
          className={linkClass(false)}
        >
          Вперёд →
        </Link>
      )}
    </nav>
  );
}
