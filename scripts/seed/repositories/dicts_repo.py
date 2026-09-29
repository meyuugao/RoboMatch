"""Справочники: industry, vendor, region, solution_type, solution_subtype,
process, object_type, characteristic_type. Все вставки идемпотентны
(UPSERT по естественному ключу).

Коды всех справочников — английские snake_case по явному словарю
mappers/code_map.py (конвенция assumptions.md §15/§20): транслит-слаги
(slugify) остаются только фолбэком с предупреждением УТОЧНИТЬ.
"""
from typing import TypeAlias

from sqlalchemy import Table, select, update
from sqlalchemy.engine import Connection, Engine

from mappers.catalog_mapper import SUBTYPE_TYPO_FIXES
from mappers.code_map import code_for
from mappers.objects_mapper import (
    OBJECT_TYPE_CODE,
    SOLUTION_TYPE_SUBTYPE_MAP,
    TYPES_WITHOUT_SUBTYPES,
)
from normalizers.strings import canonical_key, norm_text, slugify
from .common import load_id_map, upsert

LogFn: TypeAlias = object


def _dict_code(table: str, name: str, log) -> str:
    """Код справочника: явный словарь code_map.py, иначе транслит-фолбэк.

    Аналогично OBJECT_TYPE_CODE (assumptions.md §15): отсутствие записи —
    предупреждение УТОЧНИТЬ в логе seed, маппинг нужно пополнить.
    """
    code, _ = code_for(table, name)
    if code is None:
        code = slugify(name) or "x"
        log(f"УТОЧНИТЬ: для {table} «{name}» нет кода в конвенции "
            f"(code_map.py), использован транслит «{code}»")
    return code

# Единственный тип объекта с включённым расчётом после seed (mvp_scope.md:
# полный расчёт и имитация — только для склада; assumptions.md §14).
# Ищем по имени (лист XLSX), а не по коду: имя — источник данных, код —
# конвенция (assumptions.md §15, английский snake_case).
CALC_ENABLED_OBJECT_TYPE_NAME = "Склад"

# --- assumptions.md §15: транслит-коды старых версий seed -> английские коды ----
# Миграция для БД, засеянной старой версией скрипта (slugify вместо конвенции).
# Свежая БД сразу получает английские коды — шаг no-op (0 строк).
LEGACY_OBJECT_TYPE_CODES: tuple[tuple[str, str], ...] = (
    ("sklad", "warehouse"),
    ("aeroport", "airport"),
    ("meduchrezhdenie", "hospital"),
)


def seed_industries(conn, engine, tables: dict[str, Table], names: list[str], log) -> dict[str, int]:
    # английские коды — code_map.py (конвенция assumptions.md §20)
    rows = [{"code": _dict_code("industry", n, log), "name": n} for n in names]
    upsert(conn, engine, tables["industry"], rows, ["code"], ["name"], log, "industry")
    return load_id_map(conn, tables["industry"])


def seed_vendors(conn, engine, tables, names: list[str], log) -> dict[str, int]:
    rows = [{"name": n} for n in names]
    upsert(conn, engine, tables["vendor"], rows, ["name"], None, log, "vendor")
    return load_id_map(conn, tables["vendor"])


def seed_regions(conn, engine, tables, names: list[str], log) -> dict[str, int]:
    rows = [{"code": _dict_code("region", n, log), "name": n} for n in names]
    upsert(conn, engine, tables["region"], rows, ["name"], ["code"], log, "region")
    return load_id_map(conn, tables["region"])


def seed_solution_types(conn, engine, tables, names: list[str], log) -> dict[str, int]:
    # расхождение с assumptions.md §4: там перечислено 10 типов + пусто, CSV даёт 11
    from mappers.objects_mapper import SOLUTION_TYPE_SUBTYPE_MAP
    extra = [n for n in names if canonical_key(n) not in
             {canonical_key(t) for t in SOLUTION_TYPE_SUBTYPE_MAP}]
    for n in extra:
        if n in TYPES_WITHOUT_SUBTYPES:
            # известное ограничение данных (assumptions.md §16): тип без пар
            # «тип-подтип», оставлен как есть
            log(f"УТОЧНИТЬ: {n} — тип без подтипов, оставлен как есть")
        else:
            log(f"УТОЧНИТЬ: типы в CSV без маппинга в assumptions.md §4 (нет пар «тип-подтип»): {[n]}")
    rows = [{"code": _dict_code("solution_type", n, log), "name": n} for n in names]
    upsert(conn, engine, tables["solution_type"], rows, ["name"], ["code"], log, "solution_type")
    return load_id_map(conn, tables["solution_type"])


