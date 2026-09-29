import { headers } from "next/headers";
import type {
  FiltersData,
  PageResponse,
  SolutionFull,
  SolutionSummary,
} from "@/types/solution";
import type {
  PageResponse as ProjectPage,
  ProjectCreate,
  ProjectFull,
  ProjectSummary,
  ProjectUpdate,
} from "@/types/project";
import type { Attachment, ParameterDto } from "@/types/parameter";
import type { SelectionRunDto } from "@/types/selection";
import type {
  AssumptionDto,
  CalculationFull,
  CalculationDto,
  ComparisonDto,
  ScenarioDetails,
} from "@/types/economics";

/**
 * Клиент API фронтенда (BFF-архитектура).
 *
 * Страницы НИКОГДА не ходят на Spring Boot напрямую, а только к своим
 * Next.js-роутам /api/* (app/api/...), которые проксируют запрос на
 * backend. Адрес Java-сервиса знает только BFF (BACKEND_URL), в браузере
 * не возникает cross-origin проблем, а сессия (JWT в httpOnly-cookie)
 * подставляется на своей стороне.
 *
 * Откуда берётся адрес Next.js: серверный fetch требует абсолютный url —
 * берём его из заголовков текущего запроса (host + x-forwarded-proto).
 * APP_URL — ручное переопределение (см. .env.example).
 */

/** Параметры запроса списка каталога (совпадают с query-параметрами API). */
export type CatalogParams = Record<string, string | undefined>;

async function fetchFromBff(path: string, init?: RequestInit): Promise<Response> {
  const requestHeaders = await headers();
  const appUrl =
    process.env.APP_URL ??
    `${requestHeaders.get("x-forwarded-proto") ?? "http"}://${requestHeaders.get("host")}`;
  // Таймаут 10 с: при зависшем backend страница не висит до ~5 минут
  // (fetch без AbortSignal копил бы зависающие SSR-запросы).
  // Cookie текущего посетителя пробрасывается в подзапрос: серверный fetch
  // не прикрепляет их сам, а для авторизованных эндпоинтов (проекты)
  // BFF-роуту нужна httpOnly-сессия.
  return fetch(`${appUrl}${path}`, {
    cache: "no-store",
    signal: AbortSignal.timeout(10_000),
    ...init,
    headers: {
      ...(init?.headers ?? {}),
      cookie: requestHeaders.get("cookie") ?? "",
    },
  });
}

/** Список решений: фильтры, поиск, сортировка, пагинация. */
export async function fetchSolutions(
  params: CatalogParams,
): Promise<PageResponse<SolutionSummary>> {
  const qs = toQueryString(params);
  const response = await fetchFromBff(`/api/solutions${qs ? `?${qs}` : ""}`);
  if (!response.ok) {
    throw new Error(
      `Не удалось загрузить каталог (HTTP ${response.status}). ` +
        "Проверьте, что backend запущен на http://localhost:8080.",
    );
  }
  return (await response.json()) as PageResponse<SolutionSummary>;
}

/** Карточка решения по id (ТТХ, кейсы, применения, источник). */
export async function fetchSolutionById(id: string | number): Promise<SolutionFull> {
  const response = await fetchFromBff(`/api/solutions/${id}`);
  if (response.status === 404) {
    throw new Error("Решение не найдено");
  }
  if (!response.ok) {
    throw new Error(
      `Не удалось загрузить карточку решения (HTTP ${response.status}).`,
    );
  }
  return (await response.json()) as SolutionFull;
}

/** Сравнение 2-10 решений. */
export async function fetchCompare(ids: number[]): Promise<SolutionFull[]> {
  const response = await fetchFromBff(`/api/solutions/compare?ids=${ids.join(",")}`);
  if (!response.ok) {
    throw new Error(
      `Не удалось загрузить сравнение (HTTP ${response.status}). ` +
        "Проверьте, что выбраны минимум 2 существующих решения.",
    );
  }
  return (await response.json()) as SolutionFull[];
}

