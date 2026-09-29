import type { SolutionCharacteristic, SolutionFull } from "@/types/solution";

/**
 * ТТХ решения на детальной карточке:
 * - таблица зеркальных ТТХ solution (9 колонок — основные характеристики);
 * - таблица EAV-характеристик с провенансом: источник, дата, признак
 * подтверждённости — подтверждённые и неподтверждённые визуально
 * разделены.
 * NULL — норма данных (границы применимости, уточнение организатора): показываем
 * только заполненные значения.
 */

/** Отображаемое значение ТТХ: ровно одно из полей непустое (CHECK в БД). */
function characteristicValue(item: SolutionCharacteristic): string {
  if (item.valueNumeric !== null) return String(item.valueNumeric);
  if (item.valueText !== null) return item.valueText;
  if (item.valueBool !== null) return item.valueBool ? "да" : "нет";
  if (item.valueDate !== null) return item.valueDate;
  return "—";
}

const MIRRORED: { label: string; unit: string | null; get: (s: SolutionFull) => number | null }[] = [
  { label: "Грузоподъёмность", unit: "кг", get: (s) => s.payloadKg },
  { label: "Масса", unit: "кг", get: (s) => s.massKg },
  { label: "Длина", unit: "мм", get: (s) => s.lengthMm },
  { label: "Ширина", unit: "мм", get: (s) => s.widthMm },
  { label: "Высота", unit: "мм", get: (s) => s.heightMm },
  { label: "Точность позиционирования", unit: "мм", get: (s) => s.positioningAccuracyMm },
  { label: "Скорость", unit: "м/с", get: (s) => s.speedMs },
  { label: "Мощность зарядки", unit: "кВт", get: (s) => s.chargingPowerKw },
  { label: "Уровень шума", unit: "дБА", get: (s) => s.noiseLevelDba },
];

export default function SolutionCharacteristics({ solution }: { solution: SolutionFull }) {
  const mirrored = MIRRORED.filter((row) => row.get(solution) !== null);
  const characteristics = solution.characteristics;

  if (mirrored.length === 0 && characteristics.length === 0) {
    return (
      <p className="text-sm text-slate-500 dark:text-slate-400">
        Технические характеристики производителем не опубликованы — требуется
        запрос к вендору (границы применимости — по обязательным ТТХ).
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      {/* зеркальные ТТХ — основной набор для фильтрации и подбора */}
      {mirrored.length > 0 && (
        <table className="w-full max-w-xl border-collapse text-sm">
          <tbody>
            {mirrored.map((row) => (
              <tr key={row.label} className="border-b border-slate-100 dark:border-slate-800">
                <th scope="row" className="py-2 pr-4 text-left font-medium text-slate-600 dark:text-slate-300">
                  {row.label}
                </th>
                <td className="py-2 text-slate-900 dark:text-slate-100">
                  {row.get(solution)}
                  {row.unit ? <span className="ml-1 text-slate-400 dark:text-slate-500">{row.unit}</span> : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* EAV-характеристики с провенансом */}
      {characteristics.length > 0 && (
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-800">
          <table className="w-full min-w-[560px] border-collapse text-sm">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50 text-left text-xs tracking-wide text-slate-500 uppercase
                         dark:border-slate-800 dark:bg-slate-800 dark:text-slate-400">
                <th className="px-4 py-2.5 font-medium">Характеристика</th>
                <th className="px-4 py-2.5 font-medium">Значение</th>
                <th className="px-4 py-2.5 font-medium">Статус</th>
                <th className="px-4 py-2.5 font-medium">Источник</th>
              </tr>
            </thead>
            <tbody>
              {characteristics.map((item) => (
                <tr key={item.typeCode} className="border-b border-slate-100 last:border-0 dark:border-slate-800">
                  <td className="px-4 py-2.5 font-medium text-slate-700 dark:text-slate-300">
                    {item.typeName}
                  </td>
                  <td className="px-4 py-2.5 text-slate-900 dark:text-slate-100">
                    {characteristicValue(item)}
                    {item.unit ? (
                      <span className="ml-1 text-slate-400 dark:text-slate-500">{item.unit}</span>
                    ) : null}
                  </td>
                  <td className="px-4 py-2.5">
                    {item.isConfirmed ? (
                      <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800
                                   dark:bg-emerald-900/40 dark:text-emerald-300">
                        Подтверждено
                      </span>
                    ) : (
                      <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800
                                   dark:bg-amber-900/40 dark:text-amber-300">
                        Не подтверждено
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-2.5 text-xs text-slate-500 dark:text-slate-400">
                    {item.sourceUrl ? (
                      <a
                        href={item.sourceUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-blue-600 hover:underline dark:text-blue-400"
                      >
                        ссылка
                      </a>
                    ) : (
                      "—"
                    )}
                    {item.sourceDate ? (
                      <span className="ml-1">от {item.sourceDate}</span>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
