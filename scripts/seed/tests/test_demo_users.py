"""Тесты демо-аккаунтов: создание, идемпотентность, несброс паролей.

Проверяется фактическое содержимое таблицы user после run_seed:
роли, формат bcrypt-хеша ($2b$, cost 12 - читается Spring Boot) и то,
 что повторный запуск не перезаписывает существующий хеш.

test_economics не создаётся - E2E-проекты
принадлежат user; легаси-аккаунт удаляется со всеми данными.
"""
import bcrypt
from sqlalchemy import select

from run import run_seed
from repositories.users_repo import BCRYPT_COST, DEMO_USERS


# Демо-пароли (admin/adminadmin, user/useruser - пароль не равен
# логину); источник правды - DEMO_USERS в users_repo.
DEMO_PASSWORDS: dict[str, str] = {login: password for login, password, _ in DEMO_USERS}

from tests.db_schema import meta


def _users(engine) -> dict[str, dict]:
    user_table = meta.tables["user"]
    with engine.connect() as conn:
        rows = conn.execute(
            select(user_table.c.login, user_table.c.password_hash, user_table.c.role)
        ).all()
    return {login: {"password_hash": pw, "role": role} for login, pw, role in rows}


def test_demo_users_created(engine):
    """Первый seed создаёт РОВНО два аккаунта сдачи:
    admin/admin + user/user - E2E-проекты принадлежат user."""
    run_seed(engine)

    users = _users(engine)
    assert set(users) == {"admin", "user"}
    assert users["admin"]["role"] == "admin"
    assert users["user"]["role"] == "user"
    # E2E-проекты созданы под владельцем user
    with engine.connect() as conn:
        project = meta.tables["project"]
        user_t = meta.tables["user"]
        owner_login = conn.execute(
            select(user_t.c.login)
            .join(project, project.c.user_id == user_t.c.id)
            .where(project.c.name == "E2E: Максимум - 1 решение")
        ).scalar_one()
    assert owner_login == "user"


def test_legacy_test_economics_removed_with_data(engine):
    """На БД старой версии seed test_economics удаляется
    вместе с его проектами (FK-каскад), E2E-проекты пересоздаются
    под user; повторный запуск - no-op."""
    run_seed(engine)
    user_table = meta.tables["user"]
    project_table = meta.tables["project"]

    # имитируем легаси-состояние: test_economics + его проект
    with engine.begin() as conn:
        legacy_hash = bcrypt.hashpw(
            b"test_economics",
            bcrypt.gensalt(rounds=BCRYPT_COST, prefix=b"2b"),
        ).decode("ascii")
        conn.execute(
            user_table.insert().values(
                login="test_economics", password_hash=legacy_hash, role="user")
        )
        legacy_id = conn.execute(
            select(user_table.c.id).where(user_table.c.login == "test_economics")
        ).scalar_one()
        # переносим один E2E-проект на легаси-аккаунт - после чистки он
        # каскадно удалится вместе с владельцем и будет пересоздан под user
        conn.execute(
            project_table.update()
            .where(project_table.c.name == "E2E: Пиковая нагрузка")
            .values(user_id=legacy_id)
        )

    run_seed(engine)  # seed с чисткой легаси

    users = _users(engine)
    assert set(users) == {"admin", "user"}, (
        "легаси test_economics должен быть удалён (фикс A15)"
    )
    # проект вернулся под user (пересоздан после удаления легаси-владельца)
    with engine.connect() as conn:
        owner = conn.execute(
            select(meta.tables["user"].c.login)
            .join(project_table,
                  project_table.c.user_id == meta.tables["user"].c.id)
            .where(project_table.c.name == "E2E: Пиковая нагрузка")
        ).scalar_one()
    assert owner == "user"
    # повторный запуск - чистка no-op, счётчики стабильны
    run_seed(engine)
    assert set(_users(engine)) == {"admin", "user"}


def test_demo_users_bcrypt_hash_format(engine):
    """Хеши - bcrypt $2b$ cost 12: формат, который читает Spring Boot."""
    run_seed(engine)

    users = _users(engine)
    for login, info in users.items():
        password_hash = info["password_hash"]
        assert password_hash.startswith(f"$2b${BCRYPT_COST}$"), (
            f"{login}: хеш не bcrypt $2b$ cost {BCRYPT_COST}: {password_hash[:7]}..."
        )
        # хеш соответствует демо-паролю (DEMO_USERS - источник правды:
        # пароль не равен логину)
        expected_password = DEMO_PASSWORDS[login].encode("utf-8")
        assert bcrypt.checkpw(expected_password, password_hash.encode("ascii")), (
            f"{login}: хеш не соответствует демо-паролю"
        )
        # в хеше нет открытого пароля
        assert login not in password_hash


def test_demo_users_idempotent_password_not_reset(engine):
    """Повторный seed не создаёт дублей и НЕ сбрасывает пароль.

    Сценарий: после первого запуска пароль admin'а сменили (как в БД
    при реальной эксплуатации) - второй запуск обязан оставить хеш как есть
    (ON CONFLICT (login) DO NOTHING).
    """
    run_seed(engine)
    user_table = meta.tables["user"]

    # «кто-то сменил пароль админа» - кладём другой валидный bcrypt-хеш
    changed_hash = bcrypt.hashpw(
        b"new-secret-password", bcrypt.gensalt(rounds=BCRYPT_COST, prefix=b"2b")
    ).decode("ascii")
    with engine.begin() as conn:
        conn.execute(
            user_table.update().where(user_table.c.login == "admin")
            .values(password_hash=changed_hash)
        )

    run_seed(engine)  # повторный запуск seed

    users = _users(engine)
    assert len(users) == 2, "после повторного запуска не должно быть новых строк"
    assert users["admin"]["password_hash"] == changed_hash, (
        "повторный seed СБРОСИЛ пароль admin - нарушена идемпотентность"
    )
    # user не тронут: хеш по-прежнему проходит проверку демо-пароля
    assert bcrypt.checkpw(
        DEMO_PASSWORDS["user"].encode("utf-8"),
        users["user"]["password_hash"].encode("ascii"),
    )
