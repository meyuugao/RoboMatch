"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  SimulationRun,
  SimulationZone,
} from "@/types/simulation";
import { SIM_STATUS_LABELS, ZONE_LABELS } from "@/types/simulation";
import { useTheme } from "@/components/ThemeProvider";

/**
 * Палитра SVG-схемы: светлая и тёмная. Цвета — литералы (SVG
 * сериализуется в файл экспорта как есть: сохранённая схема остаётся
 * в той теме, в которой её сохранили). Стиль — промышленная
 * диспетчерская: сланцевый холст с
 * сеткой, полупрозрачные зоны, плашки-подписи, цельные иконки роботов
 * с тенями, отдельный блок легенды; контраст WCAG AA для подписей.
 */
const SCHEMA_LIGHT = {
  canvasFill: "#f8fafc",
  canvasStroke: "#cbd5e1",
  title: "#0f172a",
  subtitle: "#64748b",
  grid: "#e2e8f0",
  zoneTitle: "#334155",
  zoneSub: "#64748b",
  zoneStroke: "#94a3b8",
  zoneOpacityBase: 0.45,
  zoneOpacityLoaded: 0.6,
  zoneOpacityBottleneck: 0.7,
  bottleneckFill: "#ef4444",
  loadedFill: "#f59e0b",
  baseFill: "#94a3b8",
  chargingFill: "#10b981",
  bottleneckStroke: "#dc2626",
  bottleneckText: "#dc2626",
  badgeFill: "#fef2f2",
  badgeStroke: "#fecaca",
  plateFill: "#ffffff",
  plateStroke: "#e2e8f0",
  shelfFill: "#bfdbfe",
  shelfStroke: "#60a5fa",
  rackFill: "#e2e8f0",
  rackStroke: "#94a3b8",
  pickFill: "#fde68a",
  pickStroke: "#d97706",
  shipFill: "#bbf7d0",
  shipStroke: "#4ade80",
  stationFill: "#a7f3d0",
  stationStroke: "#10b981",
  arrow: "#64748b",
  arrowGreen: "#10b981",
  route: "#94a3b8",
  moving: "#2563eb",
  handling: "#d97706",
  idle: "#94a3b8",
  chargingRing: "#d97706",
  legendText: "#475569",
  legendTitle: "#334155",
  shadowColor: "#0f172a",
  shadowOpacity: 0.2,
  robotHalo: "#f8fafc",
};

const SCHEMA_DARK = {
  canvasFill: "#0f172a",
  canvasStroke: "#334155",
  title: "#f1f5f9",
  subtitle: "#94a3b8",
  grid: "#1e293b",
  zoneTitle: "#cbd5e1",
  zoneSub: "#94a3b8",
  zoneStroke: "#475569",
  zoneOpacityBase: 0.5,
  zoneOpacityLoaded: 0.65,
  zoneOpacityBottleneck: 0.8,
  bottleneckFill: "#ef4444",
  loadedFill: "#f59e0b",
  baseFill: "#334155",
  chargingFill: "#10b981",
  bottleneckStroke: "#f87171",
  bottleneckText: "#f87171",
  badgeFill: "#450a0a",
  badgeStroke: "#7f1d1d",
  plateFill: "#1e293b",
  plateStroke: "#334155",
  shelfFill: "#1e3a8a",
  shelfStroke: "#3b82f6",
  rackFill: "#334155",
  rackStroke: "#64748b",
  pickFill: "#451a03",
  pickStroke: "#f59e0b",
  shipFill: "#14532d",
  shipStroke: "#4ade80",
  stationFill: "#064e3b",
  stationStroke: "#34d399",
  arrow: "#94a3b8",
  arrowGreen: "#34d399",
  route: "#475569",
  moving: "#3b82f6",
  handling: "#f59e0b",
  idle: "#64748b",
  chargingRing: "#f59e0b",
  legendText: "#94a3b8",
  legendTitle: "#cbd5e1",
  shadowColor: "#000000",
  shadowOpacity: 0.45,
  robotHalo: "#0f172a",
};

/**
 * Расписание фаз робота внутри цикла (модельное время, с):
 *
 * <pre>
 * [0, pureMoveEnd) чистое движение по маршруту
 * [pureMoveEnd, apprEnd) подход к точке погрузки A (статус «движется»)
 * [apprEnd, loadEnd) ПОГРУЗКА — строго в точке A (handling)
 * [loadEnd, transitEnd) переезд A → B (статус «движется»)
 * [transitEnd, unloadEnd) РАЗГРУЗКА — строго в точке B (handling)
 * [unloadEnd, returnEnd) возврат на маршрут («движется»)
 * [returnEnd, cycleSec) простой (зарядка/ожидание)
 * </pre>
 *
 * Длину цикла выводим из KPI: доля handling = handlingPct, время
 * handling = sim_pick_drop_sec → cycleSec = pickDrop / handlingShare.
 * Доли движется/простаивает также из KPI; переходные окна (подход,
 * переезд, возврат) короткие и вычитаются из времени движения.
 */
type RobotWindows = {
  cycleSec: number;
  hasHandling: boolean;
  approachSec: number;
  transitSec: number;
  returnSec: number;
  pureMoveEnd: number;
  approachEnd: number;
  loadEnd: number;
  transitEnd: number;
  unloadEnd: number;
  returnEnd: number;
  idleStart: number;
};

function computeWindows(
  movingPct: number,
  handlingPct: number,
  pickDropSec: number,
): RobotWindows {
  const moveShare = Math.max(0, Math.min(1, movingPct / 100));
  const handleShare = Math.max(0, Math.min(1, handlingPct / 100));
  const idleShare = Math.max(0, 1 - moveShare - handleShare);
  const hasHandling = handleShare > 0 && pickDropSec > 0;
  // без погрузки-разгрузки цикл — полный круг 60 с модельного времени
  const cycleSec = hasHandling ? pickDropSec / handleShare : 60;
  const approachSec = hasHandling ? 2 : 0;
  const transitSec = hasHandling ? 2 : 0;
  const returnSec = hasHandling ? 2 : 0;
  const half = hasHandling ? pickDropSec / 2 : 0;
  // окно простоя — в конце цикла (доля idlePct)
  const idleStart = cycleSec * (1 - idleShare);
  // чистое движение: остаток после вычета переходов из движений
  const pureMoveEnd = hasHandling
    ? Math.max(0, Math.min(idleStart, cycleSec * moveShare)
      - approachSec - transitSec - returnSec)
    : Math.max(0, Math.min(idleStart, cycleSec * moveShare));
  const approachEnd = pureMoveEnd + approachSec;
  const loadEnd = approachEnd + half;
  const transitEnd = loadEnd + transitSec;
  const unloadEnd = transitEnd + half;
  const returnEnd = Math.min(unloadEnd + returnSec, cycleSec);
  return {
    cycleSec,
    hasHandling,
    approachSec,
    transitSec,
    returnSec,
    pureMoveEnd,
    approachEnd,
    loadEnd,
    transitEnd,
    unloadEnd,
    returnEnd,
    idleStart: Math.min(idleStart, cycleSec),
  };
}

