"""E2E-проекты экономики для верификации формул.

Назначение: живые данные для scripts/verify_economics.py и доктрины
docs/economics_golden.md - 5 проектов «E2E: *» склада с полным путём
(параметры -> допущения -> сценарии -> состав) под демо-аккаунтом
user/user (в старых версиях seed владельцем был отдельный test_economics -
унифицирован, легаси-аккаунт удаляется users_repo.drop_legacy_test_user).
Подбор и расчёт гоняются живым backend-ом (verify_economics.py:
POST /selection/run -> POST /calculate) - seed только готовит входы.

Идемпотентность: ВСЁ вставляется
INSERT .. ON CONFLICT DO NOTHING - повторный запуск не меняет счётчики
и не трогает то, что пользователь/прогоны уже изменили:
  - user: ON CONFLICT (login);
  - project: ON CONFLICT (user_id, name);
  - project_parameter_value: ON CONFLICT (project_id, object_type_parameter_id);
  - project_assumption: ON CONFLICT (project_id, name);
  - scenario: ON CONFLICT (project_id, name);
  - scenario_solution: ON CONFLICT (scenario_id, solution_id).

Составы ссылаются на решения каталога по external_id (UUID организатора,
стабилен между прогонами seed), а не по имени: Ronavi H1500,
Ronavi SR, AMR 100/800/1500. Цены и ТТХ берутся из каталога на живой БД -
эталон economics_golden.md считается от тех же источников.

Реальные ПДн не используются (AGENTS.md §9: только синтетика).
"""
import uuid  # noqa: F401 - external_id организатора (UUID-строки)

from sqlalchemy import Table, select
from sqlalchemy.engine import Connection, Engine

from .common import upsert

# Владелец E2E-проектов - демо-аккаунт user (пароль user,
# bcrypt cost 12, как admin; роль user - изоляция проверяется наравне
# со всеми). Логин/пароль для verify_economics.py - E2E_LOGIN/E2E_PASSWORD.
TEST_LOGIN = "user"

# Решения каталога (external_id организатора - стабилен между seed-ами):
# Ronavi H1500 - приоритетное решение склада с ценой и ТТХ зарядки;
# Ronavi SR / AMR 100 / AMR 800 / AMR 1500 - остальной состав проекта 3.
SOLUTION_EXTERNAL_IDS = {
    "ronavi_h1500": "5760e938-9a43-45a7-b8e8-f4f2e6383930",
    "ronavi_sr": "3f2aaa1b-2237-4d7b-b215-1ac5ec789ed5",
    "amr_100": "89ffd69f-f07b-4bf2-8023-1fd765a2b6ff",
    "amr_800": "5ec66969-8fcc-47b3-b517-a9c29372fffe",
    "amr_1500": "7d5a76d2-7bf3-4a40-b6f7-64590d4d9273",
}

# Решения ТЕСТОВЫХ ФИКССТУР (docs/test-fixtures, импорт - ТОЛЬКО через
# админку POST /api/admin/catalog/import; в seed фикстуры не попадают -
# поэтому состав демо-проектов вставляется, только если решения уже
# импортированы; до импорта проект создаётся без состава, повторный
# прогон seed после импорта доделывает состав (ON CONFLICT DO NOTHING)).
FIXTURE_EXTERNAL_IDS = {
    "rt_pallete_1500": "aaaa0001-4000-8000-0000-000000000001",
    "rt_komplekt_800": "aaaa0002-4000-8000-0000-000000000002",
    "at_shstacker_1000": "bbbb0001-4000-8000-0000-000000000001",
    "at_palleta_2500": "bbbb0004-4000-8000-0000-000000000004",
}

