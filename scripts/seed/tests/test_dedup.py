"""Тесты: дедупликация каталога по external_id и справочники (реальный CSV)."""
from decimal import Decimal

from normalizers.strings import canonical_key


def test_dedup_by_external_id(catalog_data):
    """223 строки CSV -> 187 решений, 23 группы дублей (data_model.md §5.1)."""
    st = catalog_data.stats
    assert st["csv_rows"] == 223
    assert st["solutions"] == 187
    assert st["dup_groups"] == 23
    assert len({s.external_id for s in catalog_data.solutions}) == 187


def test_dictionary_counts(catalog_data):
    """Справочники из фактических значений CSV (критерии приёмки)."""
    st = catalog_data.stats
    assert st["industries"] == 9
    assert st["vendors"] == 103
    assert st["regions"] == 24
    assert st["solution_types"] == 11  # включая «Мобильные манипуляторы» (нет в assumptions §4)


def test_price_min_policy_for_duplicate_groups(catalog_data):
    """price_rub = min цен группы; все цены строки -> offer_price_rub (§5.5)."""
    by_ext = {}
    for a in catalog_data.applications:
        by_ext.setdefault(a.external_id, []).append(a.offer_price_rub)
    for s in catalog_data.solutions:
        offers = by_ext.get(s.external_id, [])
        assert s.price_rub == min(offers), f"у {s.external_id} price_rub != min(offer_price_rub)"
        assert isinstance(s.price_rub, Decimal)


def test_description_longest_non_empty(catalog_data):
    for s in catalog_data.solutions:
        if s.description is not None:
            assert len(s.description) > 0


def test_applications_grain(catalog_data):
    """Зерно применения = (решение, отрасль, процесс); дубли ключа отсутствуют."""
    keys = [(a.external_id, canonical_key(a.industry), canonical_key(a.process))
            for a in catalog_data.applications]
    assert len(keys) == len(set(keys))
    # 223 строки CSV, разрезание мульти-сценариев даёт 250 применений
    assert catalog_data.stats["applications"] == 250
    # каждый процесс применения есть в справочнике процессов
    proc_keys = {canonical_key(p) for p in catalog_data.processes}
    assert all(canonical_key(a.process) in proc_keys for a in catalog_data.applications)


def test_cases_m2m(catalog_data):
    """Кейсы: уникальные тексты + связи «решение -> кейс» без дублей."""
    assert catalog_data.stats["cases"] == 175
    links = catalog_data.case_links
    assert len(links) == len(set(links))
    case_names = {canonical_key(c) for c in catalog_data.cases}
    assert all(canonical_key(name) in case_names for _, name in links)


def test_mandatory_fields_present(catalog_data):
    """NOT NULL-поля решения заполнены для всех 187 строк."""
    for s in catalog_data.solutions:
        assert s.name and s.vendor and s.product_class and s.status
        assert s.price_rub is not None
        assert s.trl is not None  # УГТ заполнен во всех строках CSV
