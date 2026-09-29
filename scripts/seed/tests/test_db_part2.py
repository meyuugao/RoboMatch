"""Тесты Части 2 (data_model.md §8–12): домены пользователя.

Проверяется тестовое зеркало db_schema.py:
- все 14 таблиц создаются после create_all;
- FK-цепочка user → project → … валидна (вставка данных проходит);
- CHECK-перечисления (role, status, type, format) отклоняют неверные значения;
- инвариант EAV «ровно одно value_*» в project_parameter_value;
- UNIQUE project (user_id, name) и scenario (project_id, name);
- каскадное удаление проекта срывает дочерние строки.
"""
import pytest
from sqlalchemy import inspect, select
from sqlalchemy.exc import IntegrityError

from tests.db_schema import create_all, meta

PART2_TABLES = [
    "user", "project", "project_parameter_value", "project_attachment",
    "scenario", "scenario_solution", "selection_result", "calculation",
    "calculation_assumption", "manual_adjustment", "simulation_result", "export",
    "project_assumption", "admin_import_log",
]


@pytest.fixture
def p2(engine):
    """Движок + вставленная базовая цепочка user/project/… для CHECK-тестов."""
    create_all(engine)
    user, project = meta.tables["user"], meta.tables["project"]
    scenario, calculation = meta.tables["scenario"], meta.tables["calculation"]
    solution, vendor = meta.tables["solution"], meta.tables["vendor"]
    industry, process = meta.tables["industry"], meta.tables["process"]
    with engine.begin() as conn:
        conn.execute(user.insert().values(login="u1", password_hash="x", role="user"))
        uid = conn.execute(select(user.c["id"])).scalar_one()
        ot = meta.tables["object_type"]
        conn.execute(ot.insert().values(code="warehouse", name="Склад"))
        otid = conn.execute(select(ot.c["id"])).scalar_one()
        # каталог: минимальные строки для FK из Части 2
        conn.execute(meta.tables["vendor"].insert().values(name="V"))
        vid = conn.execute(select(meta.tables["vendor"].c["id"])).scalar_one()
        conn.execute(solution.insert().values(
            name="R0", vendor_id=vid, product_class="brs", status="operation",
            price_rub=1))
        sol = conn.execute(select(solution.c["id"])).scalar_one()
        conn.execute(industry.insert().values(code="security", name="Безопасность"))
        iid = conn.execute(select(industry.c["id"])).scalar_one()
        conn.execute(process.insert().values(code="monitoring", name="Мониторинг"))
        prid = conn.execute(select(process.c["id"])).scalar_one()
        conn.execute(project.insert().values(user_id=uid, object_type_id=otid, name="p1"))
        pid = conn.execute(select(project.c["id"])).scalar_one()
        conn.execute(scenario.insert().values(project_id=pid, type="purchase", name="s1"))
        sid = conn.execute(select(scenario.c["id"])).scalar_one()
        conn.execute(calculation.insert().values(
            scenario_id=sid, version_data="csv-v4", version_model="m1"))
        cid = conn.execute(select(calculation.c["id"])).scalar_one()
    return {"engine": engine, "user_id": uid, "project_id": pid,
            "scenario_id": sid, "calculation_id": cid, "solution_id": sol,
            "industry_id": iid, "process_id": prid}


def test_part2_tables_created(p2):
    """Все 14 таблиц Части 2 создаются зеркалом db_schema.py."""
    names = set(inspect(p2["engine"]).get_table_names())
    missing = [t for t in PART2_TABLES if t not in names]
    assert not missing, missing
    assert len(PART2_TABLES) == 14


def test_part2_fk_chain_insert(p2):
    """FK-цепочка валидна: сущности Части 2 вставляются по реальным FK."""
    e = p2["engine"]
    with e.begin() as conn:
        # решение №2 (справочные строки уже в фикстуре p2)
        vid = conn.execute(select(meta.tables["vendor"].c["id"])).scalar_one()
        conn.execute(meta.tables["solution"].insert().values(
            name="R1", vendor_id=vid, product_class="brs", status="operation",
            price_rub=100))
        sid = conn.execute(select(meta.tables["solution"].c["id"]
                                  ).where(meta.tables["solution"].c["name"] == "R1")).scalar_one()
        conn.execute(meta.tables["scenario_solution"].insert().values(
            scenario_id=p2["scenario_id"], solution_id=sid, quantity=2))
        conn.execute(meta.tables["selection_result"].insert().values(
            project_id=p2["project_id"], solution_id=sid, status="fit", rank=1))
        conn.execute(meta.tables["project_attachment"].insert().values(
            project_id=p2["project_id"], file_name="x.xlsx",
            file_path="/tmp/x.xlsx", size_bytes=10))
        conn.execute(meta.tables["export"].insert().values(
            project_id=p2["project_id"], format="pdf", file_path="/tmp/r.pdf",
            created_by_user_id=p2["user_id"]))
        conn.execute(meta.tables["simulation_result"].insert().values(
            scenario_id=p2["scenario_id"], status="running"))
        conn.execute(meta.tables["calculation_assumption"].insert().values(
            calculation_id=p2["calculation_id"], name="K_load", value="0.75"))
        conn.execute(meta.tables["manual_adjustment"].insert().values(
            calculation_id=p2["calculation_id"], metric_name="CAPEX",
            new_value=100, reason="test", author_user_id=p2["user_id"]))


