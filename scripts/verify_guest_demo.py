#!/usr/bin/env python3
"""E2E-проверка гостевого демо-расчёта на живом стеке.

Сценарий (роль «гость», без входа): страница /demo → выбор типа объекта
(демо-набор «Склад») → предзаполненные параметры демо-набора → кнопка
«Показать демо-расчёт» → таблица сравнения трёх сценариев.

Что проверяет:
  1.  backend GET /api/demo без токена - 200, типы объектов, параметры
      и состав демо-набора;
  2.  backend POST /api/demo/calculate без токена - 200, признак демо,
      три сценария base/purchase/raas с рассчитанной экономикой;
  3.  ГЛАВНОЕ: демо-расчёт не пишет в БД - количества строк project,
      scenario, calculation, calculation_assumption не меняются
      (прямые SELECT в PostgreSQL до и после);
  4.  страница /demo открывается гостю без входа: заголовок, параметры,
      состав, кнопка;
  5.  клик «Показать демо-расчёт» - таблица сравнения рендерится:
      три колонки сценариев, CAPEX/OPEX/окупаемость;
  6.  типы без демо-набора (Аэропорт) недоступны для выбора;
  7.  ссылка «Демо-расчёт» в шапке для гостя; CTA на главной;
  8.  гость НЕ может сохранять: /projects по-прежнему ведёт на /login;
  9.  в DOM страницы нет отсылок к процессу разработки;
  10. BFF-прокси /api/demo и /api/demo/calculate работают без сессии.

Запуск (PostgreSQL + backend + frontend подняты, seed выполнен):
  FRONTEND_URL=http://localhost:3100 python3 scripts/verify_guest_demo.py

Переменные окружения:
  FRONTEND_URL - адрес frontend (по умолчанию http://localhost:3100)
  BACKEND_URL  - адрес backend (по умолчанию http://localhost:8080)
  E2E_DB_URL   - psql-строка для счётчиков строк
                 (по умолчанию postgres://postgres:postgres@localhost:5433/robomatch)
"""
import os
import subprocess
import sys
import urllib.request
import json

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3100")
BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
# E2E_DB_URL (не DATABASE_URL - то занято окружением песочницы)
DB_URL = os.environ.get(
    "E2E_DB_URL",
    "postgres://postgres:postgres@localhost:5433/robomatch",
)

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" - {detail}" if detail else ""))


