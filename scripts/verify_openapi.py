#!/usr/bin/env python3
"""E2E-проверка OpenAPI-документации на живом стеке (Spring Boot 4).

Что проверяет (HTTP + Playwright/Chromium для Swagger UI):
  1. /v3/api-docs доступен, отдаёт валидный JSON (OpenAPI 3.1);
  2. все группы контроллеров объявлены и используются (@Tag):
     Каталог, Аутентификация, Проекты, Параметры объекта, Подбор,
     Экономика, Имитация, Экспорт, Админка (+ Справочники, Сценарии);
  3. каждая операция тегирована, имеет summary и объявленные коды
     ответов; operationId уникальны;
  4. схема безопасности bearer-jwt (http/bearer/JWT) объявлена,
     защищённые операции ссылаются на неё, публичных ровно 6;
  5. примеры: все поля DTO-схем (450+) имеют example, у каждого
     JSON-тела POST/PUT есть @ExampleObject, значения - валидный JSON;
  5-bis. конвенция DTO (единый суффикс *Dto, без *View/*Response) и
     консистентность путей: ручное добавление под /scenarios, схема
     имитации POST/GET симметричны по simulationId, read-дубликат
     карточки под /api/admin убран;
  6. Swagger UI: /swagger-ui.html редиректит на index.html (200),
     UI открывается в браузере, операции отрисованы, кнопка Authorize
     присутствует;
  7. живой сценарий Authorize: логин -> JWT, защищённый эндпоинт с
     Bearer -> 200, без токена -> 401.

Запуск (PostgreSQL + backend подняты, seed выполнен):
  BACKEND_URL=http://localhost:8080 python3 scripts/verify_openapi.py

Переменные окружения:
  BACKEND_URL  - адрес backend (по умолчанию http://localhost:8080)
  E2E_LOGIN    - логин демо-аккаунта (user)
  E2E_PASSWORD - пароль (useruser)
"""
import json
import os
import sys
import urllib.error
import urllib.request

BACKEND_URL = os.environ.get("BACKEND_URL", "http://localhost:8080")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "user", "useruser")

# Группы контроллеров.
REQUIRED_TAGS = {
    "Каталог решений", "Аутентификация", "Проекты", "Параметры объекта",
    "Подбор решений", "Экономика и сценарии", "Имитация 2D и KPI",
    "Экспорт отчётов", "Админка", "Справочники каталога",
    "Сценарии расчёта",
}

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    mark = "ok " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" - {detail}" if detail else ""))


def http_get(url, headers=None, allow_redirects=False):
    """GET без внешних зависимостей. Возвращает (status, body, final_url)."""
    req = urllib.request.Request(url, headers=headers or {})
    opener = urllib.request.build_opener(
        NoRedirect() if not allow_redirects else urllib.request.HTTPRedirectHandler())
    try:
        with opener.open(req, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8"), resp.url
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8"), e.headers.get(
            "Location", url)
    except urllib.error.URLError as e:
        return 0, str(e.reason), url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_post_json(url, payload, headers=None):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.build_opener().open(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}
    except urllib.error.URLError as e:
        return 0, {"error": str(e.reason)}


