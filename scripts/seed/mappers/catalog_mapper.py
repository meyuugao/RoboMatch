"""Нормализация catalog_export_v4.csv: дедупликация решений, применения, кейсы.

Правила — data_model.md раздел 5 («CSV»), допущения — assumptions.md §4, 5, 7, 8, 9, 10,
12 («Расхождение по applications»), 13 («Объединение подтипов»).
Зерно строки CSV = решение x применение; зерно solution = external_id.
"""
from dataclasses import dataclass, field
from typing import TypeAlias

from normalizers.numbers import parse_decimal, parse_int
from normalizers.processes import split_scenarios
from normalizers.strings import canonical_key, norm_text

LogFn: TypeAlias = object  # избегаем циклического импорта типов; фактически Callable[[str], None]

PRODUCT_CLASSES = frozenset({"brs", "bas", "software"})
STATUSES = frozenset({"operation", "piloting", "rnd"})

# --- assumptions.md §10/§13: карта автоправок подтипов -----------------------
# Ключ — ошибочная форма из CSV, значение — каноническая (с дефисом / без опечатки).
# Применяется к solution.solution_subtype до построения справочника, поэтому
# в БД попадает только каноническая форма; БД, засеянная старой версией скрипта,
# чистится merge-шагом (dicts_repo.merge_solution_subtypes).
SUBTYPE_TYPO_FIXES: dict[str, str] = {
    "Робот уборщик": "Робот-уборщик",
    "Робот инвентаризатор": "Робот-инвентаризатор",
    "Модульная наводная многофункциональная платформа": "Модульная надводная многофункциональная платформа",
    # 4-я пара (assumptions.md §10/§13): в CSV — без дефиса (1 строка),
    # в маппинге §4 и Дополнениях 8 — дефисная форма «Роботизированный 3D-принтер»
    "Роботизированный 3D принтер": "Роботизированный 3D-принтер",
}


@dataclass
class SolutionRow:
    external_id: str
    name: str
    vendor: str
    product_class: str
    solution_type: str | None
    solution_subtype: str | None
    region: str | None
    status: str
    description: str | None
    price_rub: object  # Decimal
    trl: int | None
    market_potential: object  # Decimal | None


@dataclass
class ApplicationRow:
    external_id: str
    industry: str
    process: str
    offer_price_rub: object  # Decimal


@dataclass
class CatalogData:
    solutions: list[SolutionRow] = field(default_factory=list)
    applications: list[ApplicationRow] = field(default_factory=list)
    cases: list[str] = field(default_factory=list)          # уникальные тексты кейсов
    case_links: list[tuple[str, str]] = field(default_factory=list)  # (external_id, case)
    vendors: list[str] = field(default_factory=list)
    industries: list[str] = field(default_factory=list)
    regions: list[str] = field(default_factory=list)
    solution_types: list[str] = field(default_factory=list)
    solution_subtypes: list[str] = field(default_factory=list)
    processes: list[str] = field(default_factory=list)
    stats: dict = field(default_factory=dict)


# Поля карточки решения, объединяемые из группы дублей (data_model.md §5.2).
# Описание — самое длинное непустое; цена — минимальная (§5.5); остальные поля
# при расхождении внутри группы — конфликт: лог + первое значение по порядку файла.
_MERGE_FIELDS = (
    "name", "vendor", "product_class", "solution_type",
    "solution_subtype", "region", "status",
)


