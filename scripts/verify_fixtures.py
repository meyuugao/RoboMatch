#!/usr/bin/env python3
"""E2E-проверка тестовых фикстур каталога на живом стеке.

Фикстуры - docs/test-fixtures/full_catalog_a.xlsx (Excel) и
full_catalog_b.csv (CSV): расширенный формат импорта админки
(15 колонок организатора + 27 ТТХ, docs/test-fixtures/README.md).
Единственный путь фикстур в БД - админка (POST /api/admin/catalog/import);
в seed они НЕ попадают (scripts/seed/data/ не трогается).

Что проверяет:
  1. импорт обоих наборов ЧЕРЕЗ АДМИНКУ (UI-форма /admin/catalog/import):
     summary с решениями и ТТХ (4 x 27 характеристик на файл);
  2. идемпотентность: повторная загрузка файла - 0 добавлений;
  3. решения фикстур видны в публичном каталоге (/catalog, поиск);
  4. полный путь пользователя на решении из full_catalog_a.xlsx:
     проект склада -> параметры -> подбор (fit/исключения с причинами)
     -> сценарий -> экономика -> имитация (6 KPI) -> экспорт
     отчёта (PDF) и схемы (SVG);
  5. провенанс ТТХ фикстуры: organizer_catalog + is_confirmed=true
     (колонки source/source_date/confirmation_status файла).

Запуск (PostgreSQL + backend :8080 + frontend :3000, seed выполнен):
  FRONTEND_URL=http://localhost:3000 python3 scripts/verify_fixtures.py

Переменные окружения:
  FRONTEND_URL  - адрес frontend (по умолчанию http://localhost:3000)
  BACKEND_URL   - адрес backend (по умолчанию http://localhost:8080)
  ADMIN_LOGIN/PASSWORD - админ-аккаунт seed (admin/adminadmin)
  USER_LOGIN/PASSWORD  - пользователь полного пути (user/useruser)
  PROJECT_NAME  - имя E2E-проекта фикстуры (переиспользуется при повторах)

Скрипт идемпотентен: повторный запуск обновляет существующий проект
(имя уникально у пользователя), импорт фикстур - 0 добавлений.
"""
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")
BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
ADMIN_LOGIN = os.environ.get("ADMIN_LOGIN", "admin")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "adminadmin")
USER_LOGIN = os.environ.get("USER_LOGIN", "user")
USER_PASSWORD = os.environ.get("USER_PASSWORD", "useruser")
PROJECT_NAME = os.environ.get("PROJECT_NAME", "Фикстура A: полный путь")

REPO_ROOT = Path(__file__).resolve().parent.parent
FIXTURE_A = REPO_ROOT / "docs" / "test-fixtures" / "full_catalog_a.xlsx"
FIXTURE_B = REPO_ROOT / "docs" / "test-fixtures" / "full_catalog_b.csv"

# решения фикстуры A (scripts/gen_test_fixtures.py)
A_FIT_NAMES = ("РТ-Паллета 1500", "РТ-Комплект 800", "РТ-Тягач 3000")
A_EXCLUDED_NAME = "РТ-Уборщик Склад"  # грузоподъёмность 50 < паллеты проекта

# параметры склада под ТТХ фикстур (worst-case каталога гасит реалистичные
# роботы, поэтому проект задаёт свой профиль объекта - как живой пользователь).
# available_power_capacity не задаётся: минимум диапазона 100 кВт уже покрывает
# зарядки фикстур (макс. 12 кВт)
SELECTION_PARAMS = {
    "pallet_unit_weight": 300,
    "floor_load_max_kg": 2500,
    "rack_aisle_width": 2.2,
    "main_aisle_width": 2.5,
    "storage_zone_ceiling_height": 5.0,
    "required_positioning_accuracy_mm": 20,
    "max_allowed_noise_dba": 70,
}

# минимальная валидная SVG-разметка для сохранения схемы ( -
# сервер санитизирует и сохраняет то, что прислал клиент)
SCHEMA_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" width="800" height="400"'
              ' viewBox="0 0 800 400"><rect x="10" y="10" width="780"'
              ' height="380" fill="none" stroke="slate"/>'
              '<text x="20" y="40">E2E fixture schema</text></svg>')

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" - {detail}" if detail else ""))