def main():
    print(f"OpenAPI: {BACKEND_URL}/v3/api-docs")

    # --- 1. Доступность и валидность спецификации ------------------------
    status, body, _ = http_get(f"{BACKEND_URL}/v3/api-docs")
    check("спецификация доступна", status == 200, f"HTTP {status}")
    spec = {}
    try:
        spec = json.loads(body)
        check("спецификация - валидный JSON", True)
    except json.JSONDecodeError as e:
        check("спецификация - валидный JSON", False, str(e))
        return finish()

    check("версия OpenAPI 3.1", str(spec.get("openapi", "")).startswith("3.1"),
          spec.get("openapi", ""))
    info = spec.get("info", {})
    check("info.title и версия заполнены",
          bool(info.get("title")) and bool(info.get("version")),
          f"{info.get('title')} {info.get('version')}")

    # --- 2. Теги: все группы контроллеров --------------------------------
    declared = {t.get("name") for t in spec.get("tags", [])}
    missing_tags = REQUIRED_TAGS - declared
    check("все группы контроллеров объявлены (@Tag)", not missing_tags,
          f"отсутствуют: {sorted(missing_tags)}" if missing_tags
          else f"{len(declared)} групп")

    ops = []
    for path, methods in spec.get("paths", {}).items():
        for method, op in methods.items():
            if isinstance(op, dict) and method in (
                    "get", "post", "put", "patch", "delete"):
                ops.append((method.upper(), path, op))

    used_tags = set()
    untagged = []
    for method, path, op in ops:
        op_tags = op.get("tags", [])
        used_tags.update(op_tags)
        if not op_tags:
            untagged.append(f"{method} {path}")
    check("каждая операция тегирована", not untagged,
          "; ".join(untagged[:3]))
    check("теги операций совпадают с объявленными",
          used_tags <= declared and REQUIRED_TAGS <= used_tags,
          f"объявлено {len(declared)}, используется {len(used_tags)}")
    check("операций не меньше 59", len(ops) >= 59, f"{len(ops)}")

    no_summary = [f"{m} {p}" for m, p, op in ops if not op.get("summary")]
    check("у всех операций есть summary", not no_summary,
          "; ".join(no_summary[:3]))
    no_responses = [f"{m} {p}" for m, p, op in ops if not op.get("responses")]
    check("у всех операций объявлены ответы", not no_responses,
          "; ".join(no_responses[:3]))

    op_ids = [op.get("operationId") for _, _, op in ops]
    dup = {i for i in op_ids if op_ids.count(i) > 1}
    check("operationId уникальны", not dup, f"дубликаты: {sorted(dup)[:3]}")

    # --- 3. Схема безопасности -------------------------------------------
    schemes = spec.get("components", {}).get("securitySchemes", {})
    jwt = schemes.get("bearer-jwt", {})
    check("схема bearer-jwt объявлена", bool(jwt))
    check("bearer-jwt: http/bearer/JWT",
          jwt.get("type") == "http" and jwt.get("scheme") == "bearer"
          and jwt.get("bearerFormat") == "JWT",
          f"{jwt.get('type')}/{jwt.get('scheme')}/{jwt.get('bearerFormat')}")

    secured = [f"{m} {p}" for m, p, op in ops if op.get("security")]
    public = [f"{m} {p}" for m, p, op in ops if not op.get("security")]
    check("защищённые операции ссылаются на bearer-jwt",
          len(secured) >= 50 and all(
              any(s.get("bearer-jwt") is not None for s in op["security"])
              for _, _, op in ops if op.get("security")),
          f"{len(secured)} защищённых")
    # гостевой минимум: вход/регистрация, каталог, демо-расчёт
    check("публичных операций ровно 8 (auth + каталог + демо)",
          len(public) == 8, "; ".join(sorted(public)))

    # --- 4. Примеры: поля DTO и тела запросов ----------------------------
    schemas = spec.get("components", {}).get("schemas", {})
    props_total, props_no_example, stubs = 0, [], []
    for name, sch in schemas.items():
        for pname, pdesc in (sch.get("properties") or {}).items():
            props_total += 1
            ex = pdesc.get("example", pdesc.get("default"))
            if ex is None:
                props_no_example.append(f"{name}.{pname}")
            elif isinstance(ex, str) and ex.strip() in ("string", "строка"):
                stubs.append(f"{name}.{pname}")
    check("у всех полей DTO есть примеры", not props_no_example,
          f"{props_total} полей, без примера: {props_no_example[:3]}")
    check("примеры не заглушки string", not stubs,
          "; ".join(stubs[:3]))

    body_ops, body_no_ex, bad_json = [], [], []
    for method, path, op in ops:
        rb = op.get("requestBody", {})
        content = rb.get("content", {})
        json_ct = content.get("application/json")
        if not json_ct:
            continue
        # Загрузка файла (@RequestPart MultipartFile): springdoc помечает
        # такое тело application/json с format=binary - пример DTO не нужен.
        schema = json_ct.get("schema") or {}
        if not schema.get("$ref") and any(
                (p or {}).get("format") == "binary"
                for p in (schema.get("properties") or {}).values()):
            continue
        body_ops.append(f"{method} {path}")
        ex_field = json_ct.get("examples", json_ct.get("example"))
        if ex_field is None or ex_field == {}:
            body_no_ex.append(f"{method} {path}")
            continue
        # Именованные примеры: {"имя": {"value": ...}}; иначе - значение целиком
        # (springdoc парсит JSON-строку ExampleObject в объект media.example).
        if isinstance(ex_field, dict) and ex_field and all(
                isinstance(v, dict) and "value" in v
                for v in ex_field.values()):
            value = next(iter(ex_field.values()))["value"]
        else:
            value = ex_field
        try:
            json.loads(value) if isinstance(value, str) else json.loads(
                json.dumps(value))
        except (TypeError, ValueError):
            bad_json.append(f"{method} {path}")
    check("у всех JSON-тел есть @ExampleObject", not body_no_ex,
          f"{len(body_ops)} JSON-тел, без примера: {body_no_ex[:3]}")
    check("значения примеров тел - валидный JSON", not bad_json,
          "; ".join(bad_json[:3]))

    # --- 4-ter. Отсылок к ТЗ и внутренним документам в описаниях нет ----
    import re as _re
    bad_refs = []

    def _scan(obj, path):
        if isinstance(obj, dict):
            for k, v in obj.items():
                _scan(v, f"{path}.{k}")
        elif isinstance(obj, list):
            for i, v in enumerate(obj):
                _scan(v, f"{path}[{i}]")
        elif isinstance(obj, str):
            if _re.search(r"ТЗ|\bп\.\d|Дополнени|AGENTS\.md", obj):
                bad_refs.append(f"{path}: {obj[:70]}")

    _scan(spec, "")
    check("описания OpenAPI без отсылок к ТЗ/внутренним документам",
          not bad_refs, "; ".join(bad_refs[:3]))

    # --- 4-bis. Конвенция DTO и пути API ---------------------------------
    # Output-DTO - единый суффикс *Dto (architecture.md §3); ручное
    # добавление - под /scenarios; сохранение схемы симметрично
    # скачиванию по simulationId; read-дубликат карточки под /api/admin
    # убран (админ использует публичный read + write).
    dto_new = [
        "SelectionRunDto", "AuthDto", "ParameterDto", "FiltersDto",
        "CatalogImportSummaryDto", "EntityCountersDto",
        "ScenarioDto", "ScenarioDetailsDto", "ScenarioSolutionDto",
        "AssumptionDto", "SelectionResultDto", "SimulationRunDto",
        "ZoneLoadDto", "ScenarioCompositionLineDto", "SimulationInputsDto",
        "CalculationDto", "CalculationFullDto", "AssumptionSnapshotDto",
        "AdjustmentDto", "ExportDto", "ComparisonDto", "ScenarioColumnDto",
        "InterpretationDto", "AdminReferenceDto", "AdminImportDto",
        "SensitivityRowDto", "ScenarioCompositionItemDto",
        "BulkDeleteRequest", "BulkDeleteResultDto",
    ]
    missing_dto = [n for n in dto_new if n not in schemas]
    check("все новые имена *Dto в спецификации", not missing_dto,
          f"отсутствуют: {missing_dto[:3]}")

    dto_old = [
        "SelectionRunResponse", "AuthResponse", "ParameterViewDto",
        "FiltersResponseDto", "CatalogImportSummary", "EntityCounters",
        "ScenarioView", "ScenarioDetailsView", "ScenarioSolutionView",
        "AssumptionView", "SelectionResultView", "SimulationRunView",
        "ZoneLoadView", "CompositionLineView", "InputsView",
        "CalculationView", "CalculationFullView", "AssumptionSnapshotView",
        "AdjustmentView", "ExportView", "ComparisonView", "ScenarioColumn",
        "InterpretationView", "AdminReferenceView", "AdminImportView",
        "SensitivityRow", "SolutionItem",
    ]
    leftover_dto = [n for n in dto_old if n in schemas]
    check("старых имён DTO в спецификации нет", not leftover_dto,
          f"остались: {leftover_dto[:3]}")
    view_suffixed = [n for n in schemas if n.endswith("View")]
    check("суффикс *View не используется", not view_suffixed,
          "; ".join(view_suffixed[:3]))
    response_suffixed = [n for n in schemas if n.endswith("Response")]
    check("суффикс *Response не используется", not response_suffixed,
          "; ".join(response_suffixed[:3]))

    paths = spec.get("paths", {})
    ops_by_path = {}
    for path, methods in paths.items():
        ops_by_path[path] = {m for m in methods
                             if isinstance(methods[m], dict)
                             and m in ("get", "post", "put", "patch", "delete")}
    check("ручное добавление: POST /scenarios/{sid}/solutions",
          "post" in ops_by_path.get("/api/projects/{id}/scenarios/"
                                    "{scenarioId}/solutions", set()))
    check("легаси-пути ручного добавления нет",
          not any(p.startswith("/api/projects/{id}/selection/scenarios")
                  for p in paths))
    sim_export = ops_by_path.get(
        "/api/projects/{id}/simulations/{simulationId}/export", set())
    check("схема имитации: POST и GET симметричны по simulationId",
          "post" in sim_export and "get" in sim_export,
          f"методы: {sorted(sim_export)}")
    check("легаси-пути сохранения схемы нет",
          not any(p.endswith("/scenarios/{scenarioId}/simulation/export")
                  for p in paths))
    admin_card = ops_by_path.get("/api/admin/solutions/{id}", set())
    check("read-дубликат карточки под /api/admin убран",
          "get" not in admin_card, f"методы: {sorted(admin_card)}")

    # массовое удаление: оба batch-эндпоинта объявлены и тегированы
    # «Админка» (изоляция hasRole('ADMIN') - SecurityConfig)
    bulk_solutions = ops_by_path.get("/api/admin/solutions/bulk-delete",
                                     set())
    bulk_refs = ops_by_path.get(
        "/api/admin/references/{dictCode}/bulk-delete", set())
    check("bulk-delete решений объявлен (POST)", "post" in bulk_solutions,
          f"методы: {sorted(bulk_solutions)}")
    check("bulk-delete справочника объявлен (POST)", "post" in bulk_refs,
          f"методы: {sorted(bulk_refs)}")
    bulk_ops = (
        paths.get("/api/admin/solutions/bulk-delete", {}).get("post", {}),
        paths.get("/api/admin/references/{dictCode}/bulk-delete", {})
        .get("post", {}),
    )
    check("bulk-эндпоинты тегированы «Админка»",
          all(op.get("tags") == ["Админка"] for op in bulk_ops))
    check("bulk-эндпоинты документированы (summary)",
          all(op.get("summary") for op in bulk_ops))

    # --- 5. Swagger UI ----------------------------------------------------
    status, _, redirect = http_get(f"{BACKEND_URL}/swagger-ui.html")
    check("swagger-ui.html редиректит на UI", status in (301, 302, 307)
          and "/swagger-ui/index.html" in redirect,
          f"HTTP {status} -> {redirect}")
    status, html, _ = http_get(f"{BACKEND_URL}/swagger-ui/index.html")
    check("index.html Swagger UI доступен", status == 200
          and "swagger-ui" in html.lower(), f"HTTP {status}")

    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            page = browser.new_page()
            page.goto(f"{BACKEND_URL}/swagger-ui/index.html",
                      wait_until="networkidle", timeout=60000)
            page.wait_for_selector(".opblock", timeout=30000)
            blocks = page.locator(".opblock").count()
            check("Swagger UI отрисовывает операции", blocks >= 40,
                  f"{blocks} блоков операций")
            authorize = page.locator(".auth-wrapper .btn.authorize, "
                                     "button.authorize").count()
            check("в Swagger UI есть кнопка Authorize", authorize >= 1,
                  f"{authorize}")
            browser.close()
    except Exception as e:  # noqa: BLE001 - среда без Playwright
        check("Swagger UI отрисовывает операции", False,
              f"Playwright недоступен: {e}")

    # --- 6. Живой сценарий Authorize --------------------------------------
    token = None
    for pwd in PASSWORDS:
        if not pwd:
            continue
        status, data = http_post_json(
            f"{BACKEND_URL}/api/auth/login",
            {"login": LOGIN, "password": pwd})
        token = data.get("token")
        if status == 200 and token:
            break
    check("логин выдаёт JWT", bool(token), f"HTTP {status}")
    if token:
        status, _, _ = http_get(
            f"{BACKEND_URL}/api/projects",
            headers={"Authorization": f"Bearer {token}"})
        check("защищённый эндпоинт с Bearer -> 200", status == 200,
              f"HTTP {status}")
    status, _, _ = http_get(f"{BACKEND_URL}/api/projects")
    check("защищённый эндпоинт без токена -> 401", status == 401,
         f"HTTP {status}")

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
