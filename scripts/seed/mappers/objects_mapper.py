"""Нормализация Датасеты_хакатон.xlsx: parameter_type, object_type_parameter,
object_type, а также константы маппингов из assumptions.md (§1, §2, §4).

Кроме XLSX применяются корректировки из data/parameter_overrides.json:
флаги is_fixed/is_derived, worst-case переименования и дефолты,
подсказки, новые параметры.
XLSX организатора не меняется - все правки поверх него идут overrides-файлом.
"""
import json
import os
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path
from typing import TypeAlias

from loaders.xlsx_loader import OBJECT_SHEETS, ParamRow
from mappers.code_map import code_for
from normalizers.numbers import parse_decimal
from normalizers.strings import canonical_key, norm_text, slugify

LogFn: TypeAlias = object  # фактически Callable[[str], None]

# --- assumptions.md §15: конвенция кодов object_type - английский snake_case ----
# Раньше код генерировался транслитом имени листа (slugify: «sklad», «aeroport»,
# «meduchrezhdenie») - нарушало конвенцию английских кодов. Явный маппинг
# «русское имя листа -> код»; новые типы объектов добавлять сюда.
OBJECT_TYPE_CODE: dict[str, str] = {
    "Склад": "warehouse",
    "Аэропорт": "airport",
    "Медучреждение": "hospital",
}

# --- assumptions.md §16: типы решений без подтипов ----------------------------
# «Мобильные манипуляторы» есть в CSV (поле «Тип»), но не имеет группы в
# классификаторе assumptions.md §4 -> в solution_type_subtype_mapping пар нет.
TYPES_WITHOUT_SUBTYPES: tuple[str, ...] = ("Мобильные манипуляторы",)

# --- assumptions.md §1: маппинг «тип объекта -> отрасли каталога» (7 связей) --
OBJECT_TYPE_INDUSTRY: tuple[tuple[str, str], ...] = (
    ("Склад", "Торговля и услуги"),
    ("Склад", "Промышленность"),
    ("Склад", "Транспорт и логистика"),
    ("Аэропорт", "Транспорт и логистика"),
    ("Аэропорт", "Безопасность"),
    ("Медучреждение", "Безопасность"),
    ("Медучреждение", "Торговля и услуги"),
)

# --- assumptions.md §4: классификатор «solution_type -> solution_subtype» -----
# Точные пары из таблицы допущений; используется для solution_type_subtype_mapping.
SOLUTION_TYPE_SUBTYPE_MAP: dict[str, list[str]] = {
    "Мобильные роботы": [
        "AMR", "FMR", "Робот-штабелер", "Робот-тягач", "Робот-уборщик", "Робот-инвентаризатор",
        "Робот-ровер", "Робот-курьер", "Робот-паук", "Мобильный робот-комплектовщик",
        "Мобильный манипулятор", "Внутритрубные роботизированные системы", "Роботизированная тележка",
        "Робот-расцепщик", "Робот", "Мобильный робототехнический комплекс высокой проходимости",
        "Универсальная автономная роботизированная платформа", "Роботизированная теплоинспекция трубопроводов",
        "Роботы для диагностики теплосетей",
    ],
    "Роботы-манипуляторы": [
        "Робот-манипулятор", "Коллаборативный робот-манипулятор", "Сварочный робот", "Робот-укладчик",
    ],
    "Стационарные роботизированные системы": [
        "Умная система хранения", "Кран-штабелёр", "Шаттл", "Дельта-робот", "Роботизированная установка",
        "Роботизированная пристаночная ячейка", "Роботизированный 3D-принтер", "Робот-сортировщик",
        "Роботизированная доильная установка", "Конвейер автоматизированной вакцинации",
        "Роботизированный сварочный комплекс",
    ],
    "Антропоморфные роботы": ["Антропоморфный робот"],
    "Автономные наземные транспортные средства": [
        "FMR", "Беспилотный тягач", "Беспилотный трактор", "Беспилотный грузовик", "Беспилотный бульдозер",
        "Беспилотный каток", "Беспилотный асфальтоукладчик", "Беспилотный трамвай", "Беспилотное метро",
        "Беспилотное такси", "Агробот", "Интеллектуальный автобус", "Роботизированные погрузочные краны",
    ],
    "Морские роботы": [
        "ТНПА", "Беспилотный сервисный катамаран", "Многофункциональный бэзэкипажный катер",
        # «надводная» - каноническая форма (assumptions.md §10/§13: «наводная» - опечатка)
        "Модульная надводная многофункциональная платформа", "Беспилотный катер", "Безэкипажный катер",
        "Беспилотный катамаран",
    ],
    "БАС": ["БАС мультироторного типа", "Самолет", "VTOL"],
    "ПО": ["ПО для сортировки товаров"],
    "ПО БРС": ["Робот для анализа сельхозземель"],
    "Другое": ["Привязной робот на базе октокоптера", "Робот для сбора плодов"],
}