# Обязательные параметры экономики склада (assumptions.md §2) -
# источники производных (active_zone_area, picking_units_per_day) в
# списке намеренно: значения фиксируются по их источникам. Фиксированные
# (payroll_insurance_contributions_rate, working_days_per_year, has_wms)
# НЕ вставляются ни в один проект: их значения живут в дефолтах
# метаданных (§28), а строки пользователей для fixed параметров
# игнорируются приложением - в seed только читаемые конфигурации.
ECONOMY_REQUIRED_CODES = (
    "total_warehouse_area", "shifts_per_day", "working_days_per_year",
    "shift_duration", "peak_load_factor", "inbound_pallets_per_day",
    "outbound_pallets_per_day", "picking_lines_per_day", "racking_type",
    "pallet_positions", "active_sku_count", "pallet_unit_weight",
    "pallet_dimensions", "total_warehouse_staff", "pickers_count",
    "forklift_operators_count", "picker_throughput_lines_per_hour",
    "picker_salary_gross", "forklift_operator_salary_gross",
    "payroll_insurance_contributions_rate", "main_aisle_width",
    "rack_aisle_width", "storage_zone_ceiling_height",
    "floor_flatness_deviation",
)

# Индексы fixed-параметров (is_fixed в object_type_parameter): минимальный
# проект вставляет только обязательные НЕ-фиксированные + worst-case
# подбора (значения фиксированных приходят из дефолтов - как у всех).
FIXED_CODES = ("working_days_per_year",
               "payroll_insurance_contributions_rate", "has_wms")

# Параметры подбора (worst-case, assumptions.md §25-26), которых нет
# в списке экономики: +3 (паллета уже в экономике, габариты проходов - тоже).
SELECTION_EXTRA_CODES = (
    "floor_load_max_kg", "max_allowed_noise_dba",
    "required_positioning_accuracy_mm",
)

# Переопределения параметров по проектам (код -> значение); остальные -
# дефолты датасета (XLSX «Склад», базовые значения). Производные
# (active_zone_area, picking_units_per_day) не переопределяются -
# пересчитываются из источников (assumptions.md §27).
PROJECT_PARAM_OVERRIDES: dict[str, dict[str, object]] = {
    # 2 «Максимум - 1 решение»: все параметры по базовым значениям
    # (дефолты), явных переопределений нет.
    "E2E: Максимум - 1 решение": {},
    # 3 «Максимум - 5 решений»: тоже базовые значения.
    "E2E: Максимум - 5 решений": {},
    # 4 «Пиковая нагрузка»: peak_load_factor 2.5 (дефолт 1.5) -
    # worst-case по числу роботов; K_load 0.70 и K_reserve 0.20 -
    # допущения проекта (ниже).
    "E2E: Пиковая нагрузка": {"peak_load_factor": 2.5},
    # 5 «Edge case - Effect_year <= 0»: минимальный контур роботизации
    # (1 отборщик + 1 оператор, зарплаты 50 000) - малый ФОТ против
    # большого OPEX парка из 5 дорогих решений.
    "E2E: Edge case - Effect_year ≤ 0": {
        "pickers_count": 1,
        "forklift_operators_count": 1,
        "picker_salary_gross": 50000,
        "forklift_operator_salary_gross": 50000,
    },
    # Демо-проекты из тестовых фикстур (задача 4, 2026-09-27): параметры
    # откалиброваны предрасчётом на живом стеке так, что решения фикстур
    # проходят подбор (fit, без исключений по габаритам/мощности),
    # selectedRobots == requiredRobots (underpowered/overpowered = false)
    # и экономика в разумных пределах: ROI 176-319% (< 500% - без сноски
    # A10), Payback 1,6-2,8 года (< 5), Effect_year > 0.
    "Демо: Оптимальный склад": {
        "inbound_pallets_per_day": 1900,
        "outbound_pallets_per_day": 1900,
        "pallet_unit_weight": 300,
        "pickers_count": 16,
        "forklift_operators_count": 5,
        "picker_salary_gross": 70000,
        "forklift_operator_salary_gross": 80000,
        "rack_aisle_width": 2.2,
        "main_aisle_width": 2.5,
        "floor_load_max_kg": 2500,
        "required_positioning_accuracy_mm": 20,
        "max_allowed_noise_dba": 70,
    },
    "Демо: Пиковая нагрузка - успех": {
        "inbound_pallets_per_day": 1500,
        "outbound_pallets_per_day": 1500,
        "peak_load_factor": 2.5,
        "pallet_unit_weight": 800,
        "pickers_count": 17,
        "forklift_operators_count": 5,
        "picker_salary_gross": 70000,
        "forklift_operator_salary_gross": 80000,
        "rack_aisle_width": 2.4,
        "main_aisle_width": 2.8,
        "floor_load_max_kg": 3500,
        "required_positioning_accuracy_mm": 20,
        "max_allowed_noise_dba": 72,
    },
    "Демо: Умеренный масштаб": {
        "inbound_pallets_per_day": 750,
        "outbound_pallets_per_day": 750,
        "pallet_unit_weight": 200,
        "pickers_count": 15,
        "forklift_operators_count": 5,
        "picker_salary_gross": 70000,
        "forklift_operator_salary_gross": 80000,
        "rack_aisle_width": 2.2,
        "main_aisle_width": 2.5,
        "floor_load_max_kg": 2000,
        "required_positioning_accuracy_mm": 25,
        "max_allowed_noise_dba": 68,
    },
}

