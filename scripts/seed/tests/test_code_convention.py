"""Тесты: конвенция кодов object_type - английский snake_case (assumptions.md §15).

Проверяется и свежий seed (коды сразу английские), и миграция легаси-БД,
засеянной старой версией скрипта с транслит-кодами (sklad/aeroport/meduchrezhdenie).
"""
from sqlalchemy import func, select

from mappers.objects_mapper import OBJECT_TYPE_CODE
from run import run_seed

ENGLISH_CODES = {"warehouse", "airport", "hospital"}
LEGACY_CODES = {"sklad", "aeroport", "meduchrezhdenie"}


def test_object_type_codes_english(engine):
    """После seed коды object_type - warehouse/airport/hospital, транслита нет."""
    run_seed(engine)

    from tests.db_schema import meta

    ot = meta.tables["object_type"]
    with engine.connect() as conn:
        rows = conn.execute(select(ot.c["name"], ot.c["code"])).all()
    codes = {c for _, c in rows}
    assert codes == ENGLISH_CODES
    assert not (codes & LEGACY_CODES)
    # маппинг «имя листа -> код» покрыт полностью
    assert {n for n, _ in rows} == set(OBJECT_TYPE_CODE)


def test_code_convention_covers_all_sheets(objects_data):
    """Каждый тип объекта из XLSX имеет явный код в конвенции (без транслит-фолбэка)."""
    names = {ot["name"] for ot in objects_data.object_types}
    assert names <= set(OBJECT_TYPE_CODE), names - set(OBJECT_TYPE_CODE)


def test_legacy_translit_codes_migrated(engine):
    """БД со старыми транслит-кодами: seed переименовывает их до upsert,
    id сохраняются (FK не ломаются), дублей «sklad + warehouse» не возникает.
    """
    from tests.db_schema import meta

    ot = meta.tables["object_type"]
    otp = meta.tables["object_type_parameter"]

    # легаси-состояние: три типа с транслит-кодами + параметр, ссылающийся на склад
    with engine.begin() as conn:
        for name, code in (("Склад", "sklad"), ("Аэропорт", "aeroport"),
                           ("Медучреждение", "meduchrezhdenie")):
            conn.execute(ot.insert().values(code=code, name=name,
                                             is_calc_enabled=False, data_source_note=None))
        sklad_id = conn.execute(
            select(ot.c["id"]).where(ot.c["code"] == "sklad")).scalar_one()

    counts = run_seed(engine)

    # ровно по одному типу каждого вида - дублей нет
    assert counts["object_type"] == 3
    with engine.connect() as conn:
        rows = conn.execute(select(ot.c["id"], ot.c["code"], ot.c["name"])).all()
        assert {c for _, c, _ in rows} == ENGLISH_CODES
        # id склада сохранился: FK от object_type_parameter валиден
        warehouse_id = conn.execute(
            select(ot.c["id"]).where(ot.c["code"] == "warehouse")).scalar_one()
    assert warehouse_id == sklad_id
    orphan_params = engine.connect().execute(
        select(func.count()).select_from(otp)
        .where(otp.c["object_type_id"] == sklad_id)
    ).scalar_one()
    assert orphan_params > 0  # параметры склада привязаны к прежнему id

    # повторный запуск - миграция no-op, счётчики стабильны
    counts2 = run_seed(engine)
    assert counts2["object_type"] == 3


def test_migration_conflict_both_codes(engine):
    """Патологический случай: в БД есть и «sklad», и «warehouse» -
    миграция пропускает пару (UNIQUE code не нарушен), пишет предупреждение.
    """
    from tests.db_schema import meta

    ot = meta.tables["object_type"]
    with engine.begin() as conn:
        conn.execute(ot.insert().values(code="sklad", name="Склад (легаси)",
                                         is_calc_enabled=False, data_source_note=None))
        conn.execute(ot.insert().values(code="warehouse", name="Склад (дубль)",
                                        is_calc_enabled=False, data_source_note=None))
    counts = run_seed(engine)
    with engine.connect() as conn:
        codes = {row[0] for row in conn.execute(select(ot.c["code"])).all()}
    # пара пропущена, конфликтные строки не тронуты, остальное мигрировано
    assert "sklad" in codes
    assert "warehouse" in codes
    assert "airport" in codes and "hospital" in codes
    assert counts["object_type"] == 4  # 3 канонических + неразрешённый легаси-дубль