def api(method: str, url: str, body: dict | None = None):
    """Запрос без токена: (статус, JSON или None)."""
    request = urllib.request.Request(url, method=method)
    data = None
    if body is not None:
        request.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(request, data, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, None


def table_counts() -> dict[str, int] | None:
    """Количество строк ключевых таблиц (psql)."""
    psql = os.environ.get(
        "PSQL_BIN", "/home/z/pg-root/usr/lib/postgresql/17/bin/psql"
    )
    if not os.path.exists(psql):
        return None
    sql = (
        "SELECT 'project', COUNT(*) FROM project "
        "UNION ALL SELECT 'scenario', COUNT(*) FROM scenario "
        "UNION ALL SELECT 'calculation', COUNT(*) FROM calculation "
        "UNION ALL SELECT 'calculation_assumption', "
        "COUNT(*) FROM calculation_assumption;"
    )
    out = subprocess.run(
        [psql, DB_URL, "-tA", "-F", "=", "-c", sql],
        capture_output=True, text=True, timeout=30,
    )
    if out.returncode != 0:
        return None
    return dict(
        line.split("=", 1) for line in out.stdout.strip().splitlines()
    )


def main() -> int:
    # --- 1. Дескриптор демо без токена -------------------------------
    status, descriptor = api("GET", f"{BACKEND_URL}/api/demo")
    check(
        "backend: GET /api/demo без токена - 200",
        status == 200 and descriptor is not None,
        f"статус {status}",
    )
    types_ok = status == 200 and any(
        t.get("code") == "warehouse" and t.get("available")
        for t in descriptor.get("availableTypes", [])
    )
    check(
        "backend: тип «Склад» доступен, прочие - нет",
        types_ok
        and status == 200
        and any(
            not t.get("available")
            for t in descriptor.get("availableTypes", [])
        ),
    )
    check(
        "backend: параметры демо-набора заполнены",
        status == 200 and len(descriptor.get("parameters", [])) >= 10,
        f"{len(descriptor.get('parameters', []))} параметров",
    )
    check(
        "backend: демо-состав из каталога",
        status == 200
        and len(descriptor.get("composition", [])) == 1
        and descriptor["composition"][0].get("quantity") == 3,
        descriptor.get("composition", [{}])[0].get("solutionName", "-"),
    )

    # --- 2. Расчёт без токена ----------------------------------------
    before = table_counts()
    status, calc = api(
        "POST", f"{BACKEND_URL}/api/demo/calculate", {"objectTypeCode": "warehouse"}
    )
    check(
        "backend: POST /api/demo/calculate без токена - 200",
        status == 200 and calc is not None,
        f"статус {status}",
    )
    scenarios = calc.get("comparison", {}).get("scenarios", []) if calc else []
    check(
        "backend: три сценария base/purchase/raas",
        [s.get("type") for s in scenarios] == ["base", "purchase", "raas"],
        str([s.get("type") for s in scenarios]),
    )
    purchase = next(
        (s for s in scenarios if s.get("type") == "purchase"), {}
    )
    check(
        "backend: покупка - CAPEX и окупаемость рассчитаны",
        bool(purchase.get("capex"))
        and purchase.get("payback", {}).get("paybackYears") is not None,
        f"CAPEX {purchase.get('capex')}, "
        f"payback {purchase.get('payback', {}).get('paybackYears')}",
    )
    check(
        "backend: признак демо (не сохраняется)",
        calc is not None and calc.get("demo") is True,
    )
    # Ошибки выбора типа
    status_unknown, _ = api(
        "POST", f"{BACKEND_URL}/api/demo/calculate", {"objectTypeCode": "nope"}
    )
    status_disabled, _ = api(
        "POST", f"{BACKEND_URL}/api/demo/calculate", {"objectTypeCode": "airport"}
    )
    check(
        "backend: неизвестный тип - 404, без демо-набора - 400",
        status_unknown == 404 and status_disabled == 400,
        f"{status_unknown}/{status_disabled}",
    )

    # --- 3. Ничего не сохранено --------------------------------------
    after = table_counts()
    if before is not None and after is not None:
        check(
            "БД: демо-расчёт не пишет ни одной строки",
            before == after,
            f"{before} == {after}",
        )
    else:
        check("БД: psql недоступен - счётчики пропущены", False)

    # --- 4-9. Живой браузер: гостевой путь ---------------------------
    with sync_playwright() as p:
        browser = p.chromium.launch()
        context = browser.new_context(viewport={"width": 1366, "height": 900})
        page = context.new_page()
        errors: list[str] = []
        page.on("pageerror", lambda exc: errors.append(str(exc)))

        # BFF без сессии
        status_bff, _ = api("GET", f"{FRONTEND_URL}/api/demo")
        check(
            "BFF: GET /api/demo работает без сессии",
            status_bff == 200,
            f"статус {status_bff}",
        )

        page.goto(f"{FRONTEND_URL}/demo", wait_until="domcontentloaded")
        page.locator("[data-testid='demo-parameters']").wait_for(
            timeout=30_000
        )
        check(
            "UI: /demo открыта гостю - заголовок «Демо-расчёт»",
            page.get_by_role("heading", name="Демо-расчёт").count() == 1,
        )
        check(
            "UI: параметры демо-набора отрисованы",
            page.locator("[data-testid='demo-parameters']").count() == 1
            and page.locator("[data-testid='demo-parameters'] > div").count()
            >= 10,
            f"{page.locator('[data-testid=demo-parameters] > div').count()} "
            "параметров",
        )
        check(
            "UI: состав демо виден",
            "Ronavi" in page.locator("[data-testid='demo-composition']")
            .inner_text(),
        )
        check(
            "UI: Аэропорт недоступен для выбора",
            page.locator("[data-testid='demo-type-airport']").is_disabled(),
        )

        # Клик расчёта
        page.locator("[data-testid='demo-calculate-button']").click()
        page.locator("[data-testid='demo-result']").wait_for(timeout=30_000)
        result_text = page.locator("[data-testid='demo-result']").inner_text()
        check(
            "UI: таблица сравнения - три сценария с числами",
            "Текущий процесс" in result_text
            and "Покупка оборудования" in result_text
            and "Роботы как услуга" in result_text
            and "CAPEX" in result_text,
        )
        check(
            "UI: пометка «не сохраняется»",
            "не сохраняется" in result_text.lower(),
        )
        dom = page.content()
        check(
            "UI: нет упоминаний процесса разработки",
            "ТЗ" not in dom and "хакатон" not in dom.lower(),
        )
        check(
            "UI: JS-ошибок на странице нет",
            not errors,
            "; ".join(errors[:2]),
        )

        # Гость не может сохранять: /projects -> /login
        page.goto(f"{FRONTEND_URL}/projects", wait_until="domcontentloaded")
        check(
            "изоляция: гость с /projects попадает на /login",
            page.url.rstrip("/").endswith("/login"),
            page.url,
        )

        # Ссылки для гостя
        page.goto(f"{FRONTEND_URL}/", wait_until="domcontentloaded")
        check(
            "UI: «Демо-расчёт» в шапке для гостя",
            page.locator("[data-testid='nav-demo']").count() == 1,
        )
        check(
            "UI: CTA «Демо-расчёт без регистрации» на главной",
            page.get_by_role(
                "link", name="Демо-расчёт без регистрации"
            ).count() == 1,
        )

        # И снова счётчики после живого прохода
        after_browser = table_counts()
        if before is not None and after_browser is not None:
            check(
                "БД: после живого прохода - по-прежнему пусто в расчётах",
                before == after_browser,
            )

        browser.close()

    failed = [r for r in results if not r[1]]
    print()
    print(f"Итог: {len(results) - len(failed)}/{len(results)} "
          "проверок пройдено")
    if failed:
        print(f"Провалены: {', '.join(name for name, _, _ in failed)}")
        return 1
    print("Все проверки пройдены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
