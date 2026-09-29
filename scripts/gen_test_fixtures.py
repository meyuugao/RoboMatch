#!/usr/bin/env python3
"""Генератор тестовых фикстур каталога (docs/test-fixtures).

Создаёт ДВА независимых набора решений - full_catalog_a.xlsx (Excel) и
full_catalog_b.csv (CSV, «;», UTF-8 BOM) - в расширенном формате
импорта админки: 15 колонок таблицы организатора + 27 колонок ТТХ
(код characteristic_type как заголовок). Каждый набор: 4 решения,
полностью заполненные ТТХ (все 27), провенанс фикстуры - организатор
не участвовал, файл создан командой для проверок импорта и E2E.

Фикстуры НЕ попадают в seed: единственный путь в БД - админка
(POST /api/admin/catalog/import, docs/test-fixtures/README.md).

Запуск (idempotent, байты стабильны):
    python3 scripts/gen_test_fixtures.py
"""
from __future__ import annotations

import csv
import uuid
from pathlib import Path

from openpyxl import Workbook

REPO = Path(__file__).resolve().parent.parent
OUT_DIR = REPO / "docs" / "test-fixtures"

SOURCE_URL = "https://github.com/meyuugao/LCT_Hakaton"
SOURCE_DATE = "2026-09-26"
CONFIRMED = "подтверждено"

# 15 базовых колонок таблицы организатора (docs/source/catalog_export_v4.csv)
BASE_COLUMNS = [
    "id", "Название", "тип", "статус", "компания", "описание", "Тип",
    "Подтип", "Сценарий", "Кейсы", "УГТ", "Рын Потенциал", "Регион",
    "Отрасль", "Цена изделия",
]

# 27 кодов ТТХ в порядке справочника (scripts/seed/mappers/
# characteristics_mapper.py, assumptions.md §14)
CHAR_CODES = [
    "country_of_origin", "navigation_type",
    "payload_kg", "mass_kg", "length_mm", "width_mm", "height_mm",
    "positioning_accuracy_mm", "speed_m_s", "charging_power_kw",
    "noise_level_dba",
    "autonomy_h", "productivity", "operating_conditions", "lifecycle_years",
    "floor_coverage_req", "communication_req", "integration_req",
    "service_req",
    "software_cost", "implementation_cost", "maintenance_cost",
    "acquisition_model",
    "limitations",
    "source", "source_date", "confirmation_status",
]

COLUMNS = BASE_COLUMNS + CHAR_CODES


def uid(prefix: str, n: int) -> str:
    """Стабильный UUID фикстуры: 8-4-4-4-12 (валидный формат v4).
    prefix - 4 шестнадцатеричных знака (aaaa для A, bbbb для B)."""
    return f"{prefix}{n:04d}-4000-8000-0000-00000000000{n}"


def row(ext: str, name: str, product_class: str, status: str, vendor: str,
        description: str, sol_type: str, subtype: str, scenario: str,
        cases: str, trl: str, market: str, region: str, industry: str,
        price: str, chars: dict[str, object]) -> dict[str, object]:
    """Строка фикстуры: 15 базовых полей + все 27 ТТХ (без пропусков)."""
    full = {c: "" for c in COLUMNS}
    full.update({
        "id": ext, "Название": name, "тип": product_class, "статус": status,
        "компания": vendor, "описание": description, "Тип": sol_type,
        "Подтип": subtype, "Сценарий": scenario, "Кейсы": cases, "УГТ": trl,
        "Рын Потенциал": market, "Регион": region, "Отрасль": industry,
        "Цена изделия": price,
    })
    for code in CHAR_CODES:
        full[code] = chars[code]
    return full


def provenance() -> dict[str, str]:
    """Провенанс фикстуры: источник = репозиторий команды, дата, подтверждено."""
    return {"source": SOURCE_URL, "source_date": SOURCE_DATE,
            "confirmation_status": CONFIRMED}


