#!/usr/bin/env python3
"""E2E-верификация формул экономики на живом backend.

Что делает:
  1. читает машинный эталон (блок ```golden) из docs/economics_golden.md;
  2. логинится владельцем E2E-проектов - демо-аккаунт user (создаётся seed);
  3. находит проекты «E2E: *» по имени;
  4. для каждого: POST /selection/run (идемпотентный полный путь)
     + POST /calculate каждого сценария из эталона;
  5. сверяет каждое поле (формулы §2.1–2.13) с эталоном: печатает
     таблицу «формула / ожидалось / получено / Δ», при расхождении -
     exit code 1 (для CI); совпадение - exit 0.

Запуск (backend и БД подняты, seed выполнен):
  BACKEND_URL=http://localhost:8080 python3 scripts/verify_economics.py

Переменные окружения:
  BACKEND_URL   - адрес backend (по умолчанию http://localhost:8080)
  E2E_LOGIN     - логин владельца E2E-проектов (user)
  E2E_PASSWORD  - пароль (useruser; в БД, засеянной ранней версией seed, - user)

Эталон меняется ТОЛЬКО вместе с формулами (docs/economic_model.md §2)
и версией version_model (economic-model-1.x).
"""
import json
import os
import re
import sys
import urllib.request
from pathlib import Path

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
LOGIN = os.environ.get("E2E_LOGIN", "user")
# Пароль демо-аккаунта: текущий seed задаёт user/useruser; на БД,
# засеянной ранней версией, остаётся user - пробуем оба (env
# E2E_PASSWORD фиксирует один конкретный).
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "useruser", "user")
GOLDEN_PATH = Path(__file__).resolve().parent.parent / "docs" / "economics_golden.md"


def request(method, path, token=None, body=None):
    req = urllib.request.Request(BACKEND_URL + path, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data, timeout=30) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def load_golden(path: Path) -> dict:
    """Извлечь машинный эталон: блок ```golden { ... } ``` из доки."""
    text = path.read_text(encoding="utf-8")
    match = re.search(r"```golden\s*(\{.*?\})\s*```", text, re.DOTALL)
    if not match:
        raise SystemExit(f"Блок ```golden не найден в {path}")
    return json.loads(match.group(1))


def resolve(calc: dict, path: str):
    """Значение по пути: сначала details (metrics_json), затем колонки."""
    details = calc.get("details") or {}
    node = details
    for part in path.split("."):
        if not isinstance(node, dict) or part not in node:
            node = None
            break
        node = node[part]
    if node is not None:
        return node
    # колонки расчёта (зеркала details): opexDelta -> opexDeltaRub и т.п.
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
        print(f"ОШИБКА: логин {LOGIN} не удался (пробованы пароли "
              "из E2E_PASSWORD, useruser, user) - стек поднят, seed выполнен?")
        return 2

    status, page = request("GET", "/api/projects?size=100", token)
    if status != 200:
        print(f"ОШИБКА: список проектов -> HTTP {status}")
        return 2
    by_name = {p["name"]: p["id"] for p in page["content"]}

    mismatches = []
    total_checks = 0
    for project_name, scenarios in golden.items():
        pid = by_name.get(project_name)
        if pid is None:
            print(f"ОШИБКА: проект «{project_name}» не найден (seed?)")
            mismatches.append((project_name, "-", "проект", "есть", "нет"))
            continue
        # полный путь: подбор (идемпотентен) + расчёт каждого сценария
        status, _ = request("POST", f"/api/projects/{pid}/selection/run", token)
        if status != 200:
            print(f"ОШИБКА: selection/run «{project_name}» -> HTTP {status}")
            mismatches.append((project_name, "-", "подбор", "200", status))
            continue
        status, scenario_list = request("GET", f"/api/projects/{pid}/scenarios", token)
        sid_by_type = {s["type"]: s["id"] for s in scenario_list}
        print(f"\n=== {project_name} (id {pid}) ===")
        print(f"{'поле':<28} {'ожидалось':>16} {'получено':>16} {'Δ':>10}")
        for scenario_type, expected in scenarios.items():
            sid = sid_by_type.get(scenario_type)
            if sid is None:
                print(f"  [{scenario_type}] НЕТ СЦЕНАРИЯ")
                mismatches.append((project_name, scenario_type,
                                   "сценарий", "есть", "нет"))
                continue
            status, calc = request(
                "POST", f"/api/projects/{pid}/scenarios/{sid}/calculate", token)
            if status != 200:
                print(f"  [{scenario_type}] calculate -> HTTP {status}: "
                      f"{str(calc)[:160]}")
                mismatches.append((project_name, scenario_type,
                                   "calculate", "200", status))
                continue
            for field, want in expected.items():
                got = resolve(calc, field)
                total_checks += 1
                ok = (got == want) or (
                    isinstance(want, (int, float)) and isinstance(got, (int, float))
                    and not isinstance(want, bool) and not isinstance(got, bool)
                    and abs(float(got) - float(want)) < 1e-9)
                delta = ""
                if not ok:
                    delta = "≠"
                    mismatches.append((project_name, scenario_type,
                                       field, want, got))
                want_s = "null" if want is None else str(want)
                got_s = "null" if got is None else str(got)
                print(f"  [{scenario_type:<8}] {field:<16} {want_s:>16} "
                      f"{got_s:>16} {delta:>10}")

    print(f"\nПроверок: {total_checks}, расхождений: {len(mismatches)}")
    if mismatches:
        print("\nРАСХОЖДЕНИЯ (проект / сценарий / поле / ожидалось / получено):")
        for m in mismatches:
            print(f"  {' / '.join(str(x) for x in m)}")
        return 1
    print("OK: все формулы §2.1–2.13 совпадают с эталоном economics_golden.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
