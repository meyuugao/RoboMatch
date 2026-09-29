"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState } from "react";
import AdminBulkDeleteBar from "@/components/admin/AdminBulkDeleteBar";
import ConfirmDialog from "@/components/admin/ConfirmDialog";
import type { BulkDeleteResultDto } from "@/types/admin";
import type { AdminReferenceDto, DictCode } from "@/types/admin";

/**
 * Таблица записей справочника с CRUD: поиск, создание,
 * правка (инлайн), удаление с подтверждением, массовое выделение и
 * удаление через /api/admin/references/{dictCode}/bulk-delete
 * (записи с FK-ссылками остаются - причина в сводке). Клиентский
 * компонент - мутации через BFF /api/admin/references/*.
 *
 * Особенность: у vendor нет кода (только название), у process есть
 * переключатель активности, у characteristic_type - группа и тип
 * значения (только при создании).
 */
export default function AdminReferencesTable({
  dictCode,
  initialPage,
  search,
  initialItems,
  totalElements,
  totalPages,
}: {
  dictCode: DictCode;
  initialPage: number;
  search: string;
  initialItems: AdminReferenceDto[];
  totalElements: number;
  totalPages: number;
}) {
  const router = useRouter();
  const searchParams = useSearchParams();

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editCode, setEditCode] = useState("");
  const [editName, setEditName] = useState("");
  const [editActive, setEditActive] = useState(true);

  const [newCode, setNewCode] = useState("");
  const [newName, setNewName] = useState("");
  const [newGroup, setNewGroup] = useState("technical");
  const [newDataType, setNewDataType] = useState("number");
  const [newUnit, setNewUnit] = useState("");
  const [showCreate, setShowCreate] = useState(false);

  const [selected, setSelected] = useState<number[]>([]);
  const [bulkConfirmOpen, setBulkConfirmOpen] = useState(false);
  const [bulkResult, setBulkResult] = useState<BulkDeleteResultDto | null>(null);

  const hasCode = dictCode !== "vendor";
  const isProcess = dictCode === "process";
  const isCharacteristicType = dictCode === "characteristic_type";

  const allChecked =
    initialItems.length > 0 &&
    initialItems.every((item) => selected.includes(item.id));

  function toggleRow(id: number) {
    setSelected((current) =>
      current.includes(id)
        ? current.filter((value) => value !== id)
        : [...current, id],
    );
  }

  function toggleAll() {
    setSelected(allChecked ? [] : initialItems.map((item) => item.id));
  }

  async function onBulkDeleteConfirmed() {
    setBusy(true);
    setError(null);
    try {
      // мутация - прямой fetch через BFF (как создание/правка выше);
      // lib/api.ts с next/headers в клиентский бандл не попадает
      const response = await fetch(
        `/api/admin/references/${dictCode}/bulk-delete`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ ids: selected }),
        },
      );
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      setBulkResult((await response.json()) as BulkDeleteResultDto);
      setSelected([]);
      setBulkConfirmOpen(false);
      // серверный компонент перечитает страницу и отдаст свежие строки
      router.refresh();
    } catch (bulkError) {
      setError(
        bulkError instanceof Error ? bulkError.message : "Ошибка удаления",
      );
    } finally {
      setBusy(false);
    }
  }

  function reloadToPage(page: number) {
    const params = new URLSearchParams(searchParams.toString());
    params.set("page", String(page));
    router.push(`/admin/references/${dictCode}?${params}`);
  }

  async function onCreate(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body: Record<string, unknown> = { name: newName.trim() };
      if (hasCode) {
        body.code = newCode.trim();
      }
      if (isProcess) {
        body.isActive = true;
      }
      if (isCharacteristicType) {
        body.groupCode = newGroup;
        body.dataType = newDataType;
        body.unit = newUnit.trim() === "" ? null : newUnit.trim();
      }
      const response = await fetch(`/api/admin/references/${dictCode}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      setNewCode("");
      setNewName("");
      setNewUnit("");
      setShowCreate(false);
      // перезагрузка: надёжнее router.refresh после мутаций -
      // серверный компонент перечитает список с первой страницы
      reloadToPage(0);
    } catch (createError) {
      setError(
        createError instanceof Error ? createError.message : "Ошибка создания",
      );
    } finally {
      setBusy(false);
    }
  }

  async function onSaveEdit(id: number) {
    setBusy(true);
    setError(null);
    try {
      const body: Record<string, unknown> = { name: editName.trim() };
      if (hasCode) {
        body.code = editCode.trim();
      }
      if (isProcess) {
        body.isActive = editActive;
      }
      const response = await fetch(`/api/admin/references/${dictCode}/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      setEditingId(null);
      reloadToPage(initialPage);
    } catch (saveError) {
      setError(
        saveError instanceof Error ? saveError.message : "Ошибка сохранения",
      );
    } finally {
      setBusy(false);
    }
  }

  async function onDelete(item: AdminReferenceDto) {
    if (
      !window.confirm(
        `Удалить «${item.name}»?${isProcess ? " Деактивация безопаснее - переключите «Активен»." : ""}`,
      )
    ) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/admin/references/${dictCode}/${item.id}`,
        { method: "DELETE" },
      );
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      reloadToPage(initialPage);
    } catch (deleteError) {
      setError(
        deleteError instanceof Error ? deleteError.message : "Ошибка удаления",
      );
    } finally {
      setBusy(false);
    }
  }

  const wrapper = (
    <div className="flex flex-col gap-4">
      {error !== null && (
        <div
          role="alert"
          data-testid="references-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      <form
        method="get"
        action={`/admin/references/${dictCode}`}
        className="flex flex-wrap gap-2"
      >
        <input
          type="search"
          name="q"
          defaultValue={search}
          placeholder="Поиск по названию или коду…"
          className="w-64 rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-emerald-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
        />
        <button
          type="submit"
          className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Найти
        </button>
        <button
          type="button"
          onClick={() => setShowCreate((visible) => !visible)}
          data-testid="reference-new"
          className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-emerald-700"
        >
          {showCreate ? "Скрыть форму" : "Добавить запись"}
        </button>
      </form>

      {showCreate && (
        <form
          onSubmit={onCreate}
          className="flex flex-wrap items-end gap-3 rounded-xl bg-slate-50 p-4 dark:bg-slate-900"
          data-testid="reference-create-form"
        >
          {hasCode && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-slate-700 dark:text-slate-300">Код (латиница)</span>
              <input
                data-testid="reference-new-code"
                value={newCode}
                onChange={(event) => setNewCode(event.target.value)}
                pattern="[a-z][a-z0-9_]*"
                required
                className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
              />
            </label>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="font-medium text-slate-700 dark:text-slate-300">Название</span>
            <input
              data-testid="reference-new-name"
              value={newName}
              onChange={(event) => setNewName(event.target.value)}
              required
              className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            />
          </label>
          {isCharacteristicType && (
            <>
              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-slate-700 dark:text-slate-300">Группа</span>
                <select
                  value={newGroup}
                  onChange={(event) => setNewGroup(event.target.value)}
                  className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
                >
                  <option value="technical">technical</option>
                  <option value="identification">identification</option>
                  <option value="infrastructure">infrastructure</option>
                  <option value="economic">economic</option>
                  <option value="applicability">applicability</option>
                  <option value="data_quality">data_quality</option>
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-slate-700 dark:text-slate-300">Тип значения</span>
                <select
                  value={newDataType}
                  onChange={(event) => setNewDataType(event.target.value)}
                  className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
                >
                  <option value="number">number</option>
                  <option value="text">text</option>
                  <option value="boolean">boolean</option>
                  <option value="date">date</option>
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-slate-700 dark:text-slate-300">Единица</span>
                <input
                  value={newUnit}
                  onChange={(event) => setNewUnit(event.target.value)}
                  className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
                />
              </label>
            </>
          )}
          <button
            type="submit"
            disabled={busy}
            data-testid="reference-create-submit"
            className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-emerald-700 disabled:opacity-50"
          >
            Создать
          </button>
        </form>
      )}

      <AdminBulkDeleteBar
        selectedCount={selected.length}
        busy={busy}
        onRequestDelete={() => setBulkConfirmOpen(true)}
        onClearSelection={() => setSelected([])}
        result={bulkResult}
        onDismissResult={() => setBulkResult(null)}
        itemLabel="Записи"
      />

      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <table className="w-full text-left text-sm" data-testid="references-table">
          <thead>
            <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
              <th className="px-4 py-3 font-medium">
                <input
                  type="checkbox"
                  checked={allChecked}
                  onChange={toggleAll}
                  disabled={busy || initialItems.length === 0}
                  aria-label="Выбрать все записи на странице"
                  data-testid="bulk-select-all"
                  className="h-4 w-4"
                />
              </th>
              <th className="px-4 py-3 font-medium">ID</th>
              {hasCode && <th className="px-4 py-3 font-medium">Код</th>}
              <th className="px-4 py-3 font-medium">Название</th>
              {isProcess && <th className="px-4 py-3 font-medium">Активен</th>}
              {isCharacteristicType && (
                <th className="px-4 py-3 font-medium">Тип значения</th>
              )}
              <th className="px-4 py-3 font-medium">Действия</th>
            </tr>
          </thead>
          <tbody>
            {initialItems.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-6 text-center text-slate-500 dark:text-slate-400">
                  Записей нет
                </td>
              </tr>
            )}
            {initialItems.map((item) => (
              <tr
                key={item.id}
                className={
                  "border-b border-slate-100 dark:border-slate-800 " +
                  (selected.includes(item.id)
                    ? "bg-rose-50/50 dark:bg-rose-950/20"
                    : "")
                }
                data-testid="reference-row"
              >
                <td className="px-4 py-3">
                  <input
                    type="checkbox"
                    checked={selected.includes(item.id)}
                    onChange={() => toggleRow(item.id)}
                    disabled={busy}
                    aria-label={`Выбрать запись ${item.name}`}
                    data-testid="bulk-row-checkbox"
                    className="h-4 w-4"
                  />
                </td>
                <td className="px-4 py-3 text-slate-400 dark:text-slate-500">{item.id}</td>
                {hasCode &&
                  (editingId === item.id ? (
                    <td className="px-4 py-3">
                      <input
                        data-testid="reference-edit-code"
                        value={editCode}
                        onChange={(event) => setEditCode(event.target.value)}
                        pattern="[a-z][a-z0-9_]*"
                        className="w-36 rounded-lg border border-slate-300 px-2 py-1 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
                      />
                    </td>
                  ) : (
                    <td className="px-4 py-3 font-mono text-xs text-slate-600 dark:text-slate-300">
                      {item.code}
                    </td>
                  ))}
                <td className="px-4 py-3">
                  {editingId === item.id ? (
                    <input
                      data-testid="reference-edit-name"
                      value={editName}
                      onChange={(event) => setEditName(event.target.value)}
                      className="w-56 rounded-lg border border-slate-300 px-2 py-1 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
                    />
                  ) : (
                    <span className="font-medium text-slate-900 dark:text-slate-100">{item.name}</span>
                  )}
                </td>
                {isProcess &&
                  (editingId === item.id ? (
                    <td className="px-4 py-3">
                      <input
                        type="checkbox"
                        checked={editActive}
                        onChange={(event) => setEditActive(event.target.checked)}
                        className="h-4 w-4"
                      />
                    </td>
                  ) : (
                    <td className="px-4 py-3">
                      {item.isActive ? "да" : "нет"}
                    </td>
                  ))}
                {isCharacteristicType && (
                  <td className="px-4 py-3 text-slate-600 dark:text-slate-300">
                    {item.dataType}
                    {item.unit ? `, ${item.unit}` : ""}
                  </td>
                )}
                <td className="px-4 py-3">
                  <div className="flex gap-3">
                    {editingId === item.id ? (
                      <>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => onSaveEdit(item.id)}
                          className="text-sm text-emerald-700 hover:underline dark:text-emerald-400 disabled:opacity-50"
                        >
                          Сохранить
                        </button>
                        <button
                          type="button"
                          onClick={() => setEditingId(null)}
                          className="text-sm text-slate-500 hover:underline dark:text-slate-400"
                        >
                          Отмена
                        </button>
                      </>
                    ) : (
                      <>
                        <button
                          type="button"
                          onClick={() => {
                            setEditingId(item.id);
                            setEditCode(item.code ?? "");
                            setEditName(item.name);
                            setEditActive(item.isActive ?? true);
                          }}
                          className="text-sm text-emerald-700 hover:underline dark:text-emerald-400"
                        >
                          Изменить
                        </button>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => onDelete(item)}
                          className="text-sm text-rose-700 hover:underline disabled:opacity-50 dark:text-rose-400"
                        >
                          Удалить
                        </button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between text-sm">
        <span className="text-slate-500 dark:text-slate-400">Всего: {totalElements}</span>
        {totalPages > 1 && (
          <span className="flex items-center gap-3">
            {initialPage > 0 ? (
              <button
                type="button"
                onClick={() => reloadToPage(initialPage - 1)}
                className="text-emerald-700 hover:underline dark:text-emerald-400"
              >
                ← Назад
              </button>
            ) : (
              <span className="text-slate-300 dark:text-slate-600">← Назад</span>
            )}
            <span className="text-slate-500 dark:text-slate-400">
              Страница {initialPage + 1} из {totalPages}
            </span>
            {initialPage + 1 < totalPages ? (
              <button
                type="button"
                onClick={() => reloadToPage(initialPage + 1)}
                className="text-emerald-700 hover:underline dark:text-emerald-400"
              >
                Вперёд →
              </button>
            ) : (
              <span className="text-slate-300 dark:text-slate-600">Вперёд →</span>
            )}
          </span>
        )}
      </div>
    </div>
  );

  return (
    <>
      {wrapper}
      {bulkConfirmOpen && (
        <ConfirmDialog
          title="Удалить выбранные записи?"
          message={`Будет удалено ${selected.length} ${plural(
            selected.length, "запись", "записи", "записей",
          )}. Записи, на которые ссылаются решения каталога, не удалятся - причина отказа появится в сводке.`}
          confirmLabel="Удалить"
          busy={busy}
          onConfirm={onBulkDeleteConfirmed}
          onCancel={() => setBulkConfirmOpen(false)}
        />
      )}
    </>
  );
}

/** Русское склонение: 1 запись / 2-4 записи / 5+ записей. */
function plural(n: number, one: string, few: string, many: string): string {
  const mod10 = n % 10;
  const mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) {
    return one;
  }
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
    return few;
  }
  return many;
}

async function messageOf(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as {
      message?: string;
      error?: string;
    };
    return body.message ?? body.error ?? `Ошибка HTTP ${response.status}`;
  } catch {
    return `Ошибка HTTP ${response.status}`;
  }
}
