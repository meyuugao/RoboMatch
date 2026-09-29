"""Метаданные характеристик решений (characteristic_type) -, data_model.md §2.3.

Статический справочник из 27 записей (assumptions.md §14 «Заполнение
characteristic_type»): не выводится из файлов организатора, а задаётся по
таблице характеристик (дословные выжимки - docs/requirements/catalog.md,
раздел «Характеристики решений»).

Состав:
  - technical (13): 9 зеркальных ТТХ-колонок solution (data_model.md §5.8)
    + 4 EAV-характеристики без колонок (автономность, производительность,
    условия эксплуатации, срок службы);
  - identification (2), infrastructure (4), economic (4), applicability (1),
    data_quality (3).

Код - латиница snake_case; названия - русские; группы - значения CHECK
characteristic_type.group_code. sort_order - порядковый номер внутри группы.
Дата (source_date) - настоящий DATE: data_type='date', значение хранится
в solution_characteristic.value_date (assumptions.md §14, data_model.md §2.4).
"""
from dataclasses import dataclass

VALID_GROUPS = frozenset({"identification", "technical", "infrastructure",
                          "economic", "applicability", "data_quality"})
VALID_DATA_TYPES = frozenset({"number", "text", "boolean", "date"})


@dataclass(frozen=True)
class CharacteristicTypeRow:
    code: str
    name: str
    group_code: str
    data_type: str
    unit: str | None = None
    is_filterable: bool = False
    is_required: bool = False
    sort_order: int = 0


CHARACTERISTIC_TYPES: tuple[CharacteristicTypeRow, ...] = (
    # --- identification ------------------------------------------------------
    CharacteristicTypeRow("country_of_origin", "Страна происхождения",
                          "identification", "text", sort_order=1),
    CharacteristicTypeRow("navigation_type", "Тип навигации",
                          "identification", "text", sort_order=2),

    # --- technical: зеркальные ТТХ-колонки solution (§5.8, mapping 1:1) -----
    CharacteristicTypeRow("payload_kg", "Грузоподъёмность, кг",
                          "technical", "number", unit="кг",
                          is_filterable=True, is_required=True, sort_order=1),
    CharacteristicTypeRow("mass_kg", "Масса, кг",
                          "technical", "number", unit="кг",
                          is_filterable=True, is_required=True, sort_order=2),
    CharacteristicTypeRow("length_mm", "Длина, мм",
                          "technical", "number", unit="мм",
                          is_filterable=True, is_required=True, sort_order=3),
    CharacteristicTypeRow("width_mm", "Ширина, мм",
                          "technical", "number", unit="мм",
                          is_filterable=True, is_required=True, sort_order=4),
    CharacteristicTypeRow("height_mm", "Высота, мм",
                          "technical", "number", unit="мм",
                          is_filterable=True, is_required=True, sort_order=5),
    CharacteristicTypeRow("positioning_accuracy_mm", "Точность позиционирования, мм",
                          "technical", "number", unit="мм",
                          is_filterable=True, is_required=True, sort_order=6),
    CharacteristicTypeRow("speed_m_s", "Скорость, м/с",
                          "technical", "number", unit="м/с",
                          is_filterable=True, sort_order=7),
    CharacteristicTypeRow("charging_power_kw", "Мощность зарядки, кВт",
                          "technical", "number", unit="кВт",
                          is_filterable=True, is_required=True, sort_order=8),
    CharacteristicTypeRow("noise_level_dba", "Уровень шума, дБА",
                          "technical", "number", unit="дБА",
                          is_filterable=True, is_required=True, sort_order=9),

    # --- technical: EAV без колонок в solution --------------------------------
    CharacteristicTypeRow("autonomy_h", "Автономность, ч",
                          "technical", "number", unit="ч", sort_order=10),
    CharacteristicTypeRow("productivity", "Производительность",
                          "technical", "text", sort_order=11),
    CharacteristicTypeRow("operating_conditions", "Допустимые условия эксплуатации",
                          "technical", "text", sort_order=12),
    CharacteristicTypeRow("lifecycle_years", "Срок службы, лет",
                          "technical", "number", unit="лет", sort_order=13),

    # --- infrastructure -------------------------------------------------------
    CharacteristicTypeRow("floor_coverage_req", "Требования к покрытию",
                          "infrastructure", "text", sort_order=1),
    CharacteristicTypeRow("communication_req", "Требования к связи",
                          "infrastructure", "text", sort_order=2),
    CharacteristicTypeRow("integration_req", "Требования к интеграции",
                          "infrastructure", "text", sort_order=3),
    CharacteristicTypeRow("service_req", "Требования к сервисному обслуживанию",
                          "infrastructure", "text", sort_order=4),

    # --- economic -------------------------------------------------------------
    CharacteristicTypeRow("software_cost", "Стоимость ПО, руб.",
                          "economic", "number", unit="руб.", sort_order=1),
    CharacteristicTypeRow("implementation_cost", "Стоимость внедрения, руб.",
                          "economic", "number", unit="руб.", sort_order=2),
    CharacteristicTypeRow("maintenance_cost", "Стоимость обслуживания, руб./год",
                          "economic", "number", unit="руб./год", sort_order=3),
    CharacteristicTypeRow("acquisition_model", "Модель приобретения",
                          "economic", "text", sort_order=4),

    # --- applicability --------------------------------------------------------
    CharacteristicTypeRow("limitations", "Ограничения",
                          "applicability", "text", sort_order=1),

    # --- data_quality ---------------------------------------------------------
    # Дата актуализации - DATE: хранится в solution_characteristic.value_date
    CharacteristicTypeRow("source", "Источник",
                          "data_quality", "text", sort_order=1),
    CharacteristicTypeRow("source_date", "Дата актуализации",
                          "data_quality", "date", sort_order=2),
    CharacteristicTypeRow("confirmation_status", "Признак подтверждённости",
                          "data_quality", "text", sort_order=3),
)

# 9 кодов, зеркальных ТТХ-колонкам solution (data_model.md §2.1/§5.8).
# Единицы - assumptions.md §3 (едины с параметрами объектов).
MIRROR_SOLUTION_COLUMNS: tuple[str, ...] = (
    "payload_kg", "mass_kg", "length_mm", "width_mm", "height_mm",
    "positioning_accuracy_mm", "speed_m_s", "charging_power_kw", "noise_level_dba",
)


def validate() -> None:
    """Самопроверка справочника (вызывается тестами и seed)."""
    codes = [r.code for r in CHARACTERISTIC_TYPES]
    assert len(codes) == len(set(codes)) == 27, f"ожидалось 27 уникальных кодов, получено {len(codes)}"
    for r in CHARACTERISTIC_TYPES:
        assert r.group_code in VALID_GROUPS, r
        assert r.data_type in VALID_DATA_TYPES, r
        assert r.sort_order >= 1, r
        if r.data_type in ("text", "date"):
            assert r.unit is None, f"у нечисловой характеристики «{r.name}» не может быть единицы"
    # sort_order непрерывен внутри группы
    by_group: dict[str, list[int]] = {}
    for r in CHARACTERISTIC_TYPES:
        by_group.setdefault(r.group_code, []).append(r.sort_order)
    for group, orders in by_group.items():
        assert sorted(orders) == list(range(1, len(orders) + 1)), f"sort_order в группе {group}"
    # 9 зеркальных кодов совпадают с ТТХ-колонками solution
    mirror = {r.code for r in CHARACTERISTIC_TYPES if r.is_filterable}
    assert mirror == set(MIRROR_SOLUTION_COLUMNS)
