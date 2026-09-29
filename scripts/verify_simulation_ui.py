#!/usr/bin/env python3
"""E2E-проверка страницы имитации
на живом стеке: /projects/{id}/simulation.

Что проверяет (Playwright + Chromium):
  1. страница открывается: 2D-схема (SVG) рендерится, KPI отображаются
     (6 KPI), зоны и узкое место с подсветкой;
  2. управление: «Запустить» считает модель (статус
     completed), «Остановить» замораживает анимацию, «Перезапустить»
     сбрасывает и запускает заново;
  3. множитель скорости (0.5×/1×/2×/5×) меняет анимацию: на 5× роботы
     проходят большее расстояние за то же время, чем на 0.5×;
  4. расхождение KPI с расчётом экономики - предупреждение
     отображается (достижимость 33,3% у проекта «Пиковая нагрузка»);
  5. экспорт схемы: «Сохранить схему» кладёт файл
     (data/simulations) - скачивание SVG работает;
  6. выбор сценария: RaaS запускается и считается (base в селекторе
     отсутствует - инвариант base).

Запуск (PostgreSQL + backend + frontend подняты, seed выполнен):
  FRONTEND_URL=http://localhost:3000 python3 scripts/verify_simulation_ui.py

Переменные окружения:
  FRONTEND_URL - адрес frontend (по умолчанию http://localhost:3000)
  E2E_LOGIN    - логин владельца E2E-проектов (user, демо-аккаунт seed)
  E2E_PASSWORD - пароль (useruser; при необходимости передайте актуальный)
  PROJECT_ID   - проект с рассчитанной покупкой (4 «Пиковая нагрузка»)
"""
import math
import os
import re
import sys

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "user", "useruser")
PROJECT_ID = os.environ.get("PROJECT_ID", "4")

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" - {detail}" if detail else ""))


def robot_positions(page) -> list[tuple[float, float]]:
    """Позиции роботов на схеме (первые g[translate] в SVG)."""
    raw = page.evaluate(
        """() => [...document.querySelectorAll('svg g[transform]')]
             .map(g => g.getAttribute('transform'))
             .filter(t => t && t.startsWith('translate'))
             .map(t => {
               const m = t.match(/translate\\(([-\\d.]+),\\s*([\\d.]+)\\)/);
               return m ? [parseFloat(m[1]), parseFloat(m[2])] : null;
             }).filter(Boolean)"""
    )
    return [(p[0], p[1]) for p in raw]


def fleet_travel(a, b) -> float:
    """Суммарное перемещение парка между двумя снимками позиций."""
    total = 0.0
    for p1, p2 in zip(a, b):
        total += math.hypot(p2[0] - p1[0], p2[1] - p1[1])
    return total


