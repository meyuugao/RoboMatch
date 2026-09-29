"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { setCompareIds } from "@/lib/compare";
import {
  PRODUCT_CLASS_LABELS,
  SOLUTION_STATUS_LABELS,
  type SolutionFull,
} from "@/types/solution";

/**
 * Таблица сравнения решений: колонки - решения,
 * строки - унифицированные характеристики.
 *
 * Возможности:
 * - подсветка лучших значений (цена - меньше, УГТ/грузоподъёмность -
 * больше и т.д.), только когда сравнимых значений не меньше двух;
 * - сортировка решений по строке (клик по названию характеристики);
 * - удаление решения из сравнения (обновляет URL и localStorage).
 *
 * Клиентский компонент: интерактив. Данные приходят готовыми с сервера.
 */

type RowDirection = "min" | "max";

interface CompareRow {
  key: string;
  label: string;
  /** Значение для сравнения/вывода; null - «нет данных». */
  get: (solution: SolutionFull) => string | null;
  /** Числовое значение для сортировки и подсветки. */
  numeric?: (solution: SolutionFull) => number | null;
  best?: RowDirection;
}

const priceFormatter = new Intl.NumberFormat("ru-RU", {
  style: "currency",
  currency: "RUB",
  maximumFractionDigits: 0,
});

function dims(solution: SolutionFull): string | null {
  if (
    solution.lengthMm === null ||
    solution.widthMm === null ||
    solution.heightMm === null
  ) {
    return null;
  }
  return `${solution.lengthMm}×${solution.widthMm}×${solution.heightMm} мм`;
}

/** Значение EAV-характеристики по коду типа (или null). */
function characteristicNumeric(
  solution: SolutionFull,
  typeCode: string,
): number | null {
  const found = solution.characteristics.find((c) => c.typeCode === typeCode);
  return found?.valueNumeric ?? null;
}

function characteristicText(
  solution: SolutionFull,
  typeCode: string,
): string | null {
  const found = solution.characteristics.find((c) => c.typeCode === typeCode);
  return found?.valueText ?? null;
}

/** Строки таблицы: базовые поля + зеркальные ТТХ (8 обязательных, уточнение организатора)
 * + эксплуатационные из EAV (автономность, навигация - шаг 4). */
const ROWS: CompareRow[] = [
  {
    key: "vendor",
    label: "Производитель",
    get: (s) => s.vendorName,
  },
  {
    key: "type",
    label: "Тип решения",
    get: (s) =>
      [s.solutionTypeName, s.solutionSubtypeName].filter(Boolean).join(" · ") || null,
  },
  {
    key: "region",
    label: "Регион",
    get: (s) => s.regionName,
  },
  {
    key: "status",
    label: "Статус",
    get: (s) => SOLUTION_STATUS_LABELS[s.status] ?? s.status,
  },
  {
    key: "class",
    label: "Класс",
    get: (s) => PRODUCT_CLASS_LABELS[s.productClass] ?? s.productClass,
  },
  {
    key: "price",
    label: "Цена, руб. с НДС",
    get: (s) => priceFormatter.format(s.priceRub),
    numeric: (s) => s.priceRub,
    best: "min",
  },
  {
    key: "trl",
    label: "УГТ (TRL)",
    get: (s) => (s.trl === null ? null : String(s.trl)),
    numeric: (s) => s.trl,
    best: "max",
  },
  {
    key: "payload",
    label: "Грузоподъёмность, кг",
    get: (s) => (s.payloadKg === null ? null : String(s.payloadKg)),
    numeric: (s) => s.payloadKg,
    best: "max",
  },
  {
    key: "mass",
    label: "Масса, кг",
    get: (s) => (s.massKg === null ? null : String(s.massKg)),
    numeric: (s) => s.massKg,
    best: "min",
  },
  {
    key: "dims",
    label: "Габариты (Д×Ш×В), мм",
    get: dims,
    numeric: (s) =>
      s.lengthMm === null || s.widthMm === null || s.heightMm === null
        ? null
        : s.lengthMm * s.widthMm * s.heightMm, // компактнее - лучше
    best: "min",
  },
  {
    key: "accuracy",
    label: "Точность позиционирования, мм",
    get: (s) => (s.positioningAccuracyMm === null ? null : String(s.positioningAccuracyMm)),
    numeric: (s) => s.positioningAccuracyMm,
    best: "min",
  },
  {
    key: "speed",
    label: "Скорость, м/с",
    get: (s) => (s.speedMs === null ? null : String(s.speedMs)),
    numeric: (s) => s.speedMs,
    best: "max",
  },
  {
    key: "autonomy",
    label: "Автономность, ч",
    get: (s) => {
      const value = characteristicNumeric(s, "autonomy_h");
      return value === null ? null : String(value);
    },
    numeric: (s) => characteristicNumeric(s, "autonomy_h"),
    best: "max",
  },
  {
    key: "navigation",
    label: "Тип навигации",
    get: (s) => characteristicText(s, "navigation_type"),
  },
  {
    key: "charging",
    label: "Мощность зарядки, кВт",
    get: (s) => (s.chargingPowerKw === null ? null : String(s.chargingPowerKw)),
    numeric: (s) => s.chargingPowerKw,
    best: "min",
  },
  {
    key: "noise",
    label: "Уровень шума, дБА",
    get: (s) => (s.noiseLevelDba === null ? null : String(s.noiseLevelDba)),
    numeric: (s) => s.noiseLevelDba,
    best: "min",
  },
  {
    key: "market",
    label: "Рыночный потенциал",
    get: (s) => (s.marketPotential === null ? null : String(s.marketPotential)),
    numeric: (s) => s.marketPotential,
    best: "max",
  },
  {
    key: "cases",
    label: "Кейсы внедрения",
    get: (s) => (s.cases.length === 0 ? null : String(s.cases.length)),
    numeric: (s) => s.cases.length,
    best: "max",
  },
  {
    key: "applications",
    label: "Применения (отрасли)",
    get: (s) => {
      const industries = [...new Set(s.applications.map((a) => a.industryName))];
      return industries.length === 0 ? null : industries.join(", ");
    },
  },
  {
    key: "completeness",
    label: "Заполненность карточки",
    get: (s) => (s.completenessPct === null ? null : `${s.completenessPct}%`),
    numeric: (s) => s.completenessPct,
    best: "max",
  },
];