# Переопределения допущений (project_assumption) по проектам:
# p_nominal нужен для рекомендуемого количества (§2.1) - Ronavi H1500
# заявлено 90 паллет/час (открытые источники, «производительность»).
PROJECT_ASSUMPTION_OVERRIDES: dict[str, dict[str, str]] = {
    "E2E: Минимальный склад": {},
    "E2E: Максимум - 1 решение": {"robot_nominal_productivity_per_hour": "90"},
    "E2E: Максимум - 5 решений": {"robot_nominal_productivity_per_hour": "90"},
    "E2E: Пиковая нагрузка": {
        "robot_nominal_productivity_per_hour": "90",
        "k_load": "0.70",
        "k_reserve": "0.20",
    },
    # У AMR 1500 (Морос) мощность зарядки НЕ
    # публикуется (морос.рф/amr-1500 - проверено 2026-09-24: только способ
    # зарядки и время работы; цепь «1-ф 220В, 10A» есть лишь у AMR 800).
    # P_consumption задаётся ДОПУЩЕНИЕМ проекта (economic_model.md §1.2 -
    # первичный источник; charging_power_kw - лишь фолбэк): консервативная
    # оценка 2.2 кВт по цепи зарядки той же линейки (AMR 800). Без
    # допущения проект показывал электроэнергию 0 ₽ с предупреждением
    # «Потребляемая мощность не задана».
    "E2E: Edge case - Effect_year ≤ 0": {
        "robot_nominal_productivity_per_hour": "90",
        "robot_power_consumption_kw": "2.2",
    },
    # Демо-проекты: P_nominal - заявленная производительность робота
    # (допущение проекта, economic_model.md §1.2; у фикстур РТ-Паллета/
    # АТ-Паллета/АТ-Штабелер ТТХ «производительность» текстовая - «до 60/
    # 70/50 паллет/час», консервативное значение 90/90 оп/ч; для смешанного
    # парка «Умеренного масштаба» (РТ-Комплект + АТ-Штабелер) - 25 оп/ч,
    # чтобы requiredRobots совпадал с составом (7 ед.) при пике 95,5 оп/ч).
    "Демо: Оптимальный склад": {
        "robot_nominal_productivity_per_hour": "90",
    },
    "Демо: Пиковая нагрузка - успех": {
        "robot_nominal_productivity_per_hour": "90",
    },
    "Демо: Умеренный масштаб": {
        "robot_nominal_productivity_per_hour": "25",
    },
}

