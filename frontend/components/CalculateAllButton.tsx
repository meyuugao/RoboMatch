"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

/**
 * Единая кнопка расчёта: последовательно считает все
 * три сценария (base → purchase → raas) и переходит на дашборд экономики.
 * Отдельные кнопки «Рассчитать X» на карточках сценариев убраны.
 *
 * Поведение:
 * - прогресс по шагам «Расчёт base… / purchase… / raas…» ( -
 * синхронный расчёт ≤ 10 с на сценарий; при превышении 10 с показывается
 * предупреждение, ожидание продолжается);
 * - пустой роботизированный сценарий (purchase/raas без состава) или
 * ошибка расчёта - сообщение и БЕЗ перехода на дашборд;
 * - base пуст по определению (инвариант data_model.md §10.5) -
 * рассчитывается всегда.
 */
export default function CalculateAllButton({
  projectId,
  scenarios,
}: {
  projectId: string;
  /** Сценарии проекта: id, тип, название и наличие состава. */
  scenarios: Array<{
    id: number;
    type: "base" | "purchase" | "raas" | string;
    name: string;
    solutionCount: number;
  }>;
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [stepLabel, setStepLabel] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [slowWarning, setSlowWarning] = useState<string | null>(null);

  const LABELS: Record<string, string> = {
    base: "base",
    purchase: "purchase",
    raas: "raas",
  };

  async function onCalculateAll() {
    setBusy(true);
    setError(null);
    setSlowWarning(null);
    // порядок расчёта: base → purchase → raas (как в таблице сравнения)
    const ordered = ["base", "purchase", "raas"]
      .map((type) => scenarios.find((s) => s.type === type))
      .filter((s): s is NonNullable<typeof s> => s !== undefined);
    try {
      for (const scenario of ordered) {
        const label = LABELS[scenario.type] ?? scenario.type;
        // Пустой роботизированный сценарий - ошибка без перехода:
        // расчёт пустого состава возможен (ΔFOT = 0), но не
        // имеет смысла для сравнения - состав нужно наполнить
        if (scenario.type !== "base" && scenario.solutionCount === 0) {
          setError(
            `Сценарий «${scenario.name}» пуст - добавьте решения ` +
              "(подбором или вручную) и повторите расчёт.",
          );
          return;
        }
        setStepLabel(`Расчёт ${label}…`);
        // Предупреждение о превышении 10 с на сценарий,
        // ожидание НЕ прерывается
        const slowTimer = setTimeout(() => {
          setSlowWarning(
            `Расчёт ${label} длится более 10 секунд - backend ` +
              "перегружен или ждёт БД.",
          );
        }, 10_000);
        try {
          const response = await fetch(
            `/api/projects/${projectId}/scenarios/${scenario.id}/calculate`,
            { method: "POST" },
          );
          if (!response.ok) {
            let message = `Ошибка расчёта ${label} (HTTP ${response.status})`;
            try {
              const body = (await response.json()) as {
                message?: string;
                error?: string;
              };
              message = body.message ?? body.error ?? message;
            } catch {
              // fallback-текст уже задан
            }
            setError(message);
            return;
          }
        } finally {
          clearTimeout(slowTimer);
        }
      }
      // все сценарии рассчитаны - на дашборд сравнения
      setStepLabel(null);
      router.push(`/projects/${projectId}/economics`);
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    } finally {
      setBusy(false);
      setStepLabel(null);
    }
  }

  return (
    // shrink-0 - блок кнопки не сжимается длинным подзаголовком
    // и остаётся в правой части строки заголовка
    <div className="flex shrink-0 flex-col items-end gap-1">
      <button
        type="button"
        onClick={onCalculateAll}
        disabled={busy || scenarios.length === 0}
        className="inline-flex items-center gap-2 rounded-lg bg-sky-700 px-4 py-2 text-sm
                   font-medium text-white transition hover:bg-sky-800
                   disabled:cursor-wait disabled:opacity-70"
      >
        {busy ? (
          <span
            aria-hidden
            className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white"
          />
        ) : null}
        {busy
          ? stepLabel ?? "Расчёт…"
          : "Рассчитать и перейти к дашборду →"}
      </button>
      {slowWarning && !error ? (
        <p className="max-w-md text-right text-xs text-amber-700 dark:text-amber-400">
          {slowWarning}
        </p>
      ) : null}
      {error ? (
        <p className="max-w-md text-right text-xs text-rose-700 dark:text-rose-400">{error}</p>
      ) : null}
    </div>
  );
}
