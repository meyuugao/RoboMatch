/**
 * Типы гостевого демо-расчёта (зеркала DTO backend: DemoDescriptorDto,
 * DemoCalculationDto). Демо-расчёт выполняется в памяти на константах
 * демо-набора «Склад» и не сохраняется.
 */

/** Тип объекта с признаком доступности демо-набора. */
export type DemoObjectType = {
  code: string;
  name: string;
  available: boolean;
};

/** Параметр демо-набора данных для показа на странице. */
export type DemoParameter = {
  title: string;
  value: string;
  unit: string;
};

/** Решение демонстрационного состава. */
export type DemoComposition = {
  solutionName: string;
  vendorName: string;
  quantity: number;
};

/** Ответ GET /api/demo - описание демо-расчёта. */
export type DemoDescriptor = {
  objectTypeName: string;
  availableTypes: DemoObjectType[];
  parameters: DemoParameter[];
  composition: DemoComposition[];
};

/** Ответ POST /api/demo/calculate - демо-расчёт без сохранения. */
export type DemoCalculation = {
  /** Признак демо: расчёт в памяти, ничего не сохраняется. */
  demo: boolean;
  objectTypeName: string;
  comparison: {
    scenarios: import("./economics").ComparisonScenario[];
    horizonYears: number | null;
    versionModel: string | null;
  };
};