/**
 * Интерактивная имитация (,
 * 2.1.4, 4.3.3): 2D-схема склада (SVG) с зонами, маршрутами, роботами
 * и точками операций/зарядки + KPI-панель (6 KPI) + сверка с
 * расчётом экономики + управление моделью (запуск/пауза/
 * перезапуск/скорость/сценарий) + статус выполнения (индикатор,
 * предупреждение при > 60 с) + сохранение схемы.
 *
 * <p>АНИМАЦИЯ поверх расчётных KPI (
 * параметры сценария): доли статусов роботов — из kpi_json
 * (movingPct/handlingPct, простои — остаток), скорость — множитель
 * воспроизведения; роботы циркулируют по кольцевому маршруту через
 * зоны (приёмка → хранение → отбор → отгрузка) и блок зарядных
 * станций. Модель расчёта — на backend (синхронно, миллисекунды).
 */
export default function SimulationView({
  projectId,
  scenarios,
  initialScenarioId,
  initialSimulation,
}: {
  projectId: string;
  /** Роботизированные сценарии (base скрыт — заведомо 400). */
  scenarios: Array<{
    id: number;
    name: string;
    type: string;
    solutionCount: number;
  }>;
  initialScenarioId: number | null;
  initialSimulation: SimulationRun | null;
}) {
  const [scenarioId, setScenarioId] = useState<number | null>(
    initialScenarioId,
  );
  const [simulation, setSimulation] = useState<SimulationRun | null>(
    initialSimulation,
  );
  const [running, setRunning] = useState(false);
  const [paused, setPaused] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [elapsedMs, setElapsedMs] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [exportUrl, setExportUrl] = useState<string | null>(
    initialSimulation?.exportUrl ?? null,
  );
  const [savedMessage, setSavedMessage] = useState<string | null>(null);

  // тема схемы: переключается на лету (без перезапуска имитации —
  // анимация живёт в refs, ре-рендер только перекрашивает SVG)
  const { resolvedTheme } = useTheme();
  const SCHEMA = resolvedTheme === "dark" ? SCHEMA_DARK : SCHEMA_LIGHT;

  // ---- модельное время анимации (сек) --------------------------------
  const [modelTime, setModelTime] = useState(0);
  const svgRef = useRef<SVGSVGElement | null>(null);
  const animationRef = useRef<{ last: number } | null>(null);
  // пройденная доля кольцевого маршрута каждым роботом (позиция
  // робота — накопленный пробег; пауза застёгивается в ТОЙ точке, где
  // робот остановился, без телепорта в общую точку)
  const travelRef = useRef<number[]>([]);

  const completed =
    simulation !== null && simulation.status === "completed";
  const robots = simulation?.robots ?? 0;
  const movingPct = simulation?.movingPct ?? 0;
  const handlingPct = simulation?.handlingPct ?? 0;
  const zones = simulation?.zones ?? [];
  const bottleneck = useMemo(
    () =>
      zones.reduce<SimulationZone | null>(
        (max, z) =>
          max === null || z.robotTimeSharePct > max.robotTimeSharePct
            ? z
            : max,
        null,
      ),
    [zones],
  );

  // ---- цикл анимации (requestAnimationFrame, множитель скорости) ----
  // Расписание робота внутри цикла (модельное время):
  // [0, M) — чистое движение по маршруту;
  // [M, M+a) — подход: сходит с маршрута к точке погрузки A
  // (ближайшая к маршрутной позиции);
  // [M+a, M+a+h) — ПОГРУЗКА: стоит строго в точке A (h =
  // sim_pick_drop_sec / 2);
  // [M+a+h, +t) — переезд A → B (точка разгрузки — следующая
  // в очереди операций);
  // [M+a+h+t, +h) — РАЗГРУЗКА: стоит строго в точке B;
  // далее возврат на маршрут и простой (остаток цикла).
  // Суммарное время в состоянии «загружается/разгружается» = ровно
  // sim_pick_drop_sec; доля handling в цикле = handlingPct (KPI) —
  // длина цикла выводится из этих двух величин.
  const timeRef = useRef(0);
  const pickDropSec = simulation?.inputs?.pickDropSec ?? 40;
  const windows = useMemo(
    () => computeWindows(movingPct, handlingPct, pickDropSec),
    [movingPct, handlingPct, pickDropSec],
  );
  useEffect(() => {
    if (!completed || paused) {
      animationRef.current = null;
      return;
    }
    const robotsCount = robots;
    // при смене состава — переразброс начальных позиций по кругу
    if (travelRef.current.length !== robotsCount) {
      travelRef.current = Array.from(
        { length: robotsCount },
        (_, i) => robotsCount > 0 ? i / robotsCount : 0,
      );
    }
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      timeRef.current += dt * speed;
      // пробег растёт только в «чистом» движении — во время
      // погрузки/разгрузки/переездов робот стоит/сходит с маршрута,
      // маршрутная позиция замораживается
      for (let i = 0; i < robotsCount; i += 1) {
        const phase = i / robotsCount;
        const t =
          (timeRef.current + phase * windows.cycleSec) % windows.cycleSec;
        if (t < windows.pureMoveEnd) {
          travelRef.current[i] =
            (travelRef.current[i] + (dt * speed) / 60) % 1;
        }
      }
      setModelTime(timeRef.current);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [completed, paused, speed, robots, windows]);

  // ---- предупреждение о превышении 60 с ------------------
  useEffect(() => {
    if (!running) {
      return;
    }
    const timer = setTimeout(() => setElapsedMs(60_000), 60_000);
    return () => clearTimeout(timer);
  }, [running]);

  // ---- запуск имитации ------------------------------------
  const runModel = useCallback(
    async (targetScenarioId: number) => {
      setRunning(true);
      setError(null);
      setSavedMessage(null);
      setElapsedMs(0);
      const startedAt = performance.now();
      try {
        const response = await fetch(
          `/api/projects/${projectId}/scenarios/${targetScenarioId}/simulation/run`,
          { method: "POST" },
        );
        if (!response.ok) {
          let message = `Ошибка запуска имитации (HTTP ${response.status})`;
          try {
            const body = (await response.json()) as {
              message?: string;
              error?: string;
            };
            message = body.message ?? body.error ?? message;
          } catch {
            // fallback уже задан
          }
          setError(message);
          return;
        }
        const result = (await response.json()) as SimulationRun;
        setSimulation(result);
        setExportUrl(result.exportUrl ?? null);
        setModelTime(0);
        timeRef.current = 0;
        travelRef.current = Array.from(
          { length: result.robots ?? 0 },
          (_, i) => i / Math.max(1, result.robots ?? 1),
        );
        setPaused(false);
      } catch {
        setError("Сервер недоступен. Повторите попытку.");
      } finally {
        setElapsedMs(performance.now() - startedAt);
        setRunning(false);
      }
    },
    [projectId],
  );

  // ---- сохранение схемы -----------------------------------
  const saveSchema = useCallback(async () => {
    if (
      !completed ||
      simulation?.id == null ||
      svgRef.current === null
    ) {
      return;
    }
    setSavedMessage(null);
    try {
      const svg = new XMLSerializer().serializeToString(svgRef.current);
      const response = await fetch(
        `/api/projects/${projectId}/simulations/${simulation.id}/export`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ svg }),
        },
      );
      if (!response.ok) {
        let message = `Не удалось сохранить схему (HTTP ${response.status})`;
        try {
          const body = (await response.json()) as {
            message?: string;
            error?: string;
          };
          message = body.message ?? body.error ?? message;
        } catch {
          // fallback уже задан
        }
        setError(message);
        return;
      }
      const result = (await response.json()) as {
        exportUrl: string;
        file: string;
      };
      setExportUrl(result.exportUrl);
      setSavedMessage(
        "Схема сохранена — файл доступен для скачивания (SVG).",
      );
    } catch {
      setError("Сервер недоступен. Повторите попытку.");
    }
  }, [completed, projectId, simulation]);

  // ---- геометрия схемы (координаты зон и маршрута) -------------------
  const ZONE_BOXES: Record<
    string,
    { x: number; y: number; w: number; h: number }
  > = {
    receiving: { x: 30, y: 70, w: 140, h: 240 },
    storage: { x: 210, y: 70, w: 340, h: 240 },
    picking: { x: 210, y: 350, w: 340, h: 90 },
    shipping: { x: 590, y: 70, w: 140, h: 240 },
    charging: { x: 770, y: 70, w: 160, h: 130 },
  };
  // кольцевой маршрут роботов: приёмка → хранение → отбор → отгрузка →
  // зарядный блок → (по верху) → приёмка. Все нитки проведены ВНЕ
  // текстов зон/подписей/бейджей с запасом на корпус робота (±7,5),
  // след движения (±9) и обводку линий: вертикали x=185/385/772,
  // верхняя нитка y=70 (подзаголовок до y≈55, тексты зон от y≈85),
  // горизонталь y=190 (ниже бейджей, y≈144) — bbox-проверка в e2e
  const LOOP: Array<[number, number]> = [
    [185, 190],
    [385, 160],
    [385, 395],
    [660, 190],
    [772, 190],
    [772, 70],
    [185, 70],
  ];

  // Точки операций — те же координаты, что у иконок разметки:
  // полки приёмки, стеллажи хранения, корзины отбора, доки отгрузки.
  // Центр каждой иконки — место остановки робота в фазе
  // «загружается/разгружается» (assumptions.md §22-бис: строго в
  // точке операции, не в случайном месте маршрута).
  const SHELF_POINTS = [0, 1, 2].map((i) => ({
    x: ZONE_BOXES.receiving.x + 18,
    y: ZONE_BOXES.receiving.y + 80 + i * 56,
  }));
  const RACK_POINTS = [0, 1, 2, 3, 4, 5, 6, 7].map((i) => ({
    x: ZONE_BOXES.storage.x + 20 + (i % 4) * 82,
    y: ZONE_BOXES.storage.y + 80 + Math.floor(i / 4) * 92,
  }));
  const BIN_POINTS = [0, 1, 2, 3, 4, 5].map((i) => ({
    x: ZONE_BOXES.picking.x + 44 + i * 52,
    y: ZONE_BOXES.picking.y + 74,
  }));
  const DOCK_POINTS = [0, 1, 2].map((i) => ({
    x: ZONE_BOXES.shipping.x + ZONE_BOXES.shipping.w - 44,
    y: ZONE_BOXES.shipping.y + 80 + i * 56,
  }));
  // центры иконок (размеры: полка 26×22, стеллаж 64×20, корзина
  // 16×12, док 26×22) — робот останавливается ровно здесь
  const OPERATION_POINTS: Array<{ x: number; y: number }> = [
    ...SHELF_POINTS.map((p) => ({ x: p.x + 13, y: p.y + 11 })),
    ...RACK_POINTS.map((p) => ({ x: p.x + 32, y: p.y + 10 })),
    ...BIN_POINTS.map((p) => ({ x: p.x + 8, y: p.y + 6 })),
    ...DOCK_POINTS.map((p) => ({ x: p.x + 13, y: p.y + 11 })),
  ];

  const loopLengths = useMemo(() => {
    const segs: number[] = [];
    let total = 0;
    for (let i = 0; i < LOOP.length; i += 1) {
      const [x1, y1] = LOOP[i];
      const [x2, y2] = LOOP[(i + 1) % LOOP.length];
      const len = Math.hypot(x2 - x1, y2 - y1);
      segs.push(len);
      total += len;
    }
    return { segs, total };
  }, []);

  /** Точка на кольцевом маршруте по доле [0..1). */
  const loopAt = useCallback(
    (fraction: number) => {
      const f = ((fraction % 1) + 1) % 1;
      let dist = f * loopLengths.total;
      for (let i = 0; i < loopLengths.segs.length; i += 1) {
        if (dist <= loopLengths.segs[i]) {
          const [x1, y1] = LOOP[i];
          const [x2, y2] = LOOP[(i + 1) % LOOP.length];
          const t = loopLengths.segs[i] === 0 ? 0 : dist / loopLengths.segs[i];
          return { x: x1 + (x2 - x1) * t, y: y1 + (y2 - y1) * t };
        }
        dist -= loopLengths.segs[i];
      }
      return { x: LOOP[0][0], y: LOOP[0][1] };
    },
    [loopLengths],
  );

  /** Ближайшая к внешней точке точка МАРШРУТА (проекция на сегменты):
 * стык для Г-образного схода к точке операции. */
  const nearestLoopPoint = useCallback(
    (target: { x: number; y: number }) => {
      let best = { fraction: 0, x: LOOP[0][0], y: LOOP[0][1] };
      let bestDist = Number.POSITIVE_INFINITY;
      let base = 0;
      for (let i = 0; i < LOOP.length; i += 1) {
        const [x1, y1] = LOOP[i];
        const [x2, y2] = LOOP[(i + 1) % LOOP.length];
        const dx = x2 - x1;
        const dy = y2 - y1;
        const len2 = dx * dx + dy * dy;
        const u = len2 === 0
          ? 0
          : Math.max(0, Math.min(1,
              ((target.x - x1) * dx + (target.y - y1) * dy) / len2));
        const px = x1 + dx * u;
        const py = y1 + dy * u;
        const dist = (target.x - px) ** 2 + (target.y - py) ** 2;
        if (dist < bestDist) {
          bestDist = dist;
          best = {
            fraction: (base + u * loopLengths.segs[i]) / loopLengths.total,
            x: px,
            y: py,
          };
        }
        base += loopLengths.segs[i];
      }
      return best;
    },
    [loopLengths],
  );

  // ---- состояние каждого робота (движется/погрузка/простой) --------
  // Позиция — из накопленного пробега travelRef; во время погрузки/
  // разгрузки робот стоит строго в точке операции (A — погрузка,
  // B — разгрузка, соседняя точка очереди); подход/переезд/возврат —
  // короткие плавные переходы в статусе «движется» (фазы не
  // пересекаются: расписание окон внутри цикла — см. computeWindows)
  const robotStates = (() => {
    if (robots <= 0) {
      return [];
    }
    if (travelRef.current.length !== robots) {
      travelRef.current = Array.from(
        { length: robots },
        (_, i) => i / robots,
      );
    }
    const list: Array<{
      x: number;
      y: number;
      status: "moving" | "handling" | "idle";
      angle: number;
    }> = [];
    const w = windows;
    for (let i = 0; i < robots; i += 1) {
      const phase = i / robots;
      const t = (modelTime + phase * w.cycleSec) % w.cycleSec;
      const travel = Math.min(travelRef.current[i] ?? phase, 0.999);
      const routePos = loopAt(travel);
      // очередь точек: A — ближайшая к маршрутной позиции; B —
      // следующая ближайшая от A (короткий переезд внутри зоны —
      // путь не пересекает подписи)
      let aIdx = 0;
      let bestDist = Number.POSITIVE_INFINITY;
      OPERATION_POINTS.forEach((point, idx) => {
        const dist = (point.x - routePos.x) ** 2
          + (point.y - routePos.y) ** 2;
        if (dist < bestDist) {
          bestDist = dist;
          aIdx = idx;
        }
      });
      const pointA = OPERATION_POINTS[aIdx];
      let bIdx = 0;
      let bDist = Number.POSITIVE_INFINITY;
      OPERATION_POINTS.forEach((point, idx) => {
        if (idx === aIdx) {
          return;
        }
        const dist = (point.x - pointA.x) ** 2
          + (point.y - pointA.y) ** 2;
        if (dist < bDist) {
          bDist = dist;
          bIdx = idx;
        }
      });
      const pointB = OPERATION_POINTS[bIdx];
      const lerp = (from: number, to: number, u: number) =>
        from + (to - from) * u;
      // «стык» — ближайшая к точке A точка МАРШРУТА: подход/возврат
      // идут по маршруту до стыка, затем коротким Г-образным сходом
      // (вертикаль по x стыка → горизонталь на высоте A) — путь не
      // пересекает подписи зон и бейджи узкого места
      const hitch = nearestLoopPoint(pointA);
      const corner = { x: hitch.x, y: pointA.y };
      // Г-путь через corner: from→corner→to (равномерно по длине)
      const lPath = (
        from: { x: number; y: number },
        to: { x: number; y: number },
        v: number,
      ) => {
        const len1 = Math.abs(from.y - corner.y);
        const len2 = Math.abs(corner.x - to.x);
        const total = len1 + len2;
        if (total < 0.001) {
          return { x: to.x, y: to.y };
        }
        const passed = v * total;
        if (passed <= len1) {
          return { x: from.x, y: lerp(from.y, corner.y, passed / len1) };
        }
        return {
          x: lerp(corner.x, to.x, (passed - len1) / len2),
          y: corner.y,
        };
      };
      // дуга по маршруту от доли from к доли to (кратчайшее направление)
      const arc = (from: number, to: number, v: number) => {
        const fwd = (to - from + 1) % 1;
        const bwd = (from - to + 1) % 1;
        const shift = bwd < fwd ? -bwd * v : fwd * v;
        return loopAt(from + shift);
      };
      let x = routePos.x;
      let y = routePos.y;
      let status: "moving" | "handling" | "idle" = "moving";
      if (w.hasHandling) {
        if (t < w.pureMoveEnd) {
          // чистое движение по маршруту
          status = "moving";
        } else if (t < w.approachEnd) {
          // подход: 70% — доезд ПО МАРШРУТУ до стыка, 30% —
          // Г-образный сход к точке погрузки
          const u = (t - w.pureMoveEnd) / w.approachSec;
          if (u < 0.7) {
            const pos = arc(travel, hitch.fraction, u / 0.7);
            x = pos.x;
            y = pos.y;
          } else {
            const pos = lPath(hitch, pointA, (u - 0.7) / 0.3);
            x = pos.x;
            y = pos.y;
          }
          status = "moving";
        } else if (t < w.loadEnd) {
          // ПОГРУЗКА: строго в точке A, координаты стабильны
          x = pointA.x;
          y = pointA.y;
          status = "handling";
        } else if (t < w.transitEnd) {
          // переезд к точке разгрузки
          const u = (t - w.loadEnd) / w.transitSec;
          x = lerp(pointA.x, pointB.x, u);
          y = lerp(pointA.y, pointB.y, u);
          status = "moving";
        } else if (t < w.unloadEnd) {
          // РАЗГРУЗКА: строго в точке B, координаты стабильны
          x = pointB.x;
          y = pointB.y;
          status = "handling";
        } else if (t < w.returnEnd) {
          // возврат: 30% — Г-образный сход к стыку, 70% — доезд по
          // маршруту до своей точки пробега
          const u = (t - w.unloadEnd) / w.returnSec;
          if (u < 0.3) {
            const pos = lPath(pointB, hitch, u / 0.3);
            x = pos.x;
            y = pos.y;
          } else {
            const pos = arc(hitch.fraction, travel, (u - 0.3) / 0.7);
            x = pos.x;
            y = pos.y;
          }
          status = "moving";
        } else {
          status = "idle";
        }
      } else if (t >= w.idleStart) {
        status = "idle";
      }
      // угол направления — по вектору мгновенной скорости (позиция
      // чуть вперёд по расписанию), в handling — к точке разгрузки
      let ahead = { x, y };
      if (status === "handling") {
        ahead = pointB;
      } else {
        const dt2 = Math.min(0.35, w.cycleSec * 0.004);
        const t2 = t + dt2;
        if (w.hasHandling && t2 < w.approachEnd
            && t2 >= w.pureMoveEnd) {
          const u = (t2 - w.pureMoveEnd) / w.approachSec;
          const pos = u < 0.7
            ? arc(travel, hitch.fraction, u / 0.7)
            : lPath(hitch, pointA, (u - 0.7) / 0.3);
          ahead = pos;
        } else if (w.hasHandling && t2 >= w.transitEnd
            && t2 < w.unloadEnd) {
          ahead = pointB;
        } else {
          ahead = loopAt(travel + 0.01);
        }
      }
      const angle =
        (Math.atan2(ahead.y - y, ahead.x - x) * 180) / Math.PI;
      list.push({ x, y, status, angle });
    }
    return list;
  })();

  const STATUS_COLORS: Record<string, string> = {
    moving: SCHEMA.moving,
    handling: SCHEMA.handling,
    idle: SCHEMA.idle,
  };
  const STATUS_LABELS: Record<string, string> = {
    moving: "Движется",
    handling: "Загружается / разгружается",
    idle: "Простаивает (зарядка/ожидание)",
  };

  const zoneFill = (code: string): string => {
    const load =
      zones.find((z) => z.code === code)?.robotTimeSharePct ?? 0;
    if (bottleneck?.code === code && load > 0) {
      return SCHEMA.bottleneckFill;
    }
    if (load > 20) {
      return SCHEMA.loadedFill;
    }
    return SCHEMA.baseFill;
  };

  /** Прозрачность заливки зоны: семантика нагрузки видна сквозь сетку. */
  const zoneOpacity = (code: string): number => {
    const load =
      zones.find((z) => z.code === code)?.robotTimeSharePct ?? 0;
    if (bottleneck?.code === code && load > 0) {
      return SCHEMA.zoneOpacityBottleneck;
    }
    if (load > 20) {
      return SCHEMA.zoneOpacityLoaded;
    }
    return SCHEMA.zoneOpacityBase;
  };

  const activeScenario =
    scenarios.find((s) => s.id === scenarioId) ?? null;

  return (
    <div className="flex flex-col gap-6">
      {/* ---------------- Управление ---------------------- */}
      <div className="flex flex-wrap items-center gap-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <label className="text-sm text-slate-600 dark:text-slate-300" htmlFor="sim-scenario">
          Сценарий
        </label>
        <select
          id="sim-scenario"
          value={scenarioId ?? ""}
          onChange={(event) => {
            const value = Number(event.target.value);
            setScenarioId(value);
            setSimulation(null);
            setExportUrl(null);
            setSavedMessage(null);
            setError(null);
            setModelTime(0);
            void fetch(
              `/api/projects/${projectId}/scenarios/${value}/simulation`,
            )
              .then(async (response) => {
                if (response.ok) {
                  setSimulation(
                    (await response.json()) as SimulationRun,
                  );
                }
              })
              .catch(() => undefined);
          }}
          className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100"
        >
          {scenarios.length === 0 ? (
            <option value="">Нет роботизированных сценариев</option>
          ) : (
            scenarios.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
                {s.solutionCount === 0 ? " (пустой)" : ""}
              </option>
            ))
          )}
        </select>

        <button
          type="button"
          onClick={() =>
            scenarioId !== null ? void runModel(scenarioId) : undefined
          }
          disabled={running || scenarioId === null}
          className="rounded-lg bg-sky-700 px-4 py-2 text-sm font-medium text-white transition hover:bg-sky-800 disabled:cursor-wait disabled:opacity-70"
        >
          {running ? "Выполняется…" : "Запустить"}
        </button>
        <button
          type="button"
          onClick={() => setPaused((p) => !p)}
          disabled={!completed}
          className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          {paused ? "Продолжить" : "Остановить"}
        </button>
        <button
          type="button"
          onClick={() => {
            setModelTime(0);
            timeRef.current = 0;
            travelRef.current = Array.from(
              { length: robots },
              (_, i) => i / robots,
            );
            if (scenarioId !== null) {
              void runModel(scenarioId);
            }
          }}
          disabled={running || scenarioId === null}
          className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          Перезапустить
        </button>

        <div className="ml-auto flex items-center gap-1" role="group"
             aria-label="Скорость воспроизведения">
          <span className="mr-1 text-xs text-slate-500 dark:text-slate-400">Скорость</span>
          {[0.5, 1, 2, 5].map((value) => (
            <button
              key={value}
              type="button"
              onClick={() => setSpeed(value)}
              aria-pressed={speed === value}
              className={`rounded-md px-2.5 py-1.5 text-xs font-medium transition ${
                speed === value
                  ? "bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900"
                  : "bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700"
              }`}
            >
              {value}×
            </button>
          ))}
        </div>
      </div>

      {/* ---------------- Статус выполнения --------------- */}
      {running ? (
        <p
          role="status"
          className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300"
        >
          <span
            aria-hidden
            className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-slate-300 border-t-sky-700 dark:border-slate-600 dark:border-t-sky-400"
          />
          Расчёт модели… (до 60 секунд)
        </p>
      ) : null}
      {elapsedMs >= 60_000 ? (
        <p
          role="alert"
          className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-950/40 dark:text-amber-300"
        >
          Модель выполняется дольше 60 секунд — проверьте нагрузку сервера
          или уменьшите состав сценария.
        </p>
      ) : null}
      {simulation && simulation.status === "failed" ? (
        <p
          role="alert"
          className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          Имитация не выполнена: {simulation.error ?? "неизвестная ошибка"}
        </p>
      ) : null}
      {error ? (
        <p
          role="alert"
          className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-800 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
          {error}
        </p>
      ) : null}

      {scenarios.length === 0 || scenarioId === null ? (
        <p className="rounded-xl border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">
          Нет роботизированных сценариев — запустите подбор решений,
          чтобы наполнить покупку или RaaS.
        </p>
      ) : !completed ? (
        <p className="rounded-xl border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300">
          {simulation === null
            ? "Нажмите «Запустить» — модель рассчитает KPI по составу сценария и параметрам склада."
            : "Последний запуск не завершён успешно — перезапустите модель."}
        </p>
      ) : (
        <>
          {/* ---------------- 2D-схема ------------------- */}
          <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <svg
              ref={svgRef}
              viewBox="0 0 960 480"
              className="mx-auto h-auto w-full min-w-[720px]"
              role="img"
              aria-label="2D-схема склада: зоны, маршруты, роботы"
              xmlns="http://www.w3.org/2000/svg"
              data-testid="warehouse-schema"
              data-theme={resolvedTheme === "dark" ? "dark" : "light"}
              data-model-time={modelTime.toFixed(2)}
              data-pick-drop-sec={String(pickDropSec)}
              data-cycle-sec={String(Math.round(windows.cycleSec))}
            >
              <defs>
                <pattern id="schema-grid" width="20" height="20" patternUnits="userSpaceOnUse">
                  <path d="M 20 0 L 0 0 0 20" fill="none" stroke={SCHEMA.grid} strokeWidth="0.6" />
                </pattern>
                <marker id="flow-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill={SCHEMA.arrow} />
                </marker>
                <marker id="charge-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M 0 0 L 10 5 L 0 10 z" fill={SCHEMA.arrowGreen} />
                </marker>
                <filter id="schema-shadow" x="-40%" y="-40%" width="180%" height="180%">
                  <feDropShadow dx="0" dy="1.5" stdDeviation="2" floodColor={SCHEMA.shadowColor} floodOpacity={SCHEMA.shadowOpacity} />
                </filter>
              </defs>

              {/* холст с сеткой (диспетчерский стиль) */}
              <rect x="10" y="10" width="940" height="460" rx="14" fill={SCHEMA.canvasFill} stroke={SCHEMA.canvasStroke} strokeWidth="1.2" />
              <rect x="13" y="13" width="934" height="454" rx="12" fill="url(#schema-grid)" />

              {/* заголовок и подзаголовок */}
              <text x="30" y="36" fontSize="15" fontWeight="700" fill={SCHEMA.title}>
                Склад — 2D-схема
              </text>
              <text x="30" y="52" fontSize="10.5" fill={SCHEMA.subtitle}>
                Сценарий «{simulation.scenarioName ?? activeScenario?.name}» · Роботов:{" "}
                {simulation.robots ?? 0} · Зарядных станций:{" "}
                {simulation.chargingStations ?? 0}
              </text>

              {/* зоны: полупрозрачная заливка, плашка имени, поток/загрузка */}
              {Object.entries(ZONE_BOXES).map(([code, box]) => {
                const zone = zones.find((z) => z.code === code);
                const isBottleneck = bottleneck?.code === code;
                const zoneName =
                  code === "charging"
                    ? "Зарядные станции"
                    : ZONE_LABELS[code] ?? code;
                const nameWidth = Math.min(
                  box.w - 16, zoneName.length * 7.4 + 20);
                const badgeY = box.y + (zone ? 56 : 40);
                // полный формат — только для широких зон: в узких
                // (приёмка/отгрузка) длинный бейдж налезал бы на
                // вертикаль маршрута x=185/подписи
                const badgeText = box.w >= 200
                  ? `Узкое место: ${bottleneck?.robotTimeSharePct}%`
                  : `Узкое: ${bottleneck?.robotTimeSharePct}%`;
                const badgeOneLine = box.w - 20 >= badgeText.length * 6.1 + 32;
                return (
                  <g key={code} data-zone={code}>
                    {isBottleneck ? (
                      <rect
                        x={box.x - 4} y={box.y - 4}
                        width={box.w + 8} height={box.h + 8} rx="12"
                        fill="none" stroke={SCHEMA.bottleneckStroke}
                        strokeWidth="1.6" strokeDasharray="7 4"
                      />
                    ) : null}
                    <rect
                      x={box.x} y={box.y} width={box.w} height={box.h} rx="10"
                      fill={code === "charging"
                        ? SCHEMA.chargingFill : zoneFill(code)}
                      fillOpacity={code === "charging"
                        ? 0.16 : zoneOpacity(code)}
                      stroke={isBottleneck
                        ? SCHEMA.bottleneckStroke : SCHEMA.zoneStroke}
                      strokeWidth={isBottleneck ? 2 : 1.2}
                    />
                    {/* имя зоны — на плашке (не поверх содержимого) */}
                    <rect
                      x={box.x + 10} y={box.y + 10}
                      width={nameWidth} height={20} rx="6"
                      fill={SCHEMA.plateFill} stroke={SCHEMA.plateStroke}
                    />
                    <text
                      x={box.x + 20} y={box.y + 24}
                      fontSize="12" fontWeight="700" fill={SCHEMA.zoneTitle}
                    >
                      {zoneName}
                    </text>
                    {code !== "charging" && zone ? (
                      <>
                        <text
                          x={box.x + 20} y={box.y + 44}
                          fontSize="10" fill={SCHEMA.zoneSub}
                        >
                          {zone.flowPerHour} оп/ч · загрузка{" "}
                          {zone.robotTimeSharePct}%
                        </text>
                      </>
                    ) : null}
                    {isBottleneck ? (
                      <g>
                        <rect
                          x={box.x + 10} y={badgeY}
                          width={badgeOneLine
                            ? badgeText.length * 6.1 + 32 : box.w - 20}
                          height={18} rx="6"
                          fill={SCHEMA.badgeFill}
                          stroke={SCHEMA.badgeStroke}
                        />
                        <g transform={`translate(${box.x + 18}, ${badgeY + 3})`}>
                          <path
                            d="M 7 0 L 14 12 L 0 12 Z"
                            fill={SCHEMA.bottleneckText}
                          />
                          <rect x="6.1" y="4" width="1.8" height="4.6" rx="0.9" fill={SCHEMA.badgeFill} />
                          <rect x="6.1" y="9.4" width="1.8" height="1.8" rx="0.9" fill={SCHEMA.badgeFill} />
                        </g>
                        <text
                          x={box.x + 38} y={badgeY + 12.5}
                          fontSize="10" fontWeight="700"
                          fill={SCHEMA.bottleneckText}
                        >
                          {badgeText}
                        </text>
                      </g>
                    ) : null}

                    {/* точки операций — узнаваемые иконки; центр каждой
 иконки (data-x/data-y) — место остановки робота
 в фазе погрузки/разгрузки */}
                    {code === "receiving"
                      ? SHELF_POINTS.map((p, i) => (
                          <g key={i}
                            data-testid="operation-point"
                            data-x={p.x + 13} data-y={p.y + 11}
                            transform={`translate(${p.x}, ${p.y})`}>
                            <rect width="26" height="22" rx="3" fill={SCHEMA.shelfFill} fillOpacity="0.85" stroke={SCHEMA.shelfStroke} strokeWidth="1.2" />
                            <path d="M 2 8 H 24 M 13 8 V 20" fill="none" stroke={SCHEMA.shelfStroke} strokeWidth="1" />
                          </g>
                        ))
                      : null}
                    {code === "storage"
                      ? RACK_POINTS.map((p, i) => (
                          <g key={i}
                            data-testid="operation-point"
                            data-x={p.x + 32} data-y={p.y + 10}
                            transform={`translate(${p.x}, ${p.y})`}>
                            <rect width="64" height="20" rx="3" fill={SCHEMA.rackFill} fillOpacity="0.8" stroke={SCHEMA.rackStroke} strokeWidth="1.1" />
                            <path d="M 4 7 H 60 M 4 14 H 60" fill="none" stroke={SCHEMA.rackStroke} strokeWidth="1" />
                          </g>
                        ))
                      : null}
                    {code === "picking"
                      ? BIN_POINTS.map((p, i) => (
                          <g key={i}
                            data-testid="operation-point"
                            data-x={p.x + 8} data-y={p.y + 6}
                            transform={`translate(${p.x}, ${p.y})`}>
                            <rect width="16" height="12" rx="3" fill={SCHEMA.pickFill} fillOpacity="0.9" stroke={SCHEMA.pickStroke} strokeWidth="1.2" />
                            <path d="M 3 3 Q 8 -3 13 3" fill="none" stroke={SCHEMA.pickStroke} strokeWidth="1.2" />
                          </g>
                        ))
                      : null}
                    {code === "shipping"
                      ? DOCK_POINTS.map((p, i) => (
                          <g key={i}
                            data-testid="operation-point"
                            data-x={p.x + 13} data-y={p.y + 11}
                            transform={`translate(${p.x}, ${p.y})`}>
                            <rect width="26" height="22" rx="3" fill={SCHEMA.shipFill} fillOpacity="0.85" stroke={SCHEMA.shipStroke} strokeWidth="1.2" />
                            <path d="M 2 8 H 24 M 13 8 V 20" fill="none" stroke={SCHEMA.shipStroke} strokeWidth="1" />
                            <path d="M 8 3 L 13 -1 L 18 3" fill="none" stroke={SCHEMA.shipStroke} strokeWidth="1.1" />
                          </g>
                        ))
                      : null}
                    {/* зарядные станции — по числу из модели */}
                    {code === "charging"
                      ? Array.from({
                          length: Math.max(1, simulation.chargingStations ?? 1),
                        }).slice(0, 8).map((_, i) => (
                          <g key={i} transform={`translate(${box.x + 20 + (i % 4) * 38}, ${box.y + 48 + Math.floor(i / 4) * 44})`}>
                            <rect width="26" height="26" rx="6" fill={SCHEMA.stationFill} fillOpacity="0.9" stroke={SCHEMA.stationStroke} strokeWidth="1.2" />
                            <path d="M 14.5 5 L 9 14.5 H 12.5 L 11 21 L 17 11.5 H 13.5 Z" fill={SCHEMA.stationStroke} />
                          </g>
                        ))
                      : null}
                  </g>
                );
              })}

              {/* материальные потоки (статические стрелки) */}
              <line x1="176" y1="152" x2="206" y2="152" stroke={SCHEMA.arrow} strokeWidth="2.2" markerEnd="url(#flow-arrow)" />
              <line x1="380" y1="314" x2="380" y2="346" stroke={SCHEMA.arrow} strokeWidth="2.2" markerEnd="url(#flow-arrow)" />
              <polyline points="552,392 640,392 660,314" fill="none" stroke={SCHEMA.arrow} strokeWidth="2.2" markerEnd="url(#flow-arrow)" />
              {/* подписи паллет — ПОД иконками операций, внутри своих
 зон: не пересекаются с маршрутом, роботами и другими
 подписями (bbox-проверка в e2e) */}
              <g data-testid="pallet-label" data-pallet-zone="receiving">
                <rect x="40" y="286" width="118" height="18" rx="5" fill={SCHEMA.plateFill} fillOpacity="0.92" stroke={SCHEMA.plateStroke} />
                <text x="49" y="298.5" fontSize="9.5" fill={SCHEMA.zoneSub}>Входящие паллеты</text>
              </g>
              <g data-testid="pallet-label" data-pallet-zone="shipping">
                <rect x="600" y="286" width="122" height="18" rx="5" fill={SCHEMA.plateFill} fillOpacity="0.92" stroke={SCHEMA.plateStroke} />
                <text x="609" y="298.5" fontSize="9.5" fill={SCHEMA.zoneSub}>Исходящие паллеты</text>
              </g>
              {/* роботы на подзарядку (пунктир) */}
              <polyline points="736,152 770,136" fill="none" stroke={SCHEMA.arrowGreen} strokeWidth="2.2" strokeDasharray="5 4" markerEnd="url(#charge-arrow)" />

              {/* кольцевой маршрут роботов + направление */}
              <polyline
                points={LOOP.map(([x, y]) => `${x},${y}`).join(" ")}
                fill="none"
                stroke={SCHEMA.route}
                strokeWidth="2.5"
                strokeDasharray="8 6"
                strokeLinecap="round"
              />
              {[0.07, 0.27, 0.47, 0.67, 0.87].map((fraction) => {
                const point = loopAt(fraction);
                const ahead = loopAt(fraction + 0.015);
                const angle =
                  (Math.atan2(ahead.y - point.y, ahead.x - point.x)
                    * 180) / Math.PI;
                return (
                  <path
                    key={fraction}
                    transform={`translate(${point.x}, ${point.y}) rotate(${angle})`}
                    d="M -3 -4.5 L 4 0 L -3 4.5 Z"
                    fill={SCHEMA.route}
                  />
                );
              })}

              {/* роботы: цельные иконки со статусом (цвет + знак);
 data-status/data-x/data-y — для e2e-проверки
 остановок в точках операций */}
              {robotStates.map((robot, i) => {
                const color = STATUS_COLORS[robot.status];
                return (
                  <g
                    key={i}
                    data-testid="schema-robot"
                    data-robot={i}
                    data-status={robot.status}
                    data-x={robot.x.toFixed(1)}
                    data-y={robot.y.toFixed(1)}
                    transform={`translate(${robot.x}, ${robot.y})`}
                  >
                    <g filter="url(#schema-shadow)">
                      {/* ореол — не сливается с маршрутом/зонами */}
                      <rect x="-11.5" y="-7.5" width="23" height="15" rx="5" fill={SCHEMA.robotHalo} />
                      {/* корпус-капсула */}
                      <rect x="-10" y="-6" width="20" height="12" rx="4" fill={color} fillOpacity="0.92" stroke={color} strokeWidth="1.6" />
                      {/* «ветровое стекло» — сторона корпуса */}
                      <rect x="3.5" y="-3.5" width="4.5" height="7" rx="2" fill="#ffffff" fillOpacity="0.55" />
                    </g>
                    {/* указатель направления по маршруту и след
 движения (галочки позади робота ПО ХОДУ — в
 общей rotate-группе: на вертикальных участках
 не вылетают влево и не задевают подписи зон) */}
                    <g transform={`rotate(${robot.angle})`}>
                      <path d="M 12.5 -3.5 L 18 0 L 12.5 3.5 Z" fill={color} stroke={SCHEMA.robotHalo} strokeWidth="0.8" />
                      {robot.status === "moving" ? (
                        <path
                          d="M -6 -3.5 L -9 0 L -6 3.5"
                          fill="none" stroke={color} strokeWidth="1.7"
                          strokeLinecap="round"
                        />
                      ) : null}
                    </g>
                    {robot.status === "handling" ? (
                      <g>
                        <circle r="13" fill="none" stroke={SCHEMA.chargingRing} strokeWidth="1.5" strokeDasharray="3 2" />
                        <rect x="-4.5" y="-15" width="10" height="7.5" rx="1.5" fill={color} fillOpacity="0.4" stroke={color} strokeWidth="1" />
                      </g>
                    ) : null}
                    {robot.status === "idle" ? (
                      <g stroke={color} strokeWidth="1.8" strokeLinecap="round">
                        <line x1="-3.5" y1="-3.5" x2="-3.5" y2="3.5" />
                        <line x1="2.5" y1="-3.5" x2="2.5" y2="3.5" />
                      </g>
                    ) : null}
                  </g>
                );
              })}

              {/* легенда — отдельный блок (цвет = статус, иконка = точка) */}
              <g>
                <rect x="765" y="248" width="170" height="212" rx="9"
                      fill={SCHEMA.plateFill} fillOpacity="0.95" stroke={SCHEMA.plateStroke} />
                <text x="778" y="266" fontSize="10.5" fontWeight="700" fill={SCHEMA.legendTitle}>
                  Легенда
                </text>
                {([
                  ["moving", "Движется"],
                  ["handling", "Загружается"],
                  ["idle", "Простаивает (зарядка)"],
                ] as const).map(([status, label], i) => (
                  <g key={status} transform={`translate(778, ${280 + i * 20})`}>
                    <rect x="0" y="-5" width="13" height="9" rx="3" fill={STATUS_COLORS[status]} fillOpacity="0.92" stroke={STATUS_COLORS[status]} strokeWidth="1.2" />
                    <text x="21" y="3" fontSize="9.5" fill={SCHEMA.legendText}>{label}</text>
                  </g>
                ))}
                <line x1="778" y1="347" x2="922" y2="347" stroke={SCHEMA.plateStroke} strokeWidth="1" />
                <g transform="translate(778, 361)">
                  <rect width="13" height="11" rx="2" fill={SCHEMA.shelfFill} fillOpacity="0.85" stroke={SCHEMA.shelfStroke} strokeWidth="1" />
                  <text x="21" y="8" fontSize="9.5" fill={SCHEMA.legendText}>точка операции</text>
                </g>
                <g transform="translate(778, 381)">
                  <rect width="13" height="13" rx="3" fill={SCHEMA.stationFill} fillOpacity="0.9" stroke={SCHEMA.stationStroke} strokeWidth="1" />
                  <path d="M 7.5 2.5 L 4.5 7.5 H 6.5 L 5.5 10.5 L 8.5 5.8 H 6.8 Z" fill={SCHEMA.stationStroke} />
                  <text x="21" y="9" fontSize="9.5" fill={SCHEMA.legendText}>зарядная станция</text>
                </g>
                <g transform="translate(778, 401)">
                  <rect width="13" height="10" rx="3" fill={SCHEMA.pickFill} fillOpacity="0.9" stroke={SCHEMA.pickStroke} strokeWidth="1" />
                  <text x="21" y="8" fontSize="9.5" fill={SCHEMA.legendText}>точка отбора</text>
                </g>
                <line x1="778" y1="415" x2="922" y2="415" stroke={SCHEMA.plateStroke} strokeWidth="1" />
                <g transform="translate(778, 429)">
                  <line x1="0" y1="0" x2="34" y2="0" stroke={SCHEMA.route} strokeWidth="2" strokeDasharray="6 4" />
                  <path d="M 32 -3.5 L 38 0 L 32 3.5 Z" fill={SCHEMA.route} />
                  <text x="45" y="3.5" fontSize="9.5" fill={SCHEMA.legendText}>маршрут роботов</text>
                </g>
                <g transform="translate(778, 449)">
                  <rect x="0" y="-7" width="38" height="13" rx="3" fill="none" stroke={SCHEMA.bottleneckStroke} strokeWidth="1.2" strokeDasharray="4 3" />
                  <text x="45" y="3.5" fontSize="9.5" fill={SCHEMA.legendText}>узкое место</text>
                </g>
              </g>
            </svg>

            <div className="mt-3 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => void saveSchema()}
                className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200 dark:hover:bg-slate-800"
              >
                Сохранить схему (SVG)
              </button>
              {exportUrl ? (
                <a
                  href={exportUrl}
                  className="text-sm font-medium text-sky-700 hover:underline dark:text-sky-400"
                  download
                >
                  Скачать сохранённую схему →
                </a>
              ) : null}
              {savedMessage ? (
                <span className="text-xs text-emerald-700 dark:text-emerald-400">{savedMessage}</span>
              ) : null}
            </div>
          </div>

          {/* ---------------- KPI-панель ------------------- */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {[
              {
                label: "Заявленная производительность",
                value:
                  simulation.declaredThroughputPerHour !== null
                    ? `${simulation.declaredThroughputPerHour} оп/ч`
                    : "—",
                hint: "P_nominal × роботов (из сценария)",
              },
              {
                label: "Фактическая производительность",
                value:
                  simulation.actualThroughputPerHour !== null
                    ? `${simulation.actualThroughputPerHour} оп/ч`
                    : "—",
                hint: `min(пик ${simulation.peakDemandPerHour}, мощность парка)`,
              },
              {
                label: "Загрузка роботов",
                value:
                  simulation.utilizationPct !== null
                    ? `${simulation.utilizationPct}%`
                    : "—",
                hint: "Доля робот-времени с полезной работой",
              },
              {
                label: "Простои",
                value:
                  simulation.idlePct !== null
                    ? `${simulation.idlePct}%`
                    : "—",
                hint: "Зарядка и ожидание (семантика K_load)",
              },
              {
                label: "Узкое место",
                value: bottleneck ? bottleneck.name : "—",
                hint: bottleneck
                  ? `Загрузка ${bottleneck.robotTimeSharePct}% робот-времени`
                  : "Зоны не загружены",
              },
              {
                label: "Достижимость заявленной",
                value:
                  simulation.achievabilityPct !== null
                    ? `${simulation.achievabilityPct}%`
                    : "—",
                hint: `Фактическая на робота (${simulation.capacityPerRobotPerHour} оп/ч) к P_nominal`,
              },
            ].map((kpi) => (
              <div
                key={kpi.label}
                className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900"
              >
                <p className="text-xs font-medium uppercase tracking-wide text-slate-400 dark:text-slate-500">
                  {kpi.label}
                </p>
                <p className="mt-1 text-xl font-semibold text-slate-900 dark:text-slate-100">
                  {kpi.value}
                </p>
                <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{kpi.hint}</p>
              </div>
            ))}
          </div>

          {/* зоны + циклы */}
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <p className="font-semibold text-slate-800 dark:text-slate-100">
                Загрузка зон (узкие места)
              </p>
              <table className="mt-2 w-full text-sm">
                <thead>
                  <tr className="text-left text-xs text-slate-500 dark:text-slate-400">
                    <th className="py-1">Зона</th>
                    <th className="py-1">Поток, оп/ч</th>
                    <th className="py-1" title="Требуемое робот-время зоны к парку: выше 100% — дефицит мощности">
                      Робот-время, % парка
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {zones.map((zone) => (
                    <tr
                      key={zone.code}
                      className={
                        bottleneck?.code === zone.code
                          ? "font-medium text-rose-700 dark:text-rose-400"
                          : "text-slate-700 dark:text-slate-300"
                      }
                    >
                      <td className="py-1">
                        {zone.name}
                        {bottleneck?.code === zone.code ? " · узкое место" : ""}
                      </td>
                      <td className="py-1">{zone.flowPerHour}</td>
                      <td className="py-1">{zone.robotTimeSharePct}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <p className="font-semibold text-slate-800 dark:text-slate-100">
                Время цикла и маршрут
              </p>
              <dl className="mt-2 space-y-1.5 text-sm text-slate-700 dark:text-slate-300">
                <div className="flex justify-between gap-3">
                  <dt>Среднее время цикла</dt>
                  <dd className="font-medium">
                    {simulation.avgCycleTimeSec} с
                  </dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt>Цикл приёмки (приёмка → хранение)</dt>
                  <dd className="font-medium">
                    {simulation.inboundCycleTimeSec} с
                  </dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt>Цикл отгрузки (хранение → отбор → отгрузка)</dt>
                  <dd className="font-medium">
                    {simulation.outboundCycleTimeSec} с
                  </dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt>Скорость парка (ТТХ, средневзвешенная)</dt>
                  <dd className="font-medium">
                    {simulation.fleetSpeedMs ?? simulation.inputs?.speedMs} м/с
                  </dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt>Суточный пробег робота</dt>
                  <dd className="font-medium">
                    {simulation.dailyTravelKmPerRobot} км
                  </dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt>Зарядные станции</dt>
                  <dd className="font-medium">
                    {simulation.chargingStations} ед.
                  </dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt>Расчёт выполнен за</dt>
                  <dd className="font-medium">
                    {simulation.durationMs} мс (модель{" "}
                    {simulation.version})
                  </dd>
                </div>
              </dl>
            </div>
          </div>

          {/* предупреждения имитации и сверка с расчётом экономики */}
          <div className="rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <p className="font-semibold text-slate-800 dark:text-slate-100">
              Предупреждения имитации и сверка с расчётом экономики
            </p>
            <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
              Роботов в имитации: {simulation.robots} ед.
              {simulation.effectivePerRobotPerHour !== null
                ? ` · заложенная в расчёт экономики производительность робота
                   (P_effective): ${simulation.effectivePerRobotPerHour} оп/ч
                   против ${simulation.capacityPerRobotPerHour} оп/ч в модели`
                : ""}
              {simulation.composition
                ? ` · состав: ${simulation.composition
                    .map((line) => `${line.name} × ${line.quantity}`)
                    .join(", ")}`
                : ""}
              .
            </p>
            {simulation.warnings && simulation.warnings.length > 0 ? (
              <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-amber-800 dark:text-amber-300">
                {simulation.warnings.map((warning, i) => (
                  <li key={i}>{warning}</li>
                ))}
              </ul>
            ) : (
              <p className="mt-2 text-xs text-emerald-700 dark:text-emerald-400">
                Предупреждений нет — визуализация подтверждает расчёт.
              </p>
            )}
          </div>
        </>
      )}

      {/* последний статус, если было что-то запущено */}
      {simulation ? (
        <p className="text-xs text-slate-500 dark:text-slate-400">
          Последний запуск: #{simulation.id} · статус{" "}
          {SIM_STATUS_LABELS[simulation.status] ?? simulation.status}
          {simulation.finishedAt
            ? ` · ${new Date(simulation.finishedAt).toLocaleString("ru-RU")}`
            : ""}
        </p>
      ) : null}
    </div>
  );
}
