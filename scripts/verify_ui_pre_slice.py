#!/usr/bin/env python3
"""E2E-проверка интерфейса сценариев и экономики на живом стеке.

Что проверяет (Playwright + Chromium, один заход):
  1. кнопка «Рассчитать и перейти к дашборду →» на странице сценариев
     находится в ПРАВОЙ половине вьюпорта и в строке заголовка
     (getBoundingClientRect().left > viewport.width / 2);
  2. окупаемость ≈ 0,1 года (проект с paybackMonths = 1,2) отображается
     человекочитаемо: «2 месяца», дробных «1,2 мес.» на странице нет;
  3. на клиентских страницах нет упоминаний процесса разработки:
     DOM не содержит внутренних статусов готовности и служебных
     маркеров (список FORBIDDEN_SUBSTRINGS ниже; внутренние статусы -
     только в docs/*).

Запуск (PostgreSQL + backend + frontend подняты, seed выполнен):
  FRONTEND_URL=http://localhost:3000 python3 scripts/verify_ui_pre_slice.py

Переменные окружения:
  FRONTEND_URL - адрес frontend (по умолчанию http://localhost:3000)
  E2E_LOGIN    - логин владельца E2E-проектов (user, демо-аккаунт seed)
  E2E_PASSWORD - пароль (user)
  PROJECT_ID   - проект с рассчитанной экономикой и payback ≈ 0,1 года
                 (по умолчанию 4 «E2E: Пиковая нагрузка»)
"""
import os
import sys

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")
LOGIN = os.environ.get("E2E_LOGIN", "user")
# Пароль демо-аккаунта: текущий seed задаёт user/useruser; на БД,
# засеянной ранней версией, остаётся user - пробуем оба (env
# E2E_PASSWORD фиксирует один конкретный).
PASSWORDS = (
    os.environ.get("E2E_PASSWORD"),
    "useruser",
    "user",
)
PROJECT_ID = os.environ.get("PROJECT_ID", "4")

# Запрещённые подстроки в DOM клиентских страниц: внутренние статусы
# готовности и служебные маркеры (продуктовый UI их не показывает).
# «скоро» НЕ в списке: совпадает со словом «Скорость» в ТТХ каталога.
FORBIDDEN_SUBSTRINGS = [
    "в разработке",
    "учебный каркас",
    "MVP",
    "следующий срез",
    "заглушка",
    "TODO",
]

# Страницы для сканирования DOM: все клиентские страницы,
# включая имитацию.
PAGES = [
    "/",
    "/catalog",
    "/catalog/1",
    "/login",
    "/register",
    "/projects",
    "/projects/new",
    f"/projects/{PROJECT_ID}",
    f"/projects/{PROJECT_ID}/edit",
    f"/projects/{PROJECT_ID}/parameters",
    f"/projects/{PROJECT_ID}/selection",
    f"/projects/{PROJECT_ID}/scenarios",
    f"/projects/{PROJECT_ID}/economics",
    f"/projects/{PROJECT_ID}/simulation",
]

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" - {detail}" if detail else ""))


def main() -> int:
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1280, "height": 800})
        # Сессия: httpOnly-cookie ставит BFF при логине (раздел 5
        # architecture.md); UI-кнопка «Войти» в headless не тестируем
        # (известная особенность гидратации логин-формы) - логин
        # напрямую через BFF.
        page.goto(FRONTEND_URL + "/login")
        logged_in = False
        for password in PASSWORDS:
            if not password:
                continue
            resp = page.request.post(
                FRONTEND_URL + "/api/auth/login",
                data={"login": LOGIN, "password": password},
            )
            if resp.status == 200:
                logged_in = True
                break
        if not logged_in:
            print("Логин не удался - стек поднят, seed выполнен?")
            return 1

        # ---------------- кнопка расчёта справа -----------------------
        print("A1 - кнопка расчёта в правой части страницы сценариев:")
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/scenarios")
        page.wait_for_load_state("networkidle")
        button = page.get_by_role("button", name="Рассчитать и перейти к дашборду")
        if button.count() == 0:
            check("кнопка «Рассчитать и перейти к дашборду →» есть", False)
        else:
            box = button.first.bounding_box()
            vw = page.evaluate("window.innerWidth")
            check(
                "кнопка в правой половине вьюпорта",
                box is not None and box["x"] > vw / 2,
                f"left={box['x']:.0f}px при ширине {vw}px"
                if box
                else "не видима",
            )
            h1 = page.locator("h1").first
            # блок заголовка (div с h1 и подзаголовком): кнопка в ТОЙ ЖЕ
            # строке, если её верх не ниже низа блока заголовка (перенос
            # при flex-wrap опустил бы кнопку под заголовок)
            title_box = h1.locator("xpath=..").first.bounding_box()
            check(
                "кнопка в строке заголовка (не ниже переноса)",
                box is not None and title_box is not None
                and box["y"] < title_box["y"] + title_box["height"],
            )

        # ---------------- окупаемость человекочитаемо -----------------
        print("A2 - окупаемость человекочитаемо (целые месяцы, вверх):")
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/economics")
        page.wait_for_load_state("networkidle")
        body = page.locator("body").inner_text()
        check(
            "окупаемость 0,1 года отображается как «2 месяца»",
            "2 месяца" in body,
        )
        check(
            "дробных месяцев нет («1,2 мес.»)",
            "1,2 мес." not in body and "1,2 мес" not in body,
        )
        check(
            "старого формата «< 1 мес.» нет",
            "< 1 мес." not in body,
        )

        # ---------------- чистый UI без статусов разработки ----------
        print("A3 - на клиентских страницах нет упоминаний разработки:")
        for path in PAGES:
            page.goto(FRONTEND_URL + path)
            page.wait_for_load_state("networkidle")
            text = page.locator("body").inner_text()
            found = [s for s in FORBIDDEN_SUBSTRINGS if s in text]
            check(
                f"страница {path}",
                not found,
                "найдено: " + ", ".join(found) if found else "",
            )

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
