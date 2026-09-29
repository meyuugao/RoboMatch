"""solution_characteristic - значения ТТХ из открытых источников.

Запись идёт в два места (data_model.md §5.8 - «запись идёт в оба места»):
  1. EAV: solution_characteristic (UNIQUE (solution_id, characteristic_type_id));
  2. 9 зеркальных колонок solution (payload_kg, mass_kg, length_mm, width_mm,
     height_mm, positioning_accuracy_mm, speed_m_s, charging_power_kw,
     noise_level_dba).

Идемпотентность:
  - EAV: INSERT .. ON CONFLICT (solution_id, characteristic_type_id) DO UPDATE
    для всех полей, включая значение и провенанс (повторный прогон
    идемпотентен - значения и провенанс не сбрасываются в NULL).
  - solution: UPDATE зеркальных колонок из EAV-записей (только для тех кодов,
    что входят в MIRROR_SOLUTION_COLUMNS). Если у решения уже были
    значения из другого источника - они заменяются более свежими
    (open_source имеет приоритет над organizer_catalog по assumptions.md §7).

Логирование:
  characteristics: отправлено N, вставлено X, обновлено Y (EAV);
  solution_mirror: обновлено Z колонок (9 ТТХ × N решений).
"""
import datetime
from typing import TypeAlias

from sqlalchemy import Table, func, select, update
from sqlalchemy.engine import Connection, Engine

from mappers.characteristics_loader import EnrichedSolution
from mappers.characteristics_mapper import MIRROR_SOLUTION_COLUMNS
from .common import dialect_insert, upsert

LogFn: TypeAlias = object

# Маппинг «код характеристики → колонка solution» (1:1, data_model.md §5.8).
# Совпадает с MIRROR_SOLUTION_COLUMNS, но здесь - словарь для удобства.
_MIRROR_CODE_TO_COLUMN: dict[str, str] = {code: code for code in MIRROR_SOLUTION_COLUMNS}


def _to_date(value):
    """Преобразование ISO-строки 'YYYY-MM-DD' или date в date объект.

    SQLite Date column принимает только Python date; PostgreSQL - оба.
    """
    if value is None:
        return None
    if isinstance(value, datetime.date):
        return value
    if isinstance(value, str):
        return datetime.date.fromisoformat(value)
    return value


def seed_solution_characteristics(
    conn: Connection,
    engine: Engine,
    tables: dict[str, Table],
    enriched: list[EnrichedSolution],
    solution_ids: dict[str, int],
    log,
) -> dict:
    """Загрузка ТТХ в solution_characteristic + UPDATE зеркальных колонок solution.

    Аргументы:
      enriched: список EnrichedSolution из characteristics.json.
      solution_ids: карта external_id (строкой) → solution.id (int).

    Возвращает:
      {"eav_inserted": int, "eav_updated": int, "mirror_updated": int,
       "solutions_touched": int}
    """
    if not enriched:
        log("solution_characteristic: нет данных для загрузки")
        return {"eav_inserted": 0, "eav_updated": 0, "mirror_updated": 0,
                "solutions_touched": 0}

    # Карта characteristic_type.code → id
    ct_table = tables["characteristic_type"]
    code_to_id: dict[str, int] = {
        row.code: row.id
        for row in conn.execute(
            select(ct_table.c["code"], ct_table.c["id"])
        ).all()
    }
    if not code_to_id:
        raise SystemExit(
            "characteristic_type пуста - выполните seed_characteristic_types "
            "до seed_solution_characteristics"
        )

    # --- 1. UPSERT в solution_characteristic ---------------------------------
    eav_rows: list[dict] = []
    for sol in enriched:
        sol_id = solution_ids.get(sol.solution_external_id)
        if sol_id is None:
            log(f"  ПРЕДУПРЕЖДЕНИЕ: решение «{sol.solution_name}» "
                f"({sol.solution_external_id}) не найдено в solution - пропуск")
            continue
        for c in sol.characteristics:
            ct_id = code_to_id.get(c.code)
            if ct_id is None:
                log(f"  ПРЕДУПРЕЖДЕНИЕ: код «{c.code}» отсутствует в "
                    f"characteristic_type - пропуск")
                continue
            eav_rows.append({
                "solution_id": sol_id,
                "characteristic_type_id": ct_id,
                "value_numeric": c.value_numeric,
                "value_text": c.value_text,
                "value_bool": c.value_bool,
                "value_date": _to_date(c.value_date),
                "source_kind": c.source_kind,
                "source_url": c.source_url,
                "source_date": _to_date(c.source_date),
                "is_confirmed": c.is_confirmed,
            })

    inserted_eav, updated_eav = upsert(
        conn, engine, tables["solution_characteristic"], eav_rows,
        ["solution_id", "characteristic_type_id"],
        ["value_numeric", "value_text", "value_bool", "value_date",
         "source_kind", "source_url", "source_date", "is_confirmed"],
        log, "solution_characteristic",
    )

    # --- 2. UPDATE зеркальных колонок solution --------------------------------
    # Группируем по solution_id, чтобы сделать один UPDATE на решение.
    mirror_by_solution: dict[int, dict[str, float | str | bool | None]] = {}
    for sol in enriched:
        sol_id = solution_ids.get(sol.solution_external_id)
        if sol_id is None:
            continue
        for c in sol.characteristics:
            col = _MIRROR_CODE_TO_COLUMN.get(c.code)
            if col is None:
                continue  # не зеркальная характеристика
            value = (c.value_numeric if c.value_numeric is not None
                     else c.value_text if c.value_text is not None
                     else c.value_bool if c.value_bool is not None
                     else c.value_date)
            mirror_by_solution.setdefault(sol_id, {})[col] = value

    mirror_updated = 0
    if mirror_by_solution:
        sol_table = tables["solution"]
        for sol_id, cols in mirror_by_solution.items():
            # Только непустые значения; NULL не должен затирать другие источники.
            # Только 9 зеркальных ТТХ-колонок (data_model.md §5.8: «запись идёт
            # в оба места» - про значения ТТХ). Провенанс КАРТОЧКИ не трогаем:
            # source_kind='organizer_catalog' проставлен при импорте (§5.7),
            # изменение его на 'open_source' делало бы карточку внутренне
            # противоречивой (source_url остаётся «catalog_export_v4.csv»);
            # провенанс дозаполнения живёт в solution_characteristic.
            set_ = {col: val for col, val in cols.items() if val is not None}
            if not set_:
                continue
            set_["updated_at"] = func.now()
            stmt = update(sol_table).where(sol_table.c["id"] == sol_id).values(**set_)
            result = conn.execute(stmt)
            # rowcount: SQLite/PG - число затронутых строк; для UPDATE по id
            # это 0 или 1. Считаем 1 на решение (затронуто).
            mirror_updated += result.rowcount or 0

    log(f"characteristics: отправлено {len(eav_rows)}, вставлено {inserted_eav}, "
        f"обновлено {updated_eav}; solution mirror: обновлено {mirror_updated} "
        f"× {len(mirror_by_solution)} решений")

    return {
        "eav_inserted": inserted_eav,
        "eav_updated": updated_eav,
        "mirror_updated": mirror_updated,
        "solutions_touched": len(mirror_by_solution),
    }
