"""characteristic_type — метаданные характеристик решений (EAV).

Идемпотентность: UPSERT по code (UNIQUE, data_model.md §2.3); на повторном
запуске строки обновляются, счётчик вставок = 0.
"""
from typing import TypeAlias

from sqlalchemy import Table
from sqlalchemy.engine import Connection, Engine

from mappers.characteristics_mapper import CHARACTERISTIC_TYPES, validate
from .common import upsert

LogFn: TypeAlias = object


def seed_characteristic_types(conn: Connection, engine: Engine,
                              tables: dict[str, Table], log) -> None:
    """27 записей из (assumptions.md §14). Возвращает None: карта id
    не нужна, solution_characteristic при импорте каталога не сеется (§5.8).
    """
    validate()  # быстрый фейл до записи в БД

    rows = [{
        "code": r.code,
        "name": r.name,
        "group_code": r.group_code,
        "data_type": r.data_type,
        "unit": r.unit,
        "is_filterable": r.is_filterable,
        "is_required": r.is_required,
        "sort_order": r.sort_order,
    } for r in CHARACTERISTIC_TYPES]

    upsert(conn, engine, tables["characteristic_type"], rows, ["code"],
           ["name", "group_code", "data_type", "unit", "is_filterable",
            "is_required", "sort_order"],
           log, "characteristic_type")
