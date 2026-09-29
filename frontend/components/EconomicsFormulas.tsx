import type { CalculationFull, ComparisonScenario } from "@/types/economics";
import {
  CAPEX_LABELS,
  OPEX_LABELS,
  formatRub,
} from "@/types/economics";

/**
 * Блок «Формулы и допущения»: все формулы economic_model.md
 * §2, единицы и источники видны пользователю; рядом — фактические
 * значения статей последнего расчёта покупки. Серверный компонент.
 *
 * Состав блока «Входы расчёта»: пиковый коэффициент нагрузки, численность
 * целевых групп, ΔFOT базы, эффективная потребляемая мощность,
 * раскрывающийся блок «Все параметры объекта»; блок эффекта разделён
 * на до/после амортизации; строка «Персонал эксплуатации» — численность
 * из эффективных допущений.
 */
export default function EconomicsFormulas({
  purchase,
  calculation,
}: {
  purchase: ComparisonScenario | null;
  calculation: CalculationFull | null;
}) {
  const capex = calculation?.details?.capex;
  const opex = calculation?.details?.opex;
  const inputs = calculation?.details?.inputs;
  const details = calculation?.details;
  const parameters = inputs?.parameters;
  // численность персонала эксплуатации — из эффективных
  // допущений расчёта (exploitation_staff_count)
  const staffCount = details?.effectiveAssumptions?.exploitation_staff_count;

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <section className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <h3 className="font-semibold text-slate-800 dark:text-slate-200">Формулы расчёта</h3>
        <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
          Формулы — методика экономической модели (разделы 2.1–2.13);
          все коэффициенты — из допущений расчёта (разделы 22–23).
        </p>
        <dl className="mt-3 space-y-2 text-sm">
          {[
            ["Требуемый парк", "ceil(Peak_demand × (1 + K_reserve) / (P_nominal × K_load × K_availability)) — рекомендация по пиковой нагрузке; экономика считается по выбранному составу"],
            ["N_infra", "ceil(Роботы_выбраны × Ratio_infra), шт. зарядных станций"],
            ["CAPEX", "Оборудование + Инфраструктура + ПО + Интеграция + Пусконаладка + Обучение + Резерв 10%"],
            ["OPEX год", "Сервис + Лицензии + Электроэнергия + Связь + Расходники + Ремонт + Персонал (RaaS: платежи вместо сервисных статей)"],
            ["C_electricity", "Роботы_выбраны × P_потребление, кВт × Часов_в_году × Тариф, руб./кВт·ч"],
            ["ΔOPEX", "OPEX_роб − OPEX_база, руб./год (отрицательное — экономия); базовый OPEX = ФОТ целевых групп"],
            ["Годовой эффект", "ΔFOT + ΔOther − ΔOPEX_без_ФОТ − Амортизация − Аннуитет (если кредит); Effect_year — после амортизации, не является денежным потоком"],
            ["Окупаемость", "CAPEX / Годовой эффект (при положительном эффекте; после вычета платежей по кредиту)"],
            ["ROI", "Эффект × Горизонт / CAPEX × 100%"],
            ["TCO", "Покупка: CAPEX + OPEX × Горизонт + Замены; RaaS: CAPEX + Платежи × годы_контракта + (Электроэнергия + Связь + Персонал) × Горизонт"],
            ["RaaS: платёж", "fixed: 12 × ставка_мес × Роботы_выбраны; usage: ставка × операций/год; mixed: сумма"],
            ["RaaS: выкуп", "Цена × max(0, 1 − Срок контракта / Срок службы)"],
            ["Чувствительность", "±20% / ±10% / 0 по стоимости оборудования (цены × (1 + Δ)), объёму операций (парк — пропорционально выбранному составу: ceil(Роботы_выбраны × (1 + Δ))), стоимости труда (ФОТ × (1 + Δ))"],
          ].map(([name, formula]) => (
            <div key={name} className="rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-800">
              <dt className="text-xs font-semibold text-slate-700 dark:text-slate-300">{name}</dt>
              <dd className="mt-0.5 text-xs text-slate-600 dark:text-slate-300">{formula}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section className="space-y-4">
        {inputs ? (
          <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <h3 className="font-semibold text-slate-800 dark:text-slate-200">Входы расчёта</h3>
            <dl className="mt-2 grid grid-cols-1 gap-1 text-xs sm:grid-cols-2">
              {Object.entries({
                "Пиковая потребность": inputs.peakDemandPerHour,
                "Пиковый коэффициент нагрузки":
                  parameters?.peak_load_factor !== undefined
                    ? parameters?.peak_load_factor
                    : null,
                "Часов в году": inputs.hoursYear,
                "Операций в году": inputs.operationsYear,
                "Отборщиков, чел": parameters?.pickers_count,
                "Операторов погрузчиков, чел":
                  parameters?.forklift_operators_count,
                "ФОТ контура (база)": inputs.fotBaseYear,
                "Средняя з/п среднегодовая": inputs.salaryYearYear,
                "Коэф. начислений": inputs.payrollRate,
                // эффективная потребляемая мощность видна
                // пользователю — допущение или фолбэк ТТХ зарядки (§1.2)
                "Потребляемая мощность, кВт":
                  inputs.powerConsumptionKw !== undefined
                    ? inputs.powerConsumptionKw
                    : null,
              }).map(([label, value]) => (
                <div key={label} className="flex justify-between gap-2">
                  <dt className="text-slate-500 dark:text-slate-400">{label}</dt>
                  <dd className="font-medium text-slate-800 dark:text-slate-200">
                    {value === null || value === undefined
                      ? "—"
                      : Number(value).toLocaleString("ru-RU")}
                  </dd>
                </div>
              ))}
              <div className="flex justify-between gap-2 sm:col-span-2">
                <dt className="text-slate-500 dark:text-slate-400">
                  ΔFOT базы
                  <span className="block text-slate-400 dark:text-slate-500">
                    источник = ФОТ вытесненных групп × K_начислений
                  </span>
                </dt>
                <dd className="font-medium text-slate-800 dark:text-slate-200">
                  {formatRub(details?.deltaFot)}
                </dd>
              </div>
            </dl>
            {/* раскрывающийся блок всех параметров объекта (TreeMap
 из metrics_json — воспроизведение расчёта) */}
            {parameters && Object.keys(parameters).length > 0 ? (
              <details className="mt-3">
                <summary className="cursor-pointer text-xs font-medium text-slate-600 dark:text-slate-300">
                  Все параметры объекта ({Object.keys(parameters).length})
                </summary>
                <dl className="mt-2 grid grid-cols-1 gap-x-4 gap-y-0.5 text-xs sm:grid-cols-2">
                  {Object.entries(parameters).map(([code, value]) => (
                    <div key={code} className="flex justify-between gap-2">
                      <dt className="text-slate-500 dark:text-slate-400">{code}</dt>
                      <dd className="font-medium text-slate-800 dark:text-slate-200">{value}</dd>
                    </div>
                  ))}
                </dl>
              </details>
            ) : null}
          </div>
        ) : null}

        {capex ? (
          <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <h3 className="font-semibold text-slate-800 dark:text-slate-200">
              CAPEX{purchase ? ` — ${purchase.name}` : ""}
            </h3>
            <dl className="mt-2 space-y-1 text-xs">
              {Object.entries(capex)
                .filter(([key]) => key !== "total")
                .map(([key, value]) => (
                  <div key={key} className="flex justify-between gap-2">
                    <dt className="text-slate-500 dark:text-slate-400">{CAPEX_LABELS[key] ?? key}</dt>
                    <dd className="font-medium text-slate-800 dark:text-slate-200">
                      {formatRub(value)}
                    </dd>
                  </div>
                ))}
              <div className="flex justify-between gap-2 border-t border-slate-100 pt-1 dark:border-slate-800">
                <dt className="font-semibold text-slate-700 dark:text-slate-300">
                  {CAPEX_LABELS.total}
                </dt>
                <dd className="font-semibold text-slate-900 dark:text-slate-100">
                  {formatRub(capex.total)}
                </dd>
              </div>
            </dl>
          </div>
        ) : null}

        {opex ? (
          <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <h3 className="font-semibold text-slate-800 dark:text-slate-200">
              OPEX годовой{purchase ? ` — ${purchase.name}` : ""}
            </h3>
            <dl className="mt-2 space-y-1 text-xs">
              {Object.entries(opex)
                .filter(([key]) => key !== "total")
                .map(([key, value]) => (
                  <div key={key} className="flex justify-between gap-2">
                    <dt className="text-slate-500 dark:text-slate-400">{OPEX_LABELS[key] ?? key}</dt>
                    <dd className="font-medium text-slate-800 dark:text-slate-200">
                      {formatRub(value)}
                    </dd>
                  </div>
                ))}
              <div className="flex justify-between gap-2 border-t border-slate-100 pt-1 dark:border-slate-800">
                <dt className="font-semibold text-slate-700 dark:text-slate-300">
                  {OPEX_LABELS.total}
                </dt>
                <dd className="font-semibold text-slate-900 dark:text-slate-100">
                  {formatRub(opex.total)}
                </dd>
              </div>
            </dl>
            {/* подпись базового OPEX и персонала эксплуатации
 с численностью и допущением о полном замещении */}
            <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
              Базовый сценарий: OPEX = ФОТ целевых групп × K_начислений
              ({formatRub(details?.opexBase)}); прочие текущие расходы
              в baseline не учтены (см. допущения расчёта).
            </p>
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
              Персонал эксплуатации: {staffCount ?? 0} чел. — предполагается
              полное замещение целевых сотрудников (допущение
              exploitation_staff_count).
            </p>
          </div>
        ) : null}

        {/* эффект — до амортизации / амортизация / после */}
        {details && (details.effectGross !== undefined || details.amortYear !== undefined) ? (
          <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <h3 className="font-semibold text-slate-800 dark:text-slate-200">
              Годовой эффект после амортизации
            </h3>
            <dl className="mt-2 space-y-1 text-xs">
              <div className="flex justify-between gap-2">
                <dt className="text-slate-500 dark:text-slate-400">Эффект до амортизации</dt>
                <dd className="font-medium text-slate-800 dark:text-slate-200">
                  {formatRub(details.effectGross)}
                </dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-slate-500 dark:text-slate-400">
                  Амортизация (CAPEX / срок службы)
                </dt>
                <dd className="font-medium text-slate-800 dark:text-slate-200">
                  −{formatRub(details.amortYear)}
                </dd>
              </div>
              <div className="flex justify-between gap-2 border-t border-slate-100 pt-1 dark:border-slate-800">
                <dt className="font-semibold text-slate-700 dark:text-slate-300">
                  Годовой эффект после амортизации
                </dt>
                <dd className="font-semibold text-slate-900 dark:text-slate-100">
                  {formatRub(details.effectYear)}
                </dd>
              </div>
            </dl>
            <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
              Effect_year — эффект после амортизации; не является денежным
              потоком (раздел 2.6 методики расчёта).
            </p>
            {details.loan ? (
              <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                Кредит: аннуитет {formatRub(details.loan.debtPaymentYear)}
                руб./год вычтен из эффекта до расчёта окупаемости и ROI
                (раздел 2.10 методики): {formatRub(details.loan.effectYearBeforeDebt)} →{" "}
                {formatRub(details.loan.effectYearAfterDebt)} руб./год.
                Обслуживание долга в TCO не входит (финансирование,
                не владение).
              </p>
            ) : null}
          </div>
        ) : null}

        <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <h3 className="font-semibold text-slate-800 dark:text-slate-200">Единицы и округление</h3>
          <ul className="mt-2 space-y-1 text-xs text-slate-600 dark:text-slate-300">
            <li>Роботы (выбрано и требуемое) — всегда вверх (ceil); N_infra — вверх</li>
            <li>Стоимости — до рублей; окупаемость и ROI — до десятых</li>
            <li>Цены — с НДС; доставка и пусконаладка — отдельно</li>
            <li>Результат — предварительная оценка, требует верификации при обследовании объекта</li>
          </ul>
        </div>
      </section>
    </div>
  );
}