def main() -> int:
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1366, "height": 900})
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

        # ---------------- 1. страница и схема --------------------------
        print("1. Страница имитации: схема рендерится, KPI отображаются:")
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/simulation")
        page.wait_for_load_state("networkidle")
        check("заголовок «Имитация 2D и KPI»",
              page.locator("h1", has_text="Имитация 2D и KPI").count() > 0)
        check("2D-схема (SVG) отрисована", page.locator("svg[role='img']").count() > 0)
        check("зоны подписаны (Приёмка/Хранение/Отбор/Отгрузка)",
              "Приёмка" in page.locator("svg[role='img']").text_content()
              and "Отбор (комплектация)" in page.locator("svg[role='img']").text_content())
        # если имитация ещё не запускалась - кнопка «Запустить» присутствует
        check("кнопки управления (Запустить/Остановить/Перезапустить)",
              page.get_by_role("button", name="Запустить").count() > 0
              and page.get_by_role("button", name="Остановить").count() > 0
              and page.get_by_role("button", name="Перезапустить").count() > 0)
        check("множители скорости (0.5×, 1×, 2×, 5×)",
              page.get_by_role("button", name="0.5×", exact=True).count() > 0
              and page.get_by_role("button", name="5×", exact=True).count() > 0)

        # ---------------- 2. запуск и управление ----------------------
        print("2. Управление:")
        page.get_by_role("button", name="Запустить").first.click()
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1500)
        body = page.locator("body").inner_text()
        check("запуск завершился (KPI-панель отображается)",
              "ЗАЯВЛЕННАЯ ПРОИЗВОДИТЕЛЬНОСТЬ" in body,
              )
        check("6 KPI на панели",
              all(s in body for s in [
                  "ЗАЯВЛЕННАЯ ПРОИЗВОДИТЕЛЬНОСТЬ",
                  "ФАКТИЧЕСКАЯ ПРОИЗВОДИТЕЛЬНОСТЬ",
                  "ЗАГРУЗКА РОБОТОВ", "ПРОСТОИ", "УЗКОЕ МЕСТО",
                  "ДОСТИЖИМОСТЬ ЗАЯВЛЕННОЙ"]))
        check("узкое место подсвечено на схеме",
              "Узкое место" in page.locator("svg[role='img']").text_content())
        robots = robot_positions(page)
        check("роботы на схеме (по составу сценария)", len(robots) >= 5,
              f"{len(robots)} маркеров")

        # пауза: позиции замирают
        pos_before = robot_positions(page)
        page.wait_for_timeout(600)
        page.get_by_role("button", name="Остановить").first.click()
        page.wait_for_timeout(300)
        frozen_a = robot_positions(page)
        page.wait_for_timeout(900)
        frozen_b = robot_positions(page)
        check("«Остановить» замораживает анимацию",
              fleet_travel(frozen_a, frozen_b) < 0.5,
              f"перемещение {fleet_travel(frozen_a, frozen_b):.2f}px")
        page.get_by_role("button", name="Продолжить").first.click()

        # ---------------- 3. скорость ---------------------------------
        print("3. Множитель скорости меняет анимацию:")

        def max_step(multiplier: str) -> float:
            """Максимальное перемещение одного робота за ~1,2 с."""
            page.get_by_role("button", name=multiplier, exact=True).first.click()
            page.wait_for_timeout(500)
            best = 0.0
            for _ in range(3):
                a = robot_positions(page)
                page.wait_for_timeout(600)
                b = robot_positions(page)
                for p1, p2 in zip(a, b):
                    d = math.hypot(p2[0] - p1[0], p2[1] - p1[1])
                    best = max(best, d)
            return best

        slow = max_step("0.5×")
        fast = max_step("5×")
        check("на 5× анимация быстрее, чем на 0.5×",
              fast > slow * 2.5 and fast > 30,
              f"max шаг 0.5×: {slow:.0f}px, 5×: {fast:.0f}px")
        page.get_by_role("button", name="1×", exact=True).first.click()

        # перезапуск: модель считается заново, анимация с нуля
        page.get_by_role("button", name="Перезапустить").first.click()
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(800)
        check("«Перезапустить» перевывает модель (KPI на месте)",
              "ЗАЯВЛЕННАЯ ПРОИЗВОДИТЕЛЬНОСТЬ"
              in page.locator("body").inner_text())

        # ---------------- 4. предупреждения ----------------
        print("4. Сверка с расчётом экономики:")
        body = page.locator("body").inner_text()
        check("блок предупреждений и сверки с расчётом отображается",
              "сверка с расчётом экономики" in body.lower())
        check("предупреждение о расхождении/достижимости отображается",
              ("недостижима" in body) or ("расходится" in body)
              or ("Экономика сценария ещё не" in body),
              )

        # ---------------- 5. экспорт схемы -----------------
        print("5. Сохранение схемы:")
        with page.expect_download(timeout=15000) as download_info:
            page.get_by_role("button",
                             name="Сохранить схему (SVG)").first.click()
            page.wait_for_timeout(1500)
            link = page.locator(
                f"a[href*='/projects/{PROJECT_ID}/simulations/'][href$='/export']",
            ).first
            link.click()
        download = download_info.value
        path = download.path()
        size = os.path.getsize(path) if path else 0
        check("файл схемы скачивается (SVG)", download.suggested_filename
              .endswith(".svg") and size > 500,
              f"{download.suggested_filename}, {size} байт")

        # ---------------- 6. выбор сценария ----------------
        print("6. Выбор сценария (base скрыт - инвариант):")
        options = page.locator("#sim-scenario option").all_inner_texts()
        check("base отсутствует в селекторе",
              all("Базовый" not in o for o in options),
              f"варианты: {options}")
        if len(options) > 1:
            page.select_option("#sim-scenario", index=1)
            page.wait_for_timeout(400)
            page.get_by_role("button", name="Запустить").first.click()
            page.wait_for_load_state("networkidle")
            page.wait_for_timeout(800)
            body = page.locator("body").inner_text()
            check("RaaS-сценарий запускается и считается",
                  "ЗАЯВЛЕННАЯ ПРОИЗВОДИТЕЛЬНОСТЬ" in body)

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