/** Словари и диапазоны для панели фильтров. */
export async function fetchFilters(): Promise<FiltersData> {
  const response = await fetchFromBff("/api/filters");
  if (!response.ok) {
    throw new Error(
      `Не удалось загрузить справочники фильтров (HTTP ${response.status}).`,
    );
  }
  return (await response.json()) as FiltersData;
}

// ---------------------------------------------------------------------
// Проекты. Все запросы — с httpOnly-сессией:
// fetchFromBff пробрасывает cookie, BFF-роуты /api/projects подставляют
// Authorization: Bearer (как /api/auth/me).
// ---------------------------------------------------------------------

/** Сообщение об ошибке из тела ответа backend (ErrorResponse.message). */
async function backendErrorMessage(response: Response, fallback: string): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    if (body?.message) {
      return body.message;
    }
  } catch {
    // тело не JSON — остаётся fallback
  }
  return fallback;
}

/** Список проектов текущего пользователя (сортировка backend: свежие сверху). */
export async function fetchProjects(
  page?: number,
): Promise<ProjectPage<ProjectSummary>> {
  const qs = page && page > 0 ? `?page=${page}` : "";
  const response = await fetchFromBff(`/api/projects${qs}`);
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить проекты (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as ProjectPage<ProjectSummary>;
}

/** Карточка проекта. */
export async function fetchProject(id: string | number): Promise<ProjectFull> {
  const response = await fetchFromBff(`/api/projects/${id}`);
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить проект (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as ProjectFull;
}

/** Создание проекта; 409/400 возвращаются как Error с текстом backend. */
export async function createProject(data: ProjectCreate): Promise<ProjectFull> {
  const response = await fetchFromBff("/api/projects", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось создать проект (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as ProjectFull;
}

/** Редактирование проекта (имя, описание). */
export async function updateProject(
  id: string | number,
  data: ProjectUpdate,
): Promise<ProjectFull> {
  const response = await fetchFromBff(`/api/projects/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось сохранить проект (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as ProjectFull;
}

/** Копирование проекта (имя «<имя> (копия N)» подбирает backend). */
export async function copyProject(id: string | number): Promise<ProjectFull> {
  const response = await fetchFromBff(`/api/projects/${id}/copy`, {
    method: "POST",
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось скопировать проект (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as ProjectFull;
}

/** Результаты подбора проекта (сценарии + решения с объяснениями). */
export async function fetchSelection(
  id: string | number,
): Promise<SelectionRunDto> {
  const response = await fetchFromBff(`/api/projects/${id}/selection`);
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(
        response,
        `Не удалось загрузить результаты подбора (HTTP ${response.status}).`,
      ),
    );
  }
  return (await response.json()) as SelectionRunDto;
}

/** Удаление проекта (каскадно с дочерними данными). */
export async function deleteProject(id: string | number): Promise<void> {
  const response = await fetchFromBff(`/api/projects/${id}`, {
    method: "DELETE",
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось удалить проект (HTTP ${response.status}).`),
    );
  }
}

// ---------------------------------------------------------------------
// Параметры объекта.
// Чтение — серверные функции (SSR страницы); запись — клиентские
// fetch напрямую к BFF-роутам (httpOnly-cookie подставит роут).
// ---------------------------------------------------------------------

/** Все параметры типа объекта проекта с метаданными и значениями. */
export async function fetchParameters(
  projectId: string | number,
): Promise<ParameterDto[]> {
  const response = await fetchFromBff(`/api/projects/${projectId}/parameters`);
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить параметры (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as ParameterDto[];
}

/** История загрузок исходников параметров (Excel/CSV). */
export async function fetchAttachments(
  projectId: string | number,
): Promise<Attachment[]> {
  const response = await fetchFromBff(
    `/api/projects/${projectId}/parameters/attachments`,
  );
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить вложения (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as Attachment[];
}

/** Строка запроса из непустых параметров. */
function toQueryString(params: CatalogParams): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") {
      search.set(key, value);
    }
  }
  return search.toString();
}

// ---------------------------------------------------------------------
// Экономика и сценарии. Чтение — серверные
// функции (SSR); запись (расчёт, допущения, корректировка) — клиентские
// fetch к BFF-роутам (httpOnly-cookie подставит роут).
// ---------------------------------------------------------------------

/** Сценарии проекта с составом оборудования. */
export async function fetchScenarios(
  projectId: string | number,
): Promise<ScenarioDetails[]> {
  const response = await fetchFromBff(`/api/projects/${projectId}/scenarios`);
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить сценарии (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as ScenarioDetails[];
}

/** Текущие допущения экономики проекта. */
export async function fetchAssumptions(
  projectId: string | number,
): Promise<AssumptionDto[]> {
  const response = await fetchFromBff(`/api/projects/${projectId}/assumptions`);
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить допущения (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as AssumptionDto[];
}

/** Таблица сравнения сценариев. */
export async function fetchComparison(
  projectId: string | number,
): Promise<ComparisonDto> {
  const response = await fetchFromBff(`/api/projects/${projectId}/compare`);
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить сравнение (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as ComparisonDto;
}

/** История расчётов сценария. */
export async function fetchCalculationHistory(
  projectId: string | number,
  scenarioId: number,
): Promise<CalculationDto[]> {
  const response = await fetchFromBff(
    `/api/projects/${projectId}/scenarios/${scenarioId}/calculations`,
  );
  if (response.status === 404) {
    throw new Error("Проект или сценарий не найдены");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить историю расчётов (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as CalculationDto[];
}

/** Детальный расчёт (метрики + снимок допущений + разбивка). */
export async function fetchCalculation(
  projectId: string | number,
  calculationId: number,
): Promise<CalculationFull> {
  const response = await fetchFromBff(
    `/api/projects/${projectId}/calculations/${calculationId}`,
  );
  if (response.status === 404) {
    throw new Error("Расчёт не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, `Не удалось загрузить расчёт (HTTP ${response.status}).`),
    );
  }
  return (await response.json()) as CalculationFull;
}

// ---------------------------------------------------------------------
// Имитация 2D + KPI
// ---------------------------------------------------------------------

import type {
  SimulationExportResponse,
  SimulationRun,
} from "@/types/simulation";

/**
 * Запуск имитации сценария: синхронный расчёт KPI на
 * backend (≤ 60 с,; BFF-роут держит 70 с). Клиентский
 * компонент страницы имитации вызывает BFF напрямую (как
 * CalculateAllButton) — эта функция для серверных вызовов.
 */
export async function runSimulation(
  projectId: string | number,
  scenarioId: number,
): Promise<SimulationRun> {
  const response = await fetchFromBff(
    `/api/projects/${projectId}/scenarios/${scenarioId}/simulation/run`,
    { method: "POST" },
  );
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(
        response,
        `Не удалось запустить имитацию (HTTP ${response.status}).`,
      ),
    );
  }
  return (await response.json()) as SimulationRun;
}

/**
 * Последний результат имитации сценария (null — ещё не запускалась:
 * 404 от BFF трактуется как «пусто», страница показывает стартовое
 * состояние, а не ошибку).
 */
export async function getSimulation(
  projectId: string | number,
  scenarioId: number,
): Promise<SimulationRun | null> {
  const response = await fetchFromBff(
    `/api/projects/${projectId}/scenarios/${scenarioId}/simulation`,
  );
  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(
        response,
        `Не удалось загрузить результат имитации (HTTP ${response.status}).`,
      ),
    );
  }
  return (await response.json()) as SimulationRun;
}

/** История имитаций проекта (свежие сверху). */
export async function getSimulationHistory(
  projectId: string | number,
): Promise<SimulationRun[]> {
  const response = await fetchFromBff(`/api/projects/${projectId}/simulations`);
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(
        response,
        `Не удалось загрузить историю имитаций (HTTP ${response.status}).`,
      ),
    );
  }
  return (await response.json()) as SimulationRun[];
}

