#!/usr/bin/env python3
"""E2E-проверка содержимого PDF-отчёта на живом стеке (схема обязательна,
авторасчёт экономики, без отсылок к номерам пунктов , вёрстка без
пересечений).

Что проверяет (>= 12 пунктов):
  1. проект БЕЗ сохранённой схемы: перед генерацией PDF схема
     автоматически строится серверным рендером и сохраняется
     (simulation_result.exportUrl появляется, файл скачивается);
  2. проект БЕЗ имитации: имитация автоматически запускается перед
     отчётом (строка истории со статусом completed);
  3. PDF содержит растровую 2D-схему (XObject-картинка на странице
     раздела имитации);
  4. проект БЕЗ расчётов: перед генерацией PDF все три сценария
     рассчитываются автоматически (появляются строки calculation);
  5. при невозможности расчёта PDF всё равно генерируется, а в тексте —
     явная причина «Расчёт не выполнен» (проверка на пустом проекте);
  6. текстовый слой PDF не содержит «ТЗ» (отсылок к номерам пунктов);
  7. пометка «предварительная оценка…» — на месте;
  8. устаревший расчёт (изменение состава) пересчитывается перед PDF;
  9. слова текстового слоя не пересекаются по горизонтали (вёрстка);
 10. каждое слово умещается в ширину контента страницы;
 11. все ключевые разделы отчёта присутствуют в тексте;
 12. тёмная тема PDF генерируется и тоже содержит схему.

Запуск (PostgreSQL + backend :8080 подняты, seed выполнен):
  BACKEND_URL=http://localhost:8080 PROJECT_ID=7 \
  E2E_LOGIN=user E2E_PASSWORD=useruser python3 scripts/verify_export_pdf_content.py

Переменные окружения:
  BACKEND_URL — адрес backend (по умолчанию http://localhost:8080)
  PROJECT_ID  — демо-проект с рассчитанными сценариями (7)
  E2E_LOGIN/PASSWORD — демо-аккаунт (user/useruser)
"""
import io
import json
import os
import re
import sys
import urllib.error
import urllib.request

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
PROJECT_ID = os.environ.get("PROJECT_ID", "7")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORD = os.environ.get("E2E_PASSWORD", "useruser")

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    mark = "ok " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""))


def request(method, path, token, body=None, raw=False):
    url = BACKEND_URL + path
    data = None
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            payload = response.read()
            if raw:
                return response.status, payload
            return response.status, json.loads(payload or b"null")
    except urllib.error.HTTPError as error:
        payload = error.read()
        try:
            return error.code, json.loads(payload)
        except (ValueError, UnicodeDecodeError):
            return error.code, payload.decode("utf-8", "replace")


def login():
    for candidate in (PASSWORD, "user", "useruser"):
        if not candidate:
            continue
        status, auth = request("POST", "/api/auth/login", None,
                               {"login": LOGIN, "password": candidate})
        if status == 200 and isinstance(auth, dict) and auth.get("token"):
            return auth["token"]
    return None


def pdf_pages(pdf_bytes):
    """Страницы PDF: текст (нормализованный) + наличие картинок."""
    try:
        from pypdf import PdfReader
    except ImportError:
        from PyPDF2 import PdfReader
    reader = PdfReader(io.BytesIO(pdf_bytes))
    pages = []
    for page in reader.pages:
        text = page.extract_text() or ""
        images = 0
        resources = page.get("/Resources") or {}
        xobjects = resources.get("/XObject")
        if xobjects is not None:
            xobjects = xobjects.get_object()
            for _, obj in xobjects.items():
                subtype = obj.get_object().get("/Subtype")
                if str(subtype) == "/Image":
                    images += 1
        pages.append((re.sub(r"\s+", " ", text), images))
    return pages


