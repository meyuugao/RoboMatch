"""Конфигурация seed-скрипта: пути к файлам организатора и подключение к БД.

Секреты - из переменных окружения или из .env (корень проекта, затем scripts/seed).
"""
import os
from pathlib import Path

# Корень проекта: scripts/seed/config.py -> ../../
#
# В docker-контейнере (scripts/seed/Dockerfile) код лежит в /seed/config.py -
# уровней выше нет, parents[2] бросил бы IndexError и seed падал бы на старте.
# Поэтому: есть третий уровень - считаем его корнем репозитория; нет (контейнер) -
# берём корень файловой системы «/»: все пути всё равно задаются env-переменными
# docker-compose (DATABASE_URL, CATALOG_CSV, OBJECTS_XLSX), а characteristics.json
# ищется относительно SEED_DIR (см. _resolve_characteristics).
_PARENTS = Path(__file__).resolve().parents
PROJECT_ROOT = _PARENTS[2] if len(_PARENTS) > 2 else _PARENTS[-1]
SEED_DIR = Path(__file__).resolve().parent


def _load_dotenv():
    """Загружает .env из корня проекта или из scripts/seed, не перезатирая env."""
    try:
        from dotenv import load_dotenv
    except ImportError:
        return  # python-dotenv не установлен - работаем только на env

    for candidate in (PROJECT_ROOT / ".env", SEED_DIR / ".env"):
        if candidate.exists():
            load_dotenv(candidate, override=False)


_load_dotenv()

# --- БД -------------------------------------------------------------------
DATABASE_URL = os.environ.get("DATABASE_URL")


def require_database_url() -> str:
    if not DATABASE_URL:
        raise SystemExit(
            "DATABASE_URL не задан.\n"
            "Задайте в .env (корень проекта или scripts/seed):\n"
            "  DATABASE_URL=postgresql+psycopg2://user:pass@localhost:5432/dbname\n"
            "или через переменную окружения:\n"
            "  set DATABASE_URL=postgresql+psycopg2://user:pass@localhost:5432/dbname"
        )
    return DATABASE_URL

# --- Файлы организатора ---------------------------------------------------
_CATALOG_CANDIDATES = (
    "data/organizer/catalog_export_v4.csv",
    "docs/source/catalog_export_v4.csv",
)
_OBJECTS_CANDIDATES = (
    "data/organizer/Датасеты_хакатон.xlsx",
    "docs/source/Датасеты_хакатон.xlsx",
)


def _resolve(env_var: str, candidates: tuple[str, ...]) -> Path:
    override = os.environ.get(env_var)
    if override:
        return Path(override)
    for rel in candidates:
        path = PROJECT_ROOT / rel
        if path.exists():
            return path
    return PROJECT_ROOT / candidates[0]


CATALOG_CSV_PATH = _resolve("CATALOG_CSV", _CATALOG_CANDIDATES)
OBJECTS_XLSX_PATH = _resolve("OBJECTS_XLSX", _OBJECTS_CANDIDATES)

SOURCE_KIND = "organizer_catalog"
CATALOG_SOURCE_URL = "catalog_export_v4.csv"
OBJECTS_SOURCE_URL = "Датасеты_хакатон.xlsx"

# --- Дозаполнение ТТХ из открытых источников -----------
# JSON с найденными ТТХ и провенансом; путь по умолчанию - рядом с seed.
# Кандидаты ищутся и от корня проекта (локальный запуск из репозитория),
# и от каталога самого seed (docker-контейнер: WORKDIR /seed, код скопирован
# целиком - data/characteristics.json лежит рядом с config.py).
_CHARACTERISTICS_CANDIDATES = (
    "scripts/seed/data/characteristics.json",  # запуск из репозитория
    "data/characteristics.json",               # внутри seed-контейнера
)


def _resolve_characteristics(env_var: str = "CHARACTERISTICS_JSON") -> Path | None:
    """Путь к JSON с ТТХ. None - если файл отсутствует (seed не падает).

    Переопределяется через env CHARACTERISTICS_JSON. По умолчанию ищется
    относительно корня проекта. Если ни один кандидат не существует -
    возвращает первый (Path-объект, для логов), но загрузчик сам решит,
    что делать с отсутствующим файлом.
    """
    override = os.environ.get(env_var)
    if override:
        return Path(override)
    for rel in _CHARACTERISTICS_CANDIDATES:
        for root in (PROJECT_ROOT, SEED_DIR):
            path = root / rel
            if path.exists():
                return path
    # Возвращаем дефолтный путь (он не существует - load_characteristics
    # в FileNotFoundError, но в run.py мы сначала проверяем существование).
    return PROJECT_ROOT / _CHARACTERISTICS_CANDIDATES[0]


CHARACTERISTICS_JSON_PATH = _resolve_characteristics()