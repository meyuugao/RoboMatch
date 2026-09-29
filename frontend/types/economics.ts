/**
 * Типы API экономики — зеркало backend-DTO
 * (ScenarioDetailsDto, AssumptionDto, CalculationDto/CalculationFullDto,
 * ComparisonDto, SensitivityRowDto, InterpretationDto).
 */

/** Сценарий с составом оборудования (data_model.md §10.5–10.6). */
export type ScenarioDetails = {
  id: number;
  type: "base" | "purchase" | "raas";
  name: string;
  solutions: ScenarioSolution[];
  /** Дата последнего расчёта (null — не считался). */
  lastCalculatedAt: string | null;
  /** Состав изменён с момента последнего расчёта. */
  compositionChanged: boolean;
};

/** Строка состава сценария: решение каталога + количество. */
export type ScenarioSolution = {
  solutionId: number;
  solutionName: string;
  vendorName: string | null;
  quantity: number;
  priceRub: number | null;
  sumRub: number | null;
  manual: boolean;
  manualReason: string | null;
};

/** Русские подписи типов сценариев. */
export const SCENARIO_TYPE_LABELS: Record<ScenarioDetails["type"], string> = {
  base: "Базовый (без роботизации)",
  purchase: "Покупка",
  raas: "RaaS",
};

/** Допущение экономики. */
export type AssumptionDto = {
  name: string;
  title: string;
  value: string | null;
  defaultValue: string | null;
  unit: string | null;
  kind: "number" | "enum" | "boolean" | string;
  min: number | null;
  max: number | null;
  editable: boolean;
  sourceKind: "organizer_catalog" | "manual" | string;
  impactNote: string | null;
};

/** Строка истории расчётов. */
export type CalculationDto = {
  id: number;
  scenarioId: number;
  versionData: string;
  versionModel: string;
  calculatedAt: string;
  totalCapex: number | null;
  totalOpex: number | null;
  opexDeltaRub: number | null;
  effectYear: number | null;
  paybackYears: number | null;
  roiPct: number | null;
  tcoRub: number | null;
  adjusted: boolean;
};

/** Снимок допущения конкретного расчёта (§10.9). */
export type AssumptionSnapshot = {
  name: string;
  title: string;
  value: string;
  unit: string | null;
  sourceKind: string;
  impactNote: string | null;
};

/** Запись о ручной корректировке. */
export type AdjustmentDto = {
  metricName: string;
  originalValue: number | null;
  newValue: number;
  reason: string;
  authorLogin: string;
  createdAt: string;
};

/** Детальный расчёт. */
export type CalculationFull = CalculationDto & {
  details: EconomicsDetails | null;
  assumptions: AssumptionSnapshot[];
  adjustments: AdjustmentDto[];
};

/** Разбивка расчёта из metrics_json (структура — §10.8/§12). */
export type EconomicsDetails = {
  modelVersion?: string;
  /** Выбранный состав (sum(quantity)) — вся экономика по нему. */
  selectedRobots?: number;
  nInfra?: number;
  /** Требуемое по формуле §2.1 (рекомендация по пиковой нагрузке). */
  requiredRobots?: number;
  /** Парк меньше требуемого: расчёт не отражает заявленную
 * производительность. */
  underpowered?: boolean;
  horizonYears?: number;
  inputs?: CalcInputs;
  /** Эффективные допущения расчёта (персонал эксплуатации и др.). */
  effectiveAssumptions?: Record<string, string>;
  capex?: Record<string, number>;
  opex?: Record<string, number>;
  opexBase?: number;
  fotBase?: number;
  fotRob?: number;
  deltaFot?: number;
  deltaOther?: number;
  effectGross?: number;
  amortYear?: number;
  /** Зеркала колонок расчёта (синхронизируются при adjust). */
  opexDelta?: number;
  effectYear?: number;
  paybackYears?: number | null;
  roiPct?: number | null;
  tcoRub?: number;
  replacements?: number;
  avgRobotPrice?: number;
  raas?: {
    paymentModel: string;
    rateMonthRub: number;
    paymentYear: number;
    paymentYears: number;
    buyoutValue: number;
    capexRaas: number;
  };
  loan?: {
    debtPaymentYear: number;
    effectYearBeforeDebt: number;
    effectYearAfterDebt: number;
  };
  composition?: Array<{
    solutionId: number;
    solutionName: string;
    quantity: number;
    priceRub: number | null;
    manual: boolean;
  }>;
  sensitivity?: SensitivityRowDto[];
  warnings?: string[];
  adjustedFrom?: {
    sourceCalculationId: number;
    metricName: string;
    originalValue: number | null;
    newValue: number;
    reason: string;
    authorLogin: string;
    createdAt: string;
    /** Пересчитанные автоматически метрики. */
    recalculatedMetrics?: string[];
    /** Почему зависимые не пересчитаны. */
    dependentsNote?: string;
  };
};