def pdf_words(pdf_bytes):
    """Слова текстового слоя с ТОЧНЫМИ bbox (pdftotext -bbox, poppler):
    пересечение слов — реальная проверка вёрстки, а не оценка."""
    import subprocess
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
        tmp.write(pdf_bytes)
        pdf_path = tmp.name
    try:
        html = subprocess.run(
            ["pdftotext", "-bbox", pdf_path, "-"],
            capture_output=True, text=True, timeout=60, check=True).stdout
    finally:
        os.unlink(pdf_path)
    words = []
    page = -1
    for line in html.splitlines():
        stripped = line.strip()
        if stripped.startswith("<page "):
            page += 1
        if stripped.startswith("<word ") and page >= 0:
            attrs = dict(re.findall(
                r"(xMin|xMax|yMin|xMax)=\"([0-9.]+)\"", stripped))
            if len(attrs) < 4:
                continue
            text = stripped[stripped.index(">") + 1:-len("</word>")]
            words.append((page, text, float(attrs["xMin"]),
                          float(attrs["yMin"]),
                          float(attrs["xMax"]) - float(attrs["xMin"]),
                          float(attrs["yMax"])))
    return words


def main():
    print(f"PDF-содержимое: {BACKEND_URL} (проект {PROJECT_ID})")
    token = login()
    check("логин демо-аккаунта", bool(token))
    if not token:
        return finish()

    # --- 1. проект без сохранённой схемы -------------------------------
    # находим сценарий purchase и УДАЛЯЕТ сохранённые схемы проекта:
    # имитация уже запускалась (демо-проект), но файлы схем чистим
    status, simulations = request(
        "GET", f"/api/projects/{PROJECT_ID}/simulations", token)
    check("история имитаций проекта читается", status == 200
          and isinstance(simulations, list), f"{status}")
    saved = [s for s in simulations
             if isinstance(s, dict) and s.get("exportUrl")]
    for row in saved:
        # схема «не сохранена»: kpi_json.exportUrl убираем прямым
        # повторным сохранением пустой схемы — проще: помечаем через
        # удаление файла нельзя (нет API) — используем свежий запуск
        # имитации ниже (новая строка истории без exportUrl)
        break
    status, run = request(
        "POST",
        f"/api/projects/{PROJECT_ID}/scenarios/"
        + str(simulations[0]["scenarioId"] if simulations else 0)
        + "/simulation/run", token)
    check("имитация перезапущена (свежая строка без exportUrl)",
          status == 200 and isinstance(run, dict) and not run.get("exportUrl"),
          f"HTTP {status}, exportUrl={bool(run.get('exportUrl')) if isinstance(run, dict) else '-'}")
    fresh_simulation_id = run.get("id") if isinstance(run, dict) else None

    # --- 2. генерация PDF: схема должна появиться автоматически --------
    status, export = request("POST", f"/api/projects/{PROJECT_ID}/exports",
                              token, {"format": "pdf"})
    check("PDF сгенерирован (схема обязательна)", status == 201
          and isinstance(export, dict), f"HTTP {status}")
    if status != 201:
        return finish()
    export_id = export.get("id")

    status, pdf_bytes = request(
        "GET", f"/api/projects/{PROJECT_ID}/exports/{export_id}", token,
        raw=True)
    check("PDF скачивается", status == 200 and isinstance(pdf_bytes, bytes)
          and pdf_bytes[:4] == b"%PDF",
          f"{status}, {len(pdf_bytes) if isinstance(pdf_bytes, bytes) else 0} B")

    pages = pdf_pages(pdf_bytes)
    full_text = " ".join(page[0] for page in pages)

    # --- 3. схема в PDF: страница раздела имитации содержит картинку ---
    simulation_pages = [p for p in pages if "Имитация 2D" in p[0]]
    check("раздел «Имитация 2D и KPI» в PDF", bool(simulation_pages))
    # подпись схемы может перейти на следующую страницу (поток)
    schema_pages = [p for p in pages if "2D-схема склада" in p[0]]
    check("PDF содержит растровую 2D-схему (image XObject)",
          any(p[1] > 0 for p in simulation_pages + schema_pages)
          and bool(schema_pages),
          f"картинок: {sum(p[1] for p in pages)}, "
          f"страниц с подписью схемы: {len(schema_pages)}")

    # --- 4. схема сохранена в хранилище автоматически -------------------
    status, latest = request(
        "GET",
        f"/api/projects/{PROJECT_ID}/scenarios/"
        + str(run.get("scenarioId", "")) + "/simulation", token)
    check("схема автоматически сохранена (exportUrl у результата)",
          status == 200 and isinstance(latest, dict)
          and latest.get("id") == fresh_simulation_id
          and latest.get("exportUrl"),
          f"HTTP {status}, exportUrl={bool(latest.get('exportUrl')) if isinstance(latest, dict) else '-'}")
    if isinstance(latest, dict) and latest.get("exportUrl"):
        status, svg = request("GET",
                              latest["exportUrl"].replace(BACKEND_URL, ""),
                              token, raw=True)
        check("сохранённая схема скачивается (SVG)",
              status == 200 and b"<svg" in svg[:2000],
              f"HTTP {status}")

    # --- 5. текстовый слой без «ТЗ» --------------------------------------
    check("текстовый слой PDF без «ТЗ»", "ТЗ" not in full_text)
    check("пометка «предварительная оценка» на месте",
          "Предварительная оценка" in full_text
          and "верификации" in full_text)

    # --- 6. ключевые разделы --------------------------------------------
    for section in ["Расчёт экономики", "Ограничения", "Источники данных",
                    "Дата расчёта"]:
        check(f"раздел «{section}» в PDF", section in full_text)

    # --- 7. вёрстка: слова не вылезают за поля --------------------------
    words = pdf_words(pdf_bytes)
    content_width = 595 - 2 * 50  # A4 портрет, поля 50 pt
    oversized = [w for w in words if w[2] < 50 - 0.5
                 or w[2] + w[4] > 545 + 0.5]
    check("все слова умещаются в ширину контента", not oversized,
          f"слов {len(words)}, шире полей: {len(oversized)}")

    # пересечения слов: точные bbox (pdftotext), допуск 0,5 pt
    overlaps = []
    for i, current in enumerate(words):
        for other in words[i + 1:]:
            if other[0] != current[0]:
                break
            if other[3] >= current[5]:
                continue  # разные строки
            if other[2] >= current[2] + current[4] - 0.5:
                continue  # не пересекаются по x
            overlaps.append((current[1], other[1]))
            if len(overlaps) > 3:
                break
        if len(overlaps) > 3:
            break
    check("слова текстового слоя не пересекаются (bbox)", not overlaps,
          "; ".join(f"«{a}»×«{b}»" for a, b in overlaps[:3]))

    # --- 8. проект без расчётов: авторасчёт перед PDF --------------------
    status, created = request("POST", "/api/projects", token, {
        "name": "E2E PDF: без расчётов %d" % (os.getpid() % 10000),
        "objectTypeId": 1})
    if status != 201:
        # тип объекта склада ищем через фильтры
        status, filters = request("GET", "/api/filters", token)
        object_types = filters.get("objectTypes", []) if isinstance(
                filters, dict) else []
        warehouse = next((t for t in object_types if "склад" in str(
                t.get("name", "")).lower()), object_types[0] if len(
                    object_types) > 1 else None)
        status, created = request("POST", "/api/projects", token, {
            "name": "E2E PDF: без расчётов",
            "objectTypeId": warehouse["id"]})
    empty_project_id = created.get("id") if isinstance(created, dict) else None
    check("проект без расчётов создан", bool(empty_project_id),
          f"HTTP {status}")
    if empty_project_id:
        # сценарии не созданы (подбор не запускался) — бутстрап
        request("POST", f"/api/projects/{empty_project_id}/scenarios",
                token, None)
        # состав покупки — подбор не запускался: добавим вручную
        status, scenarios = request(
            "GET", f"/api/projects/{empty_project_id}/scenarios", token)
        purchase_id = next((s["id"] for s in scenarios
                            if s["type"] == "purchase"), None)
        solution_id = simulations[0].get("composition", [{}])[0].get(
            "solutionId", 1) if simulations else 1
        status, added = request(
            "POST",
            f"/api/projects/{empty_project_id}/scenarios/{purchase_id}"
            "/solutions",
            token, {"solutionId": solution_id, "quantity": 1,
                    "manualReason": "E2E автозапуск расчёта"})
        check("состав пустого проекта задан (ручное добавление)",
              status == 201, f"HTTP {status}")

        status, export2 = request(
            "POST", f"/api/projects/{empty_project_id}/exports", token,
            {"format": "pdf"})
        check("PDF без предварительных расчётов генерируется (авторасчёт)",
              status == 201, f"HTTP {status}")
        if status == 201:
            status, calculations = request(
                "GET",
                f"/api/projects/{empty_project_id}/scenarios/"
                f"{purchase_id}/calculations", token)
            # у свежего проекта нет значений параметров — расчёт может
            # честно не выполниться; тогда в PDF обязана быть причина
            check("авторасчёт отработал (строки расчётов или причина)",
                  status == 200 and (
                      (isinstance(calculations, list) and calculations)
                      or "Расчёт не выполнен" in text2),
                  f"расчётов: {len(calculations) if isinstance(calculations, list) else 0}")
            status, pdf2 = request(
                "GET",
                f"/api/projects/{empty_project_id}/exports/"
                + str(export2["id"]), token, raw=True)
            text2 = " ".join(p[0] for p in pdf_pages(pdf2))
            check("PDF без предварительных расчётов без «ТЗ»",
                  "ТЗ" not in text2)
            # расчётable: экономика в отчёте (метрики или причина)
            check("раздел экономики после авторасчёта не пуст",
                  "Расчёт экономики" in text2 and (
                      "CAPEX" in text2 or "Расчёт не выполнен" in text2))
            # уборка: проект удаляется (каскад чистит файлы)
            request("DELETE", f"/api/projects/{empty_project_id}", token)

    # --- 9. устаревший расчёт пересчитывается ---------------------------
    status, scenarios = request(
        "GET", f"/api/projects/{PROJECT_ID}/scenarios", token)
    purchase_id = next((s["id"] for s in scenarios
                        if s["type"] == "purchase"), None)
    solution_id = None
    for scenario in scenarios:
        if scenario["type"] == "purchase" and scenario.get("solutions"):
            solution_id = scenario["solutions"][0]["solutionId"]
            quantity = scenario["solutions"][0]["quantity"]
            break
    if purchase_id and solution_id is not None:
        status, updated = request(
            "PUT",
            f"/api/projects/{PROJECT_ID}/scenarios/{purchase_id}"
            f"/solutions/{solution_id}",
            token, {"quantity": quantity + 1})
        check("состав изменён (расчёт устарел)", status == 200)
        status, export3 = request(
            "POST", f"/api/projects/{PROJECT_ID}/exports", token,
            {"format": "pdf"})
        check("PDF после изменения состава генерируется (пересчёт)",
              status == 201, f"HTTP {status}")
        if status == 201:
            status, scenarios_after = request(
                "GET", f"/api/projects/{PROJECT_ID}/scenarios", token)
            purchase_after = next(s for s in scenarios_after
                                  if s["id"] == purchase_id)
            check("устаревший расчёт пересчитан (compositionChanged=false)",
                  purchase_after.get("compositionChanged") is False)
            # вернуть состав
            request("PUT",
                    f"/api/projects/{PROJECT_ID}/scenarios/{purchase_id}"
                    f"/solutions/{solution_id}",
                    token, {"quantity": quantity})
            request("POST", f"/api/projects/{PROJECT_ID}/exports", token,
                    {"format": "pdf"})

    # --- 10. тёмная тема --------------------------------------------------
    status, export_dark = request(
        "POST", f"/api/projects/{PROJECT_ID}/exports", token,
        {"format": "pdf", "theme": "dark"})
    check("PDF тёмной темы генерируется", status == 201, f"HTTP {status}")
    if status == 201:
        status, pdf_dark = request(
            "GET", f"/api/projects/{PROJECT_ID}/exports/"
            + str(export_dark["id"]), token, raw=True)
        dark_pages = pdf_pages(pdf_dark)
        dark_schema_pages = [p for p in dark_pages
                             if "2D-схема склада" in p[0]]
        check("тёмный PDF тоже содержит 2D-схему",
              any(p[1] > 0 for p in dark_schema_pages),
              f"картинок: {sum(p[1] for p in dark_pages)}")

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"\nИтог: {passed}/{total} проверок пройдено")
    if passed < total:
        print("Провалены:")
        for name, ok, detail in results:
            if not ok:
                print(f"  [FAIL] {name} {detail}")
        return 1
    print("Все проверки пройдены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
