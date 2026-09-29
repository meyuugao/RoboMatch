"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { METRIC_LABELS, formatPct, formatRub, formatYears } from "@/types/economics";

/** Значение метрики в её единицах (руб. / лет / %). */
function formatMetricValue(
  metricName: string,
  value: number | null,
): string {
  if (value === null) {
    return "-";
  }
  if (metricName === "payback_years") {
    return formatYears(value);
  }
  if (metricName === "roi_pct") {
    return formatPct(value);
  }
  return formatRub(value);
}

/**
 * Модалка ручной корректировки метрики расчёта: что / было /
 * стало / почему (автор и время фиксируются сервером из JWT и clock).
 * Порождает НОВЫЙ расчёт - история не переписывается (append-only).
 */
export default function ManualAdjustDialog({
  projectId,
  calculationId,
  metricCandidates,
  onClose,
}: {
  projectId: string;
  calculationId: number;
  metricCandidates: Array<{
    metricName: string;
    currentValue: number | null;
  }>;
  onClose: () => void;
}) {
  const router = useRouter();
  const first = metricCandidates[0];
  const [metricName, setMetricName] = useState<string>(
    first ? first.metricName : "effect_year",
  );
  const selected = metricCandidates.find((m) => m.metricName === metricName);
  const [newValue, setNewValue] = useState(
    first && first.currentValue !== null ? String(first.currentValue) : "",
  );
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    const parsed = Number(newValue.replace(/\s/g, "").replace(",", "."));
    if (!Number.isFinite(parsed) || parsed < 0) {
      setError("Укажите неотрицательное числовое значение.");
      return;
    }
    if (reason.trim().length < 5) {
      setError("Укажите причину корректировки (не короче 5 символов) - она попадёт в отчёт.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/projects/${projectId}/calculations/${calculationId}/adjust`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            metricName,
            newValue: parsed,
            reason: reason.trim(),
          }),
        },
      );
      if (response.ok) {
        router.refresh();
        onClose();
        return;
      }
      let message = `Ошибка (HTTP ${response.status})`;
      try {
        const body = (await response.json()) as { message?: string; error?: string };
        message = body.message ?? body.error ?? message;
      } catch {
        // тело не JSON - fallback
      }
      setError(message);
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-lg rounded-xl bg-white p-6 shadow-xl dark:bg-slate-900">
        <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">
          Скорректировать значение
        </h2>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Расчёт №{calculationId}. Корректировка породит новый расчёт,
          исходный останется в истории.
        </p>

        <form onSubmit={onSubmit} className="mt-4 space-y-4">
          <label className="block">
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Метрика</span>
            <select
              value={metricName}
              onChange={(e) => {
                setMetricName(e.target.value);
                const next = metricCandidates.find(
                  (m) => m.metricName === e.target.value,
                );
                setNewValue(
                  next && next.currentValue !== null
                    ? String(next.currentValue)
                    : "",
                );
              }}
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm dark:border-slate-700 dark:text-slate-100"
            >
              {metricCandidates.map((m) => (
                <option key={m.metricName} value={m.metricName}>
                  {METRIC_LABELS[m.metricName] ?? m.metricName}
                  {m.currentValue !== null
                    ? ` (было: ${formatMetricValue(m.metricName, m.currentValue)})`
                    : ""}
                </option>
              ))}
            </select>
          </label>

          <label className="block">
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">
              Новое значение
            </span>
            <input
              type="text"
              inputMode="decimal"
              value={newValue}
              onChange={(e) => setNewValue(e.target.value)}
              placeholder="Например, 145000000"
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm dark:border-slate-700 dark:text-slate-100"
            />
            {selected && selected.currentValue !== null ? (
              <span className="mt-1 block text-xs text-slate-500 dark:text-slate-400">
                Текущее: {formatMetricValue(metricName, selected.currentValue)}
              </span>
            ) : null}
          </label>

          <label className="block">
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">
              Причина (обязательна)
            </span>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              rows={3}
              placeholder="Например: согласована скидка вендора 8% на сервис"
              className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm dark:border-slate-700 dark:text-slate-100"
            />
          </label>

          {error ? (
            <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
              {error}
            </p>
          ) : null}

          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
            >
              Отмена
            </button>
            <button
              type="submit"
              disabled={saving}
              className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:opacity-60 dark:bg-slate-100 dark:text-slate-900 dark:hover:bg-slate-200"
            >
              {saving ? "Сохранение…" : "Скорректировать"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