# Состав сценариев по проектам: (ключ external_id -> количество).
# base пустой у ВСЕХ проектов - инвариант приложения.
PROJECT_COMPOSITIONS: dict[str, dict[str, int]] = {
    "E2E: Минимальный склад": {},
    "E2E: Максимум - 1 решение": {"ronavi_h1500": 1},
    "E2E: Максимум - 5 решений": {
        "ronavi_h1500": 2,
        "ronavi_sr": 3,
        "amr_100": 1,
        "amr_800": 2,
        "amr_1500": 1,
    },
    "E2E: Пиковая нагрузка": {"ronavi_h1500": 5},
    "E2E: Edge case - Effect_year ≤ 0": {"amr_1500": 5},
    # Составы демо-проектов - ТОЛЬКО решения тестовых фикстур
    # (ключи FIXTURE_EXTERNAL_IDS). Вставляется при наличии решений
    # в каталоге (после импорта фикстуры через админку).
    "Демо: Оптимальный склад": {"rt_pallete_1500": 5},
    "Демо: Пиковая нагрузка - успех": {"at_palleta_2500": 4, "at_shstacker_1000": 2},
    "Демо: Умеренный масштаб": {"rt_komplekt_800": 5, "at_shstacker_1000": 2},
}

# Каталог проектов: имя -> (описание, конфигурация для сверки).
TEST_PROJECTS: tuple[tuple[str, str], ...] = (
    (
        "E2E: Минимальный склад",
        "E2E-верификация: только обязательные параметры экономики (26) и "
        "подбора (+3 worst-case), состав сценариев пуст - проверка "
        "N_robots = 0, CAPEX = 0, предупреждений расчёта.",
    ),
    (
        "E2E: Максимум - 1 решение",
        "E2E-верификация: все параметры по базовым значениям, Ronavi H1500 "
        "в purchase и raas, base пустой - минимальная ненулевая "
        "конфигурация, все статьи CAPEX/OPEX, эталон economics_golden.md.",
    ),
    (
        "E2E: Максимум - 5 решений",
        "E2E-верификация: 5 решений (Ronavi H1500/SR, AMR 100/800/1500) в "
        "purchase и raas - суммирование CAPEX/OPEX по нескольким решениям, "
        "эталон economics_golden.md.",
    ),
    (
        "E2E: Пиковая нагрузка",
        "E2E-верификация: peak_load_factor = 2.5, k_load = 0.70, "
        "k_reserve = 0.20 - worst-case по числу роботов (округление вверх).",
    ),
    (
        "E2E: Edge case - Effect_year ≤ 0",
        "E2E-верификация: минимальный контур ФОТ против OPEX парка из 5 "
        "AMR 1500 - Effect_year ≤ 0, Payback не выводится.",
    ),
    (
        "Демо: Оптимальный склад",
        "Демо-проект на решениях тестовой фикстуры A (РТ-Паллета 1500 x5, "
        "docs/test-fixtures): подбор находит решения фикстуры (fit), "
        "состав = requiredRobots, ROI ~289%, окупаемость ~1,7 года.",
    ),
    (
        "Демо: Пиковая нагрузка - успех",
        "Демо-проект на решениях фикстуры B (АТ-Паллета 2500 x4 + "
        "АТ-Штабелер 1000 x2): peak_load_factor 2.5, состав 6 = required, "
        "ROI ~176%, окупаемость ~2,8 года.",
    ),
    (
        "Демо: Умеренный масштаб",
        "Демо-проект на решениях фикстур A+B (РТ-Комплект 800 x5 + "
        "АТ-Штабелер 1000 x2): малый склад, состав 7 = required, "
        "ROI ~319%, окупаемость ~1,6 года.",
    ),
)


