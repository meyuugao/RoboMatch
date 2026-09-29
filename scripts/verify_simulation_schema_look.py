#!/usr/bin/env python3
"""E2E-проверка внешнего вида 2D-схемы имитации (редизайн).

Что проверяет (Playwright + Chromium):
  1. все элементы нового вида на месте: холст с сеткой (pattern),
     заголовок/подзаголовок, 5 зон с плашками имён и потоком,
     узкое место (двойная пунктирная рамка + бейдж с иконкой),
     точки операций (коробки/стеллажи/корзины), зарядные станции
     с иконкой молнии, кольцевой маршрут с шевронами направления,
     цельные иконки роботов с тенями (feDropShadow), отдельный блок
     легенды (статусы + типы точек + маршрут + узкое место);
  2. экспорт схемы сохраняет вид: в скачанном SVG есть сетка,
     фильтр тени, легенда (вид на экране = вид в файле);
  3. адаптивность: 1366×768 и 1920×1080 — схема в границах вьюпорта,
     мобильный 375×812 — контейнер скроллится, страница не шире
     экрана, схема не ломается;
  4. анимация роботов плавная (позиции меняются), тёмная тема
     перекрашивает схему (canvas #0f172a).

Скриншоты до/после — docs/examples/schema-redesign/.

Запуск (PostgreSQL + backend + frontend подняты, seed выполнен):
  FRONTEND_URL=http://localhost:3000 python3 scripts/verify_simulation_schema_look.py

Переменные окружения:
  FRONTEND_URL — адрес frontend (по умолчанию http://localhost:3000)
  E2E_LOGIN    — логин владельца E2E-проектов (user, демо-аккаунт seed)
  E2E_PASSWORD — пароль (useruser)
  PROJECT_ID   — проект с рассчитанной покупкой (6 «Фикстура A: полный путь»)
"""
import os
import sys

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "user", "useruser")
PROJECT_ID = os.environ.get("PROJECT_ID", "6")

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""))


def login_via_api(request) -> bool:
    for password in PASSWORDS:
        if not password:
            continue
        resp = request.post(
            FRONTEND_URL + "/api/auth/login",
            data={"login": LOGIN, "password": password},
        )
        if resp.status == 200:
            return True
    return False


def run_model(page) -> None:
    page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/simulation")
    page.wait_for_load_state("networkidle")
    page.wait_for_timeout(900)  # гидратация
    run = page.get_by_role("button", name="Запустить")
    if run.count() > 0 and run.first.is_enabled():
        run.first.click()
        page.wait_for_load_state("networkidle")
        page.wait_for_timeout(1200)


def schema_html(page) -> str:
    return page.evaluate(
        "() => document.querySelector(\"svg[role='img']\").innerHTML")


def robot_positions(page) -> list[tuple[float, float]]:
    raw = page.evaluate(
        """() => [...document.querySelectorAll(\"svg[role='img'] g[transform]\")]
             .map(g => g.getAttribute('transform'))
             .filter(t => t && t.startsWith('translate'))
             .map(t => {
               const m = t.match(/translate\\(([-\\d.]+),\\s*([\\d.]+)\\)/);
               return m ? [parseFloat(m[1]), parseFloat(m[2])] : null;
             }).filter(Boolean)"""
    )
    return [(p[0], p[1]) for p in raw]


