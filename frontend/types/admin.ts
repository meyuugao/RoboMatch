/**
 * TypeScript-типы раздела «Управление» - зеркало backend-DTO
 * (AdminReferenceDto, CatalogImportSummaryDto, AdminImportDto).
 *
 * Числа - number, даты - строки (JSON не имеет типа Date).
 */

import type { PageResponse } from "@/types/solution";

/** Коды справочников, доступных админу. */
export type DictCode =
  | "industry"
  | "process"
  | "vendor"
  | "region"
  | "solution_type"
  | "solution_subtype"
  | "characteristic_type";

/** Человекочитаемые названия справочников (плитки и заголовки). */
export const DICT_TITLES: Record<DictCode, string> = {
  industry: "Отрасли",
  process: "Процессы",
  vendor: "Производители",
  region: "Регионы",
  solution_type: "Типы решений",
  solution_subtype: "Подтипы решений",
  characteristic_type: "Типы характеристик",
};

/** Порядок плиток на странице справочников. */
export const DICT_ORDER: DictCode[] = [
  "industry",
  "process",
  "vendor",
  "region",
  "solution_type",
  "solution_subtype",
  "characteristic_type",
];

/** Запись справочника (GET/POST/PUT /api/admin/references/*). */
export interface AdminReferenceDto {
  id: number;
  code: string | null;
  name: string;
  /** Только process: активен ли процесс. */
  isActive: boolean | null;
  /** Только characteristic_type. */
  groupCode: string | null;
  dataType: string | null;
  unit: string | null;
}

/** Счётчики одной сущности в summary импорта. */
export interface EntityCountersDto {
  added: number;
  updated: number;
  skipped: number;
}

/** Результат импорта таблицы каталога. */
export interface CatalogImportSummaryDto {
  importId: number;
  fileName: string | null;
  status: string;
  startedAt: string | null;
  finishedAt: string | null;
  csvRows: number;
  solutions: number;
  dupGroups: number;
  entities: Record<string, EntityCountersDto>;
  warnings: string[] | null;
}

/** Строка истории импортов (V7 admin_import_log). */
export interface AdminImportDto {
  id: number;
  fileName: string;
  sizeBytes: number;
  startedAt: string;
  finishedAt: string | null;
  status: "running" | "completed" | "failed";
  summary: Record<string, unknown> | null;
}

/** Подписи статусов импорта для UI. */
export const IMPORT_STATUS_LABELS: Record<AdminImportDto["status"], string> = {
  running: "Выполняется",
  completed: "Завершён",
  failed: "Ошибка",
};

/** Форма решения (создание/правка вручную). */
export interface AdminSolutionForm {
  name: string;
  vendorId: number | null;
  productClass: "brs" | "bas" | "software";
  solutionTypeId: number | null;
  solutionSubtypeId: number | null;
  regionId: number | null;
  status: "operation" | "piloting" | "rnd";
  description: string;
  priceRub: string;
  trl: string;
  marketPotential: string;
  payloadKg: string;
  massKg: string;
  lengthMm: string;
  widthMm: string;
  heightMm: string;
  positioningAccuracyMm: string;
  speedMs: string;
  chargingPowerKw: string;
  noiseLevelDba: string;
}

/** Текучие счётчики страниц (переиспользует PageResponse из каталога). */
export type AdminPage<T> = PageResponse<T>;

/** Отказ по одной позиции массового удаления (BulkDeleteResultDto). */
export interface BulkDeleteFailureDto {
  id: number;
  reason: string;
}

/** Результат массового удаления: что удалено и причины отказов. */
export interface BulkDeleteResultDto {
  deleted: number[];
  failed: BulkDeleteFailureDto[];
}
