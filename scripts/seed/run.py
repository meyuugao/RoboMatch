#!/usr/bin/env python3
"""Идемпотентный seed каталога решений и параметров объектов.

Читает:
  - data/organizer/catalog_export_v4.csv  (фактически docs/source/…, см. config.py)
  - data/organizer/Датасеты_хакатон.xlsx  (аналогично)

Пишет в PostgreSQL по схеме docs/data_model.md (разделы 2, 3, 4).
Схема НЕ создаётся и НЕ меняется: ожидается, что миграции Spring Boot уже применены.

Свойства:
  - одна транзакция на весь импорт (либо всё, либо откат);
  - идемпотентность через INSERT .. ON CONFLICT по естественным ключам;
  - каждый шаг логируется: прочитано / вставлено / обновлено / конфликты.

Запуск:
  DATABASE_URL='postgresql+psycopg2://user:pass@host:5432/db' python scripts/seed/run.py
"""
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # запуск из любой каталога

from sqlalchemy import MetaData, create_engine, func, inspect, select  # noqa: E402

import config  # noqa: E402
from loaders.csv_loader import load_catalog_rows  # noqa: E402
from loaders.xlsx_loader import load_objects  # noqa: E402
from mappers.catalog_mapper import map_catalog  # noqa: E402
from mappers.characteristics_loader import load_characteristics  # noqa: E402
from mappers.objects_mapper import map_objects  # noqa: E402
from repositories import catalog_repo, characteristics_repo, dicts_repo, params_repo, solution_characteristic_repo, test_projects_repo, users_repo  # noqa: E402

LOG = logging.getLogger("seed")

EXPECTED_TABLES = [
    "industry", "vendor", "region", "solution_type", "solution_subtype", "process",
    "object_type", "parameter_type", "characteristic_type", "solution", "solution_application",
    "solution_case", "solution_case_link", "solution_type_subtype_mapping",
    "object_type_industry", "object_type_parameter", "user",
]

# Сверка с критериями приёмки; расхождение — не ошибка, а громкий WARNING
# (данные организатора могут обновиться; решение принимает человек).
EXPECTED_COUNTS = {
    "solution": 187,
    "industry": 9,
    "solution_type": 11,
    "solution_subtype": 71,          # после объединения 4 пар (assumptions.md §13)
    "region": 24,
    "vendor": (103, 105),   # 103 из каталога; +2 вендора тестовых фикстур
    "process": 95,
    "object_type": 3,
    "object_type_industry": 7,
    "object_type_parameter": 145,         # 138 из XLSX + 7 worst-case
                                          # (floor_load_max_kg — склад;
                                          # max_allowed_noise_dba,
                                          # required_positioning_accuracy_mm —
                                          # все 3 типа; parameter_overrides.json)
    "parameter_type": 135,                # 132 из XLSX + 3 новых (overrides)
    "characteristic_type": 27,       # (assumptions.md §14)
    "solution_application": 250,     # 256 без карты исключений — см. assumptions.md §12
    "solution_case": 175,
    "solution_case_link": 195,
    # Демо-аккаунты сдачи: admin/admin + user/user (acceptance.md).
    # test_economics не создаётся — 5 проектов «E2E: *»
    # принадлежат user; легаси-аккаунт (БД старой версии seed) удаляется
    # вместе с его данными; сами проекты НЕ входят в EXPECTED_COUNTS.
    "user": 2,
    # solution_characteristic не включён в EXPECTED_COUNTS: после дозаполнения
    # из открытых источников ожидается > 0, но точное число зависит
    # от характеристик, найденных в источниках. Проверка — в _verify отдельным
    # предупреждением, а не жёстким сверением со счётчиком.
}


