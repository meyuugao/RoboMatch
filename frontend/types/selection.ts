/**
 * Типы API подбора решений - зеркало
 * backend-DTO (SelectionRunDto, SelectionResultDto, ScenarioDto,
 * CriterionContributionDto, ManualAddRequest).
 */

export type SelectionStatus = "fit" | "needs_check" | "excluded";

/** Русские подписи статусов подбора (data_model.md §10.7). */
export const SELECTION_STATUS_LABELS: Record<SelectionStatus, string> = {
  fit: "Подходит",
  needs_check: "Требует проверки",
  excluded: "Исключено",
};

/** Сценарий расчёта (data_model.md §10.5: base/purchase/raas). */
export type ScenarioDto = {
  id: number;
  type: "base" | "purchase" | "raas";
  name: string;
};

/** Русские подписи типов сценариев (assumptions.md §18). */
export const SCENARIO_TYPE_LABELS: Record<ScenarioDto["type"], string> = {
  base: "Базовый (без роботизации)",
  purchase: "Покупка",
  raas: "RaaS",
};

/** Вклад критерия в Score. */
export type CriterionContribution = {
  code: string;
  name: string;
  weight: number;
  normalizedValue: number | null;
  contribution: number;
  evaluated: boolean;
};

/** Результат подбора одного решения с объяснением. */
export type SelectionResultDto = {
  solutionId: number;
  solutionName: string;
  vendorName: string | null;
  /** Тип решения. */
  solutionTypeName: string | null;
  status: SelectionStatus;
  reason: string | null;
  rank: number | null;
  score: number | null;
  criteriaContribution: CriterionContribution[] | null;
  missingData: string[] | null;
};

/** Ответ GET /selection и POST /selection/run. */
export type SelectionRunDto = {
  scenarios: ScenarioDto[];
  results: SelectionResultDto[];
};

/** Тело POST /scenarios/{scenarioId}/solutions. */
export type ManualAddRequest = {
  solutionId: number;
  manualReason: string;
  quantity?: number;
};