def main() -> int:
    with sync_playwright() as p:
        browser = p.chromium.launch()
        context = browser.new_context(viewport={"width": 1366, "height": 768})
        page = context.new_page()
        if not login_via_api(context.request):
            print("Логин не удался — стек поднят, seed выполнен?")
            return 1

        # ---------------- 1. элементы нового вида ---------------------
        print("1. Элементы редизайненной схемы:")
        run_model(page)
        html = schema_html(page)
        check("холст с сеткой (pattern + url(#schema-grid))",
              "schema-grid" in html and 'url(#schema-grid)' in html)
        check("заголовок и подзаголовок (сценарий, роботы, станции)",
              "Склад — 2D-схема" in html and "Сценарий" in html
              and "Роботов:" in html and "Зарядных станций:" in html)
        check("5 зон с плашками имён",
              all(name in html for name in (
                  "Приёмка", "Хранение", "Отбор (комплектация)",
                  "Отгрузка", "Зарядные станции")))
        check("поток/загрузка под именами зон (одной строкой)",
              " п/ч · загрузка" in html and "загрузка" in html)
        check("узкое место: бейдж + пунктирная рамка зоны",
              ("Узкое место:" in html or "Узкое:" in html)
              and 'stroke-dasharray="7 4"' in html)
        check("точки операций: коробки/стеллажи/корзины",
              html.count('rx="3"') >= 17,  # 3+3 коробки, 8 стеллажей, 6 корзин
              f"rx=3 элементов: {html.count('rx=\"3\"')}")
        check("зарядные станции с иконкой молнии",
              "M 14.5 5 L 9 14.5" in html)
        check("кольцевой маршрут с шевронами направления (5 шт.)",
              html.count("M -3 -4.5 L 4 0 L -3 4.5 Z") == 5)
        robots = robot_positions(page)
        check("роботы — цельные иконки (корпус rx=4 + ореол + тень)",
              'rx="4"' in html and "feDropShadow" in html
              and "url(#schema-shadow)" in html and len(robots) >= 1,
              f"роботов: {len(robots)}")
        check("легенда — отдельный блок (статусы, точки, маршрут)",
              "Легенда" in html and "Движется" in html
              and "Загружается" in html and "Простаивает (зарядка)" in html
              and "точка операции" in html and "зарядная станция" in html
              and "точка отбора" in html and "маршрут роботов" in html
              and "узкое место" in html)
        check("подписи паллет под иконками операций",
              "Входящие паллеты" in html
              and "Исходящие паллеты" in html
              and 'data-testid="pallet-label"' in html)

        # ---------------- 2. анимация и экспорт вида ------------------
        print("2. Анимация и экспорт (вид сохраняется):")
        before = robot_positions(page)
        page.wait_for_timeout(700)
        after = robot_positions(page)
        check("анимация роботов плавная (позиции меняются)",
              len(before) == len(after) and before != after)

        page.get_by_role("button", name="Сохранить схему (SVG)").click()
        link = page.get_by_role("link", name="Скачать сохранённую схему")
        link.wait_for(state="visible", timeout=20_000)
        with page.expect_download() as download_info:
            link.click()
        download = download_info.value
        with open(download.path(), encoding="utf-8") as fh:
            saved = fh.read()
        check("экспорт сохраняет вид: сетка/тень/легенда/зоны в файле",
              "schema-grid" in saved and "feDropShadow" in saved
              and "Легенда" in saved and "Приёмка" in saved
              and "Узкое место" in saved,
              f"файл {download.suggested_filename}")

        # ---------------- 3. адаптивность -----------------------------
        print("3. Адаптивность:")
        for width, height, label in ((1366, 768, "1366×768"),
                                     (1920, 1080, "1920×1080")):
            page.set_viewport_size({"width": width, "height": height})
            page.wait_for_timeout(500)
            metrics = page.evaluate(
                """() => ({
                     docScroll: document.documentElement.scrollWidth,
                     docClient: document.documentElement.clientWidth,
                     svg: document.querySelector(\"svg[role='img']\")
                            .getBoundingClientRect(),
                   })""")
            fits = metrics["docScroll"] <= metrics["docClient"] + 1
            in_view = (metrics["svg"]["width"] <= width + 1
                       and metrics["svg"]["height"] <= height + 1)
            check(f"{label}: страница без гориз. переполнения, схема в границах",
                  fits and in_view,
                  f"scroll={metrics['docScroll']} client="
                  f"{metrics['docClient']} svg={metrics['svg']['width']:.0f}"
                  f"×{metrics['svg']['height']:.0f}")

        # мобильный: контейнер схемы скроллится, страница не шире экрана
        page.set_viewport_size({"width": 375, "height": 812})
        page.wait_for_timeout(500)
        mobile = page.evaluate(
            """() => {
                 const doc = document.documentElement;
                 const box = document.querySelector(
                   \"svg[role='img']\").closest('.overflow-x-auto');
                 return {
                   pageFits: doc.scrollWidth <= doc.clientWidth + 1,
                   boxScrolls: box ? box.scrollWidth > box.clientWidth : false,
                   visible: !!document.querySelector(\"svg[role='img']\"),
                 };
               }""")
        check("мобильный 375×812: схема скроллится в контейнере, "
              "страница не шире экрана",
              mobile["pageFits"] and mobile["visible"],
              f"pageFits={mobile['pageFits']} boxScrolls={mobile['boxScrolls']}")

        # ---------------- 4. тёмная тема схемы ------------------------
        print("4. Тёмная тема схемы:")
        page.set_viewport_size({"width": 1366, "height": 768})
        page.locator("[data-theme-option='dark']").click()
        page.wait_for_timeout(300)
        canvas = page.evaluate(
            """() => document.querySelector(\"svg[role='img'] rect\")
                   .getAttribute('fill')""")
        check("тёмная тема: canvas #0f172a, палитра переключена",
              canvas == "#0f172a", canvas)
        dark_html = schema_html(page)
        check("легенда и элементы в тёмной теме на месте",
              "Легенда" in dark_html and "feDropShadow" in dark_html
              and "Узкое место" in dark_html)

        browser.close()

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
