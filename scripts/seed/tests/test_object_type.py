"""Тесты: object_type.is_calc_enabled - true только для склада (mvp_scope.md).

Код склада - конвенция английского snake_case (assumptions.md §15): «warehouse».
Флаг устанавливается по имени (лист XLSX) - источник данных, код - конвенция.
"""
from sqlalchemy import select

from run import run_seed


def test_warehouse_calc_enabled_only(engine):
    """Ровно один object_type с is_calc_enabled=true - «Склад» (код warehouse)."""
    run_seed(engine)

    from tests.db_schema import meta

    ot = meta.tables["object_type"]
    with engine.connect() as conn:
        rows = conn.execute(
            select(ot.c["name"], ot.c["code"], ot.c["is_calc_enabled"])
        ).all()
    assert len(rows) == 3
    enabled = [(n, c) for n, c, flag in rows if flag]
    assert len(enabled) == 1
    assert enabled[0][0] == "Склад"
    assert enabled[0][1] == "warehouse"  # конвенция кодов (assumptions.md §15)
    # аэропорт и медучреждение остались false
    disabled = {n for n, _, flag in rows if not flag}
    assert disabled == {"Аэропорт", "Медучреждение"}


def test_flag_not_reset_by_rerun(engine):
    """Повторный запуск seed не сбрасывает флаг склада."""
    run_seed(engine)
    run_seed(engine)

    from tests.db_schema import meta

    ot = meta.tables["object_type"]
    with engine.connect() as conn:
        rows = conn.execute(
            select(ot.c["name"], ot.c["is_calc_enabled"])
        ).all()
    assert dict(rows)["Склад"] is True
    assert sum(1 for _, flag in rows if flag) == 1