# ---------------------------------------------------------------- Фикстура A
# Вендор «ООО Роботест», отрасль «Транспорт и логистика» (склад), 4 решения.
FIXTURE_A: list[dict[str, object]] = [
    row(
        uid("aaaa", 1), "РТ-Паллета 1500", "brs", "operation",
        "ООО Роботест",
        "Автономный погрузчик паллет с лидарной навигацией: транспортировка "
        "тяжёлых паллет между зонами приёмки, хранения и отгрузки, "
        "автоматическая зарядка без участия оператора.",
        "Мобильные роботы", "AMR",
        "Внутрискладская логистика, Погрузка-разгрузка",
        "Ретейл-склад 40 000 м2: 12 роботов, окупаемость 3,1 года",
        "9", "4", "Москва", "Транспорт и логистика", "2 990 000,00",
        {
            "country_of_origin": "Россия",
            "navigation_type": "лидарная SLAM",
            "payload_kg": "1500",
            "mass_kg": "1350",
            "length_mm": "2050",
            "width_mm": "1150",
            "height_mm": "2200",
            "positioning_accuracy_mm": "10",
            "speed_m_s": "2.0",
            "charging_power_kw": "9.5",
            "noise_level_dba": "68",
            "autonomy_h": "8",
            "productivity": "до 60 паллет/час",
            "operating_conditions": "от -25 до +45 °C, влажность до 85%",
            "lifecycle_years": "7",
            "floor_coverage_req": "ровный бетон, неровности до 10 мм/2 м",
            "communication_req": "Wi-Fi 5 ГГц, LTE (опция)",
            "integration_req": "WMS через REST API",
            "service_req": "плановое ТО раз в 6 месяцев",
            "software_cost": "350000",
            "implementation_cost": "600000",
            "maintenance_cost": "240000",
            "acquisition_model": "покупка или RaaS",
            "limitations": "уклон пола до 3°",
            **provenance(),
        },
    ),
    row(
        uid("aaaa", 2), "РТ-Комплект 800", "brs", "operation",
        "ООО Роботест",
        "Мобильный робот комплектации: доставка штучных заказов к столам "
        "отбора, навигация по QR-меткам, интеграция с WMS.",
        "Мобильные роботы", "AMR",
        "Внутрискладская логистика",
        "Фулфилмент 12 000 м2: 20 роботов, +38% к скорости сборки",
        "8", "4", "Москва", "Транспорт и логистика", "1 750 000,00",
        {
            "country_of_origin": "Россия",
            "navigation_type": "QR-метки",
            "payload_kg": "800",
            "mass_kg": "950",
            "length_mm": "1750",
            "width_mm": "950",
            "height_mm": "1950",
            "positioning_accuracy_mm": "8",
            "speed_m_s": "1.8",
            "charging_power_kw": "3.0",
            "noise_level_dba": "62",
            "autonomy_h": "10",
            "productivity": "до 120 заказов/час",
            "operating_conditions": "от 0 до +40 °C",
            "lifecycle_years": "6",
            "floor_coverage_req": "ровный пол, допуск ±15 мм/2 м",
            "communication_req": "Wi-Fi 2,4/5 ГГц",
            "integration_req": "WMS через REST API",
            "service_req": "плановое ТО раз в квартал",
            "software_cost": "280000",
            "implementation_cost": "450000",
            "maintenance_cost": "180000",
            "acquisition_model": "покупка",
            "limitations": "рабочие коридоры от 1,2 м",
            **provenance(),
        },
    ),
    row(
        uid("aaaa", 3), "РТ-Уборщик Склад", "brs", "operation",
        "ООО Роботест",
        "Робот сухой и влажной уборки складских и производственных полов: "
        "лидарная навигация, обход препятствий, отчёты по API.",
        "Мобильные роботы", "Робот-уборщик",
        "Уборка помещений",
        "Распредцентр 25 000 м2: 3 робота, уборка в 2 смены без персонала",
        "8", "3", "Москва", "Транспорт и логистика", "1 200 000,00",
        {
            "country_of_origin": "Россия",
            "navigation_type": "лидарная SLAM",
            "payload_kg": "50",
            "mass_kg": "480",
            "length_mm": "1350",
            "width_mm": "850",
            "height_mm": "1450",
            "positioning_accuracy_mm": "15",
            "speed_m_s": "1.2",
            "charging_power_kw": "1.5",
            "noise_level_dba": "60",
            "autonomy_h": "6",
            "productivity": "до 2000 м2/час",
            "operating_conditions": "от +5 до +40 °C",
            "lifecycle_years": "5",
            "floor_coverage_req": "ровный бетон/полимерный пол",
            "communication_req": "Wi-Fi",
            "integration_req": "автономная работа, отчёты по API",
            "service_req": "плановое ТО раз в месяц",
            "software_cost": "120000",
            "implementation_cost": "150000",
            "maintenance_cost": "90000",
            "acquisition_model": "покупка или RaaS",
            "limitations": "влажная уборка вне стеллажных зон",
            **provenance(),
        },
    ),
    row(
        uid("aaaa", 4), "РТ-Тягач 3000", "brs", "piloting",
        "ООО Роботест",
        "Автономный тягач с прицепными тележками: буксировка до 3 т по "
        "главным проездам, работа на улице и в цеху, LTE-резерв канала.",
        "Мобильные роботы", "Робот-тягач",
        "Внутрискладская логистика, Погрузка-разгрузка",
        "Завод 60 000 м2: 6 тягачей, межцеховая логистика без водителей",
        "7", "3", "Москва", "Транспорт и логистика", "3 400 000,00",
        {
            "country_of_origin": "Россия",
            "navigation_type": "лидарная SLAM",
            "payload_kg": "3000",
            "mass_kg": "1500",
            "length_mm": "1900",
            "width_mm": "1050",
            "height_mm": "1250",
            "positioning_accuracy_mm": "20",
            "speed_m_s": "1.6",
            "charging_power_kw": "12",
            "noise_level_dba": "65",
            "autonomy_h": "12",
            "productivity": "до 40 т/смену",
            "operating_conditions": "от -30 до +50 °C",
            "lifecycle_years": "8",
            "floor_coverage_req": "асфальт или бетон",
            "communication_req": "Wi-Fi + LTE",
            "integration_req": "WMS/TMS",
            "service_req": "плановое ТО раз в 6 месяцев",
            "software_cost": "400000",
            "implementation_cost": "700000",
            "maintenance_cost": "260000",
            "acquisition_model": "покупка",
            "limitations": "уклон до 6°",
            **provenance(),
        },
    ),
]

