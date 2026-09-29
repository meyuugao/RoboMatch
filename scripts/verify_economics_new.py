#!/usr/bin/env python3
"""E2E-верификация демо-проектов из тестовых фикстур (задача 4) на живом backend.

Что делает:
  1. импортирует фикстуры A/B через админ-API (идемпотентно - повтор
     ничего не меняет);
  2. находит проекты «Демо: *» (создаются seed-ом
     test_projects_repo.py; если seed шёл ДО импорта фикстур и состав
     пуст - добавляет решения через API, как живой пользователь);
  3. для каждого: POST /selection/run (составы фикстур - fit, без
     исключений по габаритам/мощности) + POST /calculate ×3
     (base/purchase/raas) + GET /economics/compare;
  4. сверяет метрики с машинным эталоном docs/economics_golden_new.md
     и проверяет инварианты задачи 4: underpowered/overpowered = false,
     Effect_year > 0, ROI ≤ 500 %, Payback ≤ 5 лет, состав - только
     решения фикстур (РТ-*/АТ-*).

Запуск (backend и БД подняты, seed выполнен):
  BACKEND_URL=http://localhost:8080 python3 scripts/verify_economics_new.py

Переменные окружения:
  BACKEND_URL    - адрес backend (по умолчанию http://localhost:8080)
  E2E_LOGIN      - логин владельца (user)
  E2E_PASSWORD   - пароль (useruser)
  ADMIN_PASSWORD - пароль админа для импорта фикстур (adminadmin)
"""
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "useruser", "user")
ADMIN_PASSWORDS = (os.environ.get("ADMIN_PASSWORD"), "adminadmin")
REPO = Path(__file__).resolve().parent.parent
FIXTURES = (REPO / "docs" / "test-fixtures" / "full_catalog_a.xlsx",
            REPO / "docs" / "test-fixtures" / "full_catalog_b.csv")
GOLDEN_PATH = REPO / "docs" / "economics_golden_new.md"

DEMO_COMPOSITIONS = {
    "Демо: Оптимальный склад": {"РТ-Паллета 1500": 5},
    "Демо: Пиковая нагрузка - успех": {"АТ-Паллета 2500": 4,
                                       "АТ-Штабелер 1000": 2},
    "Демо: Умеренный масштаб": {"РТ-Комплект 800": 5,
                                "АТ-Штабелер 1000": 2},
}

results: list[tuple[str, bool, str]] = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" - {detail}" if detail else ""))


