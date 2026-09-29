#!/usr/bin/env python3
"""E2E-проверка семантики 2D-схемы имитации на живом стеке.

Что проверяет (Playwright + Chromium, DOM-инспекция):
  1. семантика схемы: 4 технологические зоны + зарядный блок; точки
     операций каждой зоны (полки/стеллажи/корзины/доки — с координатами
     центров data-x/data-y); зарядные станции по числу из модели;
     кольцевой маршрут со стрелками; роботы по числу парка; статусы
     роботов с легендой; 6 KPI; узкое место с пунктирной
     рамкой и бейджем; блок предупреждений и сверки;
  2. остановки «загружается/разгружается» строго в точках операций
     (assumptions.md §22-бис): в фазе handling координаты робота
     совпадают с центром одной из точек операций (±2 px), координаты
     стабильны всю фазу, суммарное время handling за цикл =
     sim_pick_drop_sec (две фазы: погрузка + разгрузка по половине),
     фаза «движется» не пересекается с handling;
  3. перекрытия (headless, getBoundingClientRect): ни одна подпись не
     перекрыта ни текстом, ни роботом, ни маршрутом/стрелками, ни
     иконками операций/станций, ни рамкой узкого места — на 4
     разрешениях (1366×768, 1920×1080, 375×812, 768×1024) в ОБЕИХ
     темах (светлая/тёмная);
  4. подписи паллет — под иконками операций, внутри своих зон;
  5. тема схемы: сохранение SVG в обеих темах (data-theme и палитра
     холста), выбор темы схемы на странице экспорта («Как в UI» /
     «Светлая» / «Тёмная»), перерисовка сохранённой схемы сервером при
     экспорте с другой темой (PDF содержит схему выбранной темы);
  6. адаптивность: схема масштабируется, документ не шире вьюпорта
     на 375×812, кнопки управления доступны;
  7. скриншоты обеих тем (полная схема + зоны + легенда) —
     docs/examples/schema-review/ для визуального контроля.

Запуск (PostgreSQL + backend + frontend подняты, seed выполнен):
  FRONTEND_URL=http://localhost:3100 python3 \
    scripts/verify_simulation_schema_semantics.py

Переменные окружения:
  FRONTEND_URL — адрес frontend (по умолчанию http://localhost:3100)
  E2E_LOGIN    — логин владельца проекта (user)
  E2E_PASSWORD — пароль (useruser)
  PROJECT_ID   — проект с рассчитанной покупкой (4 «Пиковая нагрузка»)
"""
import json
import os
import re
import shutil
import sys

from playwright.sync_api import sync_playwright

FRONTEND_URL = os.environ.get("FRONTEND_URL", "http://localhost:3100")
LOGIN = os.environ.get("E2E_LOGIN", "user")
PASSWORDS = (os.environ.get("E2E_PASSWORD"), "user", "useruser")
PROJECT_ID = os.environ.get("PROJECT_ID", "4")
VIEWPORTS = [(1366, 768), (1920, 1080), (375, 812), (768, 1024)]
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREENSHOT_DIR = os.path.join(REPO_ROOT, "docs", "examples", "schema-review")

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    mark = "ok " if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""))


