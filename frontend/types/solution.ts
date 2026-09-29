/**
 * TypeScript-типы каталога - зеркало backend-DTO.
 *
 * Важно: тип описывает, КАК данные приходят из API. Числа - number,
 * даты - строки (JSON не имеет типа Date). null означает «в каталоге
 * нет значения» (ТТХ не заполнены - норма данных каталога).
 */

export type ProductClass = "brs" | "bas" | "software";
export type SolutionStatus = "operation" | "piloting" | "rnd";
export type SourceKind = "organizer_catalog" | "open_source" | "manual";

/** Элемент словаря фильтров (GET /api/filters). */
export interface DictItem {
  id: number;
  code: string;
  name: string;
}

/** Допустимое сочетание тип-подтип (для зависимого списка фильтра). */
export interface TypeSubtypeLink {
  typeId: number;
  subtypeId: number;
}

/** Ответ GET /api/filters - словари и диапазоны панели фильтров. */
export interface FiltersData {
  types: DictItem[];
  /** Типы объектов пользователя (склад/аэропорт/медучреждение) - для проектов. */
  objectTypes: DictItem[];
  subtypes: DictItem[];
  industries: DictItem[];
  processes: DictItem[];
  statuses: SolutionStatus[];
  typeSubtypeMapping: TypeSubtypeLink[];
  priceMin: number | null;
  priceMax: number | null;
  trlMin: number | null;
  trlMax: number | null;
}

/** Элемент списка каталога (GET /api/solutions) - с именами справочников. */
export interface SolutionSummary {
  id: number;
  name: string;
  vendorName: string;
  productClass: ProductClass;
  solutionTypeName: string | null;
  solutionSubtypeName: string | null;
  regionName: string | null;
  status: SolutionStatus;
  description: string | null;
  /** Цена, руб. с НДС. */
  priceRub: number;
  /** УГТ (TRL) 1..9. */
  trl: number | null;
  /** Рыночный потенциал 2.0..5.0. */
  marketPotential: number | null;
  // --- ТТХ (могут отсутствовать в данных каталога) ---
  payloadKg: number | null;
  massKg: number | null;
  lengthMm: number | null;
  widthMm: number | null;
  heightMm: number | null;
  positioningAccuracyMm: number | null;
  speedMs: number | null;
  chargingPowerKw: number | null;
  noiseLevelDba: number | null;
  completenessPct: number | null;
  sourceKind: SourceKind;
}

/** ТТХ решения из EAV с провенансом. */
export interface SolutionCharacteristic {
  solutionId: number;
  typeCode: string;
  typeName: string;
  groupCode: string;
  unit: string | null;
  dataType: "number" | "text" | "boolean" | "date";
  valueNumeric: number | null;
  valueText: string | null;
  valueBool: boolean | null;
  valueDate: string | null;
  sourceKind: SourceKind;
  sourceUrl: string | null;
  sourceDate: string | null;
  isConfirmed: boolean;
}

/** Кейс внедрения в карточке решения. */
export interface SolutionCaseItem {
  id: number;
  name: string;
  description: string | null;
  sourceUrl: string | null;
  sourceDate: string | null;
}

/** Применение решения: отрасль + процесс. */
export interface SolutionApplicationItem {
  industryId: number;
  industryName: string;
  processId: number;
  processName: string;
  offerPriceRub: number | null;
}

/** Полная карточка (GET /api/solutions/{id}, /compare). */
export interface SolutionFull extends SolutionSummary {
  externalId: string | null;
  sourceUrl: string | null;
  sourceDate: string | null;
  characteristics: SolutionCharacteristic[];
  cases: SolutionCaseItem[];
  applications: SolutionApplicationItem[];
}

/** Страница результатов (собственная обёртка backend - стабильный контракт). */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Человекочитаемые подписи классов продукта. */
export const PRODUCT_CLASS_LABELS: Record<ProductClass, string> = {
  brs: "Робототехника",
  bas: "БАС",
  software: "ПО",
};

/** Человекочитаемые подписи статусов. */
export const SOLUTION_STATUS_LABELS: Record<SolutionStatus, string> = {
  operation: "В эксплуатации",
  piloting: "Пилотирование",
  rnd: "НИОКР",
};

/** Подписи происхождения записи. */
export const SOURCE_KIND_LABELS: Record<SourceKind, string> = {
  organizer_catalog: "Каталог решений",
  open_source: "Открытый источник",
  manual: "Добавлено вручную",
};
