"use client";

import { useEffect, useState } from "react";
import ScenarioComparison from "@/components/ScenarioComparison";
import type {
  DemoCalculation,
  DemoDescriptor,
} from "@/types/demo";

/**
 * Интерактивная часть гостевого демо-расчёта: загрузка описания демо-набора
 * (типы объектов, параметры, состав), выбор типа объекта и запуск расчёта.
 * Результат - та же таблица сравнения трёх сценариев, что и в проекте,
 * с пометкой о том, что демо-расчёт не сохраняется.
 *
 * Клиентский компонент: расчёт запускается кнопкой; состояние - локальное
 * (у гостя нет проектов - сохранять нечего и некуда).
 */
export default function DemoCalculator() {
  const [descriptor, setDescriptor] = useState<DemoDescriptor | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [objectTypeCode, setObjectTypeCode] = useState<string>("warehouse");
  const [result, setResult] = useState<DemoCalculation | null>(null);
  const [calculating, setCalculating] = useState(false);
  const [calcError, setCalcError] = useState<string | null>(null);
  const [calculatedOnce, setCalculatedOnce] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch("/api/demo", { cache: "no-store" })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error("Не удалось загрузить описание демо-расчёта");
        }
        return (await response.json()) as DemoDescriptor;
      })
      .then((data) => {
        if (!cancelled) {
          setDescriptor(data);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLoadError(
            "Сервис расчёта недоступен. Обновите страницу или попробуйте позже.",
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function calculate() {
    if (calculating) {
      return;
    }
    setCalculating(true);
    setCalcError(null);
    try {
      const response = await fetch("/api/demo/calculate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ objectTypeCode }),
      });
      const body = await response.json();
      if (!response.ok) {
        setCalcError(
          typeof body?.message === "string"
            ? body.message
            : "Не удалось выполнить демо-расчёт. Попробуйте ещё раз.",
        );
        return;
      }
      setResult(body as DemoCalculation);
      setCalculatedOnce(true);
    } catch {
      setCalcError("Сеть недоступна. Проверьте подключение и повторите.");
    } finally {
      setCalculating(false);
    }
  }

  /**
 * Значение параметра демо-набора: числа - с разделителями тысяч
 * (ru-RU), прочее (тексты, диапазоны) - как есть.
 */
  function formatValue(value: string): string {
    const parsed = Number(value.replace(",", "."));
    if (!Number.isFinite(parsed) || !/^\d+([.,]\d+)?$/.test(value)) {
      return value;
    }
    return parsed.toLocaleString("ru-RU", {
      maximumFractionDigits: 3,
    });
  }

  if (loadError) {
    return (
      <div
        role="alert"
        className="rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-800 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-300"
      >
        {loadError}
      </div>
    );
  }

  if (!descriptor) {
    return (
      <div
        data-testid="demo-loading"
        className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-500 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-400"
      >
        Загружаем демо-набор данных…
      </div>
    );
  }

  const availableTypes = descriptor.availableTypes ?? [];
  const activeType =
    availableTypes.find((type) => type.code === objectTypeCode) ??
    availableTypes.find((type) => type.available) ??
    null;

  return (
    <div className="space-y-6">
      {/* Выбор типа объекта */}
      <section
        aria-label="Выбор типа объекта"
        className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:p-6"
      >
        <h2 className="mb-1 text-lg font-semibold text-slate-900 dark:text-slate-100">
          Тип объекта
        </h2>
        <p className="mb-4 text-sm text-slate-600 dark:text-slate-400">
          Демо-набор данных подготовлен для склада - другие типы появятся
          по мере расширения платформы.
        </p>
        <div
          role="radiogroup"
          aria-label="Тип объекта демо-расчёта"
          className="flex flex-wrap gap-3"
        >
          {availableTypes.map((type) => {
            const selected = type.code === objectTypeCode;
            return (
              <button
                key={type.code}
                type="button"
                role="radio"
                aria-checked={selected}
                disabled={!type.available}
                data-testid={`demo-type-${type.code}`}
                title={
                  type.available
                    ? `Демо-расчёт для типа «${type.name}»`
                    : `Демо-расчёт для «${type.name}» пока не поддерживается`
                }
                onClick={() => setObjectTypeCode(type.code)}
                className={[
                  "min-h-11 rounded-lg border px-4 py-2 text-sm font-medium transition-colors",
                  type.available
                    ? "cursor-pointer"
                    : "cursor-not-allowed opacity-50",
                  selected
                    ? "border-emerald-600 bg-emerald-50 text-emerald-800 dark:border-emerald-500 dark:bg-emerald-950 dark:text-emerald-300"
                    : "border-slate-300 bg-white text-slate-700 hover:border-slate-400 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300",
                ].join(" ")}
              >
                {type.name}
                {!type.available && (
                  <span className="ml-2 text-xs text-slate-400 dark:text-slate-500">
                    скоро
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </section>

      {/* Параметры демо-набора */}
      <section
        aria-label="Параметры демо-набора"
        className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:p-6"
      >
        <h2 className="mb-1 text-lg font-semibold text-slate-900 dark:text-slate-100">
          Параметры объекта
        </h2>
        <p className="mb-4 text-sm text-slate-600 dark:text-slate-400">
          Предзаполненные значения из демо-набора данных
          {activeType ? ` - ${activeType.name.toLowerCase()}` : ""}. В демо
          значения не редактируются: свои параметры можно задать в проекте
          после регистрации.
        </p>
        <dl
          data-testid="demo-parameters"
          className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-3"
        >
          {descriptor.parameters.map((parameter) => (
            <div
              key={parameter.title}
              className="flex items-baseline justify-between gap-2 border-b border-dashed border-slate-200 pb-2 dark:border-slate-800"
            >
              <dt className="text-sm text-slate-600 dark:text-slate-400">
                {parameter.title}
              </dt>
              <dd className="whitespace-nowrap text-sm font-semibold text-slate-900 dark:text-slate-100">
                {formatValue(parameter.value)}
                {parameter.unit && (
                  <span className="ml-1 text-xs font-normal text-slate-500 dark:text-slate-400">
                    {parameter.unit}
                  </span>
                )}
              </dd>
            </div>
          ))}
        </dl>
      </section>

      {/* Демонстрационный состав */}
      <section
        aria-label="Демонстрационный состав"
        className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:p-6"
      >
        <h2 className="mb-1 text-lg font-semibold text-slate-900 dark:text-slate-100">
          Демонстрационный состав
        </h2>
        <p className="mb-4 text-sm text-slate-600 dark:text-slate-400">
          Решения из каталога, которые сравниваются с текущим процессом.
        </p>
        <ul data-testid="demo-composition" className="space-y-2">
          {descriptor.composition.map((line) => (
            <li
              key={line.solutionName}
              className="flex flex-wrap items-baseline justify-between gap-2 rounded-lg bg-slate-50 px-4 py-2 dark:bg-slate-800"
            >
              <span className="text-sm font-medium text-slate-900 dark:text-slate-100">
                {line.solutionName}
              </span>
              <span className="text-sm text-slate-500 dark:text-slate-400">
                {line.vendorName} · {line.quantity} ед.
              </span>
            </li>
          ))}
        </ul>
      </section>

      {/* Запуск расчёта */}
      <div className="flex flex-wrap items-center gap-4">
        <button
          type="button"
          data-testid="demo-calculate-button"
          onClick={calculate}
          disabled={calculating}
          className="min-h-11 rounded-lg bg-emerald-600 px-5 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {calculating ? "Считаем…" : "Показать демо-расчёт"}
        </button>
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Расчёт выполняется в памяти и не сохраняется.
        </p>
      </div>

      {calcError && (
        <div
          role="alert"
          className="rounded-lg border border-red-300 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300"
        >
          {calcError}
        </div>
      )}

      {/* Результат: таблица сравнения трёх сценариев */}
      {result && (
        <section
          aria-label="Результат демо-расчёта"
          data-testid="demo-result"
          className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900 sm:p-6"
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
              Сравнение сценариев - {result.objectTypeName}
            </h2>
            <span
              data-testid="demo-disclaimer"
              className="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-600 dark:bg-slate-800 dark:text-slate-300"
            >
              Демонстрационный расчёт · не сохраняется
            </span>
          </div>
          <ScenarioComparison
            scenarios={result.comparison.scenarios}
            horizonYears={result.comparison.horizonYears}
          />
          <p className="text-xs text-slate-500 dark:text-slate-400">
            Модель расчёта: {result.comparison.versionModel ?? "-"}. Это
            демонстрация: значения взяты из демо-набора данных, а не из
            обследования вашего объекта. Зарегистрируйтесь, чтобы создать
            проект со своими параметрами.
          </p>
        </section>
      )}

      {calculatedOnce && !result && !calcError && (
        <p className="text-sm text-slate-500 dark:text-slate-400">
          Готово - расчёт не сохранён. Нажмите «Показать демо-расчёт» ещё раз.
        </p>
      )}
    </div>
  );
}