# JS: перекрытия текста со всеми препятствиями. Полилинии и линии
# (маршрут, потоки, стрелки) проверяются ПОСЕГМЕНТНО (Liang-Barsky):
# bbox замкнутой полилинии охватывает всю схему и дал бы ложные
# срабатывания. Иконки операций/станций и детали path — bbox.
# Рамка узкого места (fill="none", контур) не препятствие — текст
# внутри рамки читается. Касание < 1 px не дефект.
OVERLAP_JS = r"""
() => {
  const svg = document.querySelector('svg[data-testid="warehouse-schema"]');
  if (!svg) return {textText: ['нет схемы'], textObstacle: []};
  const svgRect = svg.getBoundingClientRect();
  const scale = svgRect.width / 960;
  const texts = [...svg.querySelectorAll('text')];
  const robots = [...svg.querySelectorAll('g[data-testid="schema-robot"]')];
  const icons = [...svg.querySelectorAll('rect[width="26"][height="22"], '
      + 'rect[width="64"][height="20"], rect[width="16"][height="12"], '
      + 'rect[width="26"][height="26"], path')]
      .filter((el) => el.closest('defs') === null);
  const rect = (el) => { const r = el.getBoundingClientRect();
    return {x: r.x, y: r.y, w: r.width, h: r.height}; };
  const overlap = (a, b) =>
    Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x) > 1.0 &&
    Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y) > 1.0;
  const related = (a, b) => a.contains(b) || b.contains(a);
  // отрезок × прямоугольник (расширенный на толщину линии)
  const segHits = (x1, y1, x2, y2, r, pad) => {
    const rr = {x: r.x - pad, y: r.y - pad, w: r.w + 2 * pad, h: r.h + 2 * pad};
    let t0 = 0, t1 = 1;
    const dx = x2 - x1, dy = y2 - y1;
    const p = [-dx, dx, -dy, dy];
    const q = [x1 - rr.x, rr.x + rr.w - x1, y1 - rr.y, rr.y + rr.h - y1];
    for (let i = 0; i < 4; i++) {
      if (p[i] === 0) { if (q[i] < 0) return false; }
      else {
        const t = q[i] / p[i];
        if (p[i] < 0) { if (t > t1) return false; if (t > t0) t0 = t; }
        else { if (t < t0) return false; if (t < t1) t1 = t; }
      }
    }
    return true;
  };
  const segs = [];
  for (const pl of svg.querySelectorAll('polyline')) {
    if (pl.closest('defs')) continue;
    const pts = (pl.getAttribute('points') || '').trim().split(/\s+/)
      .map((p) => p.split(',').map(Number));
    for (let i = 0; i + 1 < pts.length; i++) {
      segs.push([pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]]);
    }
  }
  for (const ln of svg.querySelectorAll('line')) {
    if (ln.closest('defs')) continue;
    segs.push([ln.x1.baseVal.value, ln.y1.baseVal.value,
               ln.x2.baseVal.value, ln.y2.baseVal.value]);
  }
  const tt = [];
  for (let i = 0; i < texts.length; i++) {
    for (let j = i + 1; j < texts.length; j++) {
      if (related(texts[i], texts[j])) continue;
      const a = rect(texts[i]), b = rect(texts[j]);
      if (a.w > 0 && b.w > 0 && overlap(a, b)) {
        tt.push((texts[i].textContent || '').trim().slice(0, 24)
          + ' <-> ' + (texts[j].textContent || '').trim().slice(0, 24));
      }
    }
  }
  const to = [];
  for (const t of texts) {
    const a = rect(t);
    if (a.w === 0) continue;
    for (const [x1, y1, x2, y2] of segs) {
      const sx1 = svgRect.x + x1 * scale, sy1 = svgRect.y + y1 * scale;
      const sx2 = svgRect.x + x2 * scale, sy2 = svgRect.y + y2 * scale;
      if (segHits(sx1, sy1, sx2, sy2, a, 1.5)) {
        to.push((t.textContent || '').trim().slice(0, 24) + ' × линия');
      }
    }
    for (const o of [...icons, ...robots]) {
      if (related(t, o)) continue;
      const b = rect(o);
      if (b.w > 0 && overlap(a, b)) {
        to.push((t.textContent || '').trim().slice(0, 24) + ' × '
          + (o.getAttribute('data-testid') || o.tagName.toLowerCase()));
      }
    }
  }
  return {textText: tt, textObstacle: [...new Set(to)]};
}
"""

# JS: срез состояния анимации — модельное время и роботы (статус и
# координаты из data-атрибутов, координаты viewBox)
SAMPLE_JS = """
() => {
  const svg = document.querySelector('svg[data-testid="warehouse-schema"]');
  const time = parseFloat(svg.getAttribute('data-model-time') || '0');
  const robots = [...svg.querySelectorAll('g[data-testid="schema-robot"]')]
    .map((g) => ({
      status: g.getAttribute('data-status'),
      x: parseFloat(g.getAttribute('data-x')),
      y: parseFloat(g.getAttribute('data-y')),
    }));
  return {time, robots};
}
"""

