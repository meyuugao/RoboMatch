-- V5: хранение текущих переопределений допущений экономики проекта.
-- ТЗ 3.5.3 (пользователь изменяет ключевые допущения) требует персистентности
-- между сессиями и расчётами; в V1 такой таблицы нет (calculation_assumption -
-- только снимок конкретного расчёта, п.10.9 data_model.md).
-- Структура повторяет calculation_assumption (name/value) без провенанса:
-- провенанс у допущения постоянен (assumptions.md п.22-23) и попадает в снимок
-- расчёта; здесь хранится только текущее значение пользователя.
-- Дефолты НЕ дублируются (data_model.md п.12): отсутствие строки = дефолт.

CREATE TABLE IF NOT EXISTS project_assumption
(
    id
    bigserial
    PRIMARY
    KEY,
    project_id
    bigint
    NOT
    NULL
    REFERENCES
    project
(
    id
) ON DELETE CASCADE,
    name text NOT NULL,
    value text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now
(
),
    UNIQUE
(
    project_id,
    name
)
    );

CREATE INDEX IF NOT EXISTS idx_project_assumption_project
    ON project_assumption(project_id);