def request(method, path, token=None, body=None, multipart=None):
    req = urllib.request.Request(BACKEND_URL + path, method=method)
    data = None
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    if multipart is not None:
        boundary = "----verifydemo"
        parts = []
        for name, (fname, content) in multipart.items():
            parts.append(
                f"--{boundary}\r\nContent-Disposition: form-data; "
                f'name="{name}"; filename="{fname}"\r\n'
                f"Content-Type: application/octet-stream\r\n\r\n".encode()
                + content + b"\r\n")
        parts.append(f"--{boundary}--\r\n".encode())
        data = b"".join(parts)
        req.add_header("Content-Type",
                       f"multipart/form-data; boundary={boundary}")
    try:
        with urllib.request.urlopen(req, data, timeout=90) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def load_golden(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    match = re.search(r"```golden\s*(\{.*?\})\s*```", text, re.DOTALL)
    if not match:
        raise SystemExit(f"Блок ```golden не найден в {path}")
    return json.loads(match.group(1))


def resolve(calc: dict, path: str):
    details = calc.get("details") or {}
    node = details
    for part in path.split("."):
        if not isinstance(node, dict) or part not in node:
            node = None
            break
        node = node[part]
    if node is not None:
        return node
    top = {"capex.total": "totalCapex", "opex.total": "totalOpex",
           "opexDelta": "opexDeltaRub"}.get(path, path)
    return calc.get(top)


def main() -> int:
    golden = load_golden(GOLDEN_PATH)

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
        print("ОШИБКА: логин не удался - стек поднят, seed выполнен?")
        return 2

    # 1. импорт фикстур (идемпотентен)
    admin = None
    for password in ADMIN_PASSWORDS:
        if not password:
            continue
        status, auth = request("POST", "/api/auth/login",
                               body={"login": "admin", "password": password})
        if status == 200:
            admin = auth["token"]
            break
    if admin is None:
        print("ОШИБКА: вход администратора не удался")
        return 2
    print("1. Импорт фикстур через админку (идемпотентный):")
    for fixture in FIXTURES:
        status, summary = request("POST", "/api/admin/catalog/import", admin,
                                  multipart={"file": (
                                      fixture.name, fixture.read_bytes())})
        added = ((summary or {}).get("entities", {})
                 .get("solutions", {}).get("added"))
        check(f"импорт {fixture.name}", status == 200
              and added in (0, 4), f"HTTP {status}, added={added}")

    # 2. решения фикстур в каталоге
    print("2. Решения фикстур в каталоге:")
    sol_ids = {}
    for q in ("РТ-", "АТ-"):
        status, sols = request(
            "GET", "/api/solutions?q=" + urllib.parse.quote(q) + "&size=50",
            token)
        for s in (sols.get("content") or []):
            sol_ids[s["name"]] = s["id"]
    check("8 решений фикстур найдено", len(sol_ids) == 8,
          ", ".join(sorted(sol_ids)))

    status, page = request("GET", "/api/projects?size=100", token)
    by_name = {p["name"]: p["id"] for p in page["content"]}

    mismatches = []
    total_checks = 0
    for project_name, expected_scenarios in golden.items():
        pid = by_name.get(project_name)
        print(f"\n=== {project_name} ===")
        if pid is None:
            check("проект создан seed-ом", False, "не найден")
            mismatches.append((project_name, "-", "проект", "есть", "нет"))
            continue
        check("проект создан seed-ом", True, f"id {pid}")

        # 3. состав: seed мог идти до импорта фикстур - добавляем как
        # пользователь (409 = уже есть)
        status, scenario_list = request(
            "GET", f"/api/projects/{pid}/scenarios", token)
        sid_by_type = {s["type"]: s["id"] for s in scenario_list}
        for stype in ("purchase", "raas"):
            sid = sid_by_type.get(stype)
            for sol_name, qty in DEMO_COMPOSITIONS[project_name].items():
                # 409 = строка уже в составе (seed успел раньше импорта
                # фикстур или прошлый прогон) - норма
                status, _ = request(
                    "POST",
                    f"/api/projects/{pid}/scenarios/{sid}/solutions",
                    token, {"solutionId": sol_ids[sol_name], "quantity": qty,
                            "manualReason": "демо-состав из фикстуры"})
                if status not in (201, 409):
                    check(f"состав {stype}: {sol_name}", False,
                          f"HTTP {status}")
                    mismatches.append((project_name, stype, "состав",
                                       "201/409", status))

        # 4. подбор: решения фикстур fit
        status, run = request("POST",
                              f"/api/projects/{pid}/selection/run", token)
        by_sol = {r["solutionName"]: r for r in (run.get("results") or [])}
        for sol_name in DEMO_COMPOSITIONS[project_name]:
            r = by_sol.get(sol_name, {})
            check(f"подбор: {sol_name} - fit",
                  (r.get("status") or "").upper() == "FIT",
                  f"{r.get('status')} {str(r.get('reason'))[:60]}")

        # 5. расчёт ×3 + сверка с эталоном + инварианты задачи 4
        for scenario_type, expected in expected_scenarios.items():
            sid = sid_by_type.get(scenario_type)
            status, calc = request(
                "POST", f"/api/projects/{pid}/scenarios/{sid}/calculate",
                token)
            if status != 200:
                check(f"calculate {scenario_type}", False, f"HTTP {status}")
                mismatches.append((project_name, scenario_type, "calculate",
                                   "200", status))
                continue
            for field, want in expected.items():
                got = resolve(calc, field)
                total_checks += 1
                ok = (got == want) or (
                    isinstance(want, (int, float))
                    and isinstance(got, (int, float))
                    and not isinstance(want, bool) and not isinstance(got, bool)
                    and abs(float(got) - float(want)) < 1e-6)
                if not ok:
                    mismatches.append((project_name, scenario_type,
                                       field, want, got))
            check(f"расчёт {scenario_type} совпал с эталоном "
                  f"({len(expected)} полей)",
                  not [m for m in mismatches
                       if m[0] == project_name and m[1] == scenario_type])

            details = calc.get("details") or {}
            if scenario_type in ("purchase", "raas"):
                comp = details.get("composition") or []
                only_fixtures = all(
                    c["solutionName"].startswith(("РТ-", "АТ-"))
                    for c in comp)
                check(f"{scenario_type}: состав только из фикстур",
                      only_fixtures and len(comp) > 0,
                      ", ".join(c["solutionName"] for c in comp))
                check(f"{scenario_type}: underpowered/overpowered = false",
                      details.get("underpowered") is False
                      and details.get("overpowered") is False,
                      f"under={details.get('underpowered')} "
                      f"over={details.get('overpowered')}")
                check(f"{scenario_type}: Effect_year > 0",
                      (calc.get("effectYear") or 0) > 0,
                      str(calc.get("effectYear")))
            if scenario_type == "purchase":
                roi = calc.get("roiPct")
                payback = calc.get("paybackYears")
                check("purchase: ROI ≤ 500 % (сноска A10 не работает)",
                      roi is not None and roi <= 500, str(roi))
                check("purchase: Payback ≤ 5 лет",
                      payback is not None and payback <= 5, str(payback))

        # 6. сравнение сценариев (GET /economics/compare)
        status, comparison = request(
            "GET", f"/api/projects/{pid}/compare", token)
        scenarios_rows = (comparison or {}).get("scenarios") \
            if isinstance(comparison, dict) else comparison
        check("сравнение сценариев доступно (3 сценария)",
              status == 200 and isinstance(scenarios_rows, list)
              and len(scenarios_rows) == 3, f"HTTP {status}")

    print(f"\nПроверок: {total_checks} полей эталона, "
          f"расхождений: {len(mismatches)}")
    passed = sum(1 for _, ok, _ in results if ok)
    total = len(results)
    print(f"Итог: {passed}/{total} проверок пройдено")
    if mismatches:
        print("\nРАСХОЖДЕНИЯ (проект / сценарий / поле / ожидалось / получено):")
        for m in mismatches:
            print(f"  {' / '.join(str(x) for x in m)}")
    if passed < total or mismatches:
        print("Провалены:")
        for name, ok, detail in results:
            if not ok:
                print(f"  [FAIL] {name} {detail}")
        return 1
    print("OK: демо-проекты воспроизводятся, эталон economics_golden_new.md "
          "и инварианты задачи 4 выполнены")
    return 0


if __name__ == "__main__":
    sys.exit(main())
