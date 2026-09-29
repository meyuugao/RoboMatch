/**
 * Типы API имитации (,
 * 3.7.4, 4.3.3) — зеркало SimulationRunDto (backend)
 *
 * 6 KPI
 * роботов, простои, узкие места (зоны), достижимость заявленной
 * производительности. KPI сохраняются в simulation_result.kpi_json
 * (снимок переменного состава, data_model.md §10.11/§12).
 */

/** Зона склада с загрузкой (узкие места). */
export type SimulationZone = {
  /** receiving | storage | picking | shipping */
  code: "receiving" | "storage" | "picking" | "shipping" | string;
  name: string;
  /** Пиковый поток зоны (паллет-точки), оп/час. */
  flowPerHour: number;
  /** Доля робот-времени парка в зоне, %. */
  robotTimeSharePct: number;
};

/** Решение в составе сценария (для схемы). */
export type SimulationCompositionLine = {
  solutionId: number;
  name: string;
  quantity: number;
  /** Скорость из ТТХ, м/с; null — нет в ТТХ (применяется допущение). */
  speedMs: number | null;
};

/** Входы KPI-модели (для отображения и воспроизведения). */
export type SimulationInputs = {
  routeLengthM: number;
  pickDropSec: number;
  speedMs: number;
  hoursPerDay: number;
  inboundPerDay: number;
  outboundPerDay: number;
  peakFactor: number;
};

/** Результат имитации: метаданные выполнения + KPI. */
export type SimulationRun = {
  id: number;
  scenarioId: number;
  scenarioName: string | null;
  /** running | completed | failed. */
  status: "running" | "completed" | "failed" | string;
  startedAt: string | null;
  finishedAt: string | null;
  /** Длительность расчёта, мс. */
  durationMs: number | null;
  // --- 6 KPI ---
  declaredThroughputPerHour: number | null;
  actualThroughputPerHour: number | null;
  utilizationPct: number | null;
  idlePct: number | null;
  zones: SimulationZone[] | null;
  achievabilityPct: number | null;
  // --- разбивки ---
  fleetSpeedMs: number | null;
  capacityPerRobotPerHour: number | null;
  effectivePerRobotPerHour: number | null;
  peakDemandPerHour: number | null;
  avgCycleTimeSec: number | null;
  inboundCycleTimeSec: number | null;
  outboundCycleTimeSec: number | null;
  dailyTravelKmPerRobot: number | null;
  chargingStations: number | null;
  robots: number | null;
  movingPct: number | null;
  handlingPct: number | null;
  composition: SimulationCompositionLine[] | null;
  inputs: SimulationInputs | null;
  /** Предупреждения (сверка с экономикой —). */
  warnings: string[] | null;
  /** Ссылка на сохранённую схему. */
  exportUrl: string | null;
  /** Причина провала (status=failed). */
  error: string | null;
  /** Версия KPI-модели. */
  version: string | null;
};

/** Ответ сохранения схемы. */
export type SimulationExportResponse = {
  exportUrl: string;
  file: string;
};

export const ZONE_LABELS: Record<string, string> = {
  receiving: "Приёмка",
  storage: "Хранение",
  picking: "Отбор (комплектация)",
  shipping: "Отгрузка",
};

export const SIM_STATUS_LABELS: Record<string, string> = {
  running: "Выполняется",
  completed: "Завершена",
  failed: "Ошибка",
};