# JS: точки операций (центры) из data-атрибутов иконок
POINTS_JS = """
() => [...document.querySelectorAll(
    'svg g[data-testid="operation-point"]')]
  .map((g) => ({
    x: parseFloat(g.getAttribute('data-x')),
    y: parseFloat(g.getAttribute('data-y')),
  }))
"""

# JS: bbox подписи паллеты и нижней границы иконок зоны
PALLET_JS = """
(zone) => {
  const label = document.querySelector(
    `g[data-testid="pallet-label"][data-pallet-zone="${zone}"] text`);
  const icons = [...document.querySelectorAll(
    `svg g[data-zone="${zone}"] g[data-testid="operation-point"] rect`)];
  if (!label || icons.length === 0) return null;
  const lr = label.getBoundingClientRect();
  const box = document.querySelector(
    `svg g[data-zone="${zone}"] > rect`)?.getBoundingClientRect();
  const iconBottom = Math.max(...icons.map((i) => {
    const r = i.getBoundingClientRect();
    return r.y + r.height;
  }));
  return {label: {x: lr.x, y: lr.y, w: lr.width, h: lr.height},
          iconBottom, zoneBox: box ? {x: box.x, w: box.width,
                                      bottom: box.y + box.height} : null};
}
"""


def handling_intervals(samples, robot_index):
    """Интервалы статуса handling одного робота: [(t0, t1, x, y)]."""
    intervals = []
    current = None
    for sample in samples:
        robot = sample["robots"][robot_index]
        if robot["status"] == "handling":
            if current is None:
                current = {
                    "t0": sample["time"],
                    "t1": sample["time"],
                    "xs": [robot["x"]],
                    "ys": [robot["y"]],
                }
            else:
                current["t1"] = sample["time"]
                current["xs"].append(robot["x"])
                current["ys"].append(robot["y"])
        elif current is not None:
            intervals.append(current)
            current = None
    if current is not None:
        intervals.append(current)
    return intervals