/** Входы расчёта: производные формул + все параметры объекта. */
export type CalcInputs = {
  peakDemandPerHour?: number;
  hoursYear?: number;
  operationsYear?: number;
  /** Эффективная потребляемая мощность, кВт:
 * допущение или фолбэк ТТХ зарядки — прозрачность входов. */
  powerConsumptionKw?: number | null;
  fotBaseYear?: number;
  salaryYearYear?: number;
  payrollRate?: number;
  /** Все эффективные параметры объекта (TreeMap). */
  parameters?: Record<string, string>;
};

/** Строка чувствительности. */
export type SensitivityRowDto = {
  parameter: "equipment" | "operations" | "labor" | string;
  deltaPct: number;
  effectYear: number | null;
  effectDelta: number | null;
  paybackYears: number | null;
  roiPct: number | null;
  /** Примечание: линейное масштабирование парка при
 * незаданном P_nominal — формула §2.1 неприменима. */
  note?: string | null;
};

/** Русские подписи параметров чувствительности. */
export const SENSITIVITY_PARAM_LABELS: Record<string, string> = {
  equipment: "Стоимость оборудования",
  operations: "Объём операций",
  labor: "Стоимость труда",
};

/** Интерпретация окупаемости. */
export type Interpretation = {
  paybackYears: number | null;
  /** Окупаемость в месяцах при сроке < 0,5 года (только
 * отображение: round(payback × 12, 1)); иначе null. Сохранено для
 * отчёта и API — UI рендерит человекочитаемо. */
  paybackMonths: number | null;
  /** Человекочитаемый срок: «2 месяца» / «1 год 3 месяца» /
 * «6 лет» — целые, округление вверх (§4). */
  paybackHuman?: string | null;
  category: "fast" | "moderate" | "long" | "none" | "undefined"
    | "not_applicable" | "underpowered" | "empty_composition" | string;
  text: string;
};

/** Колонка сценария в сравнении. */
export type ComparisonScenario = {
  scenarioId: number | null;
  calculationId: number | null;
  type: "base" | "purchase" | "raas" | string;
  name: string;
  calculated: boolean;
  calculatedAt: string | null;
  /** Выбранный состав, ед. — вся экономика по нему. */
  selectedRobots: number | null;
  /** Требуемое по пиковой нагрузке (формула §2.1). */
  requiredRobots: number | null;
  /** Парк меньше требуемого. */
  underpowered: boolean;
  /** Парк превышает требуемый: возможна переплата —
 * нейтральная информация, не ошибка. */
  overpowered: boolean;
  nInfra: number | null;
  capex: number | null;
  opexYear: number | null;
  opexDelta: number | null;
  deltaFot: number | null;
  effectYear: number | null;
  payback: Interpretation | null;
  roiPct: number | null;
  tco: number | null;
  capexDeltaToBase: number | null;
  tcoDeltaToBase: number | null;
  effectDeltaToBase: number | null;
  sensitivity: SensitivityRowDto[];
  warnings: string[];
  versionData: string | null;
};

/** Ответ GET /compare. */
export type ComparisonDto = {
  scenarios: ComparisonScenario[];
  horizonYears: number | null;
  versionModel: string | null;
};

