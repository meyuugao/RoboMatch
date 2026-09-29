#!/usr/bin/env python3
"""Скриншоты демо-проектов (задача 4) для docs/examples/demo_projects/.

Открывает страницы экономики и сценариев (сравнение) каждого
демо-проекта «Демо: *» под демо-аккаунтом user и сохраняет
скриншоты 1366×900 (светлая тема).

Запуск (стек поднят, seed + импорт фикстур + расчёты выполнены -
например, после scripts/verify_economics_new.py):
  FRONTEND_URL=http://localhost:3100 python3 scripts/shoot_demo_projects.py
"""
import os
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3100")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "user", "useruser")
OUT_DIR = Path(__file__).resolve().parent.parent / "docs" / "examples" / "demo_projects"

# id фиксированы seed-порядком (после 5 E2E-проектов; при изменении
# порядка seed - искать по имени через /api/projects)
PROJECTS = (
    ("Демо: Оптимальный склад", "optimal"),
    ("Демо: Пиковая нагрузка - успех", "peak"),
    ("Демо: Умеренный масштаб", "moderate"),
)


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        page = browser.new_context(
            viewport={"width": 1366, "height": 900}, device_scale_factor=1).new_page()
        # BFF-логин: httpOnly-cookie для страниц
        resp = page.request.post(
            f"{FRONTEND_URL}/api/auth/login",
            data={"login": LOGIN, "password": "useruser"})
        if resp.status != 200:
            print("вход не удался")
            return 1
        # id по имени (стабильность против порядка seed) - прямой backend
        import json
        import urllib.request
        req_login = urllib.request.Request(
            "http://127.0.0.1:8080/api/auth/login",
            data=json.dumps({"login": LOGIN, "password": "useruser"}).encode(),
            headers={"Content-Type": "application/json"})
        token = json.load(urllib.request.urlopen(req_login))["token"]
        req = urllib.request.Request(
            "http://127.0.0.1:8080/api/projects?size=100",
            headers={"Authorization": "Bearer " + token})
        projects = json.load(urllib.request.urlopen(req))["content"]
        ids = {pr["name"]: pr["id"] for pr in projects}
        for name, slug in PROJECTS:
            pid = ids[name]
            for path, what in ((f"/projects/{pid}/economics", "economics"),
                               (f"/projects/{pid}/scenarios", "comparison")):
                page.goto(f"{FRONTEND_URL}{path}", wait_until="networkidle")
                page.wait_for_timeout(1200)
                out = OUT_DIR / f"{slug}-{what}.png"
                page.screenshot(path=str(out), full_page=True)
                print(f"  сохранён {out.name}")
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
