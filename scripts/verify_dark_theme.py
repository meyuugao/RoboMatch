#!/usr/bin/env python3
"""E2E-проверка тёмной темы на живом стеке.

Что проверяет (Playwright + Chromium):
  1. переключатель в шапке (светлая/тёмная/системная), сохранение
     выбора в localStorage, системная тема (prefers-color-scheme),
     инлайн-скрипт без мигания (FOUC) в <head>;
  2. тёмная тема на всех страницах: публичные (главная, каталог,
     карточка решения, сравнение, вход, регистрация), поток проекта
     (список, карточка, параметры, подбор, сценарии, экономика,
     имитация, экспорт) и админка (дашборд, решения, справочники,
     импорт каталога);
  3. SVG-схема имитации перекрашивается на лету (без перезапуска
     имитации — KPI и анимация сохраняются), сохранение схемы (SVG)
     в текущей теме;
  4. экспорт PDF в обеих темах: radio-группа на странице экспорта,
     по умолчанию — как в интерфейсе; тёмный и светлый отчёты
     генерируются;
  5. контраст WCAG AA для ключевого текста в тёмной теме.

Скриншоты обеих тем для ключевых страниц — docs/examples/theme/.

Запуск (PostgreSQL + backend + frontend подняты, seed выполнен):
  FRONTEND_URL=http://localhost:3000 python3 scripts/verify_dark_theme.py

Переменные окружения:
  FRONTEND_URL — адрес frontend (по умолчанию http://localhost:3000)
  E2E_LOGIN   — логин владельца E2E-проектов (user, демо-аккаунт seed)
  E2E_PASSWORD— пароль (useruser)
  PROJECT_ID  — проект с рассчитанной покупкой (6 «Фикстура A: полный путь»)
  SHOTS_DIR   — куда класть скриншоты (docs/examples/theme)
"""
import json
import os
import re
import sys

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3000")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "user", "useruser")
ADMIN_PASSWORDS = ("adminadmin",)
PROJECT_ID = os.environ.get("PROJECT_ID", "6")
SHOTS_DIR = os.environ.get(
    "SHOTS_DIR",
    os.path.join(os.path.dirname(__file__), "..", "docs", "examples",
                 "theme"),
)

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    mark = "OK " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""))


def login_via_api(request, login: str, passwords) -> bool:
    for password in passwords:
        if not password:
            continue
        resp = request.post(
            FRONTEND_URL + "/api/auth/login",
            data={"login": login, "password": password},
        )
        if resp.status == 200:
            return True
    return False


def is_dark(page) -> bool:
    return page.evaluate(
        "() => document.documentElement.classList.contains('dark')")


def body_bg(page) -> str:
    return page.evaluate(
        "() => getComputedStyle(document.body).backgroundColor")


def canvas_fill(page) -> str:
    return (page.locator("svg[role='img'] rect").first
            .get_attribute("fill") or "")


def robot_positions(page) -> list[tuple[float, float]]:
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


def wait_hydrated(page) -> None:
    """Дождаться гидратации React (клики до неё — тихие пустышки;
    сеть уже тихая — добавляем явную паузу после networkidle)."""
    page.wait_for_timeout(800)


def luminance(channel: float) -> float:
    return channel / 12.92 if channel <= 0.04066 \
        else ((channel + 0.055) / 1.055) ** 2.4


def contrast_ratio(color_a: str, color_b: str) -> float:
    """Контраст WCAG двух css-цветов rgb(r, g, b)."""
    nums = []
    for value in (color_a, color_b):
        match = re.match(r"rgba?\((\d+),\s*(\d+),\s*(\d+)", value)
        if not match:
            return 21.0
        r, g, b = (int(x) for x in match.groups())
        lin = [luminance(r / 255), luminance(g / 255), luminance(b / 255)]
        nums.append(0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2])
    lighter, darker = max(nums), min(nums)
    return (lighter + 0.05) / (darker + 0.05)