def run_seed(engine, csv_path=None, xlsx_path=None) -> dict:
    """Полный цикл импорта в одной транзакции. Возвращает счётчики таблиц."""
    # --- 0. Схема: отражаем существующие таблицы, ничего не создаём ----------
    meta = MetaData()
    meta.reflect(bind=engine)
    tables = {t.name: t for t in meta.sorted_tables}
    missing = [t for t in EXPECTED_TABLES if t not in tables]
    if missing:
        raise SystemExit(f"В БД нет таблиц (схема не применена?): {missing}")
    # Конвенция английских кодов (assumptions.md §20): region / solution_type /
    # solution_subtype получили колонку code в новой версии схемы — на легаси-БД
    # её добавляет migrations.sql; без неё seed упадёт на вставке кода.
    no_code = [t for t in ("region", "solution_type", "solution_subtype")
               if "code" not in tables[t].c]
    if no_code:
        raise SystemExit(
            f"В таблицах {no_code} нет колонки code (конвенция английских кодов, "
            "assumptions.md §20). Примените scripts/seed/migrations.sql и повторите seed.")

    # --- 1. Чтение и нормализация источников (до транзакции — быстрый фейл) --
    raw_csv = load_catalog_rows(csv_path, log=LOG.info)
    catalog = map_catalog(raw_csv, log=LOG.warning)

    sheets, legend = load_objects(xlsx_path)
    LOG.info("XLSX прочитан: листы %s", {k: len(v) for k, v in sheets.items()})
    objects = map_objects(sheets, legend, log=LOG.warning)

    # --- 2. Импорт: одна транзакция, порядок по FK ---------------------------
    with engine.begin() as conn:
        maps: dict = {}
        # миграции транслит-кодов старых версий seed — ДО upsert
        # справочников, ключ которых code; на свежей БД — no-op:
        # * object_type: warehouse/airport/hospital (assumptions.md §15)
        # * industry/process/parameter_type: английские коды
        # (code_map.py, assumptions.md §20), переименование по имени
        dicts_repo.migrate_object_type_codes(conn, tables, LOG.info)
        dicts_repo.migrate_dict_codes(conn, tables, LOG.info)
        maps["industry"] = dicts_repo.seed_industries(conn, engine, tables, catalog.industries, LOG.info)
        maps["vendor"] = dicts_repo.seed_vendors(conn, engine, tables, catalog.vendors, LOG.info)
        maps["region"] = dicts_repo.seed_regions(conn, engine, tables, catalog.regions, LOG.info)
        maps["solution_type"] = dicts_repo.seed_solution_types(conn, engine, tables, catalog.solution_types, LOG.info)
        maps["solution_subtype"] = dicts_repo.seed_solution_subtypes(conn, engine, tables, catalog.solution_subtypes, LOG.info)
        maps["process"] = dicts_repo.seed_processes(conn, engine, tables, catalog.processes, LOG.info)
        maps["object_type"] = dicts_repo.seed_object_types(conn, engine, tables, objects.object_types, LOG.info)
        dicts_repo.enable_calc_for_warehouse(conn, tables, LOG.info)  # склад: is_calc_enabled=true
        maps["parameter_type"] = params_repo.seed_parameter_types(conn, engine, tables, objects, LOG.info)

        dicts_repo.seed_type_subtype_mapping(conn, engine, tables, maps["solution_type"],
                                             maps["solution_subtype"], LOG.info)
        dicts_repo.seed_object_type_industry(conn, engine, tables, maps["object_type"],
                                             maps["industry"], LOG.info)
        # чистка дублей подтипов на БД, засеянной старой версией скрипта
        # (свежая БД получает только канонические формы из маппера — шаг no-op)
        dicts_repo.merge_solution_subtypes(conn, tables, LOG.info)
        characteristics_repo.seed_characteristic_types(conn, engine, tables, LOG.info)

        solution_ids = catalog_repo.seed_solutions(conn, engine, tables, catalog, maps, LOG.info)
        catalog_repo.seed_applications(conn, engine, tables, catalog, maps, solution_ids, LOG.info)
        catalog_repo.seed_cases(conn, engine, tables, catalog, solution_ids, LOG.info)
        params_repo.seed_object_type_parameters(conn, engine, tables, objects,
                                                maps["object_type"], maps["parameter_type"], LOG.info)

        # Дозаполнение ТТХ из открытых источников.
        # После seed_solutions (есть external_id→id) и до финальной сводки.
        # Источник: scripts/seed/data/characteristics.json (по умолчанию);
        # путь переопределяется env CHARACTERISTICS_JSON (config.py).
        # Если файл отсутствует — seed не падает: просто пропускает шаг,
        # solution_characteristic остаётся пустой (предупреждение в _verify).
        enriched: list = []
        if config.CHARACTERISTICS_JSON_PATH and config.CHARACTERISTICS_JSON_PATH.exists():
            enriched = load_characteristics(config.CHARACTERISTICS_JSON_PATH)
        if enriched:
            LOG.info("characteristics.json: %s решений, %s значений ТТХ",
                     len(enriched), sum(len(s.characteristics) for s in enriched))
            solution_characteristic_repo.seed_solution_characteristics(
                conn, engine, tables, enriched, solution_ids, LOG.info)
        else:
            LOG.info("characteristics.json: файл отсутствует или пуст — "
                     "дозаполнение ТТХ пропущено (solution_characteristic останется пустой)")

        # Демо-аккаунты (acceptance.md): admin/admin + user/user.
        # После справочников и каталога — в той же транзакции; ON CONFLICT DO
        # NOTHING: повторный запуск не сбрасывает пароли.
        users_repo.seed_demo_users(conn, engine, tables, LOG.info)
        # Легаси-аккаунт test_economics (владелец E2E-проектов в старых
        # версиях seed) — удалить со всеми его данными, ДО создания E2E-
        # проектов под user; на свежей БД — no-op.
        users_repo.drop_legacy_test_user(conn, tables, LOG.info)

        # E2E-проекты экономики: 5 проектов «E2E: *»
        # (параметры/допущения/сценарии/состав) под владельцем user —
        # входы для scripts/verify_economics.py (подбор и расчёт — живым
        # backend-ом). После справочников (нужны решения и параметры склада)
        # и ПОСЛЕ демо-аккаунтов (владелец). Идемпотентно: DO NOTHING везде.
        test_projects_repo.seed_test_projects(conn, engine, tables, LOG.info)

        counts = {name: conn.execute(select(func.count()).select_from(tbl)).scalar_one()
                  for name, tbl in tables.items()}
    return counts