// ---------------------------------------------------------------------
// Экспорт отчётов. Скачивание — прямой
// GET BFF-роута (браузер по cookie сессии); генерация — POST, файлы —
// GET/DELETE.
// ---------------------------------------------------------------------

import type { ExportDto } from "@/types/export";

/** История выгрузок проекта (свежие сверху, SSR страницы экспорта). */
export async function listExports(
  projectId: string | number,
): Promise<ExportDto[]> {
  const response = await fetchFromBff(`/api/projects/${projectId}/exports`);
  if (response.status === 404) {
    throw new Error("Проект не найден");
  }
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(
        response,
        `Не удалось загрузить историю выгрузок (HTTP ${response.status}).`,
      ),
    );
  }
  return (await response.json()) as ExportDto[];
}

/**
 * Сгенерировать отчёт (POST /exports; формат pdf | xlsx | csv).
 * Серверная функция — клиентский компонент страницы вызывает BFF-роут
 * напрямую (как CalculateAllButton), эта — для серверных сценариев.
 */
export async function createExport(
  projectId: string | number,
  format: "pdf" | "xlsx" | "csv",
): Promise<ExportDto> {
  const response = await fetchFromBff(`/api/projects/${projectId}/exports`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ format }),
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(
        response,
        `Не удалось сгенерировать отчёт (HTTP ${response.status}).`,
      ),
    );
  }
  return (await response.json()) as ExportDto;
}

