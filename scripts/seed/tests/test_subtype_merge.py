"""Тесты: объединение подтипов-дублей (assumptions.md §10/§13).

Четыре пары: «Робот уборщик»/«Робот-уборщик», «Робот инвентаризатор»/
«Робот-инвентаризатор», «наводная»/«надводная» платформа,
«Роботизированный 3D принтер»/«Роботизированный 3D-принтер».
Каноническая форма - с дефисом / без опечатки.
"""
from sqlalchemy import func, select

from mappers.catalog_mapper import SUBTYPE_TYPO_FIXES, fix_subtype
from normalizers.strings import canonical_key

BAD_FORMS = {
    "Робот уборщик",
    "Робот инвентаризатор",
    "Модульная наводная многофункциональная платформа",
    "Роботизированный 3D принтер",
}
CANONICAL_FORMS = set(SUBTYPE_TYPO_FIXES.values())


def test_fix_subtype_mapping():
    """Карта автоправок: четыре пары, каноническая форма с дефисом / без опечатки."""
    assert len(SUBTYPE_TYPO_FIXES) == 4
    assert fix_subtype("Робот уборщик") == "Робот-уборщик"
    assert fix_subtype("Робот инвентаризатор") == "Робот-инвентаризатор"
    assert fix_subtype("Модульная наводная многофункциональная платформа") == \
        "Модульная надводная многофункциональная платформа"
    assert fix_subtype("Роботизированный 3D принтер") == "Роботизированный 3D-принтер"
    # неизвестные значения проходят без изменений, NULL -> NULL
    assert fix_subtype("AMR") == "AMR"
    assert fix_subtype(None) is None
    # каждая пара реально различается (дефис/опечатка), т.е. canonical_key не схлопнет
    for bad, good in SUBTYPE_TYPO_FIXES.items():
        assert canonical_key(bad) != canonical_key(good)


def test_catalog_contains_no_typo_forms(catalog_data):
    """Справочник и решения после маппера не содержат ошибочных форм."""
    subtypes = {canonical_key(s) for s in catalog_data.solution_subtypes}
    for bad in BAD_FORMS:
        assert canonical_key(bad) not in subtypes, bad
    for good in CANONICAL_FORMS:
        assert canonical_key(good) in subtypes, good
    for s in catalog_data.solution_subtypes:
        assert s not in BAD_FORMS
    for s in catalog_data.solutions:
        assert s.solution_subtype not in BAD_FORMS
    # 7 решений CSV имели неканонический подтип (6 + 1 «3D принтер» без дефиса) -
    # все перенаправлены
    redirected = [s for s in catalog_data.solutions
                  if s.solution_subtype in CANONICAL_FORMS]
    assert len(redirected) >= 7


def test_db_subtypes_have_no_duplicates(engine):
    """После seed в solution_subtype нет «Робот уборщик» (без дефиса),
    «Робот инвентаризатор» (без дефиса) и «наводной»/«3D принтер» без дефиса."""
    from run import run_seed
    from tests.db_schema import meta

    run_seed(engine)
    subtype = meta.tables["solution_subtype"]
    with engine.connect() as conn:
        names = {row[0] for row in conn.execute(select(subtype.c["name"])).all()}
    for bad in BAD_FORMS:
        assert bad not in names, bad
    assert not any("наводная" in n for n in names)
    for good in CANONICAL_FORMS:
        assert good in names, good
    total = engine.connect().execute(select(func.count()).select_from(subtype)).scalar_one()
    assert total == 71  # 68 CSV-подтипов после объединения + 3 из маппинга §4


def test_solution_subtype_fk_resolves(engine):
    """Все solution.solution_subtype_id указывают на существующие подтипы."""
    from run import run_seed
    from tests.db_schema import meta

    run_seed(engine)
    solution, subtype = meta.tables["solution"], meta.tables["solution_subtype"]
    with engine.connect() as conn:
        orphans = conn.execute(
            select(func.count()).select_from(solution)
            .outerjoin(subtype, solution.c["solution_subtype_id"] == subtype.c["id"])
            .where(solution.c["solution_subtype_id"].isnot(None),
                   subtype.c["id"].is_(None))
        ).scalar_one()
        assert orphans == 0
        # решения с бывшими опечатками ссылаются на канонические подтипы
        joined = conn.execute(
            select(func.count()).select_from(solution)
            .join(subtype, solution.c["solution_subtype_id"] == subtype.c["id"])
            .where(subtype.c["name"].in_(tuple(CANONICAL_FORMS)))
        ).scalar_one()
        assert joined >= 7


def test_merge_repairs_legacy_duplicates(engine):
    """БД, засеянная старой версией скрипта (с дублями), чинится повторным seed:
    дубли удаляются, ссылки перенаправляются, mapping не ломается.
    """
    from run import run_seed
    from tests.db_schema import meta

    counts1 = run_seed(engine)
    subtype = meta.tables["solution_subtype"]
    solution = meta.tables["solution"]
    mapping = meta.tables["solution_type_subtype_mapping"]

    # легаси-состояние: дубль подтипа (с транслит-кодом старой версии seed)
    # + ручное решение (вне CSV) на него + mapping
    with engine.begin() as conn:
        conn.execute(subtype.insert().values(
            name="Робот уборщик", code="robot_uborshchik"))
        conn.execute(subtype.insert().values(
            name="Роботизированный 3D принтер", code="robotizirovannyy_3d_printer"))
        bad_id = conn.execute(
            select(subtype.c["id"]).where(subtype.c["name"] == "Робот уборщик")
        ).scalar_one()
        good_id = conn.execute(
            select(subtype.c["id"]).where(subtype.c["name"] == "Робот-уборщик")
        ).scalar_one()
        vendor_id = conn.execute(select(solution.c["vendor_id"]).limit(1)).scalar_one()
        conn.execute(solution.insert().values(
            external_id=None, name="Ручной тестовый робот", vendor_id=vendor_id,
            product_class="brs", status="operation", price_rub=100000,
            solution_subtype_id=bad_id))
        type_id = conn.execute(select(mapping.c["solution_type_id"]).limit(1)).scalar_one()
        conn.execute(mapping.insert().values(
            solution_type_id=type_id, solution_subtype_id=bad_id, source_note="legacy"))

    counts2 = run_seed(engine)
    assert counts2["solution_subtype"] == counts1["solution_subtype"]
    assert counts2["solution_type_subtype_mapping"] == counts1["solution_type_subtype_mapping"]
    assert counts2["solution"] == counts1["solution"] + 1  # ручное решение сохранено

    with engine.connect() as conn:
        names = {row[0] for row in conn.execute(select(subtype.c["name"])).all()}
        assert "Робот уборщик" not in names
        assert "Роботизированный 3D принтер" not in names
        redirected = conn.execute(
            select(solution.c["solution_subtype_id"])
            .where(solution.c["name"] == "Ручной тестовый робот")
        ).scalar_one()
        assert redirected == good_id
