"""Тесты: нормализация цены и чисел (запятая -> точка, пробелы-тысячи)."""
from decimal import Decimal

import pytest

from normalizers.numbers import parse_decimal, parse_int


def test_price_thousands_space_and_comma():
    """«2 700 000,00» -> 2700000.00 (data_model.md §5.5)."""
    assert parse_decimal("2 700 000,00") == Decimal("2700000.00")
    assert parse_decimal("950 000,00") == Decimal("950000.00")
    assert parse_decimal("1 400 000,00") == Decimal("1400000.00")


def test_price_plain_forms():
    assert parse_decimal("4") == Decimal("4")
    assert parse_decimal("4.00") == Decimal("4.00")
    assert parse_decimal("4,00") == Decimal("4.00")


def test_price_xlsx_numeric_cells():
    """XLSX (openpyxl) отдаёт числа как int/float, а не строки."""
    assert parse_decimal(20000) == Decimal("20000")
    assert parse_decimal(1.302) == Decimal("1.302")
    assert parse_decimal(-25) == Decimal("-25")
    assert parse_decimal(-40) == Decimal("-40")


def test_price_absent_values():
    """Отсутствие значения = NULL: None/''/'-'/NaN -> None (data_model.md)."""
    assert parse_decimal(None) is None
    assert parse_decimal("") is None
    assert parse_decimal("-") is None
    assert parse_decimal(" ") is None


def test_price_unparseable_returns_none():
    """Нечисловое значение не валит парсер: решение принимает вызывающий код."""
    assert parse_decimal("Да") is None
    assert parse_decimal("1С:ERP") is None
    assert parse_decimal("1200×800×1600") is None


def test_parse_int_with_range():
    assert parse_int("8", lo=1, hi=9) == 8
    assert parse_int("3", lo=1, hi=9) == 3


def test_parse_int_rejects_fraction_and_out_of_range():
    with pytest.raises(ValueError):
        parse_int("9.5", lo=1, hi=9)
    with pytest.raises(ValueError):
        parse_int("10", lo=1, hi=9)  # trl CHECK 1..9
    with pytest.raises(ValueError):
        parse_int("1", lo=2, hi=5)  # market_potential CHECK 2..5
