"""Интеграционный тест: идемпотентность двойного запуска seed.

По умолчанию — SQLite (tmp-файл); при TEST_DATABASE_URL — целевой PostgreSQL.
Проверяется и сама вставка (счётчики приёмки), и повторный запуск без дублей.
"""
import pytest
from sqlalchemy import func, select

from run import run_seed




def _counts(engine, tables) -> dict:
    return {name: engine.connect().execute(select(func.count()).select_from(t)).scalar_one()
            for name, t in tables.items()}


def test_double_run_is_idempotent(engine):
    """Первый запуск заполняет БД, второй — не дублирует ни одной строки."""
    counts1 = run_seed(engine)

    # --- ключевые счётчики приёмки (критерии успеха) ---
    assert counts1["solution"] == 187
    assert counts1["vendor"] == 103
    assert counts1["industry"] == 9
    assert counts1["solution_type"] == 11
    assert counts1["solution_subtype"] == 71  # после объединения 4 пар (assumptions §13)
    assert counts1["process"] == 95
    assert counts1["region"] == 24
    assert counts1["object_type"] == 3
    assert counts1["object_type_industry"] == 7
    # 132/138 из XLSX + 3/7 worst-case из parameter_overrides.json
    assert counts1["parameter_type"] == 135
    assert counts1["object_type_parameter"] == 145
    assert counts1["solution_application"] == 250
    assert counts1["solution_case"] == 175
    assert counts1["solution_case_link"] == 195
    assert counts1["solution_type_subtype_mapping"] == 62
    # ТТХ при импорте NULL (data_model.md §5.8), но метаданные характеристик
    # сеются (assumptions.md §14) — 27 записей characteristic_type.
    # После дозаполнения из открытых источников —
    # solution_characteristic заполняется значениями с провенансом
    # (data/characteristics.json). Актуальное число — 80 (10 решений:
    # Ronavi H1500/H2000/SR/SD × 9, AMR 100/800/1500,
    # DMR 1200 × 8, Сёмабот × 2, RoboCV штабелёр × 7; добавлены
    # charging_power_kw Ronavi и точность/автономность/навигация DMR 1200,
    # charging_power_kw AMR 800 «1-ф 220В, 10A» = 2,2 кВт).
    assert counts1["characteristic_type"] == 27
    assert counts1.get("solution_characteristic", 0) == 80, (
        "solution_characteristic: ожидалось 80 значений "
        "(10 решений, data/characteristics.json)"
    )
    # Демо-аккаунты сдачи: ровно admin + user —
    # test_economics не создаётся (E2E-проекты принадлежат user)
    assert counts1["user"] == 2, (
        "user: ожидалось 2 аккаунта (admin + user, фикс A15)"
    )
    # E2E-проекты экономики + демо-проекты фикстур: 5 «E2E: *» +
    # 3 «Демо: *» у user (задача 4; составы демо вставляются только при
    # импортированных фикстурах — в тестовой БД их нет, проекты без
    # состава)
    assert counts1.get("project", 0) == 8, (
        "project: ожидалось 8 (5 E2E + 3 демо)"
    )
    assert counts1.get("scenario", 0) == 24, "scenario: 8 проектов × 3 сценария"
    assert counts1.get("project_assumption", 0) == 10, (
        "project_assumption: ожидалось 10 (p_nominal × 4 + k_load/k_reserve "
        "+ robot_power_consumption_kw у «Edge case», фикс A2; + P_nominal "
        "× 3 демо-проекта)"
    )

    # --- второй запуск: те же счётчики, ни одной новой строки ---
    counts2 = run_seed(engine)
    for table, c1 in counts1.items():
        assert counts2[table] == c1, f"таблица {table}: после второго запуска {counts2[table]} != {c1}"

    # точечная проверка уникальности по естественным ключам
    from tests.db_schema import meta

    solution = meta.tables["solution"]
    application = meta.tables["solution_application"]
    with engine.connect() as conn:
        # количество external_id, встречающихся более одного раза
        dup_ext_rows = conn.execute(
            select(func.count()).select_from(solution)
            .group_by(solution.c.external_id).having(func.count() > 1)
        ).all()
        assert len(dup_ext_rows) == 0
        dup_app_rows = conn.execute(
            select(func.count()).select_from(application)
            .group_by(application.c.solution_id, application.c.industry_id,
                      application.c.process_id)
            .having(func.count() > 1)
        ).all()
        assert len(dup_app_rows) == 0

    # флаги worst-case стабильны после повторного запуска
    from tests.db_schema import meta
    otp = meta.tables["object_type_parameter"]
    pt = meta.tables["parameter_type"]
    with engine.connect() as conn:
        fixed = conn.execute(select(func.count()).select_from(otp)
                             .where(otp.c.is_fixed)).scalar_one()
        derived = conn.execute(select(func.count()).select_from(otp)
                               .where(otp.c.is_derived)).scalar_one()
        renamed = conn.execute(select(pt.c.name).where(
            pt.c.code == "pallet_unit_weight")).scalar_one()
    assert fixed == 5   # ФОТ x3 типа + рабочие дни + WMS
    assert derived == 2  # штуки/сутки + активная зона
    assert renamed == "Максимальная масса грузовой единицы (паллет), кг"


def test_foreign_keys_resolve(engine):
    """После seed нет «висячих» ссылок: применения ссылаются на существующие строки."""
    from sqlalchemy import and_

    from tests.db_schema import meta

    solution, application = meta.tables["solution"], meta.tables["solution_application"]
    with engine.connect() as conn:
        orphans = conn.execute(
            select(func.count()).select_from(application)
            .outerjoin(solution, and_(solution.c.id == application.c.solution_id))
            .where(solution.c.id.is_(None))
        ).scalar_one()
        assert orphans == 0
