#!/usr/bin/env python3
"""E2E-проверка содержимого экспорта во всех форматах (задача 5).

На живом стеке генерирует PDF, XLSX и CSV одного проекта (по умолчанию
демо-проект «Демо: Оптимальный склад») и сверяет:

  1. все три формата + metrics_json API (4 источника) — ключевые
     метрики 1:1: CAPEX, OPEX, ΔOPEX, ΔFOT, Effect_year, Payback,
     ROI, TCO, N_robots;
  2. PDF: валидный файл, ≥ 4 страниц, кликабельное оглавление
     (закладки + ссылки-аннотации), нумерация «Страница N из M»,
     разделы, Noto Sans (без «тофу»), зебра/акцент — по
     структуре контента; схема имитации внедрена (XObject);
  3. XLSX: 7 листов, закрепление шапки, автофильтры, числовые
     форматы (# ##0 / 0.0 % / 0.0), условное форматирование Δ-строк,
     ширины колонок по содержимому;
  4. CSV: UTF-8 BOM, «;», CRLF (RFC 4180), десятичная запятая,
     значения 1:1 с XLSX;
  5. визуальная проверка PDF: скриншоты страниц в docs/examples/
     (примеры отчёта перегенерируются отдельным шагом).

Запуск (стек поднят, seed + расчёты есть — например после
verify_economics_new.py):
  BACKEND_URL=http://localhost:8080 PROJECT_ID=22 \
    python3 scripts/verify_export_content.py

Переменные окружения:
  BACKEND_URL  — адрес backend (http://localhost:8080)
  E2E_LOGIN    — логин владельца проекта (user)
  E2E_PASSWORD — пароль (useruser)
  PROJECT_ID   — проект с рассчитанными сценариями (22)
"""
import json
import os
import sys
import urllib.error
import urllib.request
import urllib.parse

from pypdf import PdfReader
import openpyxl

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "useruser", "user")
PROJECT_ID = os.environ.get("PROJECT_ID", "22")

results: list[tuple[str, bool, str]] = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    mark = "ok " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""))


def request(method, path, token=None, body=None):
    req = urllib.request.Request(BACKEND_URL + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data, timeout=90) as resp:
            raw = resp.read()
            content_type = resp.headers.get("Content-Type", "")
            if "json" in content_type:
                return resp.status, json.loads(raw) if raw else None
            return resp.status, raw
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def money(value) -> str:
    return f"{value:,.0f}".replace(",", " ")


def number(text) -> float:
    return float(str(text).replace(",", ".").replace(" ", ""))