# ---------------------------------------------------------------- Фикстура B
# Вендор «ООО Автоном Тест», отрасль «Торговля и услуги» (склад), 4 решения.
FIXTURE_B: list[dict[str, object]] = [
    row(
        uid("bbbb", 1), "АТ-Штабелер 1000", "brs", "operation",
        "ООО Автоном Тест",
        "Автономный штабелер: подъём паллет до 3,5 м, работа в узких "
        "межстеллажных проходах, навигация по QR-меткам.",
        "Мобильные роботы", "Робот-штабелер",
        "Погрузка-разгрузка",
        "Склад стройматериалов 8 000 м2: 4 штабелера, минус 6 операторов",
        "9", "4", "Санкт-Петербург", "Торговля и услуги", "2 400 000,00",
        {
            "country_of_origin": "Россия",
            "navigation_type": "QR-метки",
            "payload_kg": "1000",
            "mass_kg": "1600",
            "length_mm": "2100",
            "width_mm": "1200",
            "height_mm": "2400",
            "positioning_accuracy_mm": "12",
            "speed_m_s": "1.5",
            "charging_power_kw": "8",
            "noise_level_dba": "66",
            "autonomy_h": "7",
            "productivity": "до 50 паллет/час",
            "operating_conditions": "от 0 до +40 °C",
            "lifecycle_years": "7",
            "floor_coverage_req": "бетон, ровность ±10 мм/2 м",
            "communication_req": "Wi-Fi 5 ГГц",
            "integration_req": "WMS",
            "service_req": "плановое ТО раз в квартал",
            "software_cost": "300000",
            "implementation_cost": "500000",
            "maintenance_cost": "200000",
            "acquisition_model": "покупка или лизинг",
            "limitations": "высота подъёма до 3,5 м",
            **provenance(),
        },
    ),
    row(
        uid("bbbb", 2), "АТ-Инвентаризатор RFID", "brs", "operation",
        "ООО Автоном Тест",
        "Робот инвентаризации: считывание UHF RFID-меток на стеллажах "
        "ночью, построение отчётов расхождений для WMS.",
        "Мобильные роботы", "Робот-инвентаризатор",
        "Инвентаризация",
        "Ретейл-склад 30 000 м2: полная инвентаризация за 4 ночи",
        "8", "3", "Санкт-Петербург", "Торговля и услуги", "900 000,00",
        {
            "country_of_origin": "Россия",
            "navigation_type": "лидарная SLAM",
            "payload_kg": "20",
            "mass_kg": "350",
            "length_mm": "1100",
            "width_mm": "700",
            "height_mm": "1500",
            "positioning_accuracy_mm": "15",
            "speed_m_s": "1.0",
            "charging_power_kw": "0.8",
            "noise_level_dba": "45",
            "autonomy_h": "14",
            "productivity": "до 15000 меток/час",
            "operating_conditions": "от +5 до +35 °C",
            "lifecycle_years": "5",
            "floor_coverage_req": "любое ровное покрытие",
            "communication_req": "Wi-Fi, UHF RFID",
            "integration_req": "WMS/ERP",
            "service_req": "калибровка раз в год",
            "software_cost": "90000",
            "implementation_cost": "100000",
            "maintenance_cost": "60000",
            "acquisition_model": "RaaS или покупка",
            "limitations": "только товар с RFID-метками",
            **provenance(),
        },
    ),
    row(
        uid("bbbb", 3), "АТ-Курьер Лайт", "brs", "piloting",
        "ООО Автоном Тест",
        "Курьерский робот indoor: доставка мелких грузов между офисами и "
        "зонами, вызов через API, работа в лифтах.",
        "Мобильные роботы", "Робот-курьер",
        "Внутрискладская логистика",
        "Бизнес-центр класса А: 5 роботов, 300 доставок в день",
        "7", "4", "Санкт-Петербург", "Торговля и услуги", "650 000,00",
        {
            "country_of_origin": "Россия",
            "navigation_type": "визуальная SLAM",
            "payload_kg": "150",
            "mass_kg": "180",
            "length_mm": "800",
            "width_mm": "600",
            "height_mm": "1200",
            "positioning_accuracy_mm": "10",
            "speed_m_s": "1.3",
            "charging_power_kw": "0.6",
            "noise_level_dba": "40",
            "autonomy_h": "10",
            "productivity": "до 80 доставок/час",
            "operating_conditions": "от +10 до +35 °C, только indoor",
            "lifecycle_years": "4",
            "floor_coverage_req": "любое ровное покрытие",
            "communication_req": "Wi-Fi",
            "integration_req": "API заказов",
            "service_req": "осмотр раз в год",
            "software_cost": "60000",
            "implementation_cost": "40000",
            "maintenance_cost": "30000",
            "acquisition_model": "RaaS",
            "limitations": "груз до 150 кг, лифты от 900 мм",
            **provenance(),
        },
    ),
    row(
        uid("bbbb", 4), "АТ-Паллета 2500", "brs", "operation",
        "ООО Автоном Тест",
        "Тяжёлый AMR-погрузчик: паллеты до 2,5 т, промышленный контур, "
        "работа на холоде, LTE-резерв канала связи.",
        "Мобильные роботы", "FMR",
        "Погрузка-разгрузка, Внутрискладская логистика",
        "Холодильный склад 15 000 м2: 8 роботов, минус 12% потерь",
        "9", "5", "Санкт-Петербург", "Торговля и услуги", "4 100 000,00",
        {
            "country_of_origin": "Россия",
            "navigation_type": "лидарная SLAM",
            "payload_kg": "2500",
            "mass_kg": "1900",
            "length_mm": "2250",
            "width_mm": "1250",
            "height_mm": "2300",
            "positioning_accuracy_mm": "10",
            "speed_m_s": "1.7",
            "charging_power_kw": "11",
            "noise_level_dba": "69",
            "autonomy_h": "9",
            "productivity": "до 70 паллет/час",
            "operating_conditions": "от -20 до +45 °C",
            "lifecycle_years": "8",
            "floor_coverage_req": "промышленный бетон",
            "communication_req": "Wi-Fi + LTE",
            "integration_req": "WMS/ERP",
            "service_req": "плановое ТО раз в 6 месяцев",
            "software_cost": "450000",
            "implementation_cost": "800000",
            "maintenance_cost": "300000",
            "acquisition_model": "покупка",
            "limitations": "проезды от 2,3 м",
            **provenance(),
        },
    ),
]


