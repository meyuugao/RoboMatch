"use client";

import { useMemo, useState } from "react";
import type { ParameterDto } from "@/types/parameter";

/**
 * Форма параметров объекта: секции по
 * groupName, поля по valueType, дефолт в подсказке, источник норматива,
 * диапазоны, inline-ошибки со способом исправления.
 *
 * Схема сохранения - per-field PUT: кнопка «Сохранить»
 * отправляет ТОЛЬКО изменённые поля, последовательно (await), ошибки
 * привязываются к конкретному полю; сброс значения - DELETE (крестик).
 * Валидация на клиенте совпадает с серверной (тип/диапазон).
 */
export default function ParameterForm({
  projectId,
  parameters,
}: {
  projectId: string;
  parameters: ParameterDto[];
}) {
  // Строковое представление значения поля (для number/text) и «да/нет»
  // для boolean: единая карта по id параметра.
  const [values, setValues] = useState<Record<number, string>>(() =>
    Object.fromEntries(
      parameters.map((p) => [p.id, initialValue(p)]),
    ),
  );
  const [errors, setErrors] = useState<Record<number, string>>({});
  const [saved, setSaved] = useState<Record<number, boolean>>({});
  const [globalError, setGlobalError] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const groups = useMemo(() => groupByGroupName(parameters), [parameters]);

  /** Изменённые поля (сравнение с начальным значением из API).
 * Фиксированные и производные не редактируются - в diff
 * не попадают, даже если сервер прислал их значение. */
  function changedParameters(): ParameterDto[] {
    return parameters.filter(
      (p) => !notEditable(p) && (values[p.id] ?? "") !== (initialValue(p) ?? ""),
    );
  }

  /** Клиентская валидация, зеркальная серверной. */
  function validateField(p: ParameterDto, raw: string): string | null {
    const trimmed = raw.trim();
    if (trimmed === "") {
      return null; // пустое - не отправляем; обязательность гейтится при расчёте
    }
    if (p.valueType === "number") {
      const normalized = trimmed.replace(",", ".").replace(/\s/g, "");
      if (!/^-?\d+(\.\d+)?$/.test(normalized)) {
        return `Введите число, например ${hintDefault(p)}`;
      }
      const value = Number(normalized);
      if (p.minValue !== null && value < p.minValue) {
        return `Значение не может быть меньше ${p.minValue}${unitSuffix(p)}`;
      }
      if (p.maxValue !== null && value > p.maxValue) {
        return `Значение не может быть больше ${p.maxValue}${unitSuffix(p)}`;
      }
    }
    return null;
  }

  function onFieldChange(p: ParameterDto, raw: string) {
    setValues((prev) => ({ ...prev, [p.id]: raw }));
    setErrors((prev) => {
      const next = { ...prev };
      delete next[p.id];
      return next;
    });
    setSaved((prev) => {
      const next = { ...prev };
      delete next[p.id];
      return next;
    });
    setStatus(null);
    setGlobalError(null);
  }

  async function onSave(event: React.FormEvent) {
    event.preventDefault();
    const changed = changedParameters();
    if (changed.length === 0) {
      setStatus("Нет изменений для сохранения");
      return;
    }
    // сначала клиентская валидация всех изменённых
    const clientErrors: Record<number, string> = {};
    for (const p of changed) {
      const message = validateField(p, values[p.id] ?? "");
      if (message !== null) {
        clientErrors[p.id] = message;
      }
    }
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length > 0) {
      setGlobalError(
        "Проверьте выделенные поля - они не сохранены, остальные можно сохранить",
      );
      return;
    }

    setSaving(true);
    setGlobalError(null);
    let okCount = 0;
    const serverErrors: Record<number, string> = {};
    for (const p of changed) {
      const raw = (values[p.id] ?? "").trim();
      if (raw === "") {
        // сброс: пустое поле = удалить значение (дефолт вернётся из метаданных)
        const response = await fetch(
          `/api/projects/${projectId}/parameters/${p.id}`,
          { method: "DELETE" },
        );
        if (response.ok) {
          okCount += 1;
        } else {
          serverErrors[p.id] = await errorMessage(response);
        }
        continue;
      }
      const body = JSON.stringify({ value: typedValue(p, raw) });
      const response = await fetch(
        `/api/projects/${projectId}/parameters/${p.id}`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body,
        },
      );
      if (response.ok) {
        okCount += 1;
      } else {
        serverErrors[p.id] = await errorMessage(response);
      }
    }
    setSaving(false);
    setErrors(serverErrors);
    setSaved(
      Object.fromEntries(
        changed
          .filter((p) => !(p.id in serverErrors))
          .map((p) => [p.id, true]),
      ),
    );
    const failed = Object.keys(serverErrors).length;
    if (failed === 0) {
      setStatus(`Сохранено полей: ${okCount}`);
    } else {
      setGlobalError(
        `Сохранено ${okCount}, не сохранено ${failed} - исправьте выделенные поля`,
      );
    }
    if (okCount > 0) {
      // производные параметры зависят от источников: подтянуть пересчёт
      await refreshComputed();
    }
  }

  /**
 * Обновление фиксированных/производных значений с сервера после
 * изменения источников (сервер - источник истины, обновление на GET).
 * Пользовательские правки редактируемых полей не затрагиваются.
 */
  async function refreshComputed() {
    try {
      const response = await fetch(`/api/projects/${projectId}/parameters`);
      if (!response.ok) {
        return;
      }
      const fresh = (await response.json()) as ParameterDto[];
      setValues((prev) => {
        const next = { ...prev };
        for (const param of fresh) {
          if (notEditable(param)) {
            next[param.id] = initialValue(param);
          }
        }
        return next;
      });
    } catch {
      // сервер недоступен - вычисленные значения обновятся при перезагрузке
    }
  }

  async function onReset(p: ParameterDto) {
    setSaving(true);
    const response = await fetch(
      `/api/projects/${projectId}/parameters/${p.id}`,
      { method: "DELETE" },
    );
    setSaving(false);
    if (response.ok) {
      onFieldChange(p, "");
      setStatus(`Значение «${p.name}» сброшено`);
      await refreshComputed();
    } else {
      const message = await errorMessage(response);
      setErrors((prev) => ({ ...prev, [p.id]: message }));
    }
  }

  if (parameters.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">
        Для этого типа объекта параметры ещё не заведены. Обратитесь к
        администратору - справочник параметров наполняется через раздел
        «Управление».
      </div>
    );
  }

  return (
    <form onSubmit={onSave} className="flex flex-col gap-5">
      {globalError !== null && (
        <div
          role="alert"
          className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950/40 dark:text-red-300"
        >
          {globalError}
        </div>
      )}
      {status !== null && globalError === null && (
        <div
          role="status"
          className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-400"
        >
          {status}
        </div>
      )}

      {groups.map(([groupName, groupParameters]) => (
        <section
          key={groupName}
          className="rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900"
        >
          <h2 className="border-b border-slate-100 px-4 py-3 text-sm font-semibold text-slate-700 dark:border-slate-800 dark:text-slate-300">
            {groupName}
          </h2>
          <div className="grid grid-cols-1 gap-x-6 gap-y-4 p-4 lg:grid-cols-2">
            {groupParameters.map((p) => (
              <div key={p.id} className="flex flex-col gap-1">
                <label htmlFor={`param-${p.id}`} className="text-sm text-slate-700 dark:text-slate-300">
                  {p.isRequired && !notEditable(p) && (
                    <span className="mr-0.5 text-red-600 dark:text-red-400" title="Обязательный параметр">
                      *
                    </span>
                  )}
                  {p.name}
                  {displayUnit(p) !== null && (
                    <span className="ml-1 text-slate-400 dark:text-slate-500">({displayUnit(p)})</span>
                  )}
                  {notEditable(p) && (
                    <span
                      title={
                        p.isFixed
                          ? "Фиксированный параметр: значение задаётся системой"
                          : "Значение рассчитывается автоматически"
                      }
                      className="ml-2 rounded-full border border-slate-300 bg-slate-100
                                 px-2 py-0.5 text-[11px] font-medium text-slate-500
                                 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-400"
                    >
                      фиксировано
                    </span>
                  )}
                </label>

                <div className="flex items-center gap-2">
                  {p.valueType === "boolean" ? (
                    <select
                      id={`param-${p.id}`}
                      value={values[p.id] ?? ""}
                      onChange={(e) => onFieldChange(p, e.target.value)}
                      disabled={notEditable(p)}
                      className="w-full rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700
                                 text-sm text-slate-900 focus:border-slate-500 focus:outline-none
                                 disabled:cursor-not-allowed disabled:border-slate-200
                                 disabled:bg-slate-100 disabled:text-slate-500
                                 dark:text-slate-100 dark:disabled:border-slate-800
                                 dark:disabled:bg-slate-800 dark:disabled:text-slate-400"
                    >
                      <option value="">- не задано -</option>
                      <option value="да">да</option>
                      <option value="нет">нет</option>
                    </select>
                  ) : (
                    <input
                      id={`param-${p.id}`}
                      type={p.valueType === "number" ? "number" : "text"}
                      step="any"
                      inputMode={p.valueType === "number" ? "decimal" : undefined}
                      value={values[p.id] ?? ""}
                      placeholder={hintDefault(p)}
                      onChange={(e) => onFieldChange(p, e.target.value)}
                      readOnly={notEditable(p)}
                      disabled={notEditable(p)}
                      aria-invalid={errors[p.id] !== undefined}
                      aria-readonly={notEditable(p)}
                      className="w-full rounded-lg border border-slate-300 px-3 py-2 dark:border-slate-700
                                 text-sm text-slate-900 focus:border-slate-500 focus:outline-none
                                 read-only:cursor-not-allowed read-only:bg-slate-100
                                 read-only:text-slate-500 disabled:cursor-not-allowed
                                 disabled:border-slate-200 disabled:bg-slate-100
                                 disabled:text-slate-500 dark:text-slate-100
                                 dark:read-only:bg-slate-800 dark:read-only:text-slate-400
                                 dark:disabled:border-slate-800 dark:disabled:bg-slate-800
                                 dark:disabled:text-slate-400"
                    />
                  )}
                  {p.currentValue !== null && !notEditable(p) && (
                    <button
                      type="button"
                      onClick={() => onReset(p)}
                      disabled={saving}
                      title="Сбросить значение (вернётся значение по умолчанию)"
                      className="shrink-0 rounded-lg border border-slate-300 px-2 py-1
                                 text-xs text-slate-500 transition hover:bg-slate-100
                                 dark:border-slate-700 dark:text-slate-400 dark:hover:bg-slate-800"
                    >
                      сброс
                    </button>
                  )}
                </div>

                <p className="text-xs text-slate-400 dark:text-slate-500">
                  {[
                    derivedHint(p),
                    hintRange(p),
                    hintSource(p),
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
                {errors[p.id] !== undefined ? (
                  <p role="alert" className="text-xs text-red-600 dark:text-red-400">
                    {errors[p.id]}
                  </p>
                ) : saved[p.id] ? (
                  <p className="text-xs text-emerald-600 dark:text-emerald-400">Сохранено</p>
                ) : null}
              </div>
            ))}
          </div>
        </section>
      ))}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={saving}
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white
                     transition hover:bg-slate-700 disabled:opacity-50 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
        >
          {saving ? "Сохранение..." : "Сохранить изменения"}
        </button>
        <span className="text-xs text-slate-500 dark:text-slate-400">
          Отправляются только изменённые поля
        </span>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------------
// Внутренние утилиты
// ---------------------------------------------------------------------

/** Начальное строковое значение поля из currentValue. */
function initialValue(p: ParameterDto): string {
  const value = p.currentValue?.value;
  if (value === null || value === undefined) {
    return "";
  }
  if (p.valueType === "boolean") {
    return value ? "да" : "нет";
  }
  if (typeof value === "number") {
    return trimNumber(value);
  }
  return String(value);
}

/** Значение для PUT по типу параметра (клиент шлёт типизированный JSON). */
function typedValue(
  p: ParameterDto,
  raw: string,
): number | string | boolean {
  if (p.valueType === "boolean") {
    return raw === "да";
  }
  if (p.valueType === "number") {
    return Number(raw.replace(",", ".").replace(/\s/g, ""));
  }
  return raw;
}

function trimNumber(value: number): string {
  return String(Number.isInteger(value) ? value : Number(value.toFixed(4)));
}

function hintDefault(p: ParameterDto): string {
  const value = p.defaultValue?.value;
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "boolean") {
    return `например: ${value ? "да" : "нет"}`;
  }
  if (typeof value === "number") {
    return `например: ${trimNumber(value)}`;
  }
  return `например: ${value}`;
}

function hintRange(p: ParameterDto): string {
  if (p.minValue !== null && p.maxValue !== null) {
    return `от ${p.minValue} до ${p.maxValue}`;
  }
  if (p.minValue !== null) {
    return `не меньше ${p.minValue}`;
  }
  if (p.maxValue !== null) {
    return `не больше ${p.maxValue}`;
  }
  return "";
}

function hintSource(p: ParameterDto): string {
  return p.sourceNote ? `Источник: ${p.sourceNote}` : "";
}

function unitSuffix(p: ParameterDto): string {
  return p.unit ? ` ${p.unit}` : "";
}

/** Фиксированный или производный - поле только для чтения. */
function notEditable(p: ParameterDto): boolean {
  return p.isFixed || p.isDerived;
}

/**
 * Единица рядом с названием: у worst-case параметров единица уже
 * входит в название («…, кг») - дубль «(кг) (кг)» не показываем.
 */
function displayUnit(p: ParameterDto): string | null {
  if (p.unit === null || p.unit === "") {
    return null;
  }
  return p.name.endsWith(` ${p.unit}`) || p.name.endsWith(`, ${p.unit}`)
    ? null
    : p.unit;
}

/** Подпись под полем для производного параметра. */
function derivedHint(p: ParameterDto): string | null {
  if (!p.isDerived) {
    return null;
  }
  return p.derivedFromName
    ? `Рассчитывается автоматически из «${p.derivedFromName}» - измените источник, значение пересчитается`
    : "Рассчитывается автоматически";
}

/** Секции формы: порядок групп - как в ответе API (group_name, id). */
function groupByGroupName(parameters: ParameterDto[]): [string, ParameterDto[]][] {
  const groups = new Map<string, ParameterDto[]>();
  for (const p of parameters) {
    const key = p.groupName || "Прочее";
    const list = groups.get(key) ?? [];
    list.push(p);
    groups.set(key, list);
  }
  return Array.from(groups.entries());
}

/** Сообщение об ошибке из тела BFF-ответа (ErrorResponse.message). */
async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string; error?: string };
    return body.message ?? body.error ?? `Ошибка (HTTP ${response.status})`;
  } catch {
    return `Ошибка (HTTP ${response.status})`;
  }
}