# --- assumptions.md §2: обязательные параметры (экономика + подбор одним -----
# списком - data_model.md §3.2). Имена соответствуют строкам XLSX дословно.
REQUIRED_PARAMS: dict[str, frozenset[str]] = {
    "Склад": frozenset({
        # экономика (26)
        "Общая площадь склада", "Площадь активной (роботизируемой) зоны",
        "Количество рабочих смен в сутки", "Рабочих дней в году", "Продолжительность смены",
        "Пиковый коэффициент нагрузки", "Объём приёмки (поддоны/сутки)", "Объём отгрузки (поддоны/сутки)",
        "Объём отбора (строк/сутки, всего)", "Объём отбора (штук/сутки, всего)",
        "Тип стеллажной системы", "Количество паллетомест", "Количество SKU (активных)",
        "Средняя масса грузовой единицы (паллет)", "Средние габариты паллеты (Д×Ш×В)",
        "Общая численность персонала склада", "Из них: отборщики (комплектовщики)",
        "Из них: операторы погрузчиков", "Средняя выработка отборщика (строк/ч)",
        "Средняя з/п отборщика (gross)", "Средняя з/п оператора погрузчика (gross)",
        "Коэффициент начислений на ФОТ (страховые взносы)", "Ширина главных проездов",
        "Ширина рабочих проходов между стеллажами", "Высота потолков в зоне хранения",
        "Ровность пола (отклонение)",
        # выбор решений (5)
        "Доля мелкоштучного отбора (piece-pick)", "Доля негабаритных/нестандартных грузов",
        "Тип напольного покрытия", "Наличие WMS", "Наличие ERP/1С",
    }),
    "Аэропорт": frozenset({
        # экономика (22)
        "Суммарная площадь терминала (ов)", "Количество терминалов",
        "Количество выходов на посадку (гейтов)", "Пассажиропоток (млн пассажиров/год)",
        "Среднесуточное количество пассажиров", "Пиковое количество пассажиров в час (PHF)",
        "Среднесуточное количество рейсов (взлёт+посадка)", "Пиковое количество рейсов в час",
        "Среднее время оборота воздушного судна (TAT)",
        "Среднее количество операций наземного обслуживания на 1 рейс",
        "Объём перемещения багажа (единиц/сутки)", "Средняя масса единицы багажа",
        "Численность персонала наземного обслуживания (рамп)",
        "Численность персонала внутри терминала (логистика, уборка)",
        "Средняя з/п сотрудника наземного обслуживания (gross)",
        "Средняя з/п уборщика терминала (gross)", "Коэффициент начислений на ФОТ",
        "Зонирование (количество режимных зон)", "Ограничения по уровню шума (зона)",
        "Температура в неотапливаемых зонах (перрон, зима)", "Площадь перрона и технических зон",
        "Требования по сертификации оборудования для airside",
        # выбор решений (4)
        "Доля трансферных пассажиров", "Годовая текучесть (персонал терминала)",
        "Наличие системы контроля доступа (СКУД)", "Наличие BMS (системы управления зданием)",
    }),
    "Медучреждение": frozenset({
        # экономика (25)
        "Тип медицинского учреждения", "Общая площадь здания(й)",
        "Количество этажей (основной корпус)", "Количество лифтов (грузовых/медицинских)",
        "Количество коек (стационар)", "Коечный фонд в эксплуатации (средняя занятость)",
        "Количество операционных", "Режим работы стационара", "Режим работы амбулатории",
        "Количество кормлений в сутки", "Общее количество порций питания в сутки",
        "Средняя масса тележки с питанием (брутто)", "Объём грязного белья (кг/сутки)",
        "Средняя масса контейнера с бельём", "Количество биоматериалов (проб) в сутки",
        "Объём доставки расходных материалов (рейсов/сутки)",
        "Объём медицинских отходов класса А (ненасыщенные)",
        "Объём медицинских отходов класса Б (инфицированные)",
        "Численность санитаров и транспортировщиков", "Численность сотрудников пищеблока (раздача)",
        "Численность сотрудников прачечной (транспорт белья)",
        "Средняя з/п санитара/транспортировщика (gross)", "Средняя з/п сотрудника пищеблока (gross)",
        "Коэффициент начислений на ФОТ", "Ширина коридоров (основных)",
        # выбор решений (5)
        "Обеззараживание робота между рейсами", "Требования к уровню шума в палатах (ночное время)",
        "Наличие СКУД (контроль доступа по зонам)", "Требования к материалу поверхностей робота",
        "Наличие системы управления лифтами (BMS)",
    }),
}


