"""Тесты: разрезание «Сценария» на процессы (data_model.md §5.3)."""
from normalizers.processes import SCENARIO_EXCEPTIONS, split_scenarios


def test_split_by_comma_space():
    assert split_scenarios("Внесение веществ на поля, Мониторинг и анализ состояния посевов и полей") == [
        "Внесение веществ на поля",
        "Мониторинг и анализ состояния посевов и полей",
    ]


def test_trailing_space_does_not_create_duplicate_process():
    """«Сортировка грузов » и «Сортировка грузов» -> один процесс."""
    assert split_scenarios("Сортировка грузов ") == ["Сортировка грузов"]
    assert split_scenarios("Сортировка грузов") == ["Сортировка грузов"]


def test_exception_values_not_split():
    """«, » внутри названия одного процесса - не разрезаем (карта исключений)."""
    assert split_scenarios("Доставка в удаленные, труднодоступные районы") == \
        ["Доставка в удаленные, труднодоступные районы"]
    assert split_scenarios("Мониторинг, патрулирование, перевозка грузов") == \
        ["Мониторинг, патрулирование, перевозка грузов"]
    long_one = ("Выполнение контрольных мероприятий на этапе строительно-монтажных работ "
                "с использованием фотограмметрии, воздушного лазерного сканирования и тепловизионной съемки")
    assert split_scenarios(long_one) == [long_one]
    assert long_one in SCENARIO_EXCEPTIONS


def test_typo_fix_applied():
    """«Доставка биоматериаловм» -> «Доставка биоматериалов» (assumptions.md §10)."""
    msgs = []
    result = split_scenarios("Доставка биоматериаловм", log=msgs.append)
    assert result == ["Доставка биоматериалов"]
    assert any("опечатка" in m for m in msgs)


def test_empty_and_none():
    assert split_scenarios(None) == []
    assert split_scenarios("") == []
    assert split_scenarios("   ") == []
