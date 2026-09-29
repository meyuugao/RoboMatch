"""Тесты: дозаполнение ТТХ из открытых источников.

Проверки:
  - JSON валиден, все external_id и code корректны;
  - после seed: solution_characteristic > 0, у каждого приоритетного
    решения есть хотя бы одно обязательное ТТХ;
  - провенанс: source_kind='open_source', source_url непустой,
    is_confirmed=true (assumptions.md §7);
  - зеркальные колонки solution (9 ТТХ) заполнены (data_model.md §5.8);
  - идемпотентность: повторный seed не меняет число записей.
"""
import pytest
from sqlalchemy import func, select

from mappers.characteristics_loader import load_characteristics
from mappers.characteristics_mapper import MIRROR_SOLUTION_COLUMNS
from run import run_seed


def test_characteristics_json_loads():
    """JSON парсится, валиден, содержит ≥5 решений (уточнение организатора лимит 5-10)."""
    from mappers.characteristics_loader import default_path

    path = default_path()
    if not path.exists():
        pytest.skip("characteristics.json отсутствует — дозаполнение не делалось")
    solutions = load_characteristics(path)
    assert len(solutions) >= 5, f"ожидалось ≥5 решений (уточнение организатора), получено {len(solutions)}"
    # Хотя бы одно обязательное ТТХ у каждого решения (assumptions.md §21)
    required_codes = {
        "payload_kg", "mass_kg", "length_mm", "width_mm", "height_mm",
        "positioning_accuracy_mm", "charging_power_kw", "noise_level_dba",
    }
    for sol in solutions:
        sol_codes = {c.code for c in sol.characteristics}
        assert sol_codes & required_codes, (
            f"решение «{sol.solution_name}» не содержит обязательных ТТХ "
            f"из {required_codes}"
        )


def test_provenance_after_seed(engine):
    """Каждая запись в solution_characteristic имеет полный провенанс
    (assumptions.md §7: source_kind, source_url, source_date, is_confirmed)."""
    from tests.db_schema import meta

    counts = run_seed(engine)
    if counts.get("solution_characteristic", 0) == 0:
        pytest.skip("characteristics.json отсутствует — провенанс не проверяется")

    sc = meta.tables["solution_characteristic"]
    with engine.connect() as conn:
        # Все строки: source_kind='open_source', is_confirmed=true, source_url непустой
        rows = conn.execute(select(
            sc.c["source_kind"], sc.c["source_url"], sc.c["source_date"],
            sc.c["is_confirmed"],
        )).all()
        assert rows, "solution_characteristic пуста — тест не имеет смысла"
        for source_kind, source_url, source_date, is_confirmed in rows:
            assert source_kind == "open_source", (
                f"source_kind ожидаемо 'open_source', получено «{source_kind}»")
            assert source_url, "source_url пуст — нарушает assumptions.md §7"
            assert source_date is not None, "source_date пуст — нарушает assumptions.md §7"
            assert is_confirmed is True, (
                "is_confirmed=false — нарушает assumptions.md §7 "
                "(данные из открытого источника считаются подтверждёнными)")


def test_mirror_solution_columns_filled(engine):
    """9 зеркальных ТТХ-колонок solution заполнены у приоритетных решений
    (data_model.md §5.8 — запись идёт в оба места)."""
    from tests.db_schema import meta

    counts = run_seed(engine)
    if counts.get("solution_characteristic", 0) == 0:
        pytest.skip("characteristics.json отсутствует")

    sol_table = meta.tables["solution"]
    sc = meta.tables["solution_characteristic"]
    ct = meta.tables["characteristic_type"]
    with engine.connect() as conn:
        # Берём все (solution_id, code, value_numeric) из EAV для зеркальных ТТХ
        rows = conn.execute(
            select(sc.c["solution_id"], ct.c["code"], sc.c["value_numeric"])
            .join(ct, ct.c["id"] == sc.c["characteristic_type_id"])
            .where(ct.c["code"].in_(MIRROR_SOLUTION_COLUMNS))
            .where(sc.c["value_numeric"].is_not(None))
        ).all()
        assert rows, "в EAV нет числовых значений для зеркальных ТТХ"
        # Группируем по solution_id: какие колонки должны быть заполнены
        by_sol: dict[int, set[str]] = {}
        for sol_id, code, _ in rows:
            by_sol.setdefault(sol_id, set()).add(code)
        # Проверяем, что в solution эти колонки действительно непустые
        for sol_id, codes in by_sol.items():
            row = conn.execute(
                select(*[sol_table.c[c] for c in codes])
                .where(sol_table.c["id"] == sol_id)
            ).one()
            for col, val in zip(codes, row):
                assert val is not None, (
                    f"колонка solution.{col} для solution_id={sol_id} "
                    f"осталась NULL, хотя в EAV значение есть (data_model.md §5.8)"
                )


def test_enrichment_idempotent(engine):
    """Повторный seed не дублирует записи в solution_characteristic
    и не сбрасывает значения (UNIQUE (solution_id, characteristic_type_id)
    + UPSERT)."""
    from tests.db_schema import meta

    counts1 = run_seed(engine)
    n1 = counts1.get("solution_characteristic", 0)
    if n1 == 0:
        pytest.skip("characteristics.json отсутствует")

    counts2 = run_seed(engine)
    n2 = counts2.get("solution_characteristic", 0)
    assert n1 == n2, (
        f"идемпотентность нарушена: первый прогон дал {n1} записей, "
        f"повторный — {n2}; ожидается равенство (UNIQUE + UPSERT)"
    )

    # Точечная проверка: нет дублей по (solution_id, characteristic_type_id)
    sc = meta.tables["solution_characteristic"]
    from sqlalchemy import func
    with engine.connect() as conn:
        dups = conn.execute(
            select(func.count()).select_from(sc)
            .group_by(sc.c["solution_id"], sc.c["characteristic_type_id"])
            .having(func.count() > 1)
        ).all()
        assert len(dups) == 0, f"найдены дубли в solution_characteristic: {len(dups)}"


def test_solution_card_provenance_unchanged(engine):
    """Дозаполнение ТТХ не меняет провенанс КАРТОЧКИ решения:
    source_kind остаётся 'organizer_catalog' (data_model.md §5.7 —
    источник карточки — файл организатора; смена его на 'open_source' делала
    бы карточку противоречивой: source_url — 'catalog_export_v4.csv').
    Провенанс дозаполнения — в solution_characteristic.
    Заменил test_solution_source_kind_updated: тот тест закреплял нарушение
    §5.7 (обоснование — в §6 журнала)."""
    from tests.db_schema import meta

    counts = run_seed(engine)
    if counts.get("solution_characteristic", 0) == 0:
        pytest.skip("characteristics.json отсутствует")

    sol_table = meta.tables["solution"]
    with engine.connect() as conn:
        # Все 187 карточек сохраняют провенанс импорта (§5.7)
        n_open = conn.execute(
            select(func.count()).select_from(sol_table)
            .where(sol_table.c["source_kind"] == "open_source")
        ).scalar_one()
        assert n_open == 0, (
            f"{n_open} карточек помечены source_kind='open_source' — "
            "нарушение data_model.md §5.7 (карточка — organizer_catalog)"
        )
        # дата источника карточки — дата импорта, не затирается дозаполнением
        n_no_source_date = conn.execute(
            select(func.count()).select_from(sol_table)
            .where(sol_table.c["source_date"].is_(None))
        ).scalar_one()
        assert n_no_source_date == 0, "source_date карточки не должен обнуляться"
