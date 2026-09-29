/**
 * Состояние сравнения решений - ids выбранных решений.
 *
 * Живёт в localStorage браузера (это UI-корзина просмотра, а не данные
 * проекта): переживает переходы по каталогу и перезагрузку страницы.
 * Синхронизация между чекбоксами карточек, панелью «Сравнить (N)» и
 * страницей сравнения - через window-событие COMPARE_CHANGED_EVENT.
 *
 * Максимум 10 решений - как в API /api/solutions/compare: таблица
 * большего размера нечитаема. SSR-безопасно: обращение к window -
 * только под защитой typeof window.
 */

const STORAGE_KEY = "robomatch_compare_ids";
export const COMPARE_CHANGED_EVENT = "robomatch:compare-changed";
export const COMPARE_LIMIT = 10;

/** Текущий набор id (уникальные, в порядке выбора; на SSR - []). */
export function getCompareIds(): number[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((x): x is number => typeof x === "number" && Number.isInteger(x))
      .slice(0, COMPARE_LIMIT);
  } catch {
    return [];
  }
}

function save(ids: number[]): void {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(ids.slice(0, COMPARE_LIMIT)));
  window.dispatchEvent(new CustomEvent(COMPARE_CHANGED_EVENT, { detail: ids }));
}

/** Добавить/убрать решение; возвращает итоговый признак «выбрано». */
export function toggleCompareId(id: number): boolean {
  const current = getCompareIds();
  const has = current.includes(id);
  let next: number[];
  if (has) {
    next = current.filter((x) => x !== id);
  } else {
    if (current.length >= COMPARE_LIMIT) return false; // лимит - молча, UI сам покажет
    next = [...current, id];
  }
  save(next);
  return !has;
}

/** Перезаписать набор (использует страница сравнения при удалении колонки). */
export function setCompareIds(ids: number[]): void {
  save(ids);
}
