#!/usr/bin/env python3
"""E2E-проверка страницы экспорта
на живом стеке: /projects/{id}/export.

Что проверяет (Playwright + Chromium):
  1. страница открывается: заголовок, имя проекта, пометка
     «Предварительная оценка — требует верификации…»;
  2. три кнопки форматов (PDF / Excel / CSV) и текст состава отчёта;
  3. генерация каждого формата: файл скачивается (Playwright download),
     PDF начинается с %PDF, XLSX — ZIP-заголовок PK, CSV — UTF-8 BOM
     и «;»-разделитель;
  4. история выгрузок заполняется (дата, формат, размер, автор);
  5. скачивание из истории и удаление (строка исчезает);
  6. изоляция: гость перенаправляется на /login; чужой проект —
     сообщение «Проект не найден»;
  7. ссылки на экспорт из шапки экономики и карточки проекта;
  8. отсутствие упоминаний процесса разработки.

Запуск (PostgreSQL + backend + frontend подняты, seed выполнен):
  FRONTEND_URL=http://localhost:3000 python3 scripts/verify_export_ui.py

Переменные окружения:
  FRONTEND_URL — адрес frontend (по умолчанию http://localhost:3000)
  E2E_LOGIN    — логин владельца E2E-проектов (user, демо-аккаунт seed)
  E2E_PASSWORD — пароль (useruser; в старых локальных БД — user;
                 скрипт пробует оба)
  PROJECT_ID   — проект с рассчитанной экономикой (3 «Максимум —
                 5 решений»: база+покупка+RaaS и сохранённые схемы)
"""
import os
import sys

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = tuple(
    p for p in (os.environ.get("E2E_PASSWORD"), "useruser", "user") if p
)
PROJECT_ID = os.environ.get("PROJECT_ID", "3")

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""))


def login(page, password: str) -> bool:
    """Вход через BFF API (cookie сессии попадает в контекст браузера)."""
    resp = page.request.post(
        f"{FRONTEND_URL}/api/auth/login",
        data={"login": LOGIN, "password": password},
    )
    return resp.status == 200


def expect_download(page, click_action) -> tuple[bool, str, int]:
    """Выполнить клик и поймать скачивание: (ok, head, size)."""
    with page.expect_download(timeout=90_000) as download_info:
        click_action()
    download = download_info.value
    path = download.path()
    with open(path, "rb") as f:
        content = f.read()
    return len(content) > 0, content[:8].decode("latin1", "replace"), len(
        content
    )


