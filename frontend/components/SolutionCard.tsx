import Link from "next/link";
import CompareCheckbox from "@/components/compare/CompareCheckbox";
import {
  PRODUCT_CLASS_LABELS,
  SOLUTION_STATUS_LABELS,
  type SolutionSummary,
} from "@/types/solution";

/**
 * Карточка решения в сетке каталога: имя производителя, тип,
 * регион, ТТХ-чипы, цена, чекбокс сравнения, ссылка на детальную
 * карточку. Серверный компонент; интерактивна только часть сравнения.
 *
 * Формат цены: Intl.NumberFormat с локалью ru-RU (пробелы-разряды).
 */
const priceFormatter = new Intl.NumberFormat("ru-RU", {
  style: "currency",
  currency: "RUB",
  maximumFractionDigits: 0,
});

/** Статус -> цвет бейджа. */
const STATUS_STYLES: Record<SolutionSummary["status"], string> = {
  operation: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300",
  piloting: "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300",
  rnd: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300",
};

export default function SolutionCard({ solution }: { solution: SolutionSummary }) {
  return (
    <article className="flex flex-col gap-3 rounded-xl border border-slate-200 bg-white p-5 shadow-sm transition hover:shadow-md dark:border-slate-800 dark:bg-slate-900">
      {/* класс продукта + статус */}
      <div className="flex items-center justify-between gap-2">
        <span className="rounded-md bg-slate-900 px-2 py-1 text-xs font-medium text-white dark:bg-slate-100 dark:text-slate-900">
          {PRODUCT_CLASS_LABELS[solution.productClass] ?? solution.productClass}
        </span>
        <span
          className={`rounded-md px-2 py-1 text-xs font-medium ${STATUS_STYLES[solution.status]}`}
        >
          {SOLUTION_STATUS_LABELS[solution.status] ?? solution.status}
        </span>
      </div>

      {/* название - ссылка на детальную карточку */}
      <h3 className="text-base leading-snug font-semibold text-slate-900 dark:text-slate-100">
        <Link href={`/catalog/${solution.id}`} className="hover:text-blue-700 dark:hover:text-blue-400">
          {solution.name}
        </Link>
      </h3>

      {/* производитель / тип / регион - имена из справочников (JOIN) */}
      <p className="text-xs text-slate-500 dark:text-slate-400">
        {solution.vendorName}
        {solution.solutionTypeName ? ` · ${solution.solutionTypeName}` : ""}
        {solution.solutionSubtypeName ? ` · ${solution.solutionSubtypeName}` : ""}
        {solution.regionName ? ` · ${solution.regionName}` : ""}
      </p>

      {/* описание - обрезаем до 2 строк, полное - на странице решения */}
      {solution.description ? (
        <p className="line-clamp-2 text-sm text-slate-600 dark:text-slate-300">{solution.description}</p>
      ) : null}

      {/* УГТ / грузоподъёмность / заполненность - если известны */}
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500 dark:text-slate-400">
        {solution.trl !== null && <span>УГТ: {solution.trl}</span>}
        {solution.payloadKg !== null && (
          <span>Грузоподъёмность: {solution.payloadKg} кг</span>
        )}
        {solution.completenessPct !== null && (
          <span>Заполненность: {solution.completenessPct}%</span>
        )}
      </div>

      {/* ТТХ из карточки каталога; NULL в данных каталога - норма,
 показываем только заполненные. */}
      {(() => {
        const specs: string[] = [];
        if (
          solution.lengthMm !== null &&
          solution.widthMm !== null &&
          solution.heightMm !== null
        ) {
          specs.push(
            `Габариты: ${solution.lengthMm}×${solution.widthMm}×${solution.heightMm} мм`,
          );
        }
        if (solution.massKg !== null) specs.push(`Масса: ${solution.massKg} кг`);
        if (solution.speedMs !== null) specs.push(`Скорость: ${solution.speedMs} м/с`);
        if (solution.positioningAccuracyMm !== null)
          specs.push(`Точность: ${solution.positioningAccuracyMm} мм`);
        if (solution.chargingPowerKw !== null)
          specs.push(`Зарядка: ${solution.chargingPowerKw} кВт`);
        if (solution.noiseLevelDba !== null)
          specs.push(`Шум: ${solution.noiseLevelDba} дБА`);
        if (specs.length === 0) return null;
        return (
          <div className="flex flex-wrap gap-1.5">
            {specs.map((spec) => (
              <span
                key={spec}
                className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-300"
              >
                {spec}
              </span>
            ))}
          </div>
        );
      })()}

      {/* цена - прижимаем к низу карточки */}
      <div className="mt-auto flex items-end justify-between gap-2 pt-2">
        <p className="text-lg font-bold text-slate-900 dark:text-slate-100">
          {priceFormatter.format(solution.priceRub)}
          <span className="ml-1 text-xs font-normal text-slate-500 dark:text-slate-400">с НДС</span>
        </p>
        <CompareCheckbox solutionId={solution.id} />
      </div>
    </article>
  );
}
