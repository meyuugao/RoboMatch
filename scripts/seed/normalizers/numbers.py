"""Парсинг чисел из «грязных» строковых значений CSV/XLSX.

data_model.md §5.5-5.6: цена «2 700 000,00» -> numeric; раздельный парсинг
десятичных разделителей (пробел-тысячи, запятая-десятичные в CSV; точка в XLSX).
"""
from decimal import Decimal, InvalidOperation

from .strings import norm_text


def parse_decimal(value) -> Decimal | None:
    """Любое числоподобное значение -> Decimal; '-', '', None, NaN -> None.

    Возвращает None вместо исключения: вызывающий код решает, критично ли
    отсутствие числа (цена карточки — критично, min/max параметра — нет).
    """
    if value is None:
        return None
    if isinstance(value, Decimal):
        return value
    if isinstance(value, bool):  # openpyxl иногда отдаёт bool для числовых ячеек
        return None
    if isinstance(value, (int, float)):
        return None if value != value else Decimal(str(value))
    s = norm_text(value)
    if s is None or s == "-":
        return None
    s = s.replace(" ", "").replace(",", ".")
    try:
        return Decimal(s)
    except InvalidOperation:
        return None


def parse_int(value, lo: int | None = None, hi: int | None = None) -> int | None:
    """Целое с проверкой диапазона (например, trl 1..9, market_potential 2..5).

    Бросает ValueError при нецелом значении или выходе за границы —
    такие данные нельзя молча исправить, импорт должен упасть с контекстом.
    """
    d = parse_decimal(value)
    if d is None:
        return None
    if d != d.to_integral_value():
        raise ValueError(f"ожидалось целое число, получено {value!r}")
    i = int(d)
    if (lo is not None and i < lo) or (hi is not None and i > hi):
        raise ValueError(f"значение {i} вне диапазона [{lo}, {hi}] ({value!r})")
    return i