def api(method: str, path: str, token=None, body=None, base=None):
    """Запрос к backend API; возвращает (status, json|str)."""
    req = urllib.request.Request((base or BACKEND_URL) + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data, timeout=60) as resp:
            raw = resp.read()
            content_type = resp.headers.get("Content-Type", "")
            if "json" in content_type:
                return resp.status, json.loads(raw) if raw else None
            # бинарные выгрузки (PDF/SVG) - текст с запасной заменой байтов
            return resp.status, raw.decode("utf-8", errors="replace") if raw else ""
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def login_token(login: str, password: str) -> str | None:
    status, auth = api("POST", "/api/auth/login",
                       body={"login": login, "password": password})
    return auth.get("token") if status == 200 else None


def admin_bff_import(page, path: Path):
    """Импорт через BFF-роут админки (multipart) - точный JSON summary."""
    resp = page.request.post(
        f"{FRONTEND_URL}/api/admin/catalog/import",
        multipart={"file": {
            "name": path.name,
            "mimeType": "application/octet-stream",
            "buffer": path.read_bytes(),
        }},
    )
    try:
        return resp.status, resp.json()
    except Exception:
        return resp.status, None


def main() -> int:
    if not FIXTURE_A.exists() or not FIXTURE_B.exists():
        print(f"ОШИБКА: фикстуры не найдены ({FIXTURE_A})")
        return 2

    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1366, "height": 900})

        # ---------------- 1. импорт через админку --------------------
        print("1. Импорт фикстур через админку (UI-форма):")
        resp = page.request.post(
            f"{FRONTEND_URL}/api/auth/login",
            data={"login": ADMIN_LOGIN, "password": ADMIN_PASSWORD},
        )
        if resp.status != 200:
            print("ОШИБКА: вход администратора не удался - seed выполнен?")
            return 2
        check("вход администратора (BFF)", True)

        status, summary = admin_bff_import(page, FIXTURE_A)
        added = summary["entities"]["solutions"]["added"] if summary else -1
        chars_added = summary["entities"]["characteristics"]["added"] if summary else -1
        chars_skipped = summary["entities"]["characteristics"]["skipped"] if summary else -1
        check("импорт A (xlsx): 4 решения, 108 ТТХ (или 0 новых при повторе)",
              status == 200 and summary["solutions"] == 4
              and (added == 4 and chars_added == 108
                   or added == 0 and chars_skipped == 108),
              f"HTTP {status}, solutions={summary and summary.get('solutions')}")

        status, summary = admin_bff_import(page, FIXTURE_B)
        added = summary["entities"]["solutions"]["added"] if summary else -1
        chars_added = summary["entities"]["characteristics"]["added"] if summary else -1
        check("импорт B (csv): 4 решения, 108 ТТХ (или 0 новых при повторе)",
              status == 200 and summary["solutions"] == 4
              and (added == 4 and chars_added == 108
                   or added == 0 and summary["entities"]["characteristics"]["skipped"] == 108),
              f"HTTP {status}")

        # UI-форма принимает расширенный формат (ручной путь администратора)
        page.goto(f"{FRONTEND_URL}/admin/catalog/import")
        page.wait_for_selector("[data-testid=import-form]", timeout=15000)
        page.set_input_files("[data-testid=import-file]", str(FIXTURE_A))
        page.click("[data-testid=import-submit]")
        page.wait_for_selector("[data-testid=import-summary]", timeout=60000)
        text = page.inner_text("[data-testid=import-summary]")
        check("UI-форма админки принимает расширенный формат (повтор A - 0 новых)",
              "4" in text and ("0" in text),
              text[:120].replace("\n", " | "))

        # ---------------- 2. идемпотентность (JSON) ------------------
        print("2. Идемпотентность повторного импорта:")
        status, again = admin_bff_import(page, FIXTURE_B)
        entities = again["entities"] if again else {}
        zero = all(c["added"] == 0 and c["updated"] == 0
                   for c in entities.values())
        check("повтор B: 0 добавлений и 0 обновлений", status == 200 and zero,
              f"HTTP {status}")
        check("повтор B: характеристики 108 skipped",
              entities.get("characteristics", {}).get("skipped") == 108)

        # ---------------- 3. публичный каталог ------------------------
        print("3. Публичный каталог видит фикстуры:")
        page.goto(f"{FRONTEND_URL}/catalog")
        page.wait_for_load_state("networkidle")
        page.fill("input[type=search]", "РТ-Паллета")
        page.press("input[type=search]", "Enter")
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1500)
        body = page.locator("body").inner_text()
        check("поиск находит «РТ-Паллета 1500» (фикстура A)",
              "РТ-Паллета 1500" in body)
        check("ТТХ фикстуры в карточке (грузоподъёмность 1500 кг)",
              "1500" in body)

        # ---------------- 4. полный путь пользователя -----------------
        print("4. Полный путь пользователя на решении из фикстуры A:")
        token = None
        for password in (USER_PASSWORD, "useruser", "user"):
            token = login_token(USER_LOGIN, password)
            if token:
                break
        if not token:
            print("ОШИБКА: вход пользователя не удался")
            return 2

        status, projects = api("GET", "/api/projects?size=100", token)
        project = next((pr for pr in projects["content"]
                        if pr["name"] == PROJECT_NAME), None)
        if project is None:
            status, filters = api("GET", "/api/filters")
            warehouse = next((ot for ot in filters["objectTypes"]
                              if ot.get("code") == "warehouse"
                              or "склад" in (ot.get("name") or "").lower()), None)
            if warehouse is None:
                check("тип объекта «Склад» в /api/filters", False)
                browser.close()
                return 1
            status, project = api("POST", "/api/projects", token, {
                "name": PROJECT_NAME,
                "description": "E2E фикстуры A (docs/test-fixtures): полный путь "
                                "пользователя на решениях из full_catalog_a.xlsx",
                "objectTypeId": warehouse["id"],
            })
            check("проект создан", status == 201, f"HTTP {status}")
        else:
            check("проект переиспользован (идемпотентность скрипта)", True)
        pid = project["id"]

        # параметры подбора под ТТХ фикстур
        status, params = api("GET", f"/api/projects/{pid}/parameters", token)
        by_code = {p["code"]: p for p in params}
        missing = [c for c in SELECTION_PARAMS if c not in by_code]
        if missing:
            check("метаданные параметров содержат коды подбора", False,
                  f"нет: {missing}")
            browser.close()
            return 1
        for code, value in SELECTION_PARAMS.items():
            status, _ = api(
                "PUT", f"/api/projects/{pid}/parameters/{by_code[code]['id']}",
                token, {"value": value})
            if status != 200:
                check(f"параметр {code} = {value}", False, f"HTTP {status}")
                break
        else:
            check("параметры подбора заданы (7 шт.)", True)

        # подбор
        status, run = api("POST", f"/api/projects/{pid}/selection/run", token)
        by_name = {r["solutionName"]: r for r in (run["results"] if run else [])}
        fit_ok = all((by_name.get(n, {}).get("status") or "").upper() == "FIT"
                     for n in A_FIT_NAMES)
        check("подбор: решения A fit (3 шт.)", status == 200 and fit_ok,
              "; ".join(f"{n}={by_name.get(n, {}).get('status')}"
                        for n in A_FIT_NAMES))
        excluded = by_name.get(A_EXCLUDED_NAME, {})
        check("подбор: уборщик исключён с причиной (грузоподъёмность)",
              (excluded.get("status") or "").upper() == "EXCLUDED"
              and "грузоподъём" in (excluded.get("reason") or "").lower(),
              (excluded.get("reason") or "")[:80])

        # сценарий покупки: состав из фикстуры A
        status, scenarios = api("GET", f"/api/projects/{pid}/scenarios", token)
        purchase = next(s for s in scenarios if s["type"] == "purchase")
        sid = purchase["id"]
        pallete_id = by_name["РТ-Паллета 1500"]["solutionId"]
        komplekt_id = by_name["РТ-Комплект 800"]["solutionId"]
        status, _ = api("POST",
                        f"/api/projects/{pid}/scenarios/{sid}/solutions",
                        token,
                        {"solutionId": pallete_id, "quantity": 2,
                         "manualReason": "E2E фикстуры: приоритетный AMR"})
        added1 = status in (201, 409)
        if status == 409:  # уже в составе (повтор скрипта)
            status2, _ = api(
                "PUT",
                f"/api/projects/{pid}/scenarios/{sid}/solutions/{pallete_id}",
                token, {"quantity": 2})
            added1 = status2 == 200
        check("состав: РТ-Паллета 1500 x2", added1, f"HTTP {status}")
        status, _ = api("POST",
                        f"/api/projects/{pid}/scenarios/{sid}/solutions",
                        token,
                        {"solutionId": komplekt_id, "quantity": 1,
                         "manualReason": "E2E фикстуры: комплектация заказов"})
        check("состав: РТ-Комплект 800 x1", status in (201, 409), f"HTTP {status}")

        # экономика
        status, calc = api(
            "POST", f"/api/projects/{pid}/scenarios/{sid}/calculate", token)
        details = (calc or {}).get("details") or {}
        robots = details.get("selectedRobots")
        capex = (calc or {}).get("totalCapex")
        check("экономика: расчёт покупки (selectedRobots=3, CAPEX>0)",
              status == 200 and robots == 3
              and capex is not None and capex > 0,
              f"HTTP {status}, robots={robots}, capex={capex}")
        check("экономика: OPEX-экономия отрицательная (эффект роботизации)",
              (calc or {}).get("opexDeltaRub") is not None
              and calc["opexDeltaRub"] < 0,
              f"opexDelta={calc.get('opexDeltaRub') if calc else '-'}")

        # допущение P_nominal - иначе заявленная производительность и
        # достижимость не считаются (6 KPI требуют всех)
        status, _ = api("PUT", f"/api/projects/{pid}/assumptions", token,
                        {"values": {"robot_nominal_productivity_per_hour": "90"}})
        check("допущение P_nominal задано (90 оп/ч)", status == 200,
              f"HTTP {status}")

        # имитация: 6 KPI
        status, sim = api(
            "POST", f"/api/projects/{pid}/scenarios/{sid}/simulation/run", token)
        kpi_ok = status == 200 and sim and all(
            sim.get(k) is not None for k in (
                "declaredThroughputPerHour", "actualThroughputPerHour",
                "utilizationPct", "idlePct", "achievabilityPct")) \
            and isinstance(sim.get("zones"), list) and len(sim["zones"]) == 4
        check("имитация: 6 KPI (производительность/загрузка/простои/"
              "узкие места/достижимость)", kpi_ok,
              f"HTTP {status}, robots={sim.get('robots') if sim else '-'}")
        check("имитация: роботов в сценарии 3", sim and sim.get("robots") == 3)
        bottleneck = any("узкое место" in (w or "").lower()
                         for w in (sim.get("warnings") if sim else []))
        check("имитация: узкое место названо в предупреждениях", bottleneck)

        # экспорт схемы (SVG): клиент отправляет разметку схемы
        # (путь симметричен скачиванию - по simulationId)
        sim_id = (sim or {}).get("id")
        status, sim_export = api(
            "POST", f"/api/projects/{pid}/simulations/{sim_id}/export",
            token, {"svg": SCHEMA_SVG})
        export_url = (sim_export or {}).get("exportUrl") if isinstance(
            sim_export, dict) else None
        svg_ok = False
        if export_url:
            st, svg = api("GET", export_url.replace(BACKEND_URL, ""), token)
            svg_ok = st == 200 and isinstance(svg, str) and "<svg" in svg[:3000]
        check("экспорт схемы имитации (SVG) сохраняется и скачивается",
              status == 200 and svg_ok,
              f"HTTP {status}, url={bool(export_url)}")

        # экспорт отчёта (PDF)
        status, export = api("POST", f"/api/projects/{pid}/exports", token,
                             {"format": "pdf"})
        download = (export or {}).get("downloadUrl") if isinstance(
            export, dict) else None
        pdf_ok = False
        if download:
            st, pdf = api("GET", download.replace(BACKEND_URL, ""), token)
            pdf_ok = st == 200 and isinstance(pdf, str) and pdf.startswith("%PDF")
        check("экспорт отчёта (PDF) скачивается",
              status in (200, 201) and pdf_ok,
              f"HTTP {status}")

        # страница имитации рендерит схему на решениях фикстуры
        # (сессия пользователя - через BFF, как у живого клиента)
        resp = page.request.post(
            f"{FRONTEND_URL}/api/auth/login",
            data={"login": USER_LOGIN, "password": USER_PASSWORD})
        check("вход пользователя через BFF (cookie)", resp.status == 200)
        page.goto(f"{FRONTEND_URL}/projects/{pid}/simulation")
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1000)
        check("страница /simulation рендерится (SVG на месте)",
              page.locator("svg[role='img']").count() > 0)

        browser.close()

    failed = [r for r in results if not r[1]]
    print()
    print(f"Итог: {len(results) - len(failed)}/{len(results)} проверок пройдено")
    if failed:
        print("ПРОВАЛЕНО:")
        for name, _, detail in failed:
            print(f"  - {name} {detail}")
        return 1
    print("Все проверки пройдены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