/** Удалить выгрузку (файл + строка истории). */
export async function deleteExport(
  projectId: string | number,
  exportId: number,
): Promise<void> {
  const response = await fetchFromBff(
    `/api/projects/${projectId}/exports/${exportId}`,
    { method: "DELETE" },
  );
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(
        response,
        `Не удалось удалить выгрузку (HTTP ${response.status}).`,
      ),
    );
  }
}

/**
 * Ссылка скачивания выгрузки через BFF (Content-Disposition attachment
 * подставляет backend; cookie сессии передаёт роут).
 */
export function downloadExportUrl(
  projectId: string | number,
  exportId: number,
): string {
  return `/api/projects/${projectId}/exports/${exportId}`;
}

// ====================================================================
// Управление — доступ
// только роли admin; роль проверяет backend (hasRole на /api/admin/**),
// BFF лишь пробрасывает cookie-сессию.
// ====================================================================

import type {
  AdminImportDto,
  AdminReferenceDto,
  AdminPage,
  CatalogImportSummaryDto,
  DictCode,
} from "@/types/admin";

/** Список решений «Управления»: поиск + фильтр «требуют проверки». */
export async function adminListSolutions(params: {
  q?: string;
  needsCheck?: boolean;
  page?: number;
  size?: number;
}): Promise<AdminPage<SolutionSummary>> {
  const search = new URLSearchParams();
  if (params.q) search.set("q", params.q);
  if (params.needsCheck) search.set("needsCheck", "true");
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 20));
  const response = await fetchFromBff(`/api/admin/solutions?${search}`);
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось загрузить список решений"),
    );
  }
  return (await response.json()) as AdminPage<SolutionSummary>;
}

/** Карточка решения для формы редактирования. */
export async function adminGetSolution(id: number | string): Promise<SolutionFull> {
  const response = await fetchFromBff(`/api/admin/solutions/${id}`);
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось загрузить решение"),
    );
  }
  return (await response.json()) as SolutionFull;
}

/** Создать решение вручную (провенанс manual). */
export async function adminCreateSolution(body: unknown): Promise<SolutionFull> {
  const response = await fetchFromBff("/api/admin/solutions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось создать решение"),
    );
  }
  return (await response.json()) as SolutionFull;
}