def _verify(counts: dict):
    """Сверка фактических счётчиков с ожиданиями приёмки."""
    LOG.info("--- Сводка по таблицам ---")
    for name, cnt in sorted(counts.items()):
        marker = ""
        expected = EXPECTED_COUNTS.get(name)
        if isinstance(expected, tuple):
            mismatch = not (expected[0] <= cnt <= expected[1])
            shown = f"{expected[0]}-{expected[1]}"
        else:
            mismatch = expected is not None and cnt != expected
            shown = str(expected)
        if mismatch:
            marker = f"  <-- ОЖИДАЛОСЬ {shown}, РАСХОЖДЕНИЕ"
            LOG.warning("таблица %s: %s%s", name, cnt, marker)
        else:
            LOG.info("таблица %s: %s%s", name, cnt, marker)
    # solution_characteristic: после дозаполнения ТТХ из открытых источников
    # таблица должна быть непустой. Пустота — повод
    # проверить, что characteristics.json присутствует и валиден.
    if counts.get("solution_characteristic", 0) == 0:
        LOG.warning("solution_characteristic пуста — дозаполнение ТТХ не выполнено "
                    "(data_model.md §5.8;)")
    if counts.get("characteristic_type", 0) != 27:
        LOG.warning("characteristic_type: %s (ожидалось 27, assumptions.md §14)",
                    counts.get("characteristic_type"))


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(message)s",
        datefmt="%H:%M:%S",
    )
    # Диагностика окружения: seed-образ ставит зависимости при сборке
    # (pip install -r requirements.txt), и набор версий может отличаться
    # от локальной машины. При падении контейнера по `docker compose logs
    # seed` первая строка показывает, с чем seed реально работал.
    import platform

    import openpyxl
    import pandas
    import sqlalchemy

    LOG.info("окружение: python %s | pandas %s | SQLAlchemy %s | openpyxl %s",
             platform.python_version(), pandas.__version__,
             sqlalchemy.__version__, openpyxl.__version__)
    LOG.info("источники: CSV=%s | XLSX=%s | ТТХ: %s",
             config.CATALOG_CSV_PATH, config.OBJECTS_XLSX_PATH,
             config.CHARACTERISTICS_JSON_PATH)
    engine = create_engine(config.require_database_url())
    try:
        counts = run_seed(engine)
    finally:
        engine.dispose()
    _verify(counts)
    LOG.info("seed завершён")


if __name__ == "__main__":
    main()