@dataclass
class ParamTypeRow:
    """Строка справочника parameter_type (дедуплицирована по имени)."""
    name: str
    unit: str | None
    value_type: str  # number | boolean | text
    # Код конвенции (assumptions.md §20): резолвится в map_objects через
    # code_map.py (фолбэк - транслит с предупреждением), чтобы overrides
    # и params_repo работали по единому ключу - коду, а не имени.
    code: str | None = None


@dataclass
class ObjectTypeParamRow:
    """Строка object_type_parameter (одно определение параметра для типа объекта)."""
    object_type: str
    param_name: str
    group_name: str
    is_required: bool
    default_value_numeric: object = None
    default_value_text: str | None = None
    default_value_bool: bool | None = None
    min_value: object = None
    max_value: object = None
    source_note: str | None = None
    # Worst-case параметры: фиксированные константы и производные
    # значения - не редактируются пользователем (миграция V4, overrides).
    is_fixed: bool = False
    is_derived: bool = False


@dataclass
class ObjectsData:
    object_types: list[dict] = field(default_factory=list)
    param_types: list[ParamTypeRow] = field(default_factory=list)
    object_type_params: list[ObjectTypeParamRow] = field(default_factory=list)
    stats: dict = field(default_factory=dict)


def map_objects(sheets: dict[str, list[ParamRow]], legend: dict[str, str], log=print) -> ObjectsData:
    data = ObjectsData()

    # --- Типы объектов: лист -> object_type, источник -> data_source_note -----
    for sheet in OBJECT_SHEETS:
        source_note = legend.get(sheet)
        if source_note is None:
            log(f"УТОЧНИТЬ: в «Легенде» нет источника данных для листа «{sheet}» -> data_source_note=NULL")
        data.object_types.append({
            "name": sheet,
            "is_calc_enabled": False,  # DEFAULT false (data_model.md §4); включение - вне seed
            "data_source_note": source_note,
        })

    # --- parameter_type: дедупликация имён между листами ---------------------
    param_types: dict[str, ParamTypeRow] = {}  # canonical_key -> row
    by_sheet: dict[str, list[ObjectTypeParamRow]] = {}

    for sheet in OBJECT_SHEETS:
        required = REQUIRED_PARAMS[sheet]
        sheet_rows: list[ObjectTypeParamRow] = []
        for p in sheets[sheet]:
            key = canonical_key(p.name)
            vt = _infer_value_type(p, log)
            if key in param_types:
                pt = param_types[key]
                if pt.unit != (None if p.unit == "-" else p.unit):
                    log(f"КОНФЛИКТ единиц у параметра «{p.name}»: «{pt.unit}» (первый лист) "
                        f"против «{p.unit}» ({sheet}) -> оставлены первые")
                if pt.value_type != vt:
                    log(f"КОНФЛИКТ value_type у параметра «{p.name}»: {pt.value_type} против "
                        f"{vt} ({sheet}) -> оставлен первый")
            else:
                param_types[key] = ParamTypeRow(
                    name=p.name,
                    unit=None if p.unit == "-" else p.unit,
                    value_type=vt,
                )
            sheet_rows.append(_to_object_type_param(sheet, p, vt, required, log))
        # сверка списка обязательных (assumptions.md §2)
        sheet_names = {canonical_key(r.name) for r in sheets[sheet]}
        not_found = [n for n in required if canonical_key(n) not in sheet_names]
        if not_found:
            raise ValueError(f"лист «{sheet}»: обязательные параметры из assumptions.md §2 "
                             f"не найдены в XLSX: {sorted(not_found)}")
        got_required = sum(1 for r in sheet_rows if r.is_required)
        log(f"лист «{sheet}»: параметров {len(sheet_rows)}, обязательных {got_required}")
        by_sheet[sheet] = sheet_rows
        data.object_type_params.extend(sheet_rows)

    data.param_types = list(param_types.values())

    # --- Корректировки поверх XLSX: parameter_overrides.json ------
    # Флаги is_fixed/is_derived, worst-case переименования и дефолты,
    # подсказки, новые параметры. Ключ - код parameter_type.
    overrides = _load_overrides(log)
    _apply_overrides(data, param_types, by_sheet, overrides, log)

    # --- EAV-инвариант: ровно одно default_value_* NOT NULL -------------------
    for r in data.object_type_params:
        filled = sum(v is not None for v in (r.default_value_numeric, r.default_value_text, r.default_value_bool))
        if filled != 1:
            raise ValueError(f"нарушен инвариант EAV (ровно одно default_value_*): {r}")

    for sheet in OBJECT_SHEETS:
        data.stats[f"params_{sheet}"] = len(by_sheet[sheet])
        data.stats[f"required_{sheet}"] = sum(1 for r in by_sheet[sheet] if r.is_required)
    data.stats["object_types"] = len(data.object_types)
    data.stats["param_types"] = len(data.param_types)
    data.stats["object_type_params"] = len(data.object_type_params)
    data.stats["fixed_params"] = sum(1 for r in data.object_type_params if r.is_fixed)
    data.stats["derived_params"] = sum(1 for r in data.object_type_params if r.is_derived)
    return data


