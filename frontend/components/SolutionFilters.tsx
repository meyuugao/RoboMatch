"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { CatalogParams } from "@/lib/api";
import { buildCatalogUrl } from "@/lib/catalog-url";
import { SOLUTION_STATUS_LABELS, type FiltersData } from "@/types/solution";

/**
 * Панель фильтров каталога: тип, подтип (зависимый список по
 * выбранному типу), отрасль, процесс, статус, УГТ, цена, грузоподъёмность.
 *
 * Состояние - в query-параметрах URL (шарящаяся ссылка). Значения
 * применяются кнопкой (без спама запросами на каждый ввод цифры),
 * «Сбросить» возвращает каталог без фильтров.
 */
export default function SolutionFilters({
  filters,
  params,
}: {
  filters: FiltersData;
  params: CatalogParams;
}) {
  const router = useRouter();
  const [typeId, setTypeId] = useState(params.typeId ?? "");
  const [subtypeId, setSubtypeId] = useState(params.subtypeId ?? "");
  const [industryId, setIndustryId] = useState(params.industryId ?? "");
  const [processId, setProcessId] = useState(params.processId ?? "");
  const [status, setStatus] = useState(params.status ?? "");
  const [trlMin, setTrlMin] = useState(params.trlMin ?? "");
  const [trlMax, setTrlMax] = useState(params.trlMax ?? "");
  const [priceMin, setPriceMin] = useState(params.priceMin ?? "");
  const [priceMax, setPriceMax] = useState(params.priceMax ?? "");
  const [payloadMin, setPayloadMin] = useState(params.payloadMin ?? "");

  // зависимый список подтипов: только сочетания из typeSubtypeMapping
  const availableSubtypes = typeId
    ? filters.subtypes.filter((subtype) =>
        filters.typeSubtypeMapping.some(
          (link) =>
            link.subtypeId === subtype.id && link.typeId === Number(typeId),
        ),
      )
    : filters.subtypes;

  function apply() {
    router.push(
      buildCatalogUrl(params, {
        typeId: typeId || undefined,
        subtypeId: subtypeId || undefined,
        industryId: industryId || undefined,
        processId: processId || undefined,
        status: status || undefined,
        trlMin: trlMin || undefined,
        trlMax: trlMax || undefined,
        priceMin: priceMin || undefined,
        priceMax: priceMax || undefined,
        payloadMin: payloadMin || undefined,
        page: undefined, // фильтр сменился - на первую страницу
      }),
    );
  }

  function reset() {
    router.push("/catalog");
  }

  return (
    <details
      className="rounded-xl border border-slate-200 bg-white p-4 text-sm dark:border-slate-800 dark:bg-slate-900"
      open={Object.keys(params).some((key) => key !== "q" && key !== "sortBy" && key !== "sortDir")}
    >
      <summary className="cursor-pointer font-medium text-slate-900 dark:text-slate-100">
        Фильтры
      </summary>
      {/* grid-cols-1 явно (не только с sm): без шаблона implicit-колонка
 растёт до max-content и на мобильных создаёт горизонтальный
 скролл; grid-cols-1 = minmax(0,1fr) - трек не шире контейнера */}
      <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <label className="flex flex-col gap-1">
          <span className="text-xs text-slate-500 dark:text-slate-400">Тип решения</span>
          <select
            value={typeId}
            onChange={(event) => {
              setTypeId(event.target.value);
              setSubtypeId(""); // тип сменился - подтип сбрасывается
            }}
            className="rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">Любой</option>
            {filters.types.map((type) => (
              <option key={type.id} value={type.id}>
                {type.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-xs text-slate-500 dark:text-slate-400">Подтип</span>
          <select
            value={subtypeId}
            onChange={(event) => setSubtypeId(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">Любой</option>
            {availableSubtypes.map((subtype) => (
              <option key={subtype.id} value={subtype.id}>
                {subtype.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-xs text-slate-500 dark:text-slate-400">Отрасль</span>
          <select
            value={industryId}
            onChange={(event) => setIndustryId(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">Любая</option>
            {filters.industries.map((industry) => (
              <option key={industry.id} value={industry.id}>
                {industry.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-xs text-slate-500 dark:text-slate-400">Процесс</span>
          <select
            value={processId}
            onChange={(event) => setProcessId(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">Любой</option>
            {filters.processes.map((process) => (
              <option key={process.id} value={process.id}>
                {process.name}
              </option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-xs text-slate-500 dark:text-slate-400">Статус</span>
          <select
            value={status}
            onChange={(event) => setStatus(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          >
            <option value="">Любой</option>
            {filters.statuses.map((code) => (
              <option key={code} value={code}>
                {SOLUTION_STATUS_LABELS[code] ?? code}
              </option>
            ))}
          </select>
        </label>

        {/* Диапазоны (УГТ, цена) - два поля в одной grid-ячейке: без
 min-w-0 flex-дети не сжимаются ниже intrinsic-ширины инпута
 (~20 символов), блок выезжает из ячейки и перекрывает соседнее
 поле «Грузоподъёмность» на 1366×768 */}
        <div className="flex min-w-0 gap-2">
          <label className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="text-xs text-slate-500 dark:text-slate-400">
              УГТ от{filters.trlMin !== null ? ` (${filters.trlMin})` : ""}
            </span>
            <input
              type="number"
              min={1}
              max={9}
              value={trlMin}
              onChange={(event) => setTrlMin(event.target.value)}
              className="w-full rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            />
          </label>
          <label className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="text-xs text-slate-500 dark:text-slate-400">
              УГТ до{filters.trlMax !== null ? ` (${filters.trlMax})` : ""}
            </span>
            <input
              type="number"
              min={1}
              max={9}
              value={trlMax}
              onChange={(event) => setTrlMax(event.target.value)}
              className="w-full rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            />
          </label>
        </div>

        <div className="flex min-w-0 gap-2">
          <label className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="text-xs text-slate-500 dark:text-slate-400">Цена от, руб.</span>
            <input
              type="number"
              min={0}
              step={100000}
              value={priceMin}
              onChange={(event) => setPriceMin(event.target.value)}
              className="w-full rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            />
          </label>
          <label className="flex min-w-0 flex-1 flex-col gap-1">
            <span className="text-xs text-slate-500 dark:text-slate-400">Цена до, руб.</span>
            <input
              type="number"
              min={0}
              step={100000}
              value={priceMax}
              onChange={(event) => setPriceMax(event.target.value)}
              className="w-full rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
            />
          </label>
        </div>

        <label className="flex min-w-0 flex-col gap-1">
          <span className="text-xs text-slate-500 dark:text-slate-400">Грузоподъёмность от, кг</span>
          <input
            type="number"
            min={0}
            step={100}
            value={payloadMin}
            onChange={(event) => setPayloadMin(event.target.value)}
            className="w-full rounded-lg border border-slate-300 px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
          />
        </label>
      </div>

      <div className="mt-4 flex gap-2">
        <button
          type="button"
          onClick={apply}
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white
                     transition hover:bg-slate-700 dark:bg-slate-100 dark:text-slate-900
                     dark:hover:bg-slate-200"
        >
          Применить
        </button>
        <button
          type="button"
          onClick={reset}
          className="rounded-lg border border-slate-300 px-4 py-2 text-sm
                     text-slate-700 transition hover:bg-slate-100
                     dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Сбросить
        </button>
      </div>
    </details>
  );
}