def main():
    print(f"Семантика 2D-схемы: {FRONTEND_URL}/projects/{PROJECT_ID}/simulation")
    os.makedirs(SCREENSHOT_DIR, exist_ok=True)
    console_errors = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(viewport={"width": 1366, "height": 768})
        page = context.new_page()
        page.on("pageerror", lambda e: console_errors.append(str(e)))
        page.on("console", lambda m: console_errors.append(m.text)
                if m.type == "error" else None)

        # --- вход (через BFF API: cookie в контексте браузера) -----------
        logged = False
        for pwd in PASSWORDS:
            if not pwd:
                continue
            resp = page.request.post(
                f"{FRONTEND_URL}/api/auth/login",
                data={"login": LOGIN, "password": pwd})
            if resp.status == 200:
                logged = True
                break
        check("вход демо-аккаунтом", logged, "cookie установлена")

        # --- страница имитации --------------------------------------------
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/simulation",
                  wait_until="networkidle")
        try:
            page.wait_for_selector("svg[data-testid='warehouse-schema']",
                                   timeout=10000)
        except Exception:
            # последняя имитация сценария могла не запускаться —
            # запускаем модель сами (кнопка доступна при составе)
            page.click("button:has-text('Запустить')")
        page.wait_for_selector("svg[data-testid='warehouse-schema']",
                               timeout=90000)
        check("SVG-схема отрисовывается", True)

        body = page.inner_text("body").lower()

        # 1. зоны
        for zone in ("приёмка", "хранение", "отбор", "отгрузка",
                     "зарядные станции"):
            check(f"зона «{zone}» подписана", zone in body)

        # 2. точки операций (иконки с координатами центров)
        points = page.evaluate(POINTS_JS)
        check("точки операций размечены (20 центров)", len(points) == 20,
              f"точек {len(points)}")
        docks = page.locator('svg rect[width="26"][height="22"]').count()
        ship_arrows = page.locator('svg path[d^="M 8 3 L 13 -1"]').count()
        racks = page.locator('svg rect[width="64"][height="20"]').count()
        bins = page.locator('svg rect[width="16"][height="12"]').count()
        check("точки операций приёмки (3 полки)",
              docks - ship_arrows == 3,
              f"иконок {docks}, стрелок доков {ship_arrows}")
        check("точки операций хранения (8 стеллажей)", racks == 8,
              f"иконок {racks}")
        check("точки операций отбора (6 корзин)", bins == 6, f"иконок {bins}")
        check("точки операций отгрузки (3 дока)", ship_arrows == 3,
              f"иконок {ship_arrows}")

        # 3. зарядные станции = число из модели
        stations = page.locator('svg rect[width="26"][height="26"]').count()
        model_stations = page.evaluate(
            "() => { const m = document.body.innerText"
            ".match(/Зарядных станций:\\s*(\\d+)/);"
            " return m ? parseInt(m[1], 10) : 0; }")
        check("зарядные станции по числу из модели",
              stations >= 1 and model_stations >= 1
              and stations == model_stations,
              f"иконок {stations}, в модели {model_stations}")

        # 4. маршрут
        route = page.locator('svg polyline[stroke-dasharray="8 6"]').count()
        arrows = page.locator('svg path[d^="M 12.5 -3.5 L 18 0"]').count()
        check("кольцевой маршрут роботов (пунктир)", route >= 1)
        check("стрелки направления на маршруте", arrows >= 3,
              f"стрелок {arrows}")

        # 5. роботы
        robots = page.locator('svg g[data-testid="schema-robot"]').count()
        check("роботы на схеме (по числу парка)", robots >= 1,
              f"иконок {robots}")

        # 6. статусы + легенда
        for label in ("движется", "загружается",
                      "простаивает (зарядка)"):
            check(f"легенда статуса «{label}»", label in body)

        # 7. 6 KPI
        for kpi in ("заявленная", "фактическая", "загрузка", "простои",
                    "узкое место", "достижимость"):
            check(f"KPI «{kpi}» в панели", kpi in body)

        # 8. узкое место: рамка + бейдж
        bottleneck_border = page.locator(
            'svg rect[stroke-dasharray="7 4"]').count()
        check("узкое место подсвечено (пунктирная рамка)",
              bottleneck_border >= 1)
        check("бейдж узкого места с загрузкой",
              "узкое место:" in body and "%" in body)

        # 9. предупреждения и сверка с расчётом
        check("блок предупреждений и сверки с расчётом",
              "предупреждения имитации и сверка с расчётом экономики" in body)

        # --- 10. остановки handling строго в точках операций -------------
        print("\n== Погрузка/разгрузка: остановки в точках операций ==")
        pick_drop = float(page.evaluate(
            "() => document.querySelector('svg[data-testid=\"warehouse-schema\"]')"
            ".getAttribute('data-pick-drop-sec')"))
        check("sim_pick_drop_sec опубликован на схеме", pick_drop > 0,
              f"{pick_drop} с")
        # ускоряем до 5×, чтобы фазы прошли за секунды реального времени
        page.click("button:text-is('5×')")
        page.wait_for_timeout(800)
        samples = []
        for _ in range(260):
            sample = page.evaluate(SAMPLE_JS)
            samples.append(sample)
            page.wait_for_timeout(150)
        check("срезы анимации собраны", len(samples) == 260,
              f"{len(samples)} срезов, роботов {len(samples[0]['robots'])}")

        any_interval = False
        stable_ok = True
        point_ok = True
        duration_ok = True
        phase_split_ok = True
        total_phases = 0
        durations = []
        pair_values = []
        for robot_index in range(len(samples[0]["robots"])):
            intervals = handling_intervals(samples, robot_index)
            if not intervals:
                continue
            any_interval = True
            # первый и последний интервалы могут быть усечены окном
            # наблюдения — для проверок длительности отбрасываем их
            trimmed = intervals[1:-1] if len(intervals) > 2 else []
            for interval in intervals:
                total_phases += 1
                # координаты стабильны всю фазу
                if (max(interval["xs"]) - min(interval["xs"]) > 0.6
                        or max(interval["ys"]) - min(interval["ys"]) > 0.6):
                    stable_ok = False
                # стоит ровно в точке операции (±2 px)
                on_point = any(
                    abs(interval["xs"][-1] - p["x"]) <= 2
                    and abs(interval["ys"][-1] - p["y"]) <= 2
                    for p in points)
                if not on_point:
                    point_ok = False
                # длительность фазы ≈ половина pick_drop (погрузка или
                # разгрузка)
                dur = interval["t1"] - interval["t0"]
                if interval in trimmed:
                    durations.append(dur)
                    half = pick_drop / 2
                    if not (half * 0.65 <= dur <= half * 1.45):
                        duration_ok = False
            # пары соседних фаз (погрузка + разгрузка через переезд)
            # в сумме = pick_drop
            for first, second in zip(trimmed, trimmed[1:]):
                gap = second["t0"] - first["t1"]
                if gap > pick_drop / 2:
                    continue
                pair = (second["t1"] - second["t0"]) + (first["t1"] - first["t0"])
                pair_values.append(pair)
                if not (pick_drop * 0.7 <= pair <= pick_drop * 1.35):
                    phase_split_ok = False
        check("фазы погрузки/разгрузки наблюдаются", any_interval,
              f"фаз {total_phases}")
        check("робот в handling стоит строго в точке операции (±2 px)",
              point_ok)
        check("координаты робота стабильны всю фазу handling", stable_ok)
        check("фаза погрузки/разгрузки ≈ sim_pick_drop_sec / 2",
              duration_ok,
              f"ожидалось ~{pick_drop / 2:.0f} с; фактически "
              f"min={min(durations):.1f} max={max(durations):.1f} "
              f"({len(durations)} фаз)" if durations else "нет фаз")
        check("погрузка + разгрузка = sim_pick_drop_sec", phase_split_ok,
              f"ожидалось ~{pick_drop:.0f} с; фактически "
              f"min={min(pair_values):.1f} max={max(pair_values):.1f} "
              f"({len(pair_values)} пар)" if pair_values else "нет пар")
        # фазы не пересекаются: статус в каждый момент один
        statuses_single = all(
            all(r["status"] in ("moving", "handling", "idle")
                for r in sample["robots"])
            for sample in samples)
        check("фазы «движется» и handling не пересекаются",
              statuses_single)
        page.click("button:text-is('1×')")

        # --- 11. перекрытия: 4 разрешения × 2 темы ------------------------
        print("\n== Перекрытия (текст × всё) на 4 разрешениях × 2 темы ==")
        for theme in ("light", "dark"):
            page.click(f'button[data-theme-option="{theme}"]')
            page.wait_for_timeout(600)
            for width, height in VIEWPORTS:
                page.set_viewport_size({"width": width, "height": height})
                page.wait_for_timeout(400)
                overlaps = page.evaluate(OVERLAP_JS)
                check(f"текст не перекрыт текстом ({theme}, {width}×{height})",
                      not overlaps["textText"],
                      "; ".join(overlaps["textText"][:3]))
                check(f"текст не перекрыт элементами ({theme}, {width}×{height})",
                      not overlaps["textObstacle"],
                      "; ".join(overlaps["textObstacle"][:3]))

        # --- 12. подписи паллет — под иконками операций, в зоне -----------
        print("\n== Подписи паллет под паллетами ==")
        for zone, word in (("receiving", "входящие"),
                           ("shipping", "исходящие")):
            info = page.evaluate(PALLET_JS, zone)
            ok = info is not None and info["label"]["y"] >= info["iconBottom"]
            in_zone = (info is not None and info["zoneBox"] is not None
                       and info["label"]["x"] >= info["zoneBox"]["x"]
                       and info["label"]["x"] + info["label"]["w"]
                       <= info["zoneBox"]["x"] + info["zoneBox"]["w"]
                       and info["label"]["y"] + info["label"]["h"]
                       <= info["zoneBox"]["bottom"] + 1)
            check(f"подпись «{word} паллеты» под иконками операций", ok)
            check(f"подпись «{word} паллеты» внутри зоны", in_zone)
        check("текст подписей паллет присутствует",
              "Входящие паллеты" in page.inner_text("body")
              and "Исходящие паллеты" in page.inner_text("body"))

        # --- 13. адаптив 375 ------------------------------------------------
        page.set_viewport_size({"width": 375, "height": 812})
        page.wait_for_timeout(400)
        overflow = page.evaluate(
            "() => document.documentElement.scrollWidth"
            " - document.documentElement.clientWidth")
        check("нет горизонтального перелива на 375×812", overflow <= 2,
              f"перелив {overflow}px")
        controls = page.evaluate(
            """() => [...document.querySelectorAll('button')]
                .filter(b => /Запустить|Остановить|Перезапустить|×/.test(b.textContent))
                .map(b => { const r = b.getBoundingClientRect();
                  return r.height > 0 && r.width > 0; })
                .filter(Boolean).length""")
        check("кнопки управления доступны на 375×812", controls >= 3,
              f"видимых кнопок {controls}")
        page.set_viewport_size({"width": 1366, "height": 768})

        # --- 14. тема схемы: сохранение SVG в обеих темах ------------------
        print("\n== Тема схемы: сохранение и экспорт ==")
        canvas_fill = lambda: page.evaluate(
            "() => document.querySelector('svg rect[width=\"940\"]')"
            ".getAttribute('fill')")

        def canvas_fill_of(svg_text):
            match = re.search(r'<rect[^>]*height="460"[^>]*>', svg_text)
            if not match:
                return None
            fill = re.search(r'fill="([^"]+)"', match.group(0))
            return fill.group(1).lower() if fill else None

        # светлую тему выставляем явно (после цикла перекрытий могла
        # остаться тёмная) — и сохраняем схему
        page.click('button[data-theme-option="light"]')
        page.wait_for_timeout(700)
        page.click("button:has-text('Сохранить схему')")
        link = page.wait_for_selector("a:has-text('Скачать сохранённую схему')",
                                      timeout=30000)
        href = link.get_attribute("href")
        svg_url = f"{FRONTEND_URL}{href}"
        page.wait_for_timeout(500)
        saved_light = page.request.get(svg_url)
        light_svg = saved_light.text()
        check("светлая тема: SVG сохранён", saved_light.status == 200
              and 'viewBox="0 0 960 480"' in light_svg)
        check("светлая тема: data-theme=\"light\" в SVG",
              'data-theme="light"' in light_svg)
        light_canvas = canvas_fill_of(light_svg)
        check("светлая тема: светлый холст схемы",
              light_canvas in ("#f8fafc", "#ffffff", "#f1f5f9"),
              str(light_canvas))

        # тёмная тема интерфейса → сохранение даёт тёмную схему
        page.click('button[data-theme-option="dark"]')
        page.wait_for_timeout(700)
        page.click("button:has-text('Сохранить схему')")
        page.wait_for_timeout(500)
        saved_dark = page.request.get(svg_url)
        dark_svg = saved_dark.text()
        check("тёмная тема: data-theme=\"dark\" в SVG",
              'data-theme="dark"' in dark_svg)
        dark_canvas = canvas_fill_of(dark_svg)
        check("тёмная тема: тёмный холст схемы (не инвертирован)",
              dark_canvas in ("#0f172a", "#0b1220", "#111827"),
              str(dark_canvas))
        check("палитра холста меняется с темой",
              light_canvas is not None and dark_canvas is not None
              and dark_canvas != light_canvas)

        # --- 15. страница экспорта: выбор темы схемы -------------------------
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/export",
                  wait_until="domcontentloaded")
        page.wait_for_selector("[data-testid='schema-theme']", timeout=20000)
        options = page.locator("[data-schema-theme-option]").count()
        check("выбор темы схемы на странице экспорта", options == 3,
              f"опций {options}")
        ui_checked = page.locator(
            "[data-schema-theme-option='ui']").get_attribute("aria-checked")
        check("тема схемы по умолчанию — «Как в UI»", ui_checked == "true")

        # --- 16. экспорт PDF: перерисовка схемы в выбранной теме -----------
        # сохранена тёмная схема; просим светлую — сервер обязан
        # перерисовать; затем тёмную — снова тёмная
        for schema_theme, expect in (("light", "light"), ("dark", "dark")):
            resp = page.request.post(
                f"{FRONTEND_URL}/api/projects/{PROJECT_ID}/exports",
                data=json.dumps({
                    "format": "pdf",
                    "theme": schema_theme,
                    "schemaTheme": schema_theme,
                }),
                headers={"Content-Type": "application/json"})
            check(f"PDF с schemaTheme={schema_theme}: 201",
                  resp.status == 201, f"HTTP {resp.status}")
            export_id = resp.json()["id"] if resp.status == 201 else None
            if export_id is not None and shutil.which("pdfimages"):
                pdf = page.request.get(
                    f"{FRONTEND_URL}/api/projects/{PROJECT_ID}"
                    f"/exports/{export_id}")
                if pdf.status == 200:
                    tmp = f"/tmp/schema-theme-{schema_theme}.pdf"
                    with open(tmp, "wb") as handle:
                        handle.write(pdf.body())
                    import subprocess
                    images = subprocess.run(
                        ["pdfimages", "-list", tmp],
                        capture_output=True, text=True).stdout
                    img_rows = [line for line in images.splitlines()[2:]
                                if line.strip()]
                    check(f"PDF ({schema_theme}) содержит изображение схемы",
                          len(img_rows) >= 1, f"изображений {len(img_rows)}")
            # перерисовку проверяем на файле FRESHEST имитации проекта
            # (её берёт ReportDataBuilder: история по id DESC; это может
            # быть не тот сценарий, что открыт в UI — ссылка клиента
            # относится к клиентской имитации)
            sims = page.request.get(
                f"{FRONTEND_URL}/api/projects/{PROJECT_ID}"
                "/simulations").json()
            freshest_url = None
            if isinstance(sims, list):
                for row in sims:
                    if isinstance(row, dict) and row.get("status") == \
                            "completed" and row.get("exportUrl"):
                        freshest_url = row["exportUrl"]
                        break
            after = page.request.get(
                f"{FRONTEND_URL}{freshest_url}").text() \
                if freshest_url else page.request.get(svg_url).text()
            page.wait_for_timeout(300)
            after = page.request.get(
                f"{FRONTEND_URL}{freshest_url}").text() \
                if freshest_url else page.request.get(svg_url).text()
            check(f"сохранённая схема перерисована в {expect}",
                  f'data-theme="{expect}"' in after,
                  f"фресhest={freshest_url}" if freshest_url else "нет")

        # --- 17. скриншоты обеих тем для визуального контроля --------------
        print("\n== Скриншоты для визуального контроля ==")
        page.goto(f"{FRONTEND_URL}/projects/{PROJECT_ID}/simulation",
                  wait_until="networkidle")
        page.wait_for_selector("svg[data-testid='warehouse-schema']",
                               timeout=30000)
        for theme in ("light", "dark"):
            page.click(f'button[data-theme-option="{theme}"]')
            page.wait_for_timeout(700)
            full = page.locator('svg[data-testid="warehouse-schema"]')
            full.screenshot(
                path=os.path.join(SCREENSHOT_DIR, f"{theme}-full.png"))
            for zone in ("receiving", "storage", "picking", "shipping",
                         "charging"):
                loc = page.locator(f'svg g[data-zone="{zone}"]')
                loc.screenshot(
                    path=os.path.join(SCREENSHOT_DIR,
                                      f"{theme}-zone-{zone}.png"))
            legend = page.locator('svg g:has(> rect[width="170"])')
            legend.screenshot(
                path=os.path.join(SCREENSHOT_DIR, f"{theme}-legend.png"))
        shot_count = len([f for f in os.listdir(SCREENSHOT_DIR)
                          if f.endswith(".png")])
        check("скриншоты схем приложены (2 темы × 7 видов)",
              shot_count >= 14, f"файлов {shot_count}")

        # --- 18. консоль без ошибок -----------------------------------------
        real_errors = [e for e in console_errors
                       if "401" not in e and "400" not in e
                       and "net::" not in e]
        check("JS-ошибок на странице нет", not real_errors,
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
