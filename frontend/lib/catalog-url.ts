import type { CatalogParams } from "@/lib/api";

/**
 * Сборка URL страницы каталога с query-параметрами.
 *
 * Формат фильтров — query-параметры URL: ссылка с фильтрами
 * шарится и открывается у любого пользователя с тем же результатом,
 * состояние кэшируется браузером, соответствует REST.
 *
 * changes поверх current: undefined/"" удаляет параметр (например,
 * сброс фильтра или возврат на первую страницу).
 */
export function buildCatalogUrl(
  current: CatalogParams,
  changes: CatalogParams,
): string {
  const merged: Record<string, string> = {};
  for (const [key, value] of Object.entries(current)) {
    if (value !== undefined && value !== "") {
      merged[key] = value;
    }
  }
  for (const [key, value] of Object.entries(changes)) {
    if (value === undefined || value === "") {
      delete merged[key];
    } else {
      merged[key] = value;
    }
  }
  const qs = new URLSearchParams(merged).toString();
  return qs ? `/catalog?${qs}` : "/catalog";
}