def seed_test_projects(conn: Connection, engine: Engine,
                       tables: dict[str, Table], log) -> int:
    """Создать 5 E2E-проектов под владельцем user. Вставок - 0
    при повторном запуске (ON CONFLICT DO NOTHING везде)."""
    user_tbl = tables["user"]
    test_user_id = conn.execute(
        select(user_tbl.c["id"]).where(user_tbl.c["login"] == TEST_LOGIN)
    ).scalar_one_or_none()
    if test_user_id is None:
        raise SystemExit(
            f"Пользователь {TEST_LOGIN} не найден - он создаётся в "
            "users_repo.seed_demo_users (DEMO_USERS); проверьте порядок шагов run.py"
        )

    # Склад (is_calc_enabled=true - экономика доступна)
    ot = tables["object_type"]
    warehouse_id = conn.execute(
        select(ot.c["id"]).where(ot.c["code"] == "warehouse")
    ).scalar_one_or_none()
    if warehouse_id is None:
        raise SystemExit("Тип объекта «warehouse» не найден - seed каталога не выполнялся")

    # id параметров по кодам (для project_parameter_value)
    otp = tables["object_type_parameter"]
    pt = tables["parameter_type"]
    param_rows = conn.execute(
        select(pt.c["code"], otp.c["id"], otp.c["default_value_numeric"],
               otp.c["default_value_text"], otp.c["default_value_bool"],
               otp.c["is_fixed"], otp.c["is_derived"])
        .join(pt, pt.c["id"] == otp.c["parameter_type_id"])
        .where(otp.c["object_type_id"] == warehouse_id)
    ).all()
    param_by_code = {row[0]: row for row in param_rows}

    # id решений по external_id (строкой: PostgreSQL приводит text->uuid,
    # SQLite-тесты хранят TEXT - uuid.UUID-объект там не матчится)
    sol = tables["solution"]
    sol_ids: dict[str, int] = {}
    for key, external_id in SOLUTION_EXTERNAL_IDS.items():
        sid = conn.execute(
            select(sol.c["id"]).where(sol.c["external_id"] == external_id)
        ).scalar_one_or_none()
        if sid is None:
            raise SystemExit(f"Решение {key} ({external_id}) не найдено в каталоге")
        sol_ids[key] = sid

    # Решения тестовых фикстур - НЕ обязательны (импортируются админкой
    # отдельно, docs/test-fixtures/README.md): пока их нет, демо-проекты
    # создаются без состава; повторный seed после импорта доделывает.
    fixture_sol_ids: dict[str, int] = {}
    for key, external_id in FIXTURE_EXTERNAL_IDS.items():
        fid = conn.execute(
            select(sol.c["id"]).where(sol.c["external_id"] == external_id)
        ).scalar_one_or_none()
        if fid is not None:
            fixture_sol_ids[key] = fid
    if not fixture_sol_ids:
        log("test_projects: решения тестовых фикстур не найдены - составы "
            "демо-проектов пропущены (импорт docs/test-fixtures через "
            "админку, затем повторный seed)")

    ppv_tbl = tables["project_parameter_value"]
    pa_tbl = tables["project_assumption"]
    scn_tbl = tables["scenario"]
    ss_tbl = tables["scenario_solution"]

    total_param_values = 0
    for name, description in TEST_PROJECTS:
        # --- проект (DO NOTHING: существующий не трогаем) ---------------
        upsert(conn, engine, tables["project"], [{
            "user_id": test_user_id,
            "object_type_id": warehouse_id,
            "name": name,
            "description": description,
            "status": "active",
        }], ["user_id", "name"], None, log, f"project «{name}»")
        project_id = conn.execute(
            select(tables["project"].c["id"])
            .where(tables["project"].c["user_id"] == test_user_id)
            .where(tables["project"].c["name"] == name)
        ).scalar_one()

        # --- значения параметров ----------------------------------------
        # Минимальный проект: обязательные экономики (кроме фиксированных -
        # их значения живут в дефолтах, строки fixed игнорируются, §28)
        # + worst-case подбора. Остальные: все редактируемые параметры
        # склада по базовым значениям (дефолты метаданных; fixed/derived
        # не вставляются).
        if name == "E2E: Минимальный склад":
            codes = [c for c in ECONOMY_REQUIRED_CODES
                     if c not in FIXED_CODES] + list(SELECTION_EXTRA_CODES)
        else:
            codes = [row[0] for row in param_rows
                     if not row[5] and not row[6]]  # не fixed и не derived
        overrides = PROJECT_PARAM_OVERRIDES.get(name, {})
        values = []
        for code in codes:
            meta = param_by_code.get(code)
            if meta is None:
                raise SystemExit(f"Параметр «{code}» не найден у склада")
            value = overrides.get(code)
            row = {"project_id": project_id,
                   "object_type_parameter_id": meta[1], "source": "manual",
                   # одинаковый набор ключей в каждой строке - требование
                   # executemany SQLAlchemy (CHECK «ровно одно значение» - в БД)
                   "value_numeric": None, "value_text": None, "value_bool": None}
            if value is not None:
                if isinstance(value, bool):
                    row["value_bool"] = value
                elif isinstance(value, (int, float)):
                    row["value_numeric"] = value
                else:
                    row["value_text"] = str(value)
            elif meta[2] is not None:       # default_value_numeric
                row["value_numeric"] = float(meta[2])
            elif meta[3] is not None:       # default_value_text
                row["value_text"] = meta[3]
            elif meta[4] is not None:       # default_value_bool
                row["value_bool"] = meta[4]
            else:
                continue
            values.append(row)
        inserted, _ = upsert(conn, engine, ppv_tbl, values,
                             ["project_id", "object_type_parameter_id"],
                             None, log, f"ppv «{name}»")
        total_param_values += inserted

        # --- допущения проекта --------------------------------
        pa_values = [{"project_id": project_id, "name": k, "value": v}
                     for k, v in PROJECT_ASSUMPTION_OVERRIDES.get(name, {}).items()]
        if pa_values:
            upsert(conn, engine, pa_tbl, pa_values, ["project_id", "name"],
                   None, log, f"assumptions «{name}»")

        # --- сценарии base/purchase/raas (base всегда пустой) ----
        scenario_names = [
            ("base", "Текущий процесс без роботизации"),
            ("purchase", "Покупка оборудования"),
            ("raas", "Роботы как услуга"),
        ]
        upsert(conn, engine, scn_tbl, [
            {"project_id": project_id, "type": type_, "name": default_name}
            for type_, default_name in scenario_names
        ], ["project_id", "name"], None, log, f"scenarios «{name}»")
        scenario_id = conn.execute(
            select(scn_tbl.c["id"])
            .where(scn_tbl.c["project_id"] == project_id)
            .where(scn_tbl.c["type"] == "purchase")
        ).scalar_one()
        raas_id = conn.execute(
            select(scn_tbl.c["id"])
            .where(scn_tbl.c["project_id"] == project_id)
            .where(scn_tbl.c["type"] == "raas")
        ).scalar_one()

        # --- состав purchase/raas (is_manual=false: состав задан
        # конфигурацией E2E, а не ручным добавлением пользователя) ---
        composition = PROJECT_COMPOSITIONS.get(name, {})
        # ключи организаторского каталога или фикстур; если хоть одно
        # решение фикстуры ещё не импортировано - состав проекта
        # пропускается целиком (доделает повторный seed после импорта)
        resolved = {key: sol_ids.get(key, fixture_sol_ids.get(key))
                    for key in composition}
        if composition and any(v is None for v in resolved.values()):
            log(f"test_projects: состав «{name}» пропущен - решения "
                f"фикстур не импортированы ({[k for k, v in resolved.items() if v is None]})")
            rows = []
        else:
            rows = [
                {"scenario_id": scenario_id, "solution_id": sid,
                 "quantity": qty, "is_manual": False}
                for key, qty in composition.items()
                if (sid := resolved[key]) is not None
            ] + [
                {"scenario_id": raas_id, "solution_id": sid,
                 "quantity": qty, "is_manual": False}
                for key, qty in composition.items()
                if (sid := resolved[key]) is not None
            ]
        if rows:
            upsert(conn, engine, ss_tbl, rows,
                   ["scenario_id", "solution_id"], None, log,
                   f"composition «{name}»")

    log(f"test_projects: пользователь {TEST_LOGIN}, 5 проектов «E2E: *» "
        f"+ 3 демо-проекта «Демо: *» (решения тестовых фикстур), "
        f"новых значений параметров: {total_param_values}")
    return total_param_values