export default function SolutionCompareTable({
  solutions,
}: {
  solutions: SolutionFull[];
}) {
  const router = useRouter();
  const [sortRow, setSortRow] = useState<string | null>(null);
  const [sortAsc, setSortAsc] = useState(true);

  /** Порядок решений (меняется сортировкой по строке). */
  const ordered = useMemo(() => {
    if (!sortRow) return solutions;
    const row = ROWS.find((r) => r.key === sortRow);
    if (!row?.numeric) return solutions;
    return [...solutions].sort((a, b) => {
      const va = row.numeric!(a);
      const vb = row.numeric!(b);
      if (va === null && vb === null) return 0;
      if (va === null) return 1; // NULL всегда в конец
      if (vb === null) return -1;
      return sortAsc ? va - vb : vb - va;
    });
  }, [solutions, sortRow, sortAsc]);

  /** Лучшее значение строки (для подсветки), null - не подсвечиваем. */
  function bestOf(row: CompareRow): number | null {
    if (!row.numeric || !row.best) return null;
    const values = solutions
      .map(row.numeric)
      .filter((v): v is number => v !== null);
    if (values.length < 2) return null; // одно значение - «лучшее» бессмысленно
    return row.best === "min" ? Math.min(...values) : Math.max(...values);
  }

  function sortByRow(row: CompareRow) {
    if (!row.numeric) return;
    if (sortRow === row.key) {
      if (sortAsc) setSortAsc(false);
      else {
        setSortRow(null); // третий клик - исходный порядок
        setSortAsc(true);
      }
    } else {
      setSortRow(row.key);
      setSortAsc(true);
    }
  }

  function removeSolution(id: number) {
    const remaining = solutions
      .map((s) => s.id)
      .filter((x) => x !== id);
    setCompareIds(remaining);
    if (remaining.length >= 2) {
      router.replace(`/catalog/compare?ids=${remaining.join(",")}`);
    } else {
      router.replace("/catalog/compare");
    }
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
      <table className="w-full min-w-[640px] border-collapse text-sm">
        <thead>
          <tr className="border-b border-slate-200 bg-slate-50 text-left dark:border-slate-800 dark:bg-slate-800">
            <th className="px-4 py-3 text-xs font-medium tracking-wide text-slate-500 uppercase dark:text-slate-400">
              Характеристика
            </th>
            {ordered.map((solution) => (
              <th key={solution.id} className="px-4 py-3">
                <div className="flex flex-col gap-1">
                  <Link
                    href={`/catalog/${solution.id}`}
                    className="font-semibold text-slate-900 hover:text-blue-700 dark:text-slate-100 dark:hover:text-blue-400"
                  >
                    {solution.name}
                  </Link>
                  <button
                    type="button"
                    onClick={() => removeSolution(solution.id)}
                    className="self-start text-xs text-slate-400 transition hover:text-red-600 dark:text-slate-500 dark:hover:text-red-400"
                  >
                    Убрать из сравнения
                  </button>
                </div>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {ROWS.map((row) => {
            const best = bestOf(row);
            return (
              <tr key={row.key} className="border-b border-slate-100 last:border-0 dark:border-slate-800">
                <th
                  scope="row"
                  className={`px-4 py-2.5 text-left align-top text-xs font-medium text-slate-600
                              dark:text-slate-300
                              ${row.numeric ? "cursor-pointer hover:text-slate-900 dark:hover:text-slate-100" : ""}`}
                  onClick={() => sortByRow(row)}
                  title={row.numeric ? "Сортировать решения по этой характеристике" : undefined}
                >
                  {row.label}
                  {sortRow === row.key && (
                    <span className="ml-1 text-blue-600 dark:text-blue-400">{sortAsc ? "▲" : "▼"}</span>
                  )}
                </th>
                {ordered.map((solution) => {
                  const value = row.get(solution);
                  const numeric = row.numeric?.(solution) ?? null;
                  const isBest = best !== null && numeric !== null && numeric === best;
                  return (
                    <td
                      key={solution.id}
                      className={`px-4 py-2.5 align-top ${
                        isBest ? "font-semibold text-emerald-700 dark:text-emerald-400" : "text-slate-700 dark:text-slate-300"
                      }`}
                    >
                      {value ?? <span className="text-slate-300 dark:text-slate-600">нет данных</span>}
                      {isBest && <span className="ml-1 text-xs">★</span>}
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
