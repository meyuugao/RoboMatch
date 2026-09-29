"""Тесты: characteristic_type — 27 записей метаданных EAV (assumptions.md §14).

Юнит-часть работает со статическим справочником без БД; интеграционная —
проверяет наполнение после полного seed.
"""
import pytest
from sqlalchemy import select

from mappers.characteristics_mapper import (
    CHARACTERISTIC_TYPES,
    MIRROR_SOLUTION_COLUMNS,
    VALID_DATA_TYPES,
    VALID_GROUPS,
    validate,
)
from repositories.catalog_repo import TTH_COLUMNS


def test_reference_data_valid():
    """Самопроверка справочника: 27 кодов, группы/типы из CHECK, sort_order."""
    validate()  # не бросает исключений


def test_twenty_seven_records():
    assert len(CHARACTERISTIC_TYPES) == 27
    codes = [r.code for r in CHARACTERISTIC_TYPES]
    assert len(set(codes)) == 27


def test_group_composition():
    """technical 13 (9 зеркальных + 4 EAV), identification 2, infrastructure 4,
    economic 4, applicability 1, data_quality 3."""
    by_group: dict[str, int] = {}
    for r in CHARACTERISTIC_TYPES:
        by_group[r.group_code] = by_group.get(r.group_code, 0) + 1
    assert by_group == {
        "technical": 13,
        "identification": 2,
        "infrastructure": 4,
        "economic": 4,
        "applicability": 1,
        "data_quality": 3,
    }


def test_mirror_codes_match_solution_tth_columns():
    """9 кодов характеристик 1:1 совпадают с ТТХ-колонками solution (§5.8)."""
    mirror = [r.code for r in CHARACTERISTIC_TYPES if r.is_filterable]
    assert set(mirror) == set(MIRROR_SOLUTION_COLUMNS) == set(TTH_COLUMNS)
    assert len(mirror) == 9


def test_flags():
    """is_filterable только у 9 ТТХ; is_required — 8 ТТХ по Дополнениям 4
    (грузоподъёмность, масса, габариты, мощность зарядки, шум, точность)."""
    by_code = {r.code: r for r in CHARACTERISTIC_TYPES}
    assert by_code["payload_kg"].is_filterable and by_code["payload_kg"].is_required
    assert by_code["positioning_accuracy_mm"].is_filterable and by_code["positioning_accuracy_mm"].is_required
    required = {r.code for r in CHARACTERISTIC_TYPES if r.is_required}
    assert required == {
        "payload_kg", "mass_kg", "length_mm", "width_mm", "height_mm",
        "positioning_accuracy_mm", "charging_power_kw", "noise_level_dba",
    }
    non_filterable = {r.code for r in CHARACTERISTIC_TYPES if not r.is_filterable}
    assert "autonomy_h" in non_filterable and "limitations" in non_filterable


def test_units_and_data_types():
    by_code = {r.code: r for r in CHARACTERISTIC_TYPES}
    assert by_code["payload_kg"].unit == "кг"
    assert by_code["speed_m_s"].unit == "м/с"
    assert by_code["charging_power_kw"].unit == "кВт"
    assert by_code["noise_level_dba"].unit == "дБА"
    assert by_code["autonomy_h"].unit == "ч"
    assert by_code["lifecycle_years"].unit == "лет"
    assert by_code["maintenance_cost"].unit == "руб./год"
    assert by_code["software_cost"].data_type == "number"
    assert by_code["acquisition_model"].data_type == "text"
    # дата актуализации — настоящий DATE: значение в value_date (§14, §2.4)
    assert by_code["source_date"].data_type == "date"
    assert by_code["source_date"].unit is None
    for r in CHARACTERISTIC_TYPES:
        assert r.data_type in VALID_DATA_TYPES
        assert r.group_code in VALID_GROUPS


def test_sort_order_within_group():
    """sort_order — порядковый номер внутри группы, непрерывный с 1."""
    by_group: dict[str, list[int]] = {}
    for r in CHARACTERISTIC_TYPES:
        by_group.setdefault(r.group_code, []).append(r.sort_order)
    for group, orders in by_group.items():
        assert sorted(orders) == list(range(1, len(orders) + 1)), group


def test_seed_fills_27_rows(engine):
    """После seed в characteristic_type ровно 27 строк (критерий приёмки)."""
    from run import run_seed

    counts = run_seed(engine)
    assert counts["characteristic_type"] == 27

    from tests.db_schema import meta

    ct = meta.tables["characteristic_type"]
    with engine.connect() as conn:
        rows = conn.execute(select(ct.c["code"], ct.c["group_code"], ct.c["sort_order"])).all()
        assert len(rows) == 27
        # первая запись группы technical — грузоподъёмность (sort_order=1)
        by_code = {r[0]: r for r in rows}
        assert by_code["payload_kg"][1] == "technical" and by_code["payload_kg"][2] == 1
        assert by_code["country_of_origin"][1] == "identification" and by_code["country_of_origin"][2] == 1
        assert by_code["limitations"][1] == "applicability" and by_code["limitations"][2] == 1
        assert by_code["confirmation_status"][1] == "data_quality" and by_code["confirmation_status"][2] == 3
