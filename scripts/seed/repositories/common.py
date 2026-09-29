"""Общие помощники репозиториев: диалектные INSERT..ON CONFLICT, счётчики, карты id."""
from typing import TypeAlias

from sqlalchemy import Table, func, select
from sqlalchemy.engine import Connection, Engine

from normalizers.strings import canonical_key

LogFn: TypeAlias = object  # фактически Callable[[str], None]


def dialect_insert(engine: Engine):
    """Конструктор диалектного INSERT (PostgreSQL / SQLite) с поддержкой ON CONFLICT."""
    name = engine.dialect.name
    if name == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
        return insert
    if name == "sqlite":
        from sqlalchemy.dialects.sqlite import insert
        return insert
    raise ValueError(f"неподдерживаемый диалект БД: {name} (ожидались postgresql/sqlite)")


def upsert(
    conn: Connection,
    engine: Engine,
    table: Table,
    rows: list[dict],
    index_elements: list[str],
    update_columns: list[str] | None = None,
    log=None,
    label: str = "",
) -> tuple[int, int]:
    """INSERT .. ON CONFLICT (index_elements) DO UPDATE | DO NOTHING.

    Возвращает (inserted, updated). inserted считается по разнице счётчиков
    строк таблицы до/после - работает одинаково на PostgreSQL и SQLite.
    """
    if not rows:
        if log:
            log(f"{label or table.name}: нет строк для вставки")
        return 0, 0
    before = conn.execute(select(func.count()).select_from(table)).scalar_one()

    insert_cls = dialect_insert(engine)
    stmt = insert_cls(table)
    if update_columns:
        set_ = {c: getattr(stmt.excluded, c) for c in update_columns}
        stmt = stmt.on_conflict_do_update(index_elements=index_elements, set_=set_)
    else:
        stmt = stmt.on_conflict_do_nothing(index_elements=index_elements)
    conn.execute(stmt, rows)

    after = conn.execute(select(func.count()).select_from(table)).scalar_one()
    inserted = after - before
    rest = len(rows) - inserted
    if log:
        # DO UPDATE: конфликтные строки реально обновлены; DO NOTHING - они
        # пропущены (существующее не трогаем, напр. пароли) - метки различаем.
        action = "обновлено" if update_columns else "пропущено"
        log(f"{label or table.name}: отправлено {len(rows)}, вставлено {inserted}, {action} {rest}")
    return inserted, rest


def load_id_map(conn: Connection, table: Table, value_column: str = "name") -> dict[str, int]:
    """Карта canonical_key(значение) -> id для резолва FK.

    Ключи канонические (lower + ё->е): сопоставление устойчиво к вариантам
    написания, при этом в БД имена хранятся в исходном виде.
    """
    result: dict[str, int] = {}
    for value, pk in conn.execute(select(table.c[value_column], table.c["id"])).all():
        key = canonical_key(value)
        if key in result:
            raise ValueError(
                f"в {table.name} несколько строк с каноническим ключом «{key}» "
                f"(например «{value}») - разрешить дубли до импорта"
            )
        result[key] = pk
    return result