# --- Английские коды всех справочников (assumptions.md §20, code_map.py) ------

from mappers import code_map  # noqa: E402

DICT_TABLES = ("industry", "process", "parameter_type",
               "solution_type", "solution_subtype", "region")


def test_code_map_valid():
    """Словарь code_map: уникальные коды, только [a-z0-9_], непустые."""
    code_map.validate()


def test_code_map_coverage(catalog_data, objects_data):
    """Каждое фактическое значение справочников имеет явный английский код
    (покрытие 9/95/132/11/71/24 - без транслит-фолбэка)."""
    from mappers.objects_mapper import SOLUTION_TYPE_SUBTYPE_MAP
    all_subtypes = set(catalog_data.solution_subtypes)
    for subs in SOLUTION_TYPE_SUBTYPE_MAP.values():
        all_subtypes.update(subs)
    actual = {
        "industry": {n for n in catalog_data.industries},
        "process": {n for n in catalog_data.processes},
        "parameter_type": {pt.name for pt in objects_data.param_types},
        "solution_type": {n for n in catalog_data.solution_types},
        "solution_subtype": all_subtypes,
        "region": {n for n in catalog_data.regions},
    }
    # parameter_type: 132 из XLSX + 3 новых worst-case (overrides)
    expected_counts = {"industry": 9, "process": 95, "parameter_type": 135,
                       "solution_type": 11, "solution_subtype": 71, "region": 24}
    for table, names in actual.items():
        assert len(names) == expected_counts[table], (table, len(names))
        missing = names - set(code_map._CODE_TABLES[table])
        assert not missing, (table, missing)


def test_seed_codes_english(engine):
    """После seed все коды всех справочников - английские snake_case
    (транслит-слаги без латинско-кириллических примесей отсутствуют)."""
    import re
    from run import run_seed
    from tests.db_schema import meta

    run_seed(engine)
    english = re.compile(r"^[a-z][a-z0-9_]*$")
    legacy_markers = ("sklad", "aeroport", "meduchrezhdenie", "torgovlya",
                      "bezopasnost", "vnutriskladskaya")
    with engine.connect() as conn:
        for table in DICT_TABLES:
            tbl = meta.tables[table]
            codes = [row[0] for row in conn.execute(select(tbl.c["code"])).all()]
            assert codes, table
            for c in codes:
                assert english.match(c), (table, c)
                assert not any(m in c for m in legacy_markers), (table, c)


def test_dict_code_migration_by_name(engine):
    """Легаси-БД с транслит-кодами industry: seed переименовывает их по имени
    до upsert (migrate_dict_codes), дублей «транслит + английский» не возникает.
    """
    from run import run_seed
    from tests.db_schema import meta

    industry = meta.tables["industry"]
    with engine.begin() as conn:
        for name, legacy in (("Торговля и услуги", "torgovlya_i_uslugi"),
                             ("Безопасность", "bezopasnost"),
                             ("Транспорт и логистика", "transport_i_logistika")):
            conn.execute(industry.insert().values(code=legacy, name=name))

    counts = run_seed(engine)

    assert counts["industry"] == 9  # дублей нет
    with engine.connect() as conn:
        codes = {row[0] for row in conn.execute(select(industry.c["code"])).all()}
    assert "trade_and_services" in codes
    assert "security" in codes
    assert "transport_and_logistics" in codes
    assert not any(c in {"torgovlya_i_uslugi", "bezopasnost",
                         "transport_i_logistika"} for c in codes)

