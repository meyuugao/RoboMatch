"""Тесты: нормализация строк (assumptions.md §10, data_model.md «Нормализация строк»)."""
from normalizers.strings import assign_codes, canonical_key, norm_text, slugify


def test_trim_and_duplicate_spaces():
    assert norm_text(" Калужская обл ") == "Калужская обл"
    assert norm_text("Сортировка  грузов") == "Сортировка грузов"
    assert norm_text("объект   с    пробелами") == "объект с пробелами"


def test_newline_and_nbsp_collapse():
    raw = "Контроль состояния складов и отвалов горнодобывающих компаний \nс использованием фотограмметрии"
    assert "\n" not in norm_text(raw)
    assert norm_text(raw).endswith("компаний с использованием фотограмметрии")
    assert norm_text("Да\u00a0") == "Да"


def test_dashes_to_hyphen():
    assert norm_text("Интеллектуальный автобус - вендинговый аппарат") == \
        "Интеллектуальный автобус - вендинговый аппарат"
    assert norm_text("Кран–штабелёр") == "Кран-штабелёр"


def test_yo_only_for_key_not_storage():
    """ё -> е только для поиска/дедупликации, отображение хранит ё (§10)."""
    assert canonical_key("Робот-штабелёр") == canonical_key("Робот-штабелер")
    assert canonical_key("Объём") == "объем"
    assert norm_text("Робот-штабелёр") == "Робот-штабелёр"  # в БД - как в данных


def test_empty_to_none():
    assert norm_text("") is None
    assert norm_text("   ") is None
    assert norm_text(None) is None


def test_slugify_deterministic():
    assert slugify("Торговля и услуги") == "torgovlya_i_uslugi"
    assert slugify("ТЭК") == "tek"
    assert slugify("БАС") == "bas"  # регистр не влияет на код
    assert slugify("") is None


def test_assign_codes_resolves_collisions():
    """На вход - канонические ключи; слаг-коллизии получают суффикс."""
    codes = assign_codes(["бас"])
    assert codes == {"бас": "bas"}
    codes = assign_codes(["а-б", "а б"])
    assert codes["а-б"] == "a_b"     # дефис -> подчёркивание
    assert codes["а б"] == "a_b_2"   # слаг-коллизия -> суффикс
    # разные ключи, одинаковый слаг -> суффикс _2
    codes = assign_codes(["сталь-1", "сталь 1"])
    assert codes["сталь-1"] == "stal_1"
    assert codes["сталь 1"] == "stal_1_2"