# -------------------------------------------------------------------------
# parameter_overrides.json - корректировки поверх XLSX организатора
# -------------------------------------------------------------------------

_ALLOWED_VALUE_TYPES = ("number", "boolean", "text")


def _parameter_code(name: str, log) -> str:
    """Код параметра: code_map.py (конвенция), фолбэк - транслит с предупреждением."""
    code, _ = code_for("parameter_type", name)
    if code is None:
        code = slugify(name) or "x"
        log(f"УТОЧНИТЬ: для parameter_type «{name}» нет кода в конвенции "
            f"(code_map.py), использован транслит «{code}»")
    return code


def _load_overrides(log) -> dict:
    """Читает data/parameter_overrides.json (рядом с кодом seed - работает и в
    репозитории, и в docker-контейнере, как characteristics.json).

    Отсутствие файла - ошибка: флаги/переименования/новые параметры - часть
    контракта данных (миграция V4), тихий пропуск оставил бы БД без worst-case
    параметров и фиксированных констант. Переопределение пути - env
    PARAMETER_OVERRIDES_JSON (тесты изолированных сценариев).
    """
    override = os.environ.get("PARAMETER_OVERRIDES_JSON")
    # mappers/../data/ = scripts/seed/data/ (и в репозитории, и в контейнере /seed)
    seed_data_dir = Path(__file__).resolve().parents[1] / "data"
    path = Path(override) if override else seed_data_dir / "parameter_overrides.json"
    if not path.exists():
        raise FileNotFoundError(
            f"не найден {path}: файл обязателен (is_fixed/is_derived, worst-case "
            "переименования и дефолты, новые параметры - задача A1, "
            "docs/assumptions.md); переопределение - env PARAMETER_OVERRIDES_JSON"
        )
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh, parse_float=Decimal)
    log(f"parameter_overrides: загружен {path.name}")
    return data


