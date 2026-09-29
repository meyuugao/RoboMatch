"""Каталог: solution, solution_application, solution_case, solution_case_link.

Идемпотентность: UPSERT solution по external_id; применений — по уникальному
(solution_id, industry_id, process_id); кейсов — по name; связей — по PK.
"""
import datetime
import uuid as uuid_mod
from typing import TypeAlias

from sqlalchemy import Table, select
from sqlalchemy.engine import Connection, Engine

import config
from mappers.catalog_mapper import CatalogData
from normalizers.strings import canonical_key
from .common import load_id_map, upsert

LogFn: TypeAlias = object

# 9 колонок ТТХ при импорте NULL (data_model.md §5.8) — проекция EAV, заполняется
# позже при дозаполнении из открытых источников; решение_characteristic не сеется.
TTH_COLUMNS = (
    "payload_kg", "mass_kg", "length_mm", "width_mm", "height_mm",
    "positioning_accuracy_mm", "speed_m_s", "charging_power_kw", "noise_level_dba",
)


def seed_solutions(conn: Connection, engine: Engine, tables: dict[str, Table],
                   catalog: CatalogData, maps: dict, log) -> dict[str, int]:
    today = datetime.date.today()  # «дата импорта» — data_model.md §5.7
    rows = []
    for s in catalog.solutions:
        ext_uuid = _to_uuid_str(s.external_id)
        rows.append({
            "external_id": ext_uuid,
            "name": s.name,
            "vendor_id": maps["vendor"][canonical_key(s.vendor)],
            "product_class": s.product_class,
            "solution_type_id": maps["solution_type"].get(canonical_key(s.solution_type)),
            "solution_subtype_id": maps["solution_subtype"].get(canonical_key(s.solution_subtype)),
            "region_id": maps["region"].get(canonical_key(s.region)),
            "status": s.status,
            "description": s.description,
            "price_rub": s.price_rub,
            "trl": s.trl,
            "market_potential": s.market_potential,
            **{c: None for c in TTH_COLUMNS},
            "source_kind": config.SOURCE_KIND,
            "source_url": config.CATALOG_SOURCE_URL,
            "source_date": today,
        })
    update_cols = [c for c in rows[0] if c not in ("external_id",)]
    upsert(conn, engine, tables["solution"], rows, ["external_id"], update_cols, log, "solution")

    # карта external_id -> id (строковое представление для сопоставления с CSV)
    ext_map: dict[str, int] = {}
    q = select(tables["solution"].c["external_id"], tables["solution"].c["id"])
    for ext, pk in conn.execute(q).all():
        ext_map[str(ext)] = pk
    return ext_map


def seed_applications(conn, engine, tables, catalog: CatalogData, maps: dict,
                      solution_ids: dict[str, int], log):
    rows = []
    for a in catalog.applications:
        rows.append({
            "solution_id": solution_ids[a.external_id],
            "industry_id": maps["industry"][canonical_key(a.industry)],
            "process_id": maps["process"][canonical_key(a.process)],
            "offer_price_rub": a.offer_price_rub,
        })
    upsert(conn, engine, tables["solution_application"], rows,
           ["solution_id", "industry_id", "process_id"], ["offer_price_rub"],
           log, "solution_application")


def seed_cases(conn, engine, tables, catalog: CatalogData, solution_ids: dict[str, int], log):
    today = datetime.date.today()
    rows = [{
        "name": name,
        "description": None,
        "source_url": config.CATALOG_SOURCE_URL,  # кейсы взяты из того же файла организатора
        "source_date": today,
    } for name in catalog.cases]
    upsert(conn, engine, tables["solution_case"], rows, ["name"],
           ["source_url", "source_date"], log, "solution_case")

    case_ids = load_id_map(conn, tables["solution_case"])
    links = [{
        "solution_id": solution_ids[ext],
        "case_id": case_ids[canonical_key(name)],
    } for ext, name in catalog.case_links]
    upsert(conn, engine, tables["solution_case_link"], links,
           ["solution_id", "case_id"], None, log, "solution_case_link")


def _to_uuid_str(value: str) -> str:
    """Валидация и канонизация external_id (строковая форма — PG приводит к uuid,
    SQLite хранит как текст в тестах)."""
    try:
        return str(uuid_mod.UUID(value))
    except ValueError as e:
        raise ValueError(f"external_id не является UUID: {value!r}") from e
