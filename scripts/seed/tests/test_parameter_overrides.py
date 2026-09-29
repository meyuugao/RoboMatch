"""Тесты корректировок параметров поверх XLSX:
data/parameter_overrides.json, читаемый mappers/objects_mapper.py.

Проверяются: флаги is_fixed/is_derived, worst-case переименования и дефолты,
подсказки в source_note, новые параметры, идемпотентность overrides при
повторном map_objects, валидация опечаток в кодах.
"""
from decimal import Decimal

import pytest

from mappers.objects_mapper import _apply_overrides, _load_overrides, map_objects


def _by_code(objects_data):
    code_to_pt = {pt.code: pt for pt in objects_data.param_types}
    rows = {}
    for r in objects_data.object_type_params:
        pt = next(p for p in objects_data.param_types if p.name == r.param_name)
        rows.setdefault(pt.code, []).append(r)
    return code_to_pt, rows


def test_overrides_file_loads():
    """Файл обязателен и парсится (тихое отсутствие оставило бы БД без
    worst-case параметров)."""
    overrides = _load_overrides(log=lambda *_: None)
    for section in ("flags", "renames", "defaults", "worst_case_hints",
                    "new_parameters"):
        assert section in overrides, section


def test_fixed_constants(objects_data):
    """Коэффициент начислений (все 3 типа), рабочих дней 365 и WMS -
    фиксированные константы, не редактируются пользователем."""
    _, rows = _by_code(objects_data)
    fixed_codes = {code for code, rr in rows.items()
                   if any(r.is_fixed for r in rr)}
    assert fixed_codes == {
        "payroll_insurance_contributions_rate",  # склад
        "payroll_tax_rate",                      # аэропорт + медучреждение
        "working_days_per_year",
        "has_wms",
    }
    # количество определений: ФОТ склад 1 + аэропорт 1 + медицина 1,
    # рабочих дней 1, WMS 1 - итого 5
    assert sum(1 for r in objects_data.object_type_params if r.is_fixed) == 5


def test_derived_parameters(objects_data):
    """Объём отбора (штук/сутки) и площадь активной зоны - производные."""
    _, rows = _by_code(objects_data)
    derived = {code for code, rr in rows.items() if any(r.is_derived for r in rr)}
    assert derived == {"picking_units_per_day", "active_zone_area"}
    for r in rows["picking_units_per_day"] + rows["active_zone_area"]:
        assert not r.is_fixed  # производный не может быть фиксированным


def test_worst_case_renames_and_defaults(objects_data):
    """Переименование в worst-case формулировку + дефолт на границу диапазона
    (max - масса, min - габариты/проходы/высота/мощность)."""
    code_to_pt, rows = _by_code(objects_data)
    expected = {
        "pallet_unit_weight": ("Максимальная масса грузовой единицы (паллет), кг",
                               Decimal("1500"), Decimal("1500")),   # max
        "sku_unit_weight": ("Максимальная масса штучной единицы (SKU), кг",
                            Decimal("20"), Decimal("20")),           # max
        "storage_zone_ceiling_height": ("Минимальная высота потолков в зоне хранения, м",
                                        Decimal("5"), Decimal("5")),  # min
        "main_aisle_width": ("Минимальная ширина главных проездов, м",
                             Decimal("2.5"), Decimal("2.5")),        # min
        "rack_aisle_width": ("Минимальная ширина рабочих проходов между стеллажами, м",
                             Decimal("1.5"), Decimal("1.5")),        # min
        "available_power_capacity": ("Минимальная доступная мощность для зарядки роботов, кВт",
                                     Decimal("100"), Decimal("100")),  # min
    }
    for code, (name, default, extreme) in expected.items():
        assert code_to_pt[code].name == name, code
        for r in rows[code]:
            assert r.default_value_numeric == default, code
            # worst-case дефолт совпадает с границей диапазона (max или min)
            assert r.default_value_numeric in (r.min_value, r.max_value), code
            assert r.default_value_numeric == extreme