def seed_solution_subtypes(conn, engine, tables, csv_names: list[str], log) -> dict[str, int]:
    """Справочник подтипов: значения CSV + подтипы из маппинга assumptions.md §4.

    Маппинг ссылается на подтипы, которых нет в CSV (например, «Робот-тягач») —
    без них FK solution_type_subtype_mapping не построить; добавление логируется.
    """
    known = {canonical_key(n): n for n in csv_names}
    rows = [{"code": _dict_code("solution_subtype", n, log), "name": n} for n in known.values()]
    added = 0
    for subtypes in SOLUTION_TYPE_SUBTYPE_MAP.values():
        for st in subtypes:
            key = canonical_key(st)
            if key not in known:
                known[key] = st
                rows.append({"code": _dict_code("solution_subtype", st, log), "name": st})
                added += 1
                log(f"подтип «{st}» отсутствует в CSV, добавлен из маппинга assumptions.md §4")
    if added:
        log(f"итого добавлено подтипов из маппинга: {added}")
    upsert(conn, engine, tables["solution_subtype"], rows, ["name"], ["code"], log, "solution_subtype")
    return load_id_map(conn, tables["solution_subtype"])


def seed_processes(conn, engine, tables, names: list[str], log) -> dict[str, int]:
    # английские коды — code_map.py; фолбэк — транслит с предупреждением
    rows = [{"code": _dict_code("process", n, log), "name": n, "is_active": True} for n in names]
    upsert(conn, engine, tables["process"], rows, ["code"], ["name", "is_active"], log, "process")
    return load_id_map(conn, tables["process"])


def migrate_object_type_codes(conn: Connection, tables: dict[str, Table], log) -> None:
    """Миграция транслит-кодов старых версий seed на английские (assumptions §15).

    Выполняется ДО upsert object_type (ключ — code): иначе на легаси-БД upsert
    вставил бы второй тип «warehouse» рядом со «sklad». Идемпотентно: на БД,
    где коды уже английские, UPDATE затрагивает 0 строк.
    Edge-case: если в БД есть и старый, и новый код — строки не трогаем
    (UNIQUE code), пишем предупреждение: разрешить руками.
    """
    ot = tables["object_type"]
    for old_code, new_code in LEGACY_OBJECT_TYPE_CODES:
        new_exists = conn.execute(
            select(ot.c["id"]).where(ot.c["code"] == new_code)
        ).first()
        old_id = conn.execute(
            select(ot.c["id"]).where(ot.c["code"] == old_code)
        ).scalar_one_or_none()
        if new_exists is not None and old_id is not None:
            log(f"КОНФЛИКТ кодов object_type: есть и «{old_code}», и «{new_code}» — "
                f"миграция пропущена, разрешить вручную")
            continue
        moved = conn.execute(
            update(ot).where(ot.c["code"] == old_code).values(code=new_code)
        ).rowcount if old_id is not None else 0
        log(f"code migrated: {old_code} -> {new_code} ({moved} строк)")


def migrate_dict_codes(conn: Connection, tables: dict[str, Table], log) -> None:
    """Миграция транслит-кодов справочников на английские (code_map.py).

    Для industry / process / parameter_type (колонка code существует со старых
    версий): переименование по имени ДО upsert — иначе upsert по ключу code
    вставил бы вторую строку рядом с транслит-кодом (UNIQUE name не даёт).
    Идемпотентно: на БД с английскими кодами UPDATE затрагивает 0 строк.
    """
    for table_name in ("industry", "process", "parameter_type"):
        tbl = tables[table_name]
        if "code" not in tbl.c or "name" not in tbl.c:
            continue
        rows = conn.execute(select(tbl.c["id"], tbl.c["name"], tbl.c["code"])).all()
        moved = 0
        for row_id, name, old_code in rows:
            new_code, _ = code_for(table_name, name)
            if new_code is not None and new_code != old_code:
                conn.execute(
                    update(tbl).where(tbl.c["id"] == row_id).values(code=new_code))
                moved += 1
                log(f"{table_name}: code migrated by name: {old_code} -> {new_code}")
        if moved:
            log(f"{table_name}: кодов переименовано по имени: {moved}")

def seed_object_types(conn, engine, tables, object_types: list[dict], log) -> dict[str, int]:
    """Коды — конвенция английского snake_case (assumptions.md §15).

    Новый тип объекта без записи в OBJECT_TYPE_CODE — предупреждение в лог:
    транслит-фолбэк ломает конвенцию, маппинг нужно пополнить.
    """
    for ot in object_types:
        explicit = OBJECT_TYPE_CODE.get(ot["name"])
        if explicit is None:
            ot["code"] = slugify(ot["name"])
            log(f"УТОЧНИТЬ: для типа объекта «{ot['name']}» нет кода в конвенции "
                f"(assumptions.md §15), использован транслит «{ot['code']}»")
        else:
            ot["code"] = explicit
    rows = [{"code": ot["code"], "name": ot["name"],
             "is_calc_enabled": ot["is_calc_enabled"],
             "data_source_note": ot["data_source_note"]} for ot in object_types]
    upsert(conn, engine, tables["object_type"], rows, ["code"],
           ["name", "is_calc_enabled", "data_source_note"], log, "object_type")
    return load_id_map(conn, tables["object_type"])


