"""Тесты: параметры объектов (реальный XLSX) и EAV-инвариант «ровно одно значение»."""
from normalizers.strings import canonical_key


def test_row_counts_per_sheet(objects_data):
    """45 + 41 + 59 = 145 строк object_type_parameter: 42+39+57 из XLSX
    (data_model.md §3.2) + 7 worst-case из parameter_overrides.json
    (floor_load_max_kg - склад; шум и точность - все 3 типа)."""
    st = objects_data.stats
    assert st["params_Склад"] == 45
    assert st["params_Аэропорт"] == 41
    assert st["params_Медучреждение"] == 59
    assert st["object_type_params"] == 145
    assert st["param_types"] == 135  # 132 из XLSX (138 - 6 общих имён) + 3 новых
    assert st["object_types"] == 3


def test_required_counts(objects_data):
    """Обязательные параметры: склад 31, аэропорт 26, медицина 30 (assumptions §2)."""
    st = objects_data.stats
    assert st["required_Склад"] == 31
    assert st["required_Аэропорт"] == 26
    assert st["required_Медучреждение"] == 30


def test_eav_exactly_one_value_not_null(objects_data):
    """CHECK data_model.md: ровно одно из default_value_* NOT NULL."""
    for r in objects_data.object_type_params:
        filled = [v for v in (r.default_value_numeric, r.default_value_text, r.default_value_bool)
                  if v is not None]
        assert len(filled) == 1, f"у «{r.param_name}» ({r.object_type}) заполнено {len(filled)} значений"


def test_value_type_inference(objects_data):
    by_name = {canonical_key(r.param_name): r for r in objects_data.object_type_params}
    assert by_name[canonical_key("Наличие WMS")].default_value_bool is True
    assert by_name[canonical_key("Наличие пандусов/подъёмников (для межэтажного AMR без лифта)")].default_value_bool is False
    # «1С:ERP» - не булево значение -> text
    erp = by_name[canonical_key("Наличие ERP/1С")]
    assert erp.default_value_text == "1С:ERP"
    # числовой диапазон -> number
    assert by_name[canonical_key("Зонирование (количество режимных зон)")].default_value_numeric is not None
    # габариты «1200×800×1600» -> text
    assert by_name[canonical_key("Средние габариты паллеты (Д×Ш×В)")].default_value_text == "1200×800×1600"


def test_boolean_range_da_da_is_null(objects_data):
    """min/max = «Да» не числовые -> NULL (колонки numeric), конфликт залогирован."""
    by_name = {r.param_name: r for r in objects_data.object_type_params}
    for name in ("Наличие WMS", "Наличие FIDS/AODB системы", "Наличие BMS (системы управления зданием)"):
        assert by_name[name].min_value is None, name
        assert by_name[name].max_value is None, name


def test_min_not_greater_than_max(objects_data):
    for r in objects_data.object_type_params:
        if r.min_value is not None and r.max_value is not None:
            assert r.min_value <= r.max_value, r.param_name


def test_shared_params_deduplicated(objects_data):
    """CAPEX/Горизонт/Коэффициент/Мощность зарядки - один parameter_type на все листы."""
    names = [canonical_key(pt.name) for pt in objects_data.param_types]
    assert len(names) == len(set(names))
    for shared in ("Планируемый бюджет на роботизацию (CAPEX)", "Горизонт расчёта окупаемости",
                   "Коэффициент начислений на ФОТ", "Доступная мощность для зарядной инфраструктуры"):
        occurrences = [r for r in objects_data.object_type_params
                       if canonical_key(r.param_name) == canonical_key(shared)]
        assert len(occurrences) == len({o.object_type for o in occurrences}), \
            f"«{shared}» должен быть одним parameter_type на несколько object_type"


def test_codes_unique_english(objects_data):
    """Код - ключ overrides и колонок шаблона импорта: уникален и без пробелов."""
    codes = [pt.code for pt in objects_data.param_types]
    assert None not in codes
    assert len(codes) == len(set(codes))
    for pt in objects_data.param_types:
        assert pt.code == pt.code.lower() and " " not in pt.code, pt.name


def test_object_types_have_source_notes(objects_data):
    """«Источники данных» легенды -> object_type.data_source_note (§5.10)."""
    for ot in objects_data.object_types:
        assert ot["data_source_note"], ot["name"]
    notes = {ot["name"]: ot["data_source_note"] for ot in objects_data.object_types}
    assert "LogLink" in notes["Склад"]
    assert "Росавиация" in notes["Аэропорт"]
    assert "Минздрав" in notes["Медучреждение"]