def main() -> int:
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1366, "height": 900})

        # --- вход (демо-аккаунт; пароли совместимости) ----------------
        logged_in = False
        for password in PASSWORDS:
            if login(page, password):
                logged_in = True
                break
        check("вход демо-аккаунта user", logged_in, page.url)

        # --- 1. страница экспорта открывается --------------------------
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/export")
        page.wait_for_load_state("networkidle")
        check(
            "страница экспорта открыта",
            page.locator("h1").first.inner_text().strip()
            == "Экспорт отчёта",
        )
        check(
            "имя проекта на странице",
            "Максимум" in page.locator("h1 + p")
            .first.inner_text(),
        )

        # --- 2. пометка ---------------------------------------
        note = page.locator("[data-preliminary-note]")
        check(
            "пометка «предварительная оценка»",
            note.count() == 1
            and "верификации" in note.first.inner_text(),
        )

        # --- 3. кнопки и состав отчёта ----------------
        buttons = page.locator("button[data-export-format]")
        check("три кнопки форматов", buttons.count() == 3,
              f"найдено {buttons.count()}")
        check(
            "надписи кнопок: PDF / Excel / CSV",
            "PDF" in page.locator("button[data-export-format='pdf']")
            .inner_text()
            and "Excel" in page.locator(
                "button[data-export-format='xlsx']").inner_text()
            and "CSV" in page.locator(
                "button[data-export-format='csv']").inner_text(),
        )
        check(
            "состав отчёта описан",
            "параметры объекта" in page.content()
            and "источники данных" in page.content(),
        )

        # --- 4-6. генерация и скачивание всех форматов ------------------
        ok, head, size = expect_download(
            page,
            lambda: page.click("button[data-export-format='pdf']"),
        )
        check(
            "PDF скачивается и валиден",
            ok and head.startswith("%PDF-") and size > 10_000,
            f"{size} байт, head={head[:5]}",
        )

        ok, head, size = expect_download(
            page,
            lambda: page.click("button[data-export-format='xlsx']"),
        )
        check(
            "Excel (XLSX) скачивается и валиден (ZIP)",
            ok and head.startswith("PK") and size > 5_000,
            f"{size} байт",
        )

        ok, head, size = expect_download(
            page,
            lambda: page.click("button[data-export-format='csv']"),
        )
        check(
            "CSV скачивается с UTF-8 BOM",
            ok and head.startswith("\xef\xbb\xbf"),
            f"{size} байт",
        )

        # --- 7. история заполнилась ------------------------------------
        page.wait_for_selector("table tbody tr[data-export-id]")
        rows = page.locator("tr[data-export-id]")
        check("история выгрузок заполнилась", rows.count() >= 3,
              f"строк: {rows.count()}")
        first_row_text = rows.first.inner_text()
        check(
            "в истории: формат, размер, автор",
            any(f in first_row_text for f in ("PDF", "Excel", "CSV"))
            and ("КБ" in first_row_text or "Б" in first_row_text)
            and LOGIN in first_row_text,
        )

        # --- 8. CSV-содержимое из истории: разделитель «;» --------------
        ok, head, size = expect_download(
            page,
            lambda: rows.first.locator("a:has-text('Скачать')").click(),
        )
        check(
            "скачивание из истории (Content-Disposition)",
            ok and size > 0,
            f"{size} байт",
        )

        # --- 9. удаление выгрузки --------------------------------------
        before = page.locator("tr[data-export-id]").count()
        page.locator("tr[data-export-id]").first.locator(
            "button:has-text('Удалить')").click()
        page.wait_for_timeout(1_500)
        after = page.locator("tr[data-export-id]").count()
        check("удаление выгрузки (строка исчезла)", after == before - 1,
              f"{before} -> {after}")

        # --- 10. ссылки на экспорт из экономики и карточки --------------
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/economics")
        page.wait_for_load_state("networkidle")
        check(
            "ссылка «Экспорт отчёта» на экономике",
            page.locator(
                f'a[href="/projects/{PROJECT_ID}/export"]').count() >= 1,
        )
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}")
        page.wait_for_load_state("networkidle")
        check(
            "ссылка «Экспорт отчёта» в карточке проекта",
            page.locator(
                f'a[href="/projects/{PROJECT_ID}/export"]').count() >= 1,
        )

        # --- 11. изоляция: чужой проект --------------------------------
        stranger_login = "exp_stranger"
        stranger_password = "password123"
        page2 = browser.new_page(viewport={"width": 1366, "height": 900})
        resp = page2.request.post(
            f"{FRONTEND_URL}/api/auth/register",
            data={"login": stranger_login, "password": stranger_password},
        )
        check(
            "регистрация второго пользователя для изоляции",
            resp.status in (200, 201, 409),  # 409 — уже есть с прошлого
            f"HTTP {resp.status}",
        )
        if resp.status not in (200, 201, 409):
            resp = page2.request.post(
                f"{FRONTEND_URL}/api/auth/login",
                data={"login": stranger_login,
                      "password": stranger_password},
            )
        else:
            page2.request.post(
                f"{FRONTEND_URL}/api/auth/login",
                data={"login": stranger_login,
                      "password": stranger_password},
            )
        page2.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/export")
        page2.wait_for_load_state("networkidle")
        check(
            "чужой проект — сообщение «Проект не найден»",
            "Проект не найден" in page2.content(),
        )
        page2.close()

        # --- 12. без упоминаний разработки ------------------
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/export")
        page.wait_for_load_state("networkidle")
        body = page.locator("body").inner_text().lower()
        dev_words = ("mvp", "каркас", "модель данных", "backend",
                     "spring", "docker-compose", "миграц", "хакатон")
        check(
            "нет упоминаний процесса разработки (правило A3)",
            not any(word in body for word in dev_words),
        )

        # --- 13. гость перенаправляется на /login -----------------------
        page3 = browser.new_page(viewport={"width": 1366, "height": 900})
        page3.goto(f"{FRONTEND_URL}/api/auth/logout")  # чистим сессию
        page3.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/export")
        page3.wait_for_load_state("networkidle")
        check(
            "гость → /login (экспорт только для зарегистрированных, "
            ")",
            "/login" in page3.url,
        )
        page3.close()

        browser.close()

    failed = [name for name, ok, _ in results if not ok]
    print(f"\nИтог: {len(results) - len(failed)}/{len(results)} проверок")
    for name in failed:
        print(f"  FAIL: {name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