def enable_calc_for_warehouse(conn: Connection, tables: dict[str, Table], log) -> None:
    """is_calc_enabled = true только для склада (после вставки object_type).

    Остальные типы остаются false (DEFAULT). Повторный запуск флаг не сбрасывает:
    шаг выполняется в той же транзакции, что и UPSERT object_type, поэтому
    состояние «false» снаружи транзакции не наблюдается.
    """
    ot = tables["object_type"]
    row = conn.execute(
        select(ot.c["id"], ot.c["code"])
        .where(ot.c["name"] == CALC_ENABLED_OBJECT_TYPE_NAME)
    ).first()
    if row is None:
        raise ValueError(
            f"тип объекта «{CALC_ENABLED_OBJECT_TYPE_NAME}» не найден — "
            "is_calc_enabled=true не установлен (проверить листы XLSX)")
    conn.execute(
        update(ot).where(ot.c["id"] == row[0]).values(is_calc_enabled=True))
    log(f"object_type {row[1]}: is_calc_enabled=true")


def merge_solution_subtypes(conn: Connection, tables: dict[str, Table], log) -> None:
    """Схлопывание дублей подтипов из карты автоправок (assumptions.md §10/§13).

    Свежая БД: ошибочные формы не вставляются (карта применяется в маппере),
    шаг — no-op. БД, засеянная старой версией скрипта: ссылки solution
    перенаправляются на канонический подтип, записи mapping и сам дубль
    удаляются. Выбор «удалить», а не «deactivate»: у solution_subtype нет
    колонки is_active (менять схему нельзя), а строки-дубли не несут смысла.
    """
    subtype = tables["solution_subtype"]
    solution = tables["solution"]
    mapping = tables["solution_type_subtype_mapping"]

    def _id_by_name(name: str) -> int | None:
        return conn.execute(
            select(subtype.c["id"]).where(subtype.c["name"] == name)
        ).scalar_one_or_none()

    merged_pairs = 0
    updated_links = 0
    for bad_name, good_name in SUBTYPE_TYPO_FIXES.items():
        bad_id = _id_by_name(bad_name)
        if bad_id is None:
            continue  # дубля нет — уже объединено или свежая БД
        good_id = _id_by_name(good_name)
        if good_id is None:
            raise ValueError(
                f"канонический подтип «{good_name}» не найден: нельзя объединить «{bad_name}»")
        redirected = conn.execute(
            update(solution)
            .where(solution.c["solution_subtype_id"] == bad_id)
            .values(solution_subtype_id=good_id)
        ).rowcount
        conn.execute(mapping.delete().where(mapping.c["solution_subtype_id"] == bad_id))
        conn.execute(subtype.delete().where(subtype.c["id"] == bad_id))
        merged_pairs += 1
        updated_links += redirected
        log(f"подтип «{bad_name}» объединён с «{good_name}»: перенаправлено ссылок {redirected}")
    log(f"solution_subtype: объединено {merged_pairs} пар, обновлено {updated_links} ссылок")


def seed_type_subtype_mapping(conn, engine, tables, type_map: dict, subtype_map: dict, log):
    """62 документированные пары «тип -> подтип» из assumptions.md §4."""
    rows = []
    missing_types, missing_subtypes = [], []
    for type_name, subtypes in SOLUTION_TYPE_SUBTYPE_MAP.items():
        t_id = type_map.get(canonical_key(type_name))
        if t_id is None:
            missing_types.append(type_name)
            continue
        for st in subtypes:
            s_id = subtype_map.get(canonical_key(st))
            if s_id is None:
                missing_subtypes.append((type_name, st))
                continue
            rows.append({
                "solution_type_id": t_id,
                "solution_subtype_id": s_id,
                "source_note": "каталог допущений, раздел 4",
            })
    if missing_types:
        log(f"УТОЧНИТЬ: типы из assumptions.md §4 отсутствуют в CSV: {missing_types}")
    if missing_subtypes:
        log(f"УТОЧНИТЬ: пары без подтипа в справочнике: {missing_subtypes}")
    upsert(conn, engine, tables["solution_type_subtype_mapping"], rows,
           ["solution_type_id", "solution_subtype_id"], None, log, "solution_type_subtype_mapping")


def seed_object_type_industry(conn, engine, tables, object_type_map: dict, industry_map: dict, log):
    """7 связей «тип объекта -> отрасль» из assumptions.md §1."""
    from mappers.objects_mapper import OBJECT_TYPE_INDUSTRY

    rows = []
    for ot_name, ind_name in OBJECT_TYPE_INDUSTRY:
        ot_id = object_type_map.get(canonical_key(ot_name))
        ind_id = industry_map.get(canonical_key(ind_name))
        if ot_id is None or ind_id is None:
            raise ValueError(f"нет справочных записей для связи ({ot_name}, {ind_name})")
        rows.append({
            "object_type_id": ot_id,
            "industry_id": ind_id,
            "source_note": "каталог допущений, раздел 1",
        })
    upsert(conn, engine, tables["object_type_industry"], rows,
           ["object_type_id", "industry_id"], None, log, "object_type_industry")
