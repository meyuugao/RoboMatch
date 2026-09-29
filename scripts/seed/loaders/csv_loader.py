"""Чтение catalog_export_v4.csv (зерно строки = решение x применение).

CSV: разделитель «;», кодировка UTF-8 с BOM, все колонки читаются как строки.
"""
from collections.abc import Callable
from typing import TypeAlias

import pandas as pd

import config

LogFn: TypeAlias = Callable[[str], None]

# Русские заголовки CSV -> внутренние имена полей (маппинг 1:1, без выдумок)
CSV_COLUMNS: dict[str, str] = {
    "id": "external_id",
    "Название": "name",
    "тип": "product_class",
    "статус": "status",
    "компания": "vendor",
    "описание": "description",
    "Тип": "solution_type",
    "Подтип": "solution_subtype",
    "Сценарий": "scenario",
    "Кейсы": "case",
    "УГТ": "trl_raw",
    "Рын Потенциал": "market_potential_raw",
    "Регион": "region",
    "Отрасль": "industry",
    "Цена изделия": "price_raw",
}


def load_catalog_rows(path=None, log: LogFn | None = None) -> list[dict]:
    path = path or config.CATALOG_CSV_PATH
    df = pd.read_csv(
        path,
        sep=";",
        dtype=str,
        encoding="utf-8-sig",
        keep_default_na=False,
        na_values=[""],
    )
    missing = set(CSV_COLUMNS) - set(df.columns)
    if missing:
        raise ValueError(f"в CSV отсутствуют колонки: {sorted(missing)}; фактические: {list(df.columns)}")
    df = df.rename(columns=CSV_COLUMNS)
    rows = df.to_dict("records")
    if log:
        log(f"CSV прочитан: {len(rows)} строк, колонки замаплены 1:1 ({len(CSV_COLUMNS)})")
    return rows