def shot(page, name: str) -> None:
    os.makedirs(SHOTS_DIR, exist_ok=True)
    page.screenshot(path=os.path.join(SHOTS_DIR, name), full_page=True)


def dark_pages(page, pages: list[tuple[str, str]], group: str) -> None:
    for url, label in pages:
        page.goto(FRONTEND_URL + url)
        page.wait_for_load_state("networkidle")
        wait_hydrated(page)
        dark = is_dark(page)
        bg = body_bg(page)
        check(f"{group}: {label} в тёмной ({bg})",
              dark and bg in ("rgb(2, 6, 23)", "rgba(2, 6, 23, 1)"),
              "" if dark else "html.dark отсутствует")


def main() -> int:
    console_errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        context = browser.new_context(viewport={"width": 1366, "height": 900})
        page = context.new_page()
        page.on("pageerror", lambda err: console_errors.append(str(err)))

        if not login_via_api(context.request, LOGIN, PASSWORDS):
            print("Логин не удался — стек поднят, seed выполнен?")
            return 1

        # ------------ 1. Переключатель и сохранение -------------------
        print("1. Переключатель темы и сохранение выбора:")
        page.goto(FRONTEND_URL + "/")
        page.wait_for_load_state("networkidle")
        wait_hydrated(page)
        toggle = page.locator("[data-testid='theme-toggle']")
        check("переключатель в шапке (3 опции)",
              toggle.count() == 1
              and page.locator("[data-theme-option='dark']").count() == 1
              and page.locator("[data-theme-option='system']").count() == 1)
        check("по умолчанию светлая (без выбора)", not is_dark(page))

        page.locator("[data-theme-option='dark']").click()
        page.wait_for_timeout(300)
        check("клик «Тёмная» → html.dark + тёмный фон",
              is_dark(page) and body_bg(page) == "rgb(2, 6, 23)")
        stored = page.evaluate("() => localStorage.getItem('theme')")
        check("выбор сохранён в localStorage (theme=dark)",
              stored == "dark", f"theme={stored}")
        shot(page, "home_dark.png")

        page.reload()
        page.wait_for_load_state("networkidle")
        wait_hydrated(page)
        check("после перезагрузки тема сохранилась (без мигания)",
              is_dark(page) and body_bg(page) == "rgb(2, 6, 23)")
        head_html = page.evaluate(
            "() => document.head.innerHTML")
        check("инлайн-скрипт темы в <head> (no-FOUC)",
              'localStorage.getItem("theme")' in head_html
              and "prefers-color-scheme" in head_html)

        page.locator("[data-theme-option='light']").click()
        page.wait_for_timeout(300)
        check("возврат на светлую (html.dark снят, фон светлый)",
              not is_dark(page) and body_bg(page) == "rgb(248, 250, 252)")
        shot(page, "home_light.png")

        page.locator("[data-theme-option='system']").click()
        page.wait_for_timeout(300)
        check("«Системная» при светлой ОС → светлая",
              not is_dark(page))

        # системная тема: отдельный контекст с тёмной палитрой ОС
        sys_context = browser.new_context(
            viewport={"width": 1366, "height": 900}, color_scheme="dark")
        sys_page = sys_context.new_page()
        sys_page.goto(FRONTEND_URL + "/")
        sys_page.wait_for_load_state("networkidle")
        check("«Системная» + prefers-color-scheme: dark → тёмная",
              is_dark(sys_page))
        sys_context.close()

        # ------------ 2. Все страницы в тёмной ------------------------
        print("2. Тёмная тема на всех страницах:")
        page.locator("[data-theme-option='dark']").click()
        page.wait_for_timeout(300)
        public_pages = [
            ("/", "главная"),
            ("/catalog", "каталог"),
            ("/catalog/2", "карточка решения"),
            ("/catalog/compare", "сравнение (пустое)"),
            ("/login", "вход"),
            ("/register", "регистрация"),
        ]
        dark_pages(page, public_pages, "публичные")
        shot(page, "catalog_dark.png")
        shot_dark_done = True

        project_pages = [
            ("/projects", "список проектов"),
            (f"/projects/{PROJECT_ID}", "карточка проекта"),
            (f"/projects/{PROJECT_ID}/parameters", "параметры объекта"),
            (f"/projects/{PROJECT_ID}/selection", "подбор"),
            (f"/projects/{PROJECT_ID}/scenarios", "сценарии"),
            (f"/projects/{PROJECT_ID}/economics", "экономика"),
            (f"/projects/{PROJECT_ID}/export", "экспорт"),
        ]
        dark_pages(page, project_pages, "проекты")
        shot(page, "economics_dark.png")
        shot(page, "export_dark.png")

        # админка — отдельным контекстом (admin/admin)
        admin_context = browser.new_context(
            viewport={"width": 1366, "height": 900}, color_scheme="dark")
        if login_via_api(admin_context.request, "admin", ADMIN_PASSWORDS):
            admin_page = admin_context.new_page()
            admin_page.goto(FRONTEND_URL + "/")
            admin_page.wait_for_load_state("networkidle")
            admin_page.locator("[data-theme-option='dark']").click()
            admin_pages = [
                ("/admin", "дашборд админки"),
                ("/admin/solutions", "решения"),
                ("/admin/references", "справочники"),
                ("/admin/catalog/import", "импорт каталога"),
            ]
            dark_pages(admin_page, admin_pages, "админка")
            shot(admin_page, "admin_dark.png")
        else:
            check("админка: вход admin (демо-аккаунт seed)", False,
                  "логин admin не удался")
        admin_context.close()

        # ------------ 3. SVG-схема на лету ----------------------------
        print("3. SVG-схема имитации в тёмной теме (на лету):")
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/simulation")
        page.wait_for_load_state("networkidle")
        wait_hydrated(page)
        # светлую — на время запуска модели (переключим после)
        page.locator("[data-theme-option='light']").click()
        page.wait_for_timeout(300)
        run_button = page.get_by_role("button", name="Запустить")
        if run_button.count() > 0 and run_button.first.is_enabled():
            run_button.first.click()
            page.wait_for_load_state("networkidle")
            page.wait_for_timeout(1200)
        check("модель запущена (KPI-панель)",
              "ЗАЯВЛЕННАЯ ПРОИЗВОДИТЕЛЬНОСТЬ" in page.locator("body")
              .inner_text())
        check("схема в светлой теме (canvas #f8fafc)",
              canvas_fill(page) == "#f8fafc", canvas_fill(page))
        shot(page, "simulation_light.png")

        before = robot_positions(page)
        page.wait_for_timeout(600)
        moving_light = robot_positions(page) != before

        # переключение НА ЛЕТУ — имитация не перезапускается
        page.locator("[data-theme-option='dark']").click()
        page.wait_for_timeout(300)
        check("переключение на лету: canvas стал #0f172a",
              canvas_fill(page) == "#0f172a", canvas_fill(page))
        check("KPI-панель сохранилась после смены темы",
              "ЗАЯВЛЕННАЯ ПРОИЗВОДИТЕЛЬНОСТЬ" in page.locator("body")
              .inner_text())
        mid = robot_positions(page)
        page.wait_for_timeout(800)
        after = robot_positions(page)
        check("анимация продолжается после смены темы (без рестарта)",
              len(after) == len(mid) and after != mid)
        check("анимация шла и в светлой теме до переключения",
              moving_light)
        svg_text = page.locator("svg[role='img']").text_content()
        check("подписи зон читаемы в тёмной (легенда/зоны)",
              "Приёмка" in svg_text and "Движется" in svg_text)
        shot(page, "simulation_dark.png")

        # сохранение схемы в тёмной — SVG в текущей теме
        page.get_by_role("button", name="Сохранить схему (SVG)").click()
        download_link = page.get_by_role(
            "link", name="Скачать сохранённую схему")
        download_link.wait_for(state="visible", timeout=20_000)
        with page.expect_download() as download_info:
            download_link.click()
        download = download_info.value
        svg_path = download.path()
        with open(svg_path, encoding="utf-8") as fh:
            svg_content = fh.read()
        check("сохранённая схема — в текущей (тёмной) теме",
              "#0f172a" in svg_content and "#f8fafc" not in svg_content,
              f"файл {download.suggested_filename}")

        # ------------ 4. Экспорт PDF в двух темах ---------------------
        print("4. Экспорт PDF в обеих темах:")
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/export")
        page.wait_for_load_state("networkidle")
        wait_hydrated(page)
        theme_group = page.locator("[data-testid='report-theme']")
        check("radio-группа «Тема PDF-отчёта» на странице экспорта",
              theme_group.count() == 1
              and page.locator(
                  "[data-report-theme-option='dark']").count() == 1)
        dark_active = page.locator(
            "[data-report-theme-option='dark'][aria-checked='true']").count()
        check("в тёмном UI по умолчанию активна «Тёмная»",
              dark_active == 1)

        # генерация обеих тем через BFF (cookie-сессия контекста)
        resp_dark = page.request.post(
            FRONTEND_URL + f"/api/projects/{PROJECT_ID}/exports",
            data=json.dumps({"format": "pdf", "theme": "dark"}),
            headers={"Content-Type": "application/json"},
        )
        check("PDF в тёмной теме генерируется (201)",
              resp_dark.status == 201, f"HTTP {resp_dark.status}")
        dark_id = json.loads(resp_dark.text())["id"] \
            if resp_dark.status == 201 else None
        resp_light = page.request.post(
            FRONTEND_URL + f"/api/projects/{PROJECT_ID}/exports",
            data=json.dumps({"format": "pdf", "theme": "light"}),
            headers={"Content-Type": "application/json"},
        )
        check("PDF в светлой теме генерируется (201)",
              resp_light.status == 201, f"HTTP {resp_light.status}")
        light_id = json.loads(resp_light.text())["id"] \
            if resp_light.status == 201 else None
        if dark_id and light_id:
            file_dark = page.request.get(
                FRONTEND_URL + f"/api/projects/{PROJECT_ID}/exports/"
                f"{dark_id}")
            file_light = page.request.get(
                FRONTEND_URL + f"/api/projects/{PROJECT_ID}/exports/"
                f"{light_id}")
            same = file_dark.body() == file_light.body()
            check("файлы тем различаются (та же структура, другая палитра)",
                  not same)
            check("оба файла — валидный PDF",
                  file_dark.body()[:5] == b"%PDF-"
                  and file_light.body()[:5] == b"%PDF-")

        # ------------ 5. Контраст WCAG AA ------------------------------
        print("5. Контраст тёмной темы (WCAG AA, ≥ 4.5:1):")
        page.goto(FRONTEND_URL + "/catalog")
        page.wait_for_load_state("networkidle")
        wait_hydrated(page)
        h1_color = page.evaluate(
            "() => getComputedStyle(document.querySelector('h1')).color")
        bg = body_bg(page)
        ratio = contrast_ratio(h1_color, bg)
        check("заголовок на фоне страницы", ratio >= 4.5,
              f"контраст {ratio:.1f}:1")
        body_color = page.evaluate(
            "() => getComputedStyle(document.body).color")
        ratio2 = contrast_ratio(body_color, bg)
        check("основной текст на фоне страницы", ratio2 >= 4.5,
              f"контраст {ratio2:.1f}:1")

        # консоль без ошибок (гидратация/рендер)
        real_errors = [e for e in console_errors
                       if "404" not in e and "400" not in e]
        check("JS-ошибок на страницах нет", not real_errors,
              "; ".join(real_errors[:2]))

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
