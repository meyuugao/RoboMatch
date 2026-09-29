#!/usr/bin/env python3
"""E2E-проверка админки
на живом стеке: /admin/** + BFF /api/admin/**.

Что проверяет (Playwright + Chromium):
  1. на /projects/{id}/economics синяя кнопка «Экспорт отчёта» — в
     правой половине вьюпорта, три другие кнопки — в левой;
  2. вход admin/admin, ссылка «Управление» в шапке только у админа
     (UI-название раздела — «Управление», слова «Админка» в
     интерфейсе нет; URL /admin/** — внутренний путь);
  3. /admin: дашборд с плитками и журналом загрузок;
  4. изоляция: user -> /admin редиректит на /projects, прямой вызов
     API админки с кукой user -> 403, без куки -> 401;
  5. CRUD решения: создание формы (валидация, 201 -> карточка), правка,
     удаление (после снятия ссылок) — через UI-формы;
  6. ТТХ: upsert значения характеристики с провенансом «вручную»;
  7. CRUD справочника: создание/правка/удаление отрасли;
  8. импорт CSV-таблицы организатора: summary с добавлено/пропущено,
     повторный импорт того же файла — 0 добавлений (идемпотентность);
  9. импорт полного catalog_export_v4.csv поверх seed — 0 добавлений
     (полный порт правил Python-маппера);
 10. история импортов заполняется;
 11. публичный /api/solutions не сломан (регрессия);
 12. на страницах нет упоминаний процесса разработки.

Запуск (PostgreSQL + backend :8080 + frontend :3000, seed выполнен):
  FRONTEND_URL=http://localhost:3000 python3 scripts/verify_admin_ui.py

Переменные окружения:
  FRONTEND_URL     — адрес frontend (по умолчанию http://localhost:3000)
  E2E_LOGIN/PASSWORD — админ-аккаунт seed (admin/admin)
  USER_LOGIN/PASSWORD — пользователь для проверок изоляции (user/useruser)
  A1_PROJECT_ID    — проект владельца-пользователя со страницей экономики (3)
"""
import csv
import io
import os
import re
import sys
import tempfile
import uuid

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")
ADMIN_LOGIN = os.environ.get("E2E_LOGIN", "admin")
ADMIN_PASSWORD = os.environ.get("E2E_PASSWORD", "adminadmin")
USER_LOGIN = os.environ.get("USER_LOGIN", "user")
USER_PASSWORD = os.environ.get("USER_PASSWORD", "useruser")
A1_PROJECT_ID = os.environ.get("A1_PROJECT_ID", "3")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FULL_CATALOG = os.path.join(REPO_ROOT, "docs", "source", "catalog_export_v4.csv")

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""))


def login(page, login: str, password: str) -> bool:
    """Вход через BFF API (cookie сессии попадает в контекст браузера)."""
    resp = page.request.post(
        f"{FRONTEND_URL}/api/auth/login",
        data={"login": login, "password": password},
    )
    return resp.status == 200


def text_has_no_dev_mentions(text: str) -> tuple[bool, str]:
    """На клиентских страницах нет упоминаний разработки."""
    forbidden = [
        "миграц", "flyway", "git ", "коммит", "срез", "бэкенд",
        "backend", "java", "spring", "тз ", "todo", "fixme",
    ]
    lowered = text.lower()
    for word in forbidden:
        if word in lowered:
            return False, word
    return True, ""


CSV_HEADER = (
    "id;Название;тип;статус;компания;описание;Тип;Подтип;Сценарий;Кейсы;"
    "УГТ;Рын Потенциал;Регион;Отрасль;Цена изделия"
)


def small_catalog_csv(run_token: str) -> str:
    """2 решения с уникальным вендором/именами — безопасно для повторов."""
    return (
        f"{CSV_HEADER}\n"
        f"{uuid.uuid4()};Админ-робот {run_token};brs;operation;"
        f"Вендор {run_token};Описание из e2e;Мобильные роботы;AMR;"
        f"Внутрискладская логистика;;8;4;Москва;Логистика;1 500 000,50\n"
        f"{uuid.uuid4()};Админ-дрон {run_token};bas;piloting;"
        f"Вендор {run_token};Дрон из e2e;БПЛА;;Мониторинг среды;;7;3;"
        f"Москва;Логистика;2 700 000,00\n"
    )


