/**
 * TypeScript-типы проектов — зеркало backend-DTO
 *. Числа — number, даты — строки (JSON не имеет Date),
 * null — «значения нет».
 */

export type ProjectStatus = "draft" | "active" | "archived";

/** Русские подписи статусов проекта (assumptions.md §18). */
export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  draft: "Черновик",
  active: "Активен",
  archived: "В архиве",
};

/** Элемент списка проектов (GET /api/projects) — с именем типа объекта. */
export interface ProjectSummary {
  id: number;
  name: string;
  description: string | null;
  objectTypeName: string;
  status: ProjectStatus;
  createdAt: string;
  updatedAt: string;
}

/** Карточка проекта (GET /api/projects/{id}) — с типом объекта целиком. */
export interface ProjectFull {
  id: number;
  name: string;
  description: string | null;
  objectTypeId: number;
  objectTypeName: string;
  objectTypeCode: string;
  objectTypeIsCalcEnabled: boolean;
  status: ProjectStatus;
  createdAt: string;
  updatedAt: string;
}

/** Тело POST /api/projects. */
export interface ProjectCreate {
  name: string;
  description?: string;
  objectTypeId: number;
}

/** Тело PUT /api/projects/{id}. */
export interface ProjectUpdate {
  name: string;
  description?: string;
}

/**
 * Стабильная обёртка страницы (единый контракт с каталогом).
 * Дублируется из types/solution.ts сознательно: домены каталога и
 * проектов независимы, а общие утилиты появятся с ростом числа
 * списочных эндпоинтов.
 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Форматирование дат для UI (локаль русская — язык интерфейса). */
export function formatProjectDate(iso: string): string {
  return new Date(iso).toLocaleString("ru-RU", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
