-- V7: история импортов каталога организатора через админку (срез «Админка»).
--
-- ТЗ 3.3.5 (ручное управление каталогом) + ТЗ 3.3.6 (обновление каталога
-- по запросу администратора): каждая загрузка файла логируется одной
-- строкой. status='running' ставится на старте, 'completed'/'failed' —
-- по итогу; единственная строка 'running' одновременно = признак
-- in-flight импорта (повторный запуск → 409).
--
-- summary_json: счётчики импорта по сущностям
-- {solutions: {added, updated}, applications: {...}, ...} — формат
-- CatalogImportSummary; NULL у failed-строк.

CREATE TABLE IF NOT EXISTS admin_import_log
(
    id
    bigserial
    PRIMARY
    KEY,
    file_name
    text
    NOT
    NULL,
    file_path
    text
    NOT
    NULL,
    size_bytes
    bigint
    NOT
    NULL,
    started_at
    timestamptz
    NOT
    NULL
    DEFAULT
    now
(
),
    finished_at timestamptz,
    status text NOT NULL DEFAULT 'running'
    CHECK
(
    status
    IN
(
    'running',
    'completed',
    'failed'
)),
    created_by_user_id bigint NOT NULL REFERENCES "user"
(
    id
),
    summary_json jsonb
    );

CREATE INDEX IF NOT EXISTS idx_admin_import_started
    ON admin_import_log(started_at DESC);
