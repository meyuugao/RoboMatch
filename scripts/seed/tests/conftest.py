"""Тесты seed-скрипта.

Юнит-тесты (нормализация, дедупликация, разрезание сценариев, параметры) работают
без БД на реальных файлах организатора. Интеграционный тест идемпотентности
по умолчанию использует SQLite (файл во временном каталоге); если задана
TEST_DATABASE_URL — гоняется на ней (например, на PostgreSQL 16).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # scripts/seed

import os  # noqa: E402

import pytest  # noqa: E402
from sqlalchemy import create_engine  # noqa: E402

from tests.db_schema import create_all, drop_all  # noqa: E402


@pytest.fixture
def engine(tmp_path):
    """Свежая БД со схемой data_model.md на каждый тест.

    На SQLite включается принуждение внешних ключей (PRAGMA foreign_keys=ON),
    чтобы тесты ловили нарушения FK и каскадные удаления как в PostgreSQL.
    """
    url = os.environ.get("TEST_DATABASE_URL")
    if url:
        eng = create_engine(url)
    else:
        eng = create_engine(f"sqlite:///{tmp_path / 'seed_test.db'}")
    if eng.dialect.name == "sqlite":
        from sqlalchemy import event

        @event.listens_for(eng, "connect")
        def _sqlite_fk_on(dbapi_conn, _record):
            dbapi_conn.execute("PRAGMA foreign_keys=ON")
    create_all(eng)
    yield eng
    drop_all(eng)
    eng.dispose()


@pytest.fixture
def log_lines() -> list[str]:
    """Коллектор лог-сообщений мапперов (проверка «конфликты не молчат»)."""
    return []


@pytest.fixture(scope="session")
def catalog_data():
    """Нормализованный каталог из реального CSV (один раз на сессию)."""
    from loaders.csv_loader import load_catalog_rows
    from mappers.catalog_mapper import map_catalog

    rows = load_catalog_rows()
    return map_catalog(rows)


@pytest.fixture(scope="session")
def objects_data():
    """Нормализованные параметры объектов из реального XLSX."""
    from loaders.xlsx_loader import load_objects
    from mappers.objects_mapper import map_objects

    sheets, legend = load_objects()
    return map_objects(sheets, legend)