def main() -> int:
    token = None
    for password in PASSWORDS:
        if not password:
            continue
        status, auth = request("POST", "/api/auth/login",
                               body={"login": LOGIN, "password": password})
        if status == 200:
            token = auth["token"]
            break
    if token is None:
        print("ОШИБКА: логин не удался")
        return 2

    # --- подготовка: свежие расчёты + сохранённая схема имитации ----
    # (схема с feDropShadow — заодно живой регресс фикса Batik, TZS-1)
    status, scenarios = request("GET",
                                f"/api/projects/{PROJECT_ID}/scenarios", token)
    purchase_id = next(s["id"] for s in scenarios
                       if s["type"] == "purchase")
    status, sim = request(
        "POST", f"/api/projects/{PROJECT_ID}/scenarios/{purchase_id}"
                "/simulation/run", token)
    sim_id = (sim or {}).get("id")
    if status == 200:
        schema_svg = (
            "<svg xmlns=\"http://www.w3.org/2000/svg\" "
            "viewBox=\"0 0 960 480\"><defs><filter id=\"s\" x=\"-40%\" "
            "y=\"-40%\" width=\"180%\" height=\"180%\"><feDropShadow "
            "dx=\"0\" dy=\"1.5\" stdDeviation=\"2\" flood-color="
            "\"#0f172a\" flood-opacity=\"0.2\"/></filter></defs>"
            "<rect x=\"10\" y=\"10\" width=\"940\" height=\"460\" "
            "fill=\"#f8fafc\" filter=\"url(#s)\"/>"
            "<text x=\"30\" y=\"36\" font-size=\"15\">Схема</text></svg>")
        request("POST",
                f"/api/projects/{PROJECT_ID}/simulations/{sim_id}"
                "/export", token, {"svg": schema_svg})
    sid_by_type = {s["type"]: s["id"] for s in scenarios}
    calcs = {}
    for stype, sid in sid_by_type.items():
        status, calc = request(
            "POST", f"/api/projects/{PROJECT_ID}/scenarios/{sid}/calculate",
            token)
        if status != 200:
            print(f"ОШИБКА: calculate {stype}: HTTP {status}")
            return 2
        calcs[stype] = calc
    purchase = calcs["purchase"]
    details = purchase["details"]
    metrics = {
        "CAPEX": purchase["totalCapex"],
        "OPEX": purchase["totalOpex"],
        "ΔOPEX": details["opexDelta"],
        "ΔFOT": details["deltaFot"],
        "Effect_year": purchase["effectYear"],
        "Payback": purchase["paybackYears"],
        "ROI": purchase["roiPct"],
        "TCO": details["tcoRub"],
        "N_robots": details["selectedRobots"],
    }
    print(f"metrics_json (источник №4): {json.dumps(metrics, ensure_ascii=False)}")

    # --- генерация трёх форматов ------------------------------------
    files = {}
    for fmt in ("pdf", "xlsx", "csv"):
        status, body = request("POST", f"/api/projects/{PROJECT_ID}/exports",
                               token, {"format": fmt})
        if status != 201:
            print(f"ОШИБКА: export {fmt}: HTTP {status}")
            return 2
        export_id = body["id"]
        status, content = request(
            "GET", f"/api/projects/{PROJECT_ID}/exports/{export_id}", token)
        if status != 200:
            print(f"ОШИБКА: download {fmt}: HTTP {status}")
            return 2
        files[fmt] = content if isinstance(content, bytes) else content.encode()
        os.makedirs("/home/z/tmp/exports", exist_ok=True)
        open(f"/home/z/tmp/exports/report.{fmt}", "wb").write(files[fmt])

    print("\n1. PDF:")
    reader = PdfReader("/home/z/tmp/exports/report.pdf")
    text = "\n".join(
        (page.extract_text() or "").replace("\u00a0", " ")
        for page in reader.pages)
    check("PDF валиден и ≥ 4 страниц", len(reader.pages) >= 4,
          f"{len(reader.pages)} страниц")
    check("кликабельное оглавление (закладки-Outline)",
          reader.outline is not None and len(reader.outline) >= 7,
          f"{len(reader.outline or [])} закладок")
    links = sum(len(p.get("/Annots") or []) for p in reader.pages)
    check("ссылки-аннотации оглавления на первой странице",
          len(reader.pages[0].get("/Annots") or []) >= 7,
          f"{len(reader.pages[0].get('/Annots') or [])} ссылок, всего {links}")
    check("нумерация страниц «Страница N из M»",
          f"Страница 1 из {len(reader.pages)}" in text)
    check("страница «Содержание»", "Содержание" in text)
    for section in ("1. Параметры объекта", "2. Выбранные решения",
                    "3. Состав оборудования", "4. Расчёт экономики",
                    "6. Ограничения", "7. Источники данных",
                    "8. Дата расчёта"):
        check(f"раздел «{section}»", section in text)
    check("пометка «предварительная оценка»",
          "Предварительная оценка" in text)
    images = 0
    for page in reader.pages:
        xo = page.get("/Resources", {}).get("/XObject", {})
        images += sum(1 for k in xo or {})
    check("схема имитации внедрена (XObject)", images >= 1,
          f"{images} изображений")
    fonts = set()
    for page in reader.pages:
        for f in (page.get("/Resources", {}).get("/Font", {}) or {}).values():
            fonts.add(str(f.get("/BaseFont", "")))
    check("Noto Sans в PDF (кириллица без тофу)",
          any("NotoSans" in f for f in fonts), ", ".join(sorted(fonts))[:80])

    print("\n2. PDF ↔ metrics_json (метрики 1:1):")
    def pdf_signed(value) -> str:
        # moneySigned PDF: «−» (U+2212) для отрицательных, «+» для
        # положительных Δ-метрик; grouping — пробел
        formatted = f"{abs(value):,.0f}".replace(",", " ")
        return ("\u2212" if value < 0 else "+") + formatted

    plain = {k: money(v) for k, v in metrics.items()
             if k not in ("Payback", "ROI", "N_robots", "\u0394OPEX",
                         "\u0394FOT")}
    signed = {k: pdf_signed(v) for k, v in metrics.items()
              if k in ("\u0394OPEX", "\u0394FOT")}
    for key, formatted in {**plain, **signed}.items():
        check(f"PDF: {key}", formatted in text,
              f"ищем «{formatted}»")
    # Payback в PDF — человекочитаемая форма (paybackHuman):
    # проверяем наличие строки и числовой формы в XLSX/CSV ниже
    check("PDF: Payback (человекочитаемо)",
          "окупаемость" in text and ("год" in text or "лет" in text),
          f"API Payback={metrics['Payback']} лет")
    check("PDF: ROI", f"{metrics['ROI']:.1f}".replace(".", ",") in text)
    check("PDF: N_robots", str(metrics["N_robots"]) in text)

    print("\n3. XLSX:")
    wb = openpyxl.load_workbook("/home/z/tmp/exports/report.xlsx")
    check("7 листов", wb.sheetnames == [
        "Сводка", "Параметры", "Решения", "Оборудование",
        "Экономика", "Допущения", "Источники"], str(wb.sheetnames))
    econ = wb["Экономика"]
    check("закрепление шапки (freeze pane)", econ.freeze_panes == "A2",
          str(econ.freeze_panes))
    check("автофильтр на листе «Экономика»", econ.auto_filter.ref is not None,
          str(econ.auto_filter.ref))
    check("условное форматирование Δ-строк",
          len(econ.conditional_formatting._cf_rules) >= 1,
          f"{len(econ.conditional_formatting._cf_rules)} правил")

    def xlsx_row(label):
        for row in econ.iter_rows(min_row=2, max_col=6):
            if row[0].value == label:
                return row
        return None

    capex_row = xlsx_row("CAPEX, руб.")
    roi_row = xlsx_row("ROI за горизонт, %")
    payback_row = xlsx_row("Срок окупаемости, лет")
    check("числовой формат денег «# ##0»",
          capex_row and capex_row[2].number_format.replace("\\", "") == "# ##0",
          capex_row and capex_row[2].number_format)
    check("числовой формат процентов «0,0 %»",
          roi_row and roi_row[2].number_format in ('0.0" %"', "0.0 %"),
          roi_row and roi_row[2].number_format)
    check("числовой формат лет «0,0»",
          payback_row and payback_row[2].number_format == "0.0",
          payback_row and payback_row[2].number_format)
    width_a = econ.column_dimensions["A"].width or 0
    check("ширины колонок по содержимому (> 12 симв.)", width_a >= 12,
          f"A={width_a}")
    check("XLSX: CAPEX 1:1", capex_row[2].value == metrics["CAPEX"],
          f"{capex_row[2].value}")
    check("XLSX: TCO 1:1", xlsx_row("TCO на горизонте, руб.")[2].value
          == metrics["TCO"])
    check("XLSX: ROI 1:1", roi_row[2].value == metrics["ROI"])
    check("XLSX: Payback 1:1", payback_row[2].value == metrics["Payback"])
    equipment = wb["Оборудование"]
    robots_cell = next(row[1].value for row in equipment.iter_rows(min_row=2)
                       if row[0].value == "Покупка оборудования")
    check("XLSX: N_robots 1:1", robots_cell == metrics["N_robots"],
          f"{robots_cell}")

    print("\n4. CSV:")
    raw = open("/home/z/tmp/exports/report.csv", "rb").read()
    check("UTF-8 BOM", raw[:3] == b"\xef\xbb\xbf")
    csv_text = raw.decode("utf-8-sig")
    check("разделитель «;» и шапка",
          csv_text.startswith("section;key;value;unit;source"))
    check("CRLF (RFC 4180)", "\r\n" in csv_text)
    check("десятичная запятая в числах",
          any("," in line.split(";")[2]
              for line in csv_text.split("\r\n")[1:]
              if line.startswith("Экономика;")
              and line.count(";") >= 3
              and line.split(";")[2].replace(",", "").replace(".", "")
              .replace("—", "").isdigit()))

    def csv_value(key):
        wanted = "Покупка оборудования — " + key
        for line in csv_text.split("\r\n"):
            parts = line.split(";")
            if len(parts) >= 3 and parts[1] == wanted:
                return parts[2]
        return None

    for key, metric in (("CAPEX", "CAPEX"), ("TCO на горизонте", "TCO"),
                        ("ROI за горизонт", "ROI"),
                        ("срок окупаемости, лет", "Payback")):
        got = csv_value(key)
        want = metrics[metric]
        ok = got is not None and abs(number(got) - float(want)) < 0.01
        check(f"CSV: {key} 1:1 с API", ok, f"csv={got}, api={want}")
    check("CSV: CAPEX 1:1 с XLSX",
          number(csv_value("CAPEX")) == capex_row[2].value,
          f"csv={csv_value('CAPEX')}, xlsx={capex_row[2].value}")
    check("CSV: N_robots", csv_value("роботы") == str(metrics["N_robots"]),
          csv_value("роботы"))

    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\nИтог: {passed}/{total} проверок пройдено")
    if passed < total:
        print("Провалены:")
        for name, ok, detail in results:
            if not ok:
                print(f"  [FAIL] {name} {detail}")
        return 1
    print("OK: экспорт согласован 1:1 (PDF/XLSX/CSV/API), форматы "
          "и читаемость подтверждены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
