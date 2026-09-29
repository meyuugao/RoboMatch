"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import type { AssumptionDto } from "@/types/economics";

/**
 * Редактор допущений экономики: изменяемые допущения —
 * значение (с валидацией диапазона на клиенте), зафиксированные
 * организатором и неизменяемые — только чтение с источником и влиянием
 *. Сохранение — PUT; null/пустое = сброс в дефолт.
 */
export default function AssumptionEditor({
  projectId,
  assumptions,
}: {
  projectId: string;
  assumptions: AssumptionDto[];
}) {
  const router = useRouter();
  const [values, setValues] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const a of assumptions) {
      initial[a.name] = a.value ?? "";
    }
    return initial;
  });
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const editable = assumptions.filter((a) => a.editable);
  const fixed = assumptions.filter((a) => !a.editable);

  function set(name: string, value: string) {
    setValues((v) => ({ ...v, [name]: value }));
    setSaved(false);
  }

  async function onSave(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    setSaved(false);
    const payload: Record<string, string | null> = {};
    for (const a of editable) {
      const raw = (values[a.name] ?? "").trim();
      payload[a.name] = raw === "" ? null : raw.replace(",", ".");
    }
    try {
      const response = await fetch(`/api/projects/${projectId}/assumptions`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ values: payload }),
      });
      if (response.ok) {
        setSaved(true);
        router.refresh();
        return;
      }
      let message = `Ошибка (HTTP ${response.status})`;
      try {
        const body = (await response.json()) as { message?: string; error?: string };
        message = body.message ?? body.error ?? message;
      } catch {
        // fallback
      }
      setError(message);
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setSaving(false);
    }
  }

  function field(a: AssumptionDto) {
    const isEnum = a.kind === "enum";
    const isBool = a.kind === "boolean";
    return (
      <div key={a.name} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800">
        <div className="flex flex-wrap items-baseline justify-between gap-1">
          <label
            htmlFor={`assumption-${a.name}`}
            className="text-sm font-medium text-slate-800 dark:text-slate-200"
          >
            {a.title}
          </label>
          <span className="text-xs text-slate-400 dark:text-slate-500">
            дефолт: {a.defaultValue ?? "не задан"}
            {a.unit ? ` ${a.unit}` : ""}
          </span>
        </div>
        {isEnum ? (
          <select
            id={`assumption-${a.name}`}
            value={values[a.name] ?? "fixed"}
            onChange={(e) => set(a.name, e.target.value)}
            className="mt-2 w-full rounded-lg border border-slate-300 px-3 py-1.5 text-sm dark:border-slate-700 dark:text-slate-100"
          >
            <option value="fixed">fixed — фиксированная ставка</option>
            <option value="usage">usage — плата за использование</option>
            <option value="mixed">mixed — смешанная</option>
          </select>
        ) : isBool ? (
          <select
            id={`assumption-${a.name}`}
            value={values[a.name] ?? "false"}
            onChange={(e) => set(a.name, e.target.value)}
            className="mt-2 w-full rounded-lg border border-slate-300 px-3 py-1.5 text-sm dark:border-slate-700 dark:text-slate-100"
          >
            <option value="false">Нет</option>
            <option value="true">Да</option>
          </select>
        ) : (
          <input
            id={`assumption-${a.name}`}
            type="text"
            inputMode="decimal"
            value={values[a.name] ?? ""}
            onChange={(e) => set(a.name, e.target.value)}
            placeholder={a.defaultValue ?? "не задано"}
            className="mt-2 w-full rounded-lg border border-slate-300 px-3 py-1.5 text-sm dark:border-slate-700 dark:text-slate-100"
          />
        )}
        <p className="mt-1.5 text-xs text-slate-500 dark:text-slate-400">{a.impactNote}</p>
        {a.min !== null || a.max !== null ? (
          <p className="mt-0.5 text-xs text-slate-400 dark:text-slate-500">
            Диапазон: {a.min !== null ? a.min.toLocaleString("ru-RU") : "−∞"} —{" "}
            {a.max !== null ? a.max.toLocaleString("ru-RU") : "+∞"}
            {a.unit ? ` ${a.unit}` : ""}
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <form
      onSubmit={onSave}
      className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
    >
      <h3 className="font-semibold text-slate-800 dark:text-slate-200">Допущения расчёта</h3>
      <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
        Ключевые допущения изменяемы; пустое поле = дефолт.
        Значения попадают в снимок следующего расчёта.
      </p>
      <div className="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
        {editable.map(field)}
      </div>

      {fixed.length > 0 ? (
        <div className="mt-4">
          <p className="text-xs font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400">
            Зафиксировано в справочнике (демо-датасет)
          </p>
          <div className="mt-2 grid grid-cols-1 gap-2 md:grid-cols-2">
            {fixed.map((a) => (
              <div
                key={a.name}
                className="rounded-lg border border-dashed border-slate-200 bg-slate-50 p-3 dark:border-slate-800 dark:bg-slate-800"
              >
                <p className="text-sm font-medium text-slate-700 dark:text-slate-300">{a.title}</p>
                <p className="mt-0.5 text-sm text-slate-900 dark:text-slate-100">
                  {a.value}
                  {a.unit ? ` ${a.unit}` : ""}
                </p>
                <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{a.impactNote}</p>
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {error ? (
        <p className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
          {error}
        </p>
      ) : null}
      {saved ? (
        <p className="mt-3 rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-400">
          Допущения сохранены. Пересчитайте сценарии, чтобы увидеть эффект.
        </p>
      ) : null}

      <div className="mt-4 flex justify-end">
        <button
          type="submit"
          disabled={saving}
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:opacity-60 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
        >
          {saving ? "Сохранение…" : "Сохранить допущения"}
        </button>
      </div>
    </form>
  );
}