@pytest.mark.parametrize("table,col,bad,check_part", [
    ("user", "role", "superadmin", "role IN"),
    ("project", "status", "deleted", "status IN"),
    ("scenario", "type", "leasing", "type IN"),
    ("selection_result", "status", "maybe", "status IN"),
    ("simulation_result", "status", "paused", "status IN"),
    ("export", "format", "docx", "format IN"),
])
def test_part2_check_enums(p2, table, col, bad, check_part):
    """CHECK-перечисления Части 2 отклоняют недопустимые коды."""
    e = p2["engine"]
    tbl = meta.tables[table]
    base = {"user": {"login": "u2", "password_hash": "x"},
            "project": {"user_id": p2["user_id"],
                        "object_type_id": _first_id(e, "object_type"),
                        "name": "p-check"},
            "scenario": {"project_id": p2["project_id"], "name": "s-check"},
            "selection_result": {"project_id": p2["project_id"],
                                 "solution_id": _first_id(e, "solution"),
                                 "status": "fit"},
            "simulation_result": {"scenario_id": p2["scenario_id"]},
            "export": {"project_id": p2["project_id"], "file_path": "/tmp/f",
                       "created_by_user_id": p2["user_id"]}}[table]
    base[col] = bad
    with pytest.raises(IntegrityError):
        with e.begin() as conn:
            conn.execute(tbl.insert().values(**base))


def _first_id(engine, table):
    t = meta.tables[table]
    with engine.connect() as conn:
        return conn.execute(select(t.c["id"]).limit(1)).scalar_one()


def test_part2_project_parameter_value_eav(p2):
    """Инвариант EAV: ровно одно из value_numeric/text/bool (CHECK)."""
    ppv = meta.tables["project_parameter_value"]
    otp = meta.tables["object_type_parameter"]
    pt = meta.tables["parameter_type"]
    e = p2["engine"]
    with e.begin() as conn:
        conn.execute(pt.insert().values(code="area", name="Площадь", value_type="number"))
        ptid = conn.execute(select(pt.c["id"])).scalar_one()
        conn.execute(otp.insert().values(
            object_type_id=_first_id(e, "object_type"), parameter_type_id=ptid,
            group_name="Г", is_required=True, default_value_numeric=100))
        otpid = conn.execute(select(otp.c["id"])).scalar_one()
    # оба значения -> нарушение CHECK
    with pytest.raises(IntegrityError):
        with e.begin() as conn:
            conn.execute(ppv.insert().values(
                project_id=p2["project_id"], object_type_parameter_id=otpid,
                value_numeric=1, value_text="x"))
    # ни одного значения -> тоже нарушение
    with pytest.raises(IntegrityError):
        with e.begin() as conn:
            conn.execute(ppv.insert().values(
                project_id=p2["project_id"], object_type_parameter_id=otpid))
    # ровно одно -> ок
    with e.begin() as conn:
        conn.execute(ppv.insert().values(
            project_id=p2["project_id"], object_type_parameter_id=otpid,
            value_numeric=1))


def test_part2_unique_project_name_per_user(p2):
    """UNIQUE (user_id, name) у project: дубль имени в том же проекте отклоняется."""
    e = p2["engine"]
    project = meta.tables["project"]
    with pytest.raises(IntegrityError):
        with e.begin() as conn:
            conn.execute(project.insert().values(
                user_id=p2["user_id"], object_type_id=_first_id(e, "object_type"),
                name="p1"))


def test_part2_cascade_delete_project(p2):
    """Каскад: удаление проекта удаляет сценарии, расчёты и вложения."""
    e = p2["engine"]
    project, scenario, calculation = (meta.tables["project"],
                                      meta.tables["scenario"],
                                      meta.tables["calculation"])
    with e.begin() as conn:
        conn.execute(project.delete().where(project.c["id"] == p2["project_id"]))
        assert conn.execute(select(scenario.c["id"])).scalar_one_or_none() is None
        assert conn.execute(select(calculation.c["id"])).scalar_one_or_none() is None


def test_part2_admin_import_log_status_check(p2):
    """V7 admin_import_log: status ограничен running/completed/failed."""
    e = p2["engine"]
    ail = meta.tables["admin_import_log"]
    with pytest.raises(IntegrityError):
        with e.begin() as conn:
            conn.execute(ail.insert().values(
                file_name="catalog.csv", file_path="data/admin-imports/1.csv",
                size_bytes=100, created_by_user_id=p2["user_id"], status="bogus"))


def test_part2_admin_import_log_running_default(p2):
    """V7 admin_import_log: статус по умолчанию running, FK на user работает."""
    e = p2["engine"]
    ail = meta.tables["admin_import_log"]
    with e.begin() as conn:
        res = conn.execute(ail.insert().values(
            file_name="catalog.csv", file_path="data/admin-imports/1.csv",
            size_bytes=100, created_by_user_id=p2["user_id"]))
        row = conn.execute(
            select(ail.c["status"]).where(ail.c["id"] == res.inserted_primary_key[0])
        ).scalar_one()
    assert row == "running"