/** Изменить решение (полное состояние формы). */
export async function adminUpdateSolution(
  id: number | string,
  body: unknown,
): Promise<SolutionFull> {
  const response = await fetchFromBff(`/api/admin/solutions/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось сохранить решение"),
    );
  }
  return (await response.json()) as SolutionFull;
}

/** Удалить решение (409, если на него ссылаются проекты). */
export async function adminDeleteSolution(id: number | string): Promise<void> {
  const response = await fetchFromBff(`/api/admin/solutions/${id}`, {
    method: "DELETE",
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось удалить решение"),
    );
  }
}

/** Счётчики записей по всем справочникам (плитки). */
export async function adminReferenceCounts(): Promise<Record<string, number>> {
  const response = await fetchFromBff("/api/admin/references");
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось загрузить справочники"),
    );
  }
  return (await response.json()) as Record<string, number>;
}

/** Страница записей одного справочника (поиск + пагинация). */
export async function adminListReferences(
  dictCode: DictCode,
  q: string | undefined,
  page: number,
  size = 50,
): Promise<AdminPage<AdminReferenceDto>> {
  const search = new URLSearchParams();
  if (q) search.set("q", q);
  search.set("page", String(page));
  search.set("size", String(size));
  const response = await fetchFromBff(
    `/api/admin/references/${dictCode}?${search}`,
  );
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось загрузить справочник"),
    );
  }
  return (await response.json()) as AdminPage<AdminReferenceDto>;
}

/** Создать запись справочника. */
export async function adminCreateReference(
  dictCode: DictCode,
  body: unknown,
): Promise<AdminReferenceDto> {
  const response = await fetchFromBff(`/api/admin/references/${dictCode}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось создать запись"),
    );
  }
  return (await response.json()) as AdminReferenceDto;
}

/** Изменить запись справочника. */
export async function adminUpdateReference(
  dictCode: DictCode,
  id: number,
  body: unknown,
): Promise<AdminReferenceDto> {
  const response = await fetchFromBff(`/api/admin/references/${dictCode}/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось сохранить запись"),
    );
  }
  return (await response.json()) as AdminReferenceDto;
}

/** Удалить запись справочника (409 при FK-ссылках). */
export async function adminDeleteReference(
  dictCode: DictCode,
  id: number,
): Promise<void> {
  const response = await fetchFromBff(
    `/api/admin/references/${dictCode}/${id}`,
    { method: "DELETE" },
  );
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось удалить запись"),
    );
  }
}

/** Загрузить таблицу каталога (CSV/XLSX). */
export async function adminImportCatalog(
  formData: FormData,
): Promise<CatalogImportSummaryDto> {
  // Файл уходит через собственный BFF-роут: fetchFromBff строит
  // запрос от серверного контекста, а файл — от клиента страницы
  const response = await fetch("/api/admin/catalog/import", {
    method: "POST",
    body: formData,
  });
  if (!response.ok) {
    let message = `Импорт не выполнен (HTTP ${response.status}).`;
    try {
      const body = (await response.json()) as { message?: string };
      if (body.message) {
        message = body.message;
      }
    } catch {
      // тело не JSON — оставляем общий текст
    }
    throw new Error(message);
  }
  return (await response.json()) as CatalogImportSummaryDto;
}

/** История импортов каталога. */
export async function adminImportHistory(limit = 10): Promise<AdminImportDto[]> {
  const response = await fetchFromBff(
    `/api/admin/catalog/import/history?limit=${limit}`,
  );
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось загрузить историю импортов"),
    );
  }
  return (await response.json()) as AdminImportDto[];
}

/** Обновить каталог по запросу — повторный импорт последнего файла. */
export async function adminRefreshCatalog(): Promise<CatalogImportSummaryDto> {
  const response = await fetchFromBff("/api/admin/catalog/refresh", {
    method: "POST",
  });
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось обновить каталог"),
    );
  }
  return (await response.json()) as CatalogImportSummaryDto;
}

/** Upsert значения ТТХ решения (EAV). */
export async function adminUpsertCharacteristic(
  solutionId: number | string,
  characteristicTypeId: number,
  body: unknown,
): Promise<unknown> {
  const response = await fetchFromBff(
    `/api/admin/solutions/${solutionId}/characteristics/${characteristicTypeId}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    },
  );
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось сохранить характеристику"),
    );
  }
  return response.json();
}

/** Удалить значение ТТХ решения. */
export async function adminDeleteCharacteristic(
  solutionId: number | string,
  characteristicTypeId: number,
): Promise<void> {
  const response = await fetchFromBff(
    `/api/admin/solutions/${solutionId}/characteristics/${characteristicTypeId}`,
    { method: "DELETE" },
  );
  if (!response.ok) {
    throw new Error(
      await backendErrorMessage(response, "Не удалось удалить характеристику"),
    );
  }
}
