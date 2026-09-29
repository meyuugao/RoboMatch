-- V4: фиксированные и производные параметры объекта (фикс среза
-- «Параметры объекта», задача A1).
--
-- is_fixed  — параметр-константа: не редактируется ни в форме, ни импортом
--             (коэффициент начислений на ФОТ 1.302, рабочих дней в году 365,
--             наличие WMS). Значение живёт в default_value_* метаданных,
--             пользовательские строки project_parameter_value игнорируются.
-- is_derived — параметр-вычисление: значение считается из других параметров
--             объекта (объём отбора штук/сутки = строки/сутки × 1.5; площадь
--             активной зоны = общая площадь × 0.5 — примечания датасета
--             организатора). PUT/DELETE значения — 400, currentValue
--             вычисляется на GET.
--
-- Флаги выставляет seed через scripts/seed/data/parameter_overrides.json
-- (XLSX организатора не меняется — AGENTS.md §10: data/organizer только
-- чтение; все корректировки — overrides-файлом).

ALTER TABLE object_type_parameter
    ADD COLUMN IF NOT EXISTS is_fixed boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_derived boolean NOT NULL DEFAULT false;