def test_worst_case_hints_in_source_note(objects_data):
    """Подсказка дописана к source_note XLSX (исходный текст сохранён)."""
    _, rows = _by_code(objects_data)
    r = rows["pallet_unit_weight"][0]
    assert "Влияет на грузоподъёмность роботов" in r.source_note  # из XLSX
    assert "worst-case" in r.source_note                           # из overrides
    r2 = rows["rack_aisle_width"][0]
    assert "Узкий проход" in r2.source_note
    assert "worst-case" in r2.source_note


def test_new_parameters_seeded(objects_data):
    """floor_load_max_kg (склад), шум и точность (все 3 типа) - с диапазонами
    и дефолтами worst-case (уточнения организатора-5)."""
    code_to_pt, rows = _by_code(objects_data)
    assert code_to_pt["floor_load_max_kg"].name == \
        "Максимальная нагрузка на пол (на точку опоры), кг"
    assert code_to_pt["floor_load_max_kg"].unit == "кг"
    assert [r.object_type for r in rows["floor_load_max_kg"]] == ["Склад"]
    floor = rows["floor_load_max_kg"][0]
    assert floor.default_value_numeric == Decimal("5000")
    assert (floor.min_value, floor.max_value) == (Decimal("500"), Decimal("5000"))

    for code, default, lo, hi in (
        ("max_allowed_noise_dba", Decimal("25"), Decimal("25"), Decimal("80")),
        ("required_positioning_accuracy_mm", Decimal("1"), Decimal("1"), Decimal("50")),
    ):
        assert sorted(r.object_type for r in rows[code]) == \
            ["Аэропорт", "Медучреждение", "Склад"], code
        for r in rows[code]:
            assert r.default_value_numeric == default, code
            assert (r.min_value, r.max_value) == (lo, hi), code
            assert not r.is_required  # дефолт есть - обязательность не нужна
            assert "worst-case" in (r.source_note or "").lower(), code


def test_overrides_idempotent_on_remap(remap_result):
    """Повторный map_objects даёт тот же результат (overrides детерминированы)."""
    sheets, legend, first = remap_result
    second = map_objects(sheets, legend, log=lambda *_: None)
    assert second.stats == first.stats
    assert [(r.param_name, r.is_fixed, r.is_derived, str(r.default_value_numeric))
            for r in second.object_type_params] == \
        [(r.param_name, r.is_fixed, r.is_derived, str(r.default_value_numeric))
         for r in first.object_type_params]


def test_unknown_code_fails_fast(objects_data):
    """Опечатка в коде overrides - быстрый ValueError, а не молчаливый пропуск.

    Безопасность для session-фикстуры: секция defaults валидируется по
    code_to_pt ДО любых изменений строк (flags/renames не заданы), поэтому
    повторный вызов _apply_overrides на уже-обработанных данных падает,
    не мутируя их.
    """
    from normalizers.strings import canonical_key
    param_types = {canonical_key(pt.name): pt for pt in objects_data.param_types}
    overrides = {"defaults": {"nonexistent_code": 1}}
    with pytest.raises(ValueError, match="nonexistent_code"):
        _apply_overrides(objects_data, param_types, {}, overrides,
                         log=lambda *_: None)


def _raw_sheets():
    from pathlib import Path
    import os
    from loaders.xlsx_loader import load_objects
    xlsx = os.environ.get("OBJECTS_XLSX")
    if not xlsx:
        xlsx = str(Path(__file__).resolve().parents[3] / "docs/source/Датасеты_хакатон.xlsx")
    return load_objects(xlsx)


@pytest.fixture(scope="module")
def remap_result():
    """(sheets, legend, data) для проверки идемпотентности overrides."""
    sheets, legend = _raw_sheets()
    data = map_objects(sheets, legend, log=lambda *_: None)
    return sheets, legend, data
def _raw_sheets():
    from loaders.xlsx_loader import load_objects
    from pathlib import Path
    import os
    xlsx = os.environ.get("OBJECTS_XLSX")
    if not xlsx:
        candidate = Path(__file__).resolve().parents[3] / "docs/source/Датасеты_хакатон.xlsx"
        xlsx = str(candidate)
    return load_objects(xlsx)


@pytest.fixture(scope="module")
def objects_data_factory():
    """(sheets, legend) + результат map_objects для проверки идемпотентности."""
    sheets, legend = _raw_sheets()
    data = map_objects(sheets, legend, log=lambda *_: None)
    return (sheets, legend), data