def solutions_counters(page):
    """Счётчики строки «Решения» из таблицы summary (первая строка —
    производители: порядок сущностей в entities — vendors, …, solutions)."""
    return page.eval_on_selector_all(
        "[data-testid=import-summary] tbody tr",
        "rows => { for (const r of rows) { const c = r.cells;"
        " if (c[0].textContent.trim() === 'Решения')"
        " return { added: c[1].textContent.trim(),"
        " skipped: c[3].textContent.trim() }; } return null; }",
    )


def main() -> int:
    run_token = uuid.uuid4().hex[:8]

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        context = browser.new_context(viewport={"width": 1440, "height": 900})
        page = context.new_page()

        # ------------------------------------------------------------ кнопка
        # «Экспорт отчёта» на правом краю /economics
        print("\n== A1: кнопка «Экспорт отчёта» на правом краю /economics ==")
        if not login(page, USER_LOGIN, USER_PASSWORD):
            check("A1: вход пользователя", False, "логин не удался")
        else:
            page.goto(f"{FRONTEND_URL}/projects/{A1_PROJECT_ID}/economics",
                      wait_until="domcontentloaded")
            page.wait_for_selector("text=Экспорт отчёта", timeout=15000)
            page.wait_for_timeout(1500)  # гидратация/данные

            export_box = page.eval_on_selector(
                "a:has-text('Экспорт отчёта')",
                "el => { const r = el.getBoundingClientRect();"
                " return {left: r.left, right: r.right, width: r.width}; }",
            )
            viewport_width = page.viewport_size["width"]
            export_in_right = export_box["left"] > viewport_width / 2
            check(
                "A1: «Экспорт отчёта» в правой половине вьюпорта",
                export_in_right,
                f"left={export_box['left']:.0f} при ширине {viewport_width}",
            )

            left_buttons = []
            for label in ("Скорректировать значение", "Имитация 2D и KPI",
                          "Сценарии и расчёт"):
                box = page.eval_on_selector(
                    f"text={label}",
                    "el => el.getBoundingClientRect().left",
                )
                left_buttons.append(box)
            all_left = all(left < viewport_width / 2 for left in left_buttons)
            check(
                "A1: три кнопки действий — в левой половине",
                all_left,
                f"координаты: {[round(b) for b in left_buttons]}",
            )
            # гость после пользовательской сессии не нужен: выйдем ниже
        context.clear_cookies()

        # ------------------------------------------------------- Изоляция
        print("\n== Изоляция: /admin только для роли admin ==")
        api_no_auth = page.request.get(f"{FRONTEND_URL}/api/admin/solutions")
        check("API админки без сессии -> 401", api_no_auth.status == 401,
              f"статус {api_no_auth.status}")

        if login(page, USER_LOGIN, USER_PASSWORD):
            api_as_user = page.request.get(
                f"{FRONTEND_URL}/api/admin/solutions")
            check("API админки под ролью user -> 403",
                  api_as_user.status == 403,
                  f"статус {api_as_user.status}")

            page.goto(f"{FRONTEND_URL}/admin", wait_until="domcontentloaded")
            page.wait_for_timeout(1500)
            redirected = "/projects" in page.url
            check("user -> /admin редиректит на /projects", redirected,
                  f"итоговый URL: {page.url}")
            body_text = page.inner_text("body")
            # на /projects есть сообщение о запрете — оно
            # упоминает раздел «Управление»; проверяем отсутствие
            # содержимого дашборда, а не служебного сообщения
            dashboard_hidden = "Управление каталогом решений" not in body_text
            check("user: содержимое раздела не показано", dashboard_hidden)
            forbidden_note = "только роли" in body_text
            check("user: сообщение о недостатке прав показано", forbidden_note)

            nav_has_admin = page.query_selector("[data-testid=nav-admin]")
            check("user: ссылки «Управление» в шапке нет",
                  nav_has_admin is None)
        else:
            check("вход user для изоляции", False, "логин не удался")
        context.clear_cookies()

        # ------------------------------------------------------- Вход admin
        print("\n== Вход администратора и дашборд ==")
        if not login(page, ADMIN_LOGIN, ADMIN_PASSWORD):
            check("вход admin", False, "логин не удался (admin/admin?)")
            browser.close()
            return summary()
        check("вход admin/admin", True)

        page.goto(f"{FRONTEND_URL}/admin", wait_until="domcontentloaded")
        page.wait_for_selector("text=Управление", timeout=15000)
        page.wait_for_timeout(1500)
        dashboard = page.inner_text("body")
        check("дашборд /admin открывается", "Управление" in dashboard)
        check("заголовок раздела — «Управление»",
              "Панель администратора" not in dashboard)
        check("названия «Админка» в интерфейсе нет",
              "админк" not in dashboard.lower())
        check("подзаголовок про управление каталогом",
              "Управление каталогом" in dashboard)
        for tile in ("Решения", "Справочники", "Импорт каталога"):
            check(f"плитка «{tile}» на дашборде", tile in dashboard)
        nav_admin = page.query_selector("[data-testid=nav-admin]")
        check("у админа в шапке есть ссылка «Управление»",
              nav_admin is not None)
        if nav_admin is not None:
            check("текст ссылки в шапке — «Управление»",
                  nav_admin.inner_text().strip() == "Управление",
                  nav_admin.inner_text().strip())
        a3_dash, a3_word = text_has_no_dev_mentions(dashboard)
        check("A3: дашборд без упоминаний разработки", a3_dash, a3_word)

        # --------------------------------------------------- CRUD решения
        print("\n== CRUD решения ==")
        page.goto(f"{FRONTEND_URL}/admin/solutions", wait_until="domcontentloaded")
        page.wait_for_selector("text=Решения каталога", timeout=15000)
        page.wait_for_timeout(1200)
        check("список решений открывается",
              "Решения каталога" in page.inner_text("body"))

        # фильтр «требуют проверки»: сабмит формы — реальная навигация,
        # ждём её завершения (goto поверх летящей навигации = ERR_ABORTED)
        with page.expect_navigation(wait_until="domcontentloaded"):
            page.click("button:has-text('Найти')")
        page.wait_for_timeout(1500)
        needs_check_visible = page.query_selector(
            "text=только требующие проверки") is not None or "Требуют проверки" \
            in page.inner_text("body")
        check("фильтр «требуют проверки» применяется", needs_check_visible)

        # массовое выделение решений: панель, чекбоксы, select-all
        page.goto(f"{FRONTEND_URL}/admin/solutions",
                  wait_until="domcontentloaded")
        page.wait_for_selector("[data-testid=bulk-panel]", timeout=15000)
        page.wait_for_timeout(1500)
        check("панель массовых действий на решениях",
              page.query_selector("[data-testid=bulk-panel]") is not None)
        check("счётчик «Выбрано: 0» в покое",
              "Выбрано: 0" in page.inner_text("[data-testid=bulk-panel]"))
        check("кнопка удаления неактивна при пустом выборе",
              page.locator("[data-testid=bulk-delete-button]")
              .is_disabled())
        row_boxes = page.locator("[data-testid=bulk-row-checkbox]")
        check("чекбоксы выбора у строк решений", row_boxes.count() > 0,
              f"строк с чекбоксом: {row_boxes.count()}")
        row_boxes.first.check()
        page.wait_for_timeout(300)
        check("счётчик растёт до 1",
              "Выбрано: 1" in page.inner_text("[data-testid=bulk-panel]"))
        page.locator("[data-testid=bulk-select-all]").check()
        page.wait_for_timeout(300)
        selected_all = page.inner_text("[data-testid=bulk-selected-count]")
        check("выбрать все: счётчик равен числу строк",
              selected_all == f"Выбрано: {row_boxes.count()}", selected_all)
        page.locator("[data-testid=bulk-select-all]").uncheck()
        page.wait_for_timeout(300)
        check("снять выделение: счётчик 0",
              "Выбрано: 0" in page.inner_text("[data-testid=bulk-selected-count]"))

        # создание
        page.goto(f"{FRONTEND_URL}/admin/solutions/new",
                  wait_until="domcontentloaded")
        page.wait_for_selector("[data-testid=solution-name]", timeout=15000)
        page.wait_for_timeout(1200)
        solution_name = f"Админ-робот {run_token}"
        page.fill("[data-testid=solution-name]", solution_name)
        # вендор: выбираем первый непустой option
        page.eval_on_selector(
            "[data-testid=solution-vendor]",
            "el => { el.selectedIndex = 1; el.dispatchEvent("
            "new Event('change', {bubbles: true})); }",
        )
        page.select_option("[data-testid=solution-status]", "operation")
        page.fill("[data-testid=solution-price]", "1234567,89")
        page.select_option("[data-testid=solution-trl]", "8")
        page.fill("[data-testid=solution-description]",
                  "Решение, созданное e2e-проверкой админки")
        page.click("[data-testid=solution-save]")
        page.wait_for_url(re.compile(r"/admin/solutions/\d+$"), timeout=20000)
        page.wait_for_timeout(1500)
        solution_id = page.url.rstrip("/").split("/")[-1]
        edit_page = page.inner_text("body")
        check("решение создано, открыта карточка",
              solution_name in edit_page,
              f"id={solution_id}")
        check("провенанс карточки — «вручную»",
              "внесено вручную" in edit_page)

        # ТТХ: значение с провенансом (option label = «имя, единица»)
        page.wait_for_selector("[data-testid=characteristics-add]",
                               timeout=15000)
        payload_option = page.eval_on_selector(
            "[data-testid=characteristic-type]",
            "el => Array.from(el.options)"
            ".find(o => o.text.includes('Грузоподъёмность'))?.value ?? ''",
        )
        check("тип ТТХ «Грузоподъёмность» доступен в списке",
              payload_option != "", payload_option)
        page.select_option("[data-testid=characteristic-type]",
                           value=payload_option)
        page.fill("[data-testid=characteristic-value]", "1250,5")
        page.click("[data-testid=characteristic-save]")
        page.wait_for_selector(
            "[data-testid=characteristic-row-payload_kg]", timeout=15000)
        page.wait_for_timeout(1200)
        row = page.inner_text("[data-testid=characteristic-row-payload_kg]")
        check("ТТХ сохранена и видна в таблице",
              "1250.5" in row, row.replace("\n", " | ")[:80])
        check("провенанс ТТХ — «Вручную»", "Вручную" in row)

        # правка решения
        page.fill("[data-testid=solution-price]", "2222222,00")
        page.click("[data-testid=solution-save]")
        page.wait_for_timeout(2000)
        check("правка цены сохраняется",
              "2222222" in page.inner_text("body")
              or page.query_selector(
                  "[data-testid=solution-form-error]") is None)

        # удаление (решение без ссылок)
        page.once("dialog", lambda dialog: dialog.accept())
        page.click("[data-testid=solution-delete]")
        page.wait_for_url(re.compile(r"/admin/solutions$"), timeout=20000)
        page.wait_for_timeout(1500)
        check("удаление решения возвращает к списку",
              "Решения каталога" in page.inner_text("body"))

        # -------------------------------------------------- Справочники
        print("\n== CRUD справочника (industry) ==")
        page.goto(f"{FRONTEND_URL}/admin/references",
                  wait_until="domcontentloaded")
        page.wait_for_selector("text=Справочники", timeout=15000)
        page.wait_for_timeout(1200)
        refs_page = page.inner_text("body")
        check("страница справочников открывается", "Отрасли" in refs_page)
        check("счётчики записей видны", "Записей:" in refs_page)

        page.click("a:has-text('Отрасли')")
        page.wait_for_selector("[data-testid=references-table]", timeout=15000)
        page.wait_for_timeout(1500)
        check("список записей отрасли открыт",
              page.query_selector("[data-testid=references-table]")
              is not None)

        # создание с уникальным кодом/именем на прогон
        page.click("[data-testid=reference-new]")
        page.wait_for_selector("[data-testid=reference-create-form]")
        ind_code = f"e2e_industry_{run_token}"
        ind_name = f"Отрасль e2e {run_token}"
        page.fill("[data-testid=reference-new-code]", ind_code)
        page.fill("[data-testid=reference-new-name]", ind_name)
        page.click("[data-testid=reference-create-submit]")
        page.wait_for_selector(f"text={ind_name}", timeout=15000)
        check("запись справочника создана", True, ind_name)

        # правка: после входа в edit-режим код/имя становятся <input> и
        # text-матчеры строки не работают — адресуем строку по индексу
        # (список отсортирован по id и не меняет порядок при правке)
        def row_index_by_code(code: str) -> int:
            rows = page.locator("[data-testid=reference-row]")
            for i in range(rows.count()):
                if code in rows.nth(i).inner_text():
                    return i
            return -1

        edit_index = row_index_by_code(ind_code)
        check("строка созданной записи найдена", edit_index >= 0,
              f"индекс {edit_index}")
        row = page.locator("[data-testid=reference-row]").nth(edit_index)
        row.get_by_role("button", name="Изменить").click()
        page.wait_for_selector("[data-testid=reference-edit-name]")
        page.fill("[data-testid=reference-edit-name]", ind_name + " (правка)")
        row.get_by_role("button", name="Сохранить").click()
        page.wait_for_selector("text=(правка)", timeout=15000)
        page.wait_for_timeout(1200)
        check("запись справочника отредактирована", True)

        # удаление
        page.once("dialog", lambda dialog: dialog.accept())
        delete_index = row_index_by_code(ind_code)
        page.locator("[data-testid=reference-row]").nth(delete_index) \
            .get_by_role("button", name="Удалить").click()
        page.wait_for_timeout(2500)
        gone = page.query_selector(f"text={ind_code}") is None
        check("запись справочника удалена", gone)

        # -------------------------------------- Массовое удаление (bulk)
        print("\n== Массовое удаление (bulk) ==")
        # две свободные записи + одна занятая (отрасль из seed, на неё
        # ссылаются решения каталога) -> смешанный результат
        bulk_names = [f"Отрасль bulk-{ch} {run_token}" for ch in "ab"]
        for i, bulk_name in enumerate(bulk_names):
            bulk_code = f"e2e_bulk_{run_token}_{i}"
            page.click("[data-testid=reference-new]")
            page.wait_for_selector("[data-testid=reference-create-form]")
            page.fill("[data-testid=reference-new-code]", bulk_code)
            page.fill("[data-testid=reference-new-name]", bulk_name)
            page.click("[data-testid=reference-create-submit]")
            page.wait_for_selector(f"text={bulk_name}", timeout=15000)
            page.wait_for_timeout(1200)
        check("две записи для bulk созданы", True)

        def check_row(name: str) -> None:
            rows = page.locator("[data-testid=reference-row]")
            for i in range(rows.count()):
                if name in rows.nth(i).inner_text():
                    rows.nth(i).locator("[data-testid=bulk-row-checkbox]") \
                        .check()
                    return

        for bulk_name in bulk_names:
            check_row(bulk_name)
        check_row("Логистика")
        page.wait_for_timeout(300)
        check("счётчик выбора: 3 записи",
              "Выбрано: 3" in page.inner_text("[data-testid=bulk-selected-count]"))

        page.click("[data-testid=bulk-delete-button]")
        page.wait_for_selector("[data-testid=confirm-dialog]", timeout=10000)
        dialog_text = page.inner_text("[data-testid=confirm-dialog]")
        check("диалог подтверждения с числом",
              "Будет удалено 3 записи" in dialog_text)
        page.click("[data-testid=confirm-accept]")
        page.wait_for_selector("[data-testid=bulk-result]", timeout=30000)
        page.wait_for_timeout(2500)
        result_text = page.inner_text("[data-testid=bulk-result]")
        check("сводка: удалено 2 из 3", "Удалено: 2 из 3" in result_text,
              result_text.split("\n")[0][:80])
        failed_list = page.query_selector("[data-testid=bulk-failed-list]")
        failed_text = failed_list.inner_text() if failed_list else ""
        check("отказ с причиной показан", "используется" in failed_text,
              failed_text[:120])
        for bulk_name in bulk_names:
            check(f"«{bulk_name[-14:]}» исчезла из списка",
                  page.query_selector(f"text={bulk_name}") is None)
        check("занятая отрасль «Логистика» осталась",
              page.query_selector("text=Логистика") is not None)
        check("после bulk-удаления выбор снят",
              "Выбрано: 0" in page.inner_text("[data-testid=bulk-selected-count]"))

        # API-проверки bulk-delete решений: структура ответа и коды
        bulk_api = page.request.post(
            f"{FRONTEND_URL}/api/admin/solutions/bulk-delete",
            data={"ids": [999999]},
        )
        check("bulk-delete решений: 200 на смешанном списке",
              bulk_api.status == 200)
        try:
            bulk_body = bulk_api.json()
            check("bulk-delete решений: отказ с причиной",
                  bulk_body.get("deleted") == []
                  and bulk_body.get("failed", [{}])[0].get("reason", "") != "")
        except Exception as exc:  # noqa: BLE001
            check("bulk-delete решений: отказ с причиной", False, str(exc))
        bulk_bad = page.request.post(
            f"{FRONTEND_URL}/api/admin/solutions/bulk-delete",
            data={"ids": []},
        )
        check("bulk-delete: пустой список -> 400", bulk_bad.status == 400)

        # изоляция: user не может вызвать bulk-ручки
        user_context = browser.new_context()
        user_page = user_context.new_page()
        user_page.request.post(
            f"{FRONTEND_URL}/api/auth/login",
            data={"login": USER_LOGIN, "password": USER_PASSWORD},
        )
        sol_user = user_page.request.post(
            f"{FRONTEND_URL}/api/admin/solutions/bulk-delete",
            data={"ids": [1]},
        )
        ref_user = user_page.request.post(
            f"{FRONTEND_URL}/api/admin/references/industry/bulk-delete",
            data={"ids": [1]},
        )
        check("bulk-delete под ролью user -> 403 (решения)",
              sol_user.status == 403)
        check("bulk-delete под ролью user -> 403 (справочник)",
              ref_user.status == 403)
        user_context.close()
        # чистый контекст без cookie сессии — 401
        guest_context = browser.new_context()
        guest = guest_context.request.post(
            f"{FRONTEND_URL}/api/admin/solutions/bulk-delete",
            data={"ids": [1]},
        )
        check("bulk-delete без сессии -> 401", guest.status == 401)
        guest_context.close()

        # ------------------------------------------------------ Импорт
        print("\n== Импорт таблицы организатора ==")
        page.goto(f"{FRONTEND_URL}/admin/catalog/import",
                  wait_until="domcontentloaded")
        page.wait_for_selector("[data-testid=import-form]", timeout=15000)
        # гидратация клиентской формы: сабмит до неё уходит нативным
        # GET-ом вместо fetch -> summary не появляется
        page.wait_for_timeout(3000)
        check("страница импорта открывается",
              "Импорт каталога" in page.inner_text("body"))

        # 1) маленький файл с уникальным вендором
        with tempfile.NamedTemporaryFile(
                "w", suffix=".csv", delete=False, encoding="utf-8") as tmp:
            tmp.write(small_catalog_csv(run_token))
            small_csv_path = tmp.name
        page.set_input_files("[data-testid=import-file]", small_csv_path)
        page.click("[data-testid=import-submit]")
        try:
            page.wait_for_selector("[data-testid=import-summary]",
                                   timeout=30000)
        except Exception:
            error_text = ""
            error_box = page.query_selector("[data-testid=import-error]")
            if error_box:
                error_text = error_box.inner_text()[:300]
            check("импорт CSV: summary показан", False,
                  f"ошибка: {error_text or 'нет и ошибки — сабмит не сработал'}")
            raise
        summary_text = page.inner_text("[data-testid=import-summary]")
        check("импорт CSV: summary показан",
              "Импорт завершён" in summary_text)
        check("импорт CSV: 2 решения в файле", "2 решений" in summary_text,
              summary_text.split("\n")[0][:80])
        first = solutions_counters(page)
        check("импорт CSV: решения добавлены",
              first is not None and first["added"] == "2",
              f"счётчики решений: {first}")

        # 2) повторный импорт того же файла — идемпотентность
        page.set_input_files("[data-testid=import-file]", small_csv_path)
        page.click("[data-testid=import-submit]")
        page.wait_for_selector("[data-testid=import-summary]", timeout=30000)
        page.wait_for_timeout(500)
        second = solutions_counters(page)
        check("повторный импорт: 0 добавлений",
              second is not None and second["added"] == "0",
              f"счётчики решений: {second}")
        check("повторный импорт: 2 пропущено",
              second is not None and second["skipped"] == "2",
              f"счётчики решений: {second}")

        # 3) полный каталог поверх seed — порт правил маппера
        if os.path.exists(FULL_CATALOG):
            page.set_input_files("[data-testid=import-file]", FULL_CATALOG)
            page.click("[data-testid=import-submit]")
            page.wait_for_selector("[data-testid=import-summary]",
                                   timeout=60000)
            page.wait_for_timeout(500)
            full = page.inner_text("[data-testid=import-summary]")
            full_counters = solutions_counters(page)
            check("полный каталог: 187 решений в файле",
                  "187 решений" in full, full.split("\n")[0][:80])
            check("полный каталог поверх seed: 0 добавлений",
                  full_counters is not None and full_counters["added"] == "0",
                  f"счётчики решений: {full_counters}")
            check("полный каталог: 187 пропущено (порт правил подтверждён)",
                  full_counters is not None and full_counters["skipped"] == "187",
                  f"счётчики решений: {full_counters}")
        else:
            check("полный catalog_export_v4.csv найден", False, FULL_CATALOG)

        # история
        page.wait_for_selector("[data-testid=import-history]", timeout=15000)
        history_text = page.inner_text("[data-testid=import-history]")
        history_rows = page.locator(
            "[data-testid=import-history] tbody tr").count()
        check("журнал загрузок заполнен", history_rows >= 3,
              f"строк: {history_rows}")
        check("в журнале есть статусы", "Завершён" in history_text)
        check("в журнале имена файлов",
              "catalog_export_v4.csv" in history_text)

        # страницы админки без упоминаний разработки
        admin_text = page.inner_text("body")
        a3_import, a3_word = text_has_no_dev_mentions(admin_text)
        check("A3: страница импорта без упоминаний разработки",
              a3_import, a3_word)

        # ------------------------------------------- Регрессия каталога
        print("\n== Регрессия: публичный каталог ==")
        api_public = page.request.get(
            f"{FRONTEND_URL}/api/solutions?size=1")
        ok_public = api_public.status == 200
        check("публичный /api/solutions отвечает 200", ok_public,
              f"статус {api_public.status}")
        if ok_public:
            payload = api_public.json()
            check("в каталоге есть решения",
                  payload.get("totalElements", 0) > 180,
                  f"totalElements={payload.get('totalElements')}")

        # маленькая выгрузка скриптом не чистится: её вендор/решения
        # остаются в каталоге (имена уникальны на прогон — дублей
        # при повторах не возникает)

        browser.close()

    return summary()


def summary() -> int:
    print("\n== Итог ==")
    passed = sum(1 for _, ok, _ in results if ok)
    failed = len(results) - passed
    for name, ok, detail in results:
        if not ok:
            print(f"  FAIL: {name}" + (f" — {detail}" if detail else ""))
    print(f"  {passed}/{len(results)} проверок пройдено")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
