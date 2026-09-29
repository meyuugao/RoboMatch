"""Нормализация строк: trim, пробелы, тире, ё, канонический ключ, слуги-коды.

Правила — data_model.md «Нормализация строк» и assumptions.md §10:
- ё -> е только для поиска/дедупликации, НЕ для отображения (в БД храним как в данных);
- тире и дефисы -> обычный дефис;
- trim пробелов, удаление дублирующих пробелов (включая \\n и NBSP).
"""
import re

# em-dash, en-dash, horizontal bar, figure dash, hyphen-bullet, non-breaking hyphen
_DASH_MAP = str.maketrans({
    "\u2014": "-",  # —
    "\u2013": "-",  # –
    "\u2015": "-",  # ―
    "\u2012": "-",  # ‒
    "\u2043": "-",  # ⁃
    "\u2011": "-",  # ‑
})


def norm_text(value) -> str | None:
    """Нормализация для хранения: пробелы схлопнуты, тире -> дефис, ё сохранена.

    Пустая строка, None и NaN -> None (отсутствие значения = NULL, data_model.md).
    """
    if value is None:
        return None
    if isinstance(value, float) and value != value:  # NaN
        return None
    s = str(value).replace("\u00a0", " ")
    s = s.translate(_DASH_MAP)
    s = re.sub(r"\s+", " ", s).strip()
    return s or None


def canonical_key(value) -> str | None:
    """Ключ дедупликации и сопоставления: norm_text + lower + ё -> е.

    Используется для поиска FK (vendor/region/subtype/process), сами имена
    в БД хранятся в исходном написании (norm_text).
    """
    s = norm_text(value)
    if s is None:
        return None
    return s.lower().replace("ё", "е")


# Транслитерация для генерации технических кодов справочников (industry.code,
# process.code, object_type.code, parameter_type.code). Механическая, без
# семантических выдумок: кириллица -> латиница, прочее -> «_».
_TRANSLIT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e",
    "ж": "zh", "з": "z", "и": "i", "й": "y", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "shch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
}


def slugify(value) -> str | None:
    """Детерминированный код из имени: канонический ключ -> транслит-слаг."""
    key = canonical_key(value)
    if key is None:
        return None
    out = []
    for ch in key:
        if ch in _TRANSLIT:
            out.append(_TRANSLIT[ch])
        elif ch.isascii() and ch.isalnum():
            out.append(ch)
        else:
            out.append("_")
    slug = re.sub(r"_+", "_", "".join(out)).strip("_")
    return slug or None


def assign_codes(keys: list[str]) -> dict[str, str]:
    """canonical_key -> уникальный код. Коллизии слагов решаются суффиксом _2, _3...

    Возвращает также счётчик коллизий через len(результат) == len(keys);
    сами коллизии фиксируются вызывающим кодом по факту повторного слага.
    """
    codes: dict[str, str] = {}
    used: set[str] = set()
    for key in keys:
        base = slugify(key) or "x"
        code, n = base, 2
        while code in used:
            code = f"{base}_{n}"
            n += 1
        used.add(code)
        codes[key] = code
    return codes