def _apply_overrides(data: ObjectsData, param_types: dict, by_sheet: dict,
                     overrides: dict, log) -> None:
    """Применяет overrides к собранным строкам. Ключи всех секций - коды.

    Порядок: сначала флаги/дефолты/подсказки (по ссылкам на справочник),
    затем переименования (меняют имя справочника и param_name строк),
    в конце - новые параметры. Все ключи проверяются против фактических
    кодов - опечатка в коде падает быстрым ValueError, а не молчит.
    """
    flags = overrides.get("flags", {})
    renames = overrides.get("renames", {})
    defaults = overrides.get("defaults", {})
    hints = overrides.get("worst_case_hints", {})
    new_parameters = overrides.get("new_parameters", [])

    # --- карты: код -> справочник, код -> строки определений ----------------
    code_to_pt: dict[str, ParamTypeRow] = {}
    for pt in param_types.values():
        if pt.code is None:
            pt.code = _parameter_code(pt.name, log)
        if pt.code in code_to_pt:
            raise ValueError(f"коллизия кодов parameter_type: «{pt.code}» у «"
                             f"{code_to_pt[pt.code].name}» и «{pt.name}»")
        code_to_pt[pt.code] = pt
    code_to_rows: dict[str, list[ObjectTypeParamRow]] = {}
    for row in data.object_type_params:
        pt = param_types[canonical_key(row.param_name)]
        code_to_rows.setdefault(pt.code, []).append(row)

    def _pt(code: str, section: str) -> ParamTypeRow:
        if code not in code_to_pt:
            raise ValueError(f"parameter_overrides[{section}]: код «{code}» не "
                             "найден среди параметров XLSX (опечатка? фактические "
                             "коды - в mappers/code_map.py)")
        return code_to_pt[code]

    def _rows(code: str, section: str) -> list[ObjectTypeParamRow]:
        _pt(code, section)
        return code_to_rows.get(code, [])

    # --- 1. Флаги is_fixed / is_derived -------------------------------------
    for code, flag_spec in flags.items():
        rows = _rows(code, "flags")
        is_fixed = bool(flag_spec.get("is_fixed", False))
        is_derived = bool(flag_spec.get("is_derived", False))
        if is_fixed and is_derived:
            raise ValueError(f"parameter_overrides[flags]: «{code}» не может быть "
                             "одновременно фиксированным и производным")
        for row in rows:
            row.is_fixed = is_fixed or row.is_fixed
            row.is_derived = is_derived or row.is_derived
        if not rows:
            raise ValueError(f"parameter_overrides[flags]: код «{code}» без строк "
                             "определений (параметр ни для одного типа объекта?)")
        log(f"parameter_overrides: {code} fixed={is_fixed} derived={is_derived} "
            f"({len(rows)} определений)")

    # --- 2. Дефолты (worst-case) --------------------------------------------
    for code, value in defaults.items():
        rows = _rows(code, "defaults")
        pt = _pt(code, "defaults")
        if pt.value_type != "number":
            raise ValueError(f"parameter_overrides[defaults]: «{code}» не числовой "
                             f"(value_type={pt.value_type}), дефолт не переопределить")
        new_default = Decimal(str(value))
        for row in rows:
            if row.min_value is not None and new_default < Decimal(str(row.min_value)):
                raise ValueError(f"parameter_overrides[defaults]: дефолт «{code}» "
                                 f"{new_default} меньше минимума {row.min_value}")
            if row.max_value is not None and new_default > Decimal(str(row.max_value)):
                raise ValueError(f"parameter_overrides[defaults]: дефолт «{code}» "
                                 f"{new_default} больше максимума {row.max_value}")
            row.default_value_numeric = new_default
        log(f"parameter_overrides: {code} default -> {new_default}")

    # --- 3. Подсказки worst-case (дописываются к source_note) ---------------
    for code, hint in hints.items():
        for row in _rows(code, "worst_case_hints"):
            row.source_note = (f"{row.source_note}. {hint}"
                               if row.source_note else hint)

    # --- 4. Переименования (worst-case в имени) ------------------------------
    for code, new_name in renames.items():
        pt = _pt(code, "renames")
        old_name = pt.name
        if new_name == old_name:
            continue
        pt.name = new_name
        for row in code_to_rows.get(code, []):
            row.param_name = new_name
        log(f"parameter_overrides: {code} переименован «{old_name}» -> «{new_name}»")

    # каноническая уникальность имён после переименований (load_id_map в
    # params_repo падает на дублях ключей - ловим раньше и с понятным текстом)
    seen_names: dict[str, str] = {}
    for pt in param_types.values():
        key = canonical_key(pt.name)
        if key in seen_names:
            raise ValueError(f"после overrides у parameter_type канонически "
                             f"совпали имена «{seen_names[key]}» и «{pt.name}»")
        seen_names[key] = pt.name

    # --- 5. Новые параметры (нет в XLSX) -------------------------------------
    for spec in new_parameters:
        code = spec.get("code")
        name = spec.get("name")
        if not code or not name:
            raise ValueError(f"parameter_overrides[new_parameters]: нужны code и "
                             f"имя, получено: code={code!r} name={name!r}")
        if code in code_to_pt:
            raise ValueError(f"parameter_overrides[new_parameters]: код «{code}» "
                             "уже существует в XLSX - используйте секцию renames")
        value_type = spec.get("value_type")
        if value_type not in _ALLOWED_VALUE_TYPES:
            raise ValueError(f"parameter_overrides[new_parameters]: «{code}» "
                             f"value_type={value_type!r} (допустимо "
                             f"{_ALLOWED_VALUE_TYPES})")
        pt = ParamTypeRow(name=name, unit=spec.get("unit") or None,
                          value_type=value_type, code=code)
        code_to_pt[code] = pt
        data.param_types.append(pt)
        per_type = spec.get("per_type") or {}
        if not per_type:
            raise ValueError(f"parameter_overrides[new_parameters]: «{code}» без "
                             "ни одного типа объекта (per_type пуст)")
        for sheet, cfg in per_type.items():
            if sheet not in by_sheet:
                raise ValueError(f"parameter_overrides[new_parameters]: «{code}» "
                                 f"ссылается на неизвестный лист «{sheet}»")
            row = ObjectTypeParamRow(
                object_type=sheet,
                param_name=name,
                group_name=cfg.get("group") or "Прочее",
                is_required=bool(cfg.get("is_required", False)),
                min_value=parse_decimal(str(cfg["min"])) if cfg.get("min") is not None else None,
                max_value=parse_decimal(str(cfg["max"])) if cfg.get("max") is not None else None,
                source_note=cfg.get("source_note"),
            )
            if value_type == "number":
                row.default_value_numeric = (parse_decimal(str(cfg["default"]))
                                             if cfg.get("default") is not None else None)
            elif value_type == "boolean":
                row.default_value_bool = bool(cfg.get("default", False))
            else:
                row.default_value_text = cfg.get("default")
            if (row.min_value is not None and row.max_value is not None
                    and row.min_value > row.max_value):
                raise ValueError(f"parameter_overrides[new_parameters]: «{code}» "
                                 f"min {row.min_value} > max {row.max_value}")
            if (row.min_value is not None and row.default_value_numeric is not None
                    and row.default_value_numeric < row.min_value) or \
               (row.max_value is not None and row.default_value_numeric is not None
                    and row.default_value_numeric > row.max_value):
                raise ValueError(f"parameter_overrides[new_parameters]: «{code}» "
                                 f"дефолт {row.default_value_numeric} вне диапазона "
                                 f"[{row.min_value}; {row.max_value}]")
            by_sheet[sheet].append(row)
            data.object_type_params.append(row)
            log(f"parameter_overrides: новый параметр {code} («{name}») для «{sheet}»")


