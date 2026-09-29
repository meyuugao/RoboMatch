import type { Metadata } from "next";
import Link from "next/link";
import AdminReferencesTable from "@/components/admin/AdminReferencesTable";
import { adminListReferences } from "@/lib/api";
import { requireAdmin } from "@/lib/auth";
import { DICT_TITLES, type DictCode } from "@/types/admin";
import type { AdminReferenceDto } from "@/types/admin";
import type { PageResponse } from "@/types/solution";

/**
 * CRUD одного справочника: /admin/references/{dictCode}.
 * Данные грузит серверный компонент (пагинация через query-параметры),
 * мутации — клиентской таблицей (AdminReferencesTable).
 */
export const metadata: Metadata = {
  title: "Справочник — Управление",
};

const VALID_DICTS = Object.keys(DICT_TITLES) as DictCode[];

export default async function DictPage({
  params,
  searchParams,
}: {
  params: Promise<{ dictCode: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  await requireAdmin();
  const { dictCode } = await params;
  const raw = await searchParams;
  const search = typeof raw.q === "string" ? raw.q : "";
  const page = Math.max(0, Number(raw.page ?? 0) || 0);

  if (!VALID_DICTS.includes(dictCode as DictCode)) {
    return (
      <div className="flex flex-col gap-4">
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 p-6 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <p className="font-medium">Неизвестный справочник</p>
          <p className="mt-1">Проверьте адрес страницы.</p>
        </div>
        <Link href="/admin/references" className="text-sm text-slate-600 hover:underline dark:text-slate-300">
          ← К списку справочников
        </Link>
      </div>
    );
  }

  const code = dictCode as DictCode;
  let list: PageResponse<AdminReferenceDto> | null = null;
  let loadError: string | null = null;
  try {
    list = await adminListReferences(code, search || undefined, page, 50);
  } catch (error) {
    loadError = error instanceof Error ? error.message : "Ошибка загрузки";
  }

  return (
    <div className="flex flex-col gap-5">
      <div>
        <Link href="/admin/references" className="text-sm text-slate-500 hover:underline dark:text-slate-400">
          ← К списку справочников
        </Link>
        <h1 className="mt-2 text-2xl font-bold">{DICT_TITLES[code]}</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          {code === "process"
            ? "Неиспользуемые процессы можно деактивировать вместо удаления."
            : "Записи со ссылками из каталога удалить нельзя — сначала освободите ссылки."}
        </p>
      </div>

      {loadError !== null && (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          <p className="font-medium">Не удалось загрузить справочник</p>
          <p className="mt-1">{loadError}</p>
        </div>
      )}

      {list !== null && (
        <AdminReferencesTable
          dictCode={code}
          initialPage={list.page}
          search={search}
          initialItems={list.content}
          totalElements={list.totalElements}
          totalPages={list.totalPages}
        />
      )}
    </div>
  );
}