/** Русские подписи метрик ручной корректировки. */
export const METRIC_LABELS: Record<string, string> = {
  total_capex: "CAPEX, руб.",
  total_opex: "Годовой OPEX, руб.",
  opex_delta_rub: "Изменение OPEX, руб.",
  effect_year: "Годовой эффект, руб.",
  payback_years: "Окупаемость, лет",
  roi_pct: "ROI, %",
  tco_rub: "TCO, руб.",
};

/** Русские подписи статей CAPEX/OPEX (economic_model.md §2.3–2.4). */
export const CAPEX_LABELS: Record<string, string> = {
  equipment: "Оборудование",
  infra: "Инфраструктура",
  software: "ПО",
  integration: "Интеграция",
  commissioning: "Пусконаладка",
  training: "Обучение",
  reserve: "Резерв",
  total: "Итого CAPEX",
};

export const OPEX_LABELS: Record<string, string> = {
  service: "Сервис",
  licenses: "Лицензии ПО",
  electricity: "Электроэнергия",
  communication: "Связь",
  consumables: "Расходники (вкл. АКБ)",
  repair: "Ремонт",
  staff: "Персонал эксплуатации",
  total: "Итого OPEX",
};

/** Форматирование сумм: рубли с разделителями (§4 — до рублей). */
export function formatRub(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return `${Math.round(value).toLocaleString("ru-RU")} ₽`;
}

/** Форматирование процентов (ROI — до десятых). */
export function formatPct(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return `${value.toLocaleString("ru-RU", {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  })}%`;
}

/** Форматирование срока, лет (до десятых). */
export function formatYears(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return "—";
  }
  return `${value.toLocaleString("ru-RU", {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  })} г.`;
}

/**
 * Окупаемость — человекочитаемо (без дробей, округление вверх —
 * не занижаем срок). Правило — economic_model.md §4 (зеркало InterpretationFormatter
 * на backend):
 * - payback < 1/12 года → «до 1 месяца»;
 * - 1–12 мес. → целые месяцы вверх: 1,2 мес. → «2 месяца»,
 * 11,4 мес. → «12 месяцев»;
 * - 1–5 лет → «1 год 3 месяца»; 0 мес. → «2 года»;
 * - ≥ 5 лет → целые годы вверх: 5,1 г. → «6 лет».
 * Только форматирование вывода — логика расчёта не меняется.
 */
export function formatPaybackShort(
  years: number | null | undefined,
): string {
  if (years === null || years === undefined) {
    return "—";
  }
  if (years < 1 / 12) {
    return "до 1 месяца";
  }
  if (years < 1) {
    // 1..12 месяцев: округление вверх, не занижаем срок
    const months = Math.ceil(years * 12);
    return `${months} ${plural(months, "месяц", "месяца", "месяцев")}`;
  }
  if (years < 5) {
    let wholeYears = Math.floor(years);
    // месяцы остатка — вверх; 12 после округления — следующий год
    let months = Math.ceil((years - wholeYears) * 12);
    if (months >= 12) {
      wholeYears += 1;
      months = 0;
    }
    if (months === 0) {
      return `${wholeYears} ${plural(wholeYears, "год", "года", "лет")}`;
    }
    return `${wholeYears} ${plural(wholeYears, "год", "года", "лет")} ${months} ${plural(months, "месяц", "месяца", "месяцев")}`;
  }
  // от 5 лет: целые годы, округление вверх
  const rounded = Math.ceil(years);
  return `${rounded} ${plural(rounded, "год", "года", "лет")}`;
}

/** Русские склонения: 1 месяц / 2 месяца / 5 месяцев (11–14 — род. падеж). */
function plural(
  n: number,
  one: string,
  few: string,
  many: string,
): string {
  const mod100 = n % 100;
  if (mod100 >= 11 && mod100 <= 14) {
    return many;
  }
  const mod10 = n % 10;
  if (mod10 === 1) {
    return one;
  }
  if (mod10 >= 2 && mod10 <= 4) {
    return few;
  }
  return many;
}

/** Форматирование даты расчёта. */
export function formatCalcDate(iso: string | null | undefined): string {
  if (!iso) {
    return "—";
  }
  return new Date(iso).toLocaleString("ru-RU", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
