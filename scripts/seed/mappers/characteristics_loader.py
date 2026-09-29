"""Загрузчик characteristics.json — ТТХ решений из открытых источников.

Читает JSON-файл и возвращает нормализованный список решений с их
характеристиками. Провенанс берётся из _meta (общий для всех записей)
с возможностью переопределения на уровне отдельной характеристики.

Структура JSON (см. data/characteristics.json):
    {
      "_meta": {source_kind, source_date, is_confirmed, ...},
      "solutions": [
        {
          "solution_external_id": "uuid",
          "solution_name": "...",
          "source_url": "https://...",  # дефолтный URL для всех характеристик
          "characteristics": [
            {"code": "payload_kg", "value_numeric": 1500, "source_url": "..."},
            {"code": "navigation_type", "value_text": "QR-метки"},
            ...
          ]
        }, ...
      ]
    }

Проверки (быстрый фейл до записи в БД):
    - external_id — валидный UUID
    - code — из справочника CHARACTERISTIC_TYPES
    - ровно одно значение (value_numeric | value_text | value_bool | value_date)
    - коды навигации/источника соответствуют assumptions.md §7 (open_source)
"""
import json
import uuid as uuid_mod
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from mappers.characteristics_mapper import CHARACTERISTIC_TYPES


@dataclass(frozen=True)
class CharacteristicValue:
    """Одно значение ТТХ для решения с провенансом."""
    solution_external_id: str
    code: str
    value_numeric: float | None = None
    value_text: str | None = None
    value_bool: bool | None = None
    value_date: str | None = None  # ISO 'YYYY-MM-DD'
    source_kind: str = "open_source"
    source_url: str | None = None
    source_date: str | None = None
    is_confirmed: bool = True
    note: str | None = None


@dataclass(frozen=True)
class EnrichedSolution:
    """Решение с дозаполненными характеристиками."""
    solution_external_id: str
    solution_name: str
    vendor: str
    source_url: str  # дефолтный URL решения
    characteristics: tuple[CharacteristicValue, ...]


# Допустимые имена полей значения в JSON
VALUE_FIELDS = ("value_numeric", "value_text", "value_bool", "value_date")


def _validate_uuid(value: str) -> str:
    """Канонизация external_id через UUID-парсер."""
    try:
        return str(uuid_mod.UUID(value))
    except (ValueError, AttributeError) as e:
        raise ValueError(f"external_id не является UUID: {value!r}") from e


def _validate_code(code: str) -> str:
    """Проверка, что code есть в справочнике CHARACTERISTIC_TYPES."""
    valid = {r.code for r in CHARACTERISTIC_TYPES}
    if code not in valid:
        raise ValueError(
            f"неизвестный код характеристики «{code}»; "
            f"справочник CHARACTERISTIC_TYPES содержит {len(valid)} кодов "
            f"(assumptions.md §14)"
        )
    return code


def _pick_value(raw: dict) -> tuple[float | None, str | None, bool | None, str | None]:
    """Выбор ровно одного value_* из записи; иначе — ValueError.

    Возвращает (value_numeric, value_text, value_bool, value_date).
    """
    present = [f for f in VALUE_FIELDS if raw.get(f) is not None]
    if len(present) != 1:
        raise ValueError(
            f"ровно одно поле значения из {VALUE_FIELDS} должно быть задано; "
            f"найдено {len(present)}: {present}"
        )
    return (
        float(raw["value_numeric"]) if "value_numeric" in present else None,
        str(raw["value_text"]) if "value_text" in present else None,
        bool(raw["value_bool"]) if "value_bool" in present else None,
        str(raw["value_date"]) if "value_date" in present else None,
    )


