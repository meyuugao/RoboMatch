"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { SolutionCharacteristic } from "@/types/solution";

/**
 * Редактор ТТХ решения (EAV solution_characteristic):
 * таблица текущих значений с провенансом + добавление/правка/удаление.
 *
 * Тип значения контролируется типом характеристики (number/text/boolean/
 * date): форма подставляет нужный инпут. Провенанс: «вручную»
 * (is_confirmed=false) или «открытый источник» (обязательны ссылка и
 * дата, is_confirmed=true -/уточнение организатора).
 */
export default function AdminCharacteristicsEditor({
  solutionId,
  characteristics,
  types,
}: {
  solutionId: number;
  characteristics: SolutionCharacteristic[];
  types: {
    id: number;
    code: string;
    name: string;
    dataType: string | null;
    unit: string | null;
  }[];
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // EAV-ключ в API - id типа характеристики; в карточке значения
  // приходят с кодом - сопоставляем код -> id
  const typeIdByCode = new Map(types.map((type) => [type.code, type.id]));

  const existingTypeIds = new Set(characteristics.map((c) => c.typeCode));
  // Upsert-семантика: можно выбрать любой тип (существующее значение
  // будет перезаписано), но по умолчанию предлагаем ещё не заполненные
  const addableTypes = [...types].sort(
    (a, b) =>
      Number(existingTypeIds.has(b.code)) - Number(existingTypeIds.has(a.code)),
  );

  const [selectedTypeId, setSelectedTypeId] = useState<number | "">("");
  const [value, setValue] = useState("");
  const [sourceKind, setSourceKind] = useState<"manual" | "open_source">("manual");
  const [sourceUrl, setSourceUrl] = useState("");
  const [sourceDate, setSourceDate] = useState("");

  const selectedType = addableTypes.find((type) => type.id === selectedTypeId);

  async function onUpsert(event: React.FormEvent) {
    event.preventDefault();
    if (selectedTypeId === "") {
      setError("Выберите характеристику");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const body: Record<string, unknown> = { sourceKind };
      if (selectedType?.dataType === "number") {
        body.valueNumeric = Number(value.replace(",", "."));
      } else if (selectedType?.dataType === "boolean") {
        body.valueBool = value === "true";
      } else if (selectedType?.dataType === "date") {
        body.valueDate = value;
      } else {
        body.valueText = value;
      }
      if (sourceKind === "open_source") {
        body.sourceUrl = sourceUrl;
        body.sourceDate = sourceDate;
      }
      const response = await fetch(
        `/api/admin/solutions/${solutionId}/characteristics/${selectedTypeId}`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        },
      );
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      setValue("");
      setSourceUrl("");
      setSourceDate("");
      setSelectedTypeId("");
      router.refresh();
    } catch (upsertError) {
      setError(
        upsertError instanceof Error ? upsertError.message : "Ошибка сохранения",
      );
    } finally {
      setBusy(false);
    }
  }

  async function onDelete(typeCode: string, label: string) {
    const typeId = typeIdByCode.get(typeCode);
    if (typeId === undefined) {
      setError("Тип характеристики не найден - обновите страницу");
      return;
    }
    if (!window.confirm(`Удалить значение «${label}»?`)) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/admin/solutions/${solutionId}/characteristics/${typeId}`,
        { method: "DELETE" },
      );
      if (!response.ok) {
        throw new Error(await messageOf(response));
      }
      router.refresh();
    } catch (deleteError) {
      setError(
        deleteError instanceof Error ? deleteError.message : "Ошибка удаления",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-800 dark:bg-slate-900">
      <h2 className="font-semibold text-slate-900 dark:text-slate-100">Характеристики (ТТХ)</h2>
      <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
        Значения хранятся с провенансом: источник, ссылка и отметка
        подтверждения видны в отчётах и подбору.
      </p>

      {error !== null && (
        <div
          role="alert"
          data-testid="characteristics-error"
          className="mt-3 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      <div className="mt-4 overflow-x-auto">
        <table className="w-full text-left text-sm" data-testid="characteristics-table">
          <thead>
            <tr className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800 dark:text-slate-400">
              <th className="py-2 pr-4 font-medium">Характеристика</th>
              <th className="py-2 pr-4 font-medium">Значение</th>
              <th className="py-2 pr-4 font-medium">Источник</th>
              <th className="py-2 pr-4 font-medium">Подтверждено</th>
              <th className="py-2 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {characteristics.length === 0 && (
              <tr>
                <td colSpan={5} className="py-4 text-center text-slate-500 dark:text-slate-400">
                  Значений пока нет
                </td>
              </tr>
            )}
            {characteristics.map((characteristic) => (
              <tr
                key={characteristic.typeCode}
                className="border-b border-slate-100 dark:border-slate-800"
                data-testid={`characteristic-row-${characteristic.typeCode}`}
              >
                <td className="py-2 pr-4 font-medium text-slate-900 dark:text-slate-100">
                  {characteristic.typeName}
                  {characteristic.unit ? `, ${characteristic.unit}` : ""}
                </td>
                <td className="py-2 pr-4 text-slate-700 dark:text-slate-300">
                  {displayValue(characteristic)}
                </td>
                <td className="py-2 pr-4 text-slate-600 dark:text-slate-300">
                  {SOURCE_LABELS[characteristic.sourceKind] ??
                    characteristic.sourceKind}
                  {characteristic.sourceUrl ? (
                    <a
                      href={characteristic.sourceUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="ml-1 text-emerald-700 hover:underline dark:text-emerald-400"
                    >
                      ссылка
                    </a>
                  ) : null}
                </td>
                <td className="py-2 pr-4">
                  {characteristic.isConfirmed ? "да" : "нет"}
                </td>
                <td className="py-2 text-right">
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => onDelete(characteristic.typeCode, characteristic.typeName)}
                    className="text-sm text-rose-700 hover:underline disabled:opacity-50 dark:text-rose-400"
                  >
                    Удалить
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <form
        onSubmit={onUpsert}
        className="mt-4 flex flex-wrap items-end gap-3 rounded-xl bg-slate-50 p-4 dark:bg-slate-800"
        data-testid="characteristics-add"
      >
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Характеристика</span>
          <select
            data-testid="characteristic-type"
            value={selectedTypeId}
            onChange={(event) => {
              setSelectedTypeId(
                event.target.value === "" ? "" : Number(event.target.value),
              );
              setValue("");
            }}
            className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">- выбрать -</option>
            {addableTypes.map((type) => (
              <option key={type.id} value={type.id}>
                {type.name}
                {type.unit ? `, ${type.unit}` : ""}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Значение</span>
          {selectedType?.dataType === "boolean" ? (
            <select
              data-testid="characteristic-value"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            >
              <option value="">-</option>
              <option value="true">Да</option>
              <option value="false">Нет</option>
            </select>
          ) : (
            <input
              data-testid="characteristic-value"
              type={selectedType?.dataType === "date" ? "date" : "text"}
              inputMode={
                selectedType?.dataType === "number" ? "decimal" : undefined
              }
              value={value}
              onChange={(event) => setValue(event.target.value)}
              required
              className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            />
          )}
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-slate-700 dark:text-slate-300">Источник</span>
          <select
            data-testid="characteristic-source"
            value={sourceKind}
            onChange={(event) =>
              setSourceKind(event.target.value as "manual" | "open_source")
            }
            className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="manual">Вручную</option>
            <option value="open_source">Открытый источник</option>
          </select>
        </label>

        {sourceKind === "open_source" && (
          <>
            <label className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-slate-700 dark:text-slate-300">Ссылка *</span>
              <input
                data-testid="characteristic-url"
                value={sourceUrl}
                onChange={(event) => setSourceUrl(event.target.value)}
                required
                className="w-64 rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-slate-700 dark:text-slate-300">Дата *</span>
              <input
                data-testid="characteristic-date"
                type="date"
                value={sourceDate}
                onChange={(event) => setSourceDate(event.target.value)}
                required
                className="rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
              />
            </label>
          </>
        )}

        <button
          type="submit"
          disabled={busy}
          data-testid="characteristic-save"
          className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-emerald-700 disabled:opacity-50"
        >
          Сохранить значение
        </button>
      </form>
    </section>
  );
}

const SOURCE_LABELS: Record<string, string> = {
  manual: "Вручную",
  open_source: "Открытый источник",
  organizer_catalog: "Таблица каталога",
};

function displayValue(characteristic: SolutionCharacteristic): string {
  if (characteristic.valueNumeric != null) {
    return String(characteristic.valueNumeric);
  }
  if (characteristic.valueText != null) {
    return characteristic.valueText;
  }
  if (characteristic.valueBool != null) {
    return characteristic.valueBool ? "Да" : "Нет";
  }
  if (characteristic.valueDate != null) {
    return characteristic.valueDate;
  }
  return "-";
}

async function messageOf(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    return body.message ?? `Ошибка HTTP ${response.status}`;
  } catch {
    return `Ошибка HTTP ${response.status}`;
  }
}