def map_catalog(raw_rows: list[dict], log=print) -> CatalogData:
    data = CatalogData()

    # --- 1. Группировка по external_id (дедупликация, §5.1) -----------------
    groups: dict[str, list[dict]] = {}
    for row in raw_rows:
        ext = norm_text(row["external_id"])
        if ext is None:
            raise ValueError(f"строка без id (external_id): компания={row.get('vendor')!r}")
        groups.setdefault(ext, []).append(row)

    dup_groups = {k: v for k, v in groups.items() if len(v) > 1}
    log(f"дедупликация: {len(raw_rows)} строк -> {len(groups)} решений "
        f"({len(dup_groups)} групп дублей, {len(raw_rows) - len(groups)} лишних строк)")

    # --- 2. Объединение групп -> SolutionRow ---------------------------------
    for ext, rows in groups.items():
        merged = {f: _merge_field(ext, f, rows, log) for f in _MERGE_FIELDS}

        descriptions = [d for d in (norm_text(r["description"]) for r in rows) if d]
        description = max(descriptions, key=len) if descriptions else None

        prices = []
        for r in rows:
            p = parse_decimal(r["price_raw"])
            if p is None:
                raise ValueError(f"у решения {ext} не парсится цена: {r['price_raw']!r}")
            prices.append(p)
        price_rub = min(prices)  # §5.5: конфлит цен -> price_rub = минимальная
        if len(set(prices)) > 1:
            log(f"КОНФЛИКТ цен у {ext}: {sorted(set(prices))} -> price_rub=min, "
                f"все цены сохранены в offer_price_rub применений")

        if merged["product_class"] not in PRODUCT_CLASSES:
            raise ValueError(f"у решения {ext} неизвестный класс «{merged['product_class']}» "
                             f"(допустимо {sorted(PRODUCT_CLASSES)})")
        if merged["status"] not in STATUSES:
            raise ValueError(f"у решения {ext} неизвестный статус «{merged['status']}» "
                             f"(допустимо {sorted(STATUSES)})")

        trl_raw = _single(ext, "trl_raw", rows, log)
        trl = parse_int(trl_raw, lo=1, hi=9) if trl_raw else None
        mp_raw = _single(ext, "market_potential_raw", rows, log)
        market_potential = parse_int(mp_raw, lo=2, hi=5) if mp_raw else None

        data.solutions.append(SolutionRow(
            external_id=ext,
            name=merged["name"],
            vendor=merged["vendor"],
            product_class=merged["product_class"],
            solution_type=merged["solution_type"],
            solution_subtype=fix_subtype(merged["solution_subtype"], log),
            region=merged["region"],
            status=merged["status"],
            description=description,
            price_rub=price_rub,
            trl=trl,
            market_potential=market_potential,
        ))
        if description is None:
            log(f"у решения {ext} («{merged['name']}») нет ни одного непустого описания -> NULL")
        if market_potential is None:
            log(f"у решения {ext} («{merged['name']}») пустой «Рын Потенциал» -> NULL")

    # --- 3. Применения и кейсы (по исходным строкам, §5.3-5.4) --------------
    app_price: dict[tuple[str, str, str], object] = {}
    case_set: dict[str, None] = {}  # ordered set канонических имён кейсов
    case_links: list[tuple[str, str]] = []

    for row in raw_rows:
        ext = norm_text(row["external_id"])
        industry = norm_text(row["industry"])
        if industry is None:
            raise ValueError(f"у строки {ext} пустая «Отрасль» (industry NOT NULL)")
        price = parse_decimal(row["price_raw"])
        for proc in split_scenarios(row["scenario"], log=log):
            key = (ext, industry, proc)
            if key in app_price and app_price[key] != price:
                log(f"КОНФЛИКТ: дубликат применения {key[:2]} + «{key[2]}» с ценой "
                    f"{app_price[key]} vs {price} -> оставлена минимальная")
                app_price[key] = min(app_price[key], price)
            else:
                app_price[key] = price

        case = norm_text(row["case"])
        if case:
            case_set.setdefault(case, None)
            link = (ext, case)
            if link not in case_links:
                case_links.append(link)

    data.applications = [
        ApplicationRow(external_id=k[0], industry=k[1], process=k[2], offer_price_rub=v)
        for k, v in app_price.items()
    ]
    data.cases = list(case_set)
    data.case_links = case_links

    # --- 4. Справочники из фактических значений CSV ---------------------------
    data.vendors = _unique_names((s.vendor for s in data.solutions), log, "vendor")
    data.industries = _unique_names((a.industry for a in data.applications), log, "industry")
    data.regions = _unique_names((s.region for s in data.solutions if s.region), log, "region")
    data.solution_types = _unique_names((s.solution_type for s in data.solutions if s.solution_type),
                                        log, "solution_type")
    data.solution_subtypes = _unique_names((s.solution_subtype for s in data.solutions if s.solution_subtype),
                                           log, "solution_subtype")
    data.processes = _unique_names((a.process for a in data.applications), log, "process")

    data.stats = {
        "csv_rows": len(raw_rows),
        "solutions": len(data.solutions),
        "dup_groups": len(dup_groups),
        "applications": len(data.applications),
        "cases": len(data.cases),
        "case_links": len(case_links),
        "vendors": len(data.vendors),
        "industries": len(data.industries),
        "regions": len(data.regions),
        "solution_types": len(data.solution_types),
        "solution_subtypes": len(data.solution_subtypes),
        "processes": len(data.processes),
    }
    return data


def fix_subtype(value: str | None, log=None) -> str | None:
    """Автоправка подтипа по карте assumptions.md §10/§13.

    Каноническая форма — с дефисом («Робот-уборщик») / без опечатки («надводная»).
    Справочник solution_subtype строится из уже исправленных значений решений,
    поэтому ошибочные формы в БД не попадают (fresh-режим).
    """
    if value is None:
        return None
    fixed = SUBTYPE_TYPO_FIXES.get(value)
    if fixed is not None and log is not None:
        log(f"подтип объединён (assumptions.md §10/§13): «{value}» -> «{fixed}»")
    return fixed if fixed is not None else value


def _merge_field(ext: str, field_name: str, rows: list[dict], log) -> str | None:
    """Первое непустое значение поля в группе дублей; расхождения — в лог."""
    values: list[str] = []
    for r in rows:
        v = norm_text(r[field_name])
        if v is not None and v not in values:
            values.append(v)
    if len(values) > 1:
        log(f"КОНФЛИКТ поля «{field_name}» в группе дублей {ext}: {values} -> взято первое")
    return values[0] if values else None


def _single(ext: str, field_name: str, rows: list[dict], log) -> str | None:
    """Первое непустое значение служебного поля (trl_raw, market_potential_raw) в группе."""
    values: list[str] = []
    for r in rows:
        v = norm_text(r.get(field_name))
        if v is not None and v not in values:
            values.append(v)
    if len(values) > 1:
        log(f"КОНФЛИКТ поля «{field_name}» в группе дублей {ext}: {values} -> взято первое")
    return values[0] if values else None


def _unique_names(names, log, label: str) -> list[str]:
    """Справочник: дедупликация по canonical_key (ё/регистр), хранится первое написание."""
    seen: dict[str, str] = {}
    for name in names:
        key = canonical_key(name)
        if key in seen and seen[key] != name:
            log(f"объединены варианты написания {label}: «{seen[key]}» + «{name}» "
                f"(канонический ключ «{key}»)")
        seen.setdefault(key, name)

    # похожие названия, отличающиеся только дефисом/пробелом: НЕ объединяем
    # (могут быть осмысленными вариациями, assumptions.md §10 — «не унифицируем жёстко»),
    # но фиксируем как потенциальные дубли для решения человеком
    folded = {}
    for key, name in seen.items():
        folded.setdefault(key.replace("-", " "), []).append(name)
    for group in folded.values():
        if len(group) > 1:
            log(f"ПОХОЖИЕ НАЗВАНИЯ {label} (дефис/пробел), НЕ объединены: "
                f"{' / '.join(sorted(group))} — проверить у организатора")
    return list(seen.values())
