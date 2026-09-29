"""Демо-аккаунты для сдачи (requirements/acceptance.md: демо-аккаунты
user/admin создаются автоматически).

Свойства:
  - пароли хешируются bcrypt cost 12 с префиксом $2b$ - ровно тот формат,
    который читает Spring BCryptPasswordEncoder (единый cost с backend);
  - идемпотентность: INSERT .. ON CONFLICT (login) DO NOTHING - повторный
    запуск НЕ трогает существующие аккаунты и НЕ сбрасывает пароли
    (даже если пароль демо-аккаунта сменили через БД);
  - реальные ПДн не используются (AGENTS.md §9: только синтетика).

Отдельный пользователь test_economics не используется - 5 E2E-проектов
«E2E: *» (test_projects_repo) принадлежат демо-аккаунту user; на БД,
засеянной старой версией seed, легаси-аккаунт удаляется вместе с его
данными (drop_legacy_test_user).
"""
import bcrypt
from sqlalchemy import Table, delete, select
from sqlalchemy.engine import Connection, Engine

from .common import upsert

# (login, password, role): роль admin выдаётся ТОЛЬКО здесь - регистрация
# через API всегда создаёт роль user (см. UserService.register, backend).
# user - демо-аккаунт сдачи И владелец проектов «E2E: *» (в старых
# версиях seed владельцем был отдельный test_economics - унифицирован).
DEMO_USERS: tuple[tuple[str, str, str], ...] = (
    ("admin", "adminadmin", "admin"),
    ("user", "useruser", "user"),
)

# Легаси-аккаунт E2E-верификации старых версий seed - удаляется при наличии
LEGACY_LOGIN = "test_economics"

BCRYPT_COST = 12  # тот же cost, что у BCryptPasswordEncoder(12) в backend


def _hash_password(password: str) -> str:
    """bcrypt-хеш с префиксом $2b$ (python-bcrypt) - читается backend'ом."""
    salt = bcrypt.gensalt(rounds=BCRYPT_COST, prefix=b"2b")
    return bcrypt.hashpw(password.encode("utf-8"), salt).decode("ascii")


def seed_demo_users(conn: Connection, engine: Engine, tables: dict[str, Table], log) -> int:
    """Создать демо-аккаунты, если их ещё нет. Возвращает число вставленных."""
    rows = [
        {
            "login": login,
            "password_hash": _hash_password(password),
            "role": role,
        }
        for login, password, role in DEMO_USERS
    ]
    # update_columns=None => DO NOTHING: существующие логины не обновляются,
    # пароли не сбрасываются (требование идемпотентности).
    inserted, _skipped = upsert(
        conn, engine, tables["user"], rows, ["login"], None, log, "users"
    )
    return inserted


def drop_legacy_test_user(conn: Connection, tables: dict[str, Table], log) -> bool:
    """Удалить легаси-пользователя test_economics со всеми его данными
    (E2E-проекты «E2E: *» принадлежат user).

    На свежей БД - no-op (аккаунта нет). На БД, засеянной старой версией
    seed, удаляет аккаунт и его проекты/сценарии/расчёты - большинство
    связей каскадятся (project.user_id ON DELETE CASCADE); ссылки на
    АВТОРА без каскада (manual_adjustment.author_user_id,
    export.created_by_user_id) чистятся явно. Идемпотентно: повторный
    вызов ничего не находит. Возвращает True, если аккаунт был удалён.
    """
    user_tbl = tables["user"]
    legacy_id = conn.execute(
        select(user_tbl.c["id"]).where(user_tbl.c["login"] == LEGACY_LOGIN)
    ).scalar_one_or_none()
    if legacy_id is None:
        return False
    # ссылки на автора без ON DELETE CASCADE
    if "manual_adjustment" in tables:
        conn.execute(delete(tables["manual_adjustment"]).where(
            tables["manual_adjustment"].c["author_user_id"] == legacy_id))
    if "export" in tables:
        conn.execute(delete(tables["export"]).where(
            tables["export"].c["created_by_user_id"] == legacy_id))
    # проект и всё ниже - каскадом (project.user_id ON DELETE CASCADE)
    conn.execute(delete(user_tbl).where(user_tbl.c["id"] == legacy_id))
    log(f"legacy: {LEGACY_LOGIN} (id {legacy_id}) удалён вместе с его "
        f"данными - E2E-проекты создаёт seed заново под владельцем user (A15)")
    return True