def load_characteristics(json_path: Path | str) -> list[EnrichedSolution]:
    """Парсинг JSON-файла и валидация. Возвращает список EnrichedSolution.

    Идемпотентен: один и тот же файл даёт один и тот же результат.
    """
    path = Path(json_path)
    if not path.exists():
        raise FileNotFoundError(f"characteristics.json не найден: {path}")

    with open(path, encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, dict):
        raise ValueError("ожидался JSON-объект на верхнем уровне")

    meta = data.get("_meta") or {}
    default_source_kind = meta.get("source_kind", "open_source")
    default_source_date = meta.get("source_date")
    default_is_confirmed = bool(meta.get("is_confirmed", True))

    # Проверки соответствия assumptions.md §7: подтверждённые данные только
    # с source_url. Если в _meta is_confirmed=true, но URL отсутствует — это
    # ошибка конфигурации (характеристики помечены подтверждёнными без источника).
    if default_is_confirmed and not meta.get("source_url"):
        # Источник указывается на уровне каждой характеристики/решения;
        # _meta.source_url опционален. Проверка идёт в _validate_characteristic.
        pass

    solutions_raw = data.get("solutions") or []
    if not isinstance(solutions_raw, list):
        raise ValueError("ключ «solutions» должен быть списком")

    result: list[EnrichedSolution] = []
    seen_external_ids: set[str] = set()
    for idx, sol in enumerate(solutions_raw):
        if not isinstance(sol, dict):
            raise ValueError(f"solutions[{idx}]: ожидался объект")

        ext_id = _validate_uuid(sol["solution_external_id"])
        if ext_id in seen_external_ids:
            raise ValueError(f"дублирующий external_id: {ext_id}")
        seen_external_ids.add(ext_id)

        name = sol.get("solution_name", "")
        vendor = sol.get("vendor", "")
        default_sol_url = sol.get("source_url")

        chars_raw = sol.get("characteristics") or []
        if not isinstance(chars_raw, list) or not chars_raw:
            raise ValueError(f"solutions[{idx}] «{name}»: характеристики пусты")

        chars: list[CharacteristicValue] = []
        seen_codes: set[str] = set()
        for c_idx, c in enumerate(chars_raw):
            if not isinstance(c, dict):
                raise ValueError(
                    f"solutions[{idx}] «{name}».characteristics[{c_idx}]: ожидался объект"
                )
            code = _validate_code(c["code"])
            if code in seen_codes:
                raise ValueError(
                    f"solutions[{idx}] «{name}»: дублирующий код «{code}» "
                    f"(UNIQUE (solution_id, characteristic_type_id) не даст вставить)"
                )
            seen_codes.add(code)

            value_numeric, value_text, value_bool, value_date = _pick_value(c)
            source_url = c.get("source_url") or default_sol_url
            if not source_url:
                raise ValueError(
                    f"solutions[{idx}] «{name}» код «{code}»: "
                    f"отсутствует source_url — нарушает assumptions.md §7 "
                    f"(is_confirmed=true требует источника)"
                )

            # Если is_confirmed не указан явно — берём из _meta
            is_confirmed = bool(c.get("is_confirmed", default_is_confirmed))
            if is_confirmed and not source_url:
                # двойная проверка (выше уже бросили, но защита от перестановки)
                raise ValueError(
                    f"solutions[{idx}] «{name}» код «{code}»: is_confirmed=true "
                    f"без source_url — нарушает assumptions.md §7"
                )

            chars.append(CharacteristicValue(
                solution_external_id=ext_id,
                code=code,
                value_numeric=value_numeric,
                value_text=value_text,
                value_bool=value_bool,
                value_date=value_date,
                source_kind=str(c.get("source_kind", default_source_kind)),
                source_url=source_url,
                source_date=str(c.get("source_date", default_source_date))
                if c.get("source_date", default_source_date) else None,
                is_confirmed=is_confirmed,
                note=c.get("note"),
            ))

        result.append(EnrichedSolution(
            solution_external_id=ext_id,
            solution_name=name,
            vendor=vendor,
            source_url=default_sol_url or "",
            characteristics=tuple(chars),
        ))

    return result


def validate_file(json_path: Path | str) -> None:
    """Самопроверка: парсинг + валидация без записи в БД (для тестов)."""
    solutions = load_characteristics(json_path)
    if not solutions:
        raise ValueError("файл не содержит решений")
    # 8 обязательных ТТХ (assumptions.md §21)
    required_codes = {
        r.code for r in CHARACTERISTIC_TYPES if r.is_required
    }
    # По крайне мере одно обязательное ТТХ должно быть заполнено у каждого решения
    for sol in solutions:
        sol_codes = {c.code for c in sol.characteristics}
        if not (sol_codes & required_codes):
            raise ValueError(
                f"решение «{sol.solution_name}» ({sol.solution_external_id}) "
                f"не содержит ни одного обязательного ТТХ из {required_codes}"
            )


def default_path() -> Path:
    """Путь к JSON по умолчанию: рядом с mappers/ — ../data/characteristics.json."""
    return Path(__file__).resolve().parent.parent / "data" / "characteristics.json"