def _infer_value_type(p: ParamRow, log) -> str:
    """Детерминированный вывод value_type из данных строки:

    1) min/max числовые  -> number (диапазон бывает только у чисел);
    2) базовое число     -> number;
    3) имя начинается с «Наличие» и базовое начинается с «Да»/«Нет» -> boolean
       (уточнение вида «Да (OSDP)» в БД не сохраняется - только в источнике и логе);
    4) иначе             -> text (габариты «1200×800×1600», «1С:ERP», «24/7/365» и т.п.).
    """
    if parse_decimal(p.vmin) is not None or parse_decimal(p.vmax) is not None:
        return "number"
    if parse_decimal(p.base) is not None:
        return "number"
    base = norm_text(p.base)
    first_word = base.split(" ", 1)[0] if base else ""
    if p.name.startswith("Наличие") and first_word in ("Да", "Нет"):
        return "boolean"
    if p.name.startswith("Наличие"):
        log(f"параметр «{p.name}»: значение «{base}» не булево -> text")
    return "text"


def _to_object_type_param(sheet: str, p: ParamRow, vt: str,
                          required: frozenset[str], log) -> ObjectTypeParamRow:
    row = ObjectTypeParamRow(
        object_type=sheet,
        param_name=p.name,
        group_name=p.group or "Прочее",
        is_required=p.name in required,
        source_note=p.note,
    )
    if vt == "number":
        row.default_value_numeric = parse_decimal(p.base)
    elif vt == "boolean":
        base = norm_text(p.base) or ""
        row.default_value_bool = base.lower().startswith("да")
        if base not in ("Да", "Нет"):
            log(f"булев параметр «{p.name}» с уточнением «{base}» -> сохранено "
                f"{row.default_value_bool}, уточнение - только в источнике")
    else:
        row.default_value_text = norm_text(p.base)

    # min/max: «-» -> NULL (data_model.md §5.9); нечисловое («Да») -> NULL + лог
    for attr, raw in (("min_value", p.vmin), ("max_value", p.vmax)):
        value = parse_decimal(raw)
        if value is None and norm_text(raw) not in (None, "-"):
            log(f"диапазон {attr} параметра «{p.name}» не числовой («{norm_text(raw)}») -> NULL")
        setattr(row, attr, value)
    if (row.min_value is not None and row.max_value is not None
            and row.min_value > row.max_value):
        raise ValueError(f"min > max у параметра «{p.name}»: {row.min_value} > {row.max_value}")
    return row