def check_invariants() -> None:
    """Самопроверка: 27 ТТХ без пропусков, UUID валидны и не пересекаются."""
    ids: set[str] = set()
    for fixture in (FIXTURE_A, FIXTURE_B):
        for r in fixture:
            ids.add(str(r["id"]))
            for code in CHAR_CODES:
                assert str(r[code]).strip(), (
                    f"пустая ячейка ТТХ {code} у {r['Название']}")
    assert len(ids) == 8, f"ожидалось 8 уникальных external_id, есть {len(ids)}"
    for ext in ids:
        uuid.UUID(ext)  # ValueError, если не UUID


def write_xlsx(path: Path, rows: list[dict[str, object]]) -> None:
    """XLSX: один лист, все ячейки - строки (POI-парсер читает как текст)."""
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "Каталог"
    sheet.append(COLUMNS)
    for r in rows:
        sheet.append([str(r[c]) for c in COLUMNS])
    workbook.save(path)


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    """CSV как у организатора: «;», UTF-8 с BOM, кавычки RFC 4180."""
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS, delimiter=";",
                                quoting=csv.QUOTE_MINIMAL)
        writer.writeheader()
        for r in rows:
            writer.writerow({c: str(r[c]) for c in COLUMNS})


def main() -> None:
    check_invariants()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    write_xlsx(OUT_DIR / "full_catalog_a.xlsx", FIXTURE_A)
    write_csv(OUT_DIR / "full_catalog_b.csv", FIXTURE_B)
    print(f"OK: {OUT_DIR / 'full_catalog_a.xlsx'} ({len(FIXTURE_A)} решений)")
    print(f"OK: {OUT_DIR / 'full_catalog_b.csv'} ({len(FIXTURE_B)} решений)")


if __name__ == "__main__":
    main()
