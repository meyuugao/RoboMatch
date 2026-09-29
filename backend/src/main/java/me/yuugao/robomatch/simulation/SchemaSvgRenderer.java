package me.yuugao.robomatch.simulation;

import java.util.List;
import java.util.Map;

/**
 * Серверный рендер 2D-схемы склада в SVG.
 *
 * <p>ЗЕРКАЛО клиентской схемы (SimulationView.tsx): та же геометрия
 * (зоны, кольцевой маршрут, точки операций, легенда), те же палитры
 * light/dark, то же правило окраски зон по нагрузке. Нужен, когда
 * отчёт формируется без сохранённой клиентом схемы: PDF всегда
 * содержит схему - если пользователь не открывал страницу имитации,
 * схема строится здесь из kpi_json последнего результата и сохраняется
 * в хранилище как обычный файл data/simulations/{projectId}/
 * {simulationId}.svg.
 *
 * <p>Разметка - декларативная статика (pattern/marker/feDropShadow
 * разрешены санитизацией хранилища; Batik растеризует для PDF).
 * Позиции роботов - начальные фазы i/N по кольцу; статусы - из долей
 * KPI (movingPct/handlingPct), как первый кадр клиентской анимации.
 */
public final class SchemaSvgRenderer {

    /**
 * Геометрия зон: код → {x, y, w, h} (как в SimulationView).
 */
    private static final Map<String, double[]> ZONE_BOXES = Map.of(
            "receiving", new double[]{30, 70, 140, 240},
            "storage", new double[]{210, 70, 340, 240},
            "picking", new double[]{210, 350, 340, 90},
            "shipping", new double[]{590, 70, 140, 240},
            "charging", new double[]{770, 70, 160, 130});
    private static final List<String> ZONE_ORDER = List.of(
            "receiving", "storage", "picking", "shipping", "charging");
    /**
 * Центры точек операций (полки/стеллажи/корзины/доки) -
 * места остановки робота при погрузке/разгрузке, как в
 * SimulationView (assumptions.md §22-бис).
 */
    private static final double[][] OPERATION_POINTS = {
            // приёмка: 3 полки 26×22
            {61, 161}, {61, 217}, {61, 273},
            // хранение: 8 стеллажей 64×20
            {262, 160}, {344, 160}, {426, 160}, {508, 160},
            {262, 252}, {344, 252}, {426, 252}, {508, 252},
            // отбор: 6 корзин 16×12
            {262, 430}, {314, 430}, {366, 430}, {418, 430},
            {470, 430}, {522, 430},
            // отгрузка: 3 дока 26×22
            {699, 161}, {699, 217}, {699, 273}};
    /**
 * Кольцевой маршрут роботов (как в SimulationView).
 */
    private static final double[][] LOOP = {
            {185, 190}, {385, 160}, {385, 395}, {660, 190},
            {772, 190}, {772, 70}, {185, 70}};
    private static final Map<String, String> ZONE_LABELS = Map.of(
            "receiving", "Приёмка",
            "storage", "Хранение",
            "picking", "Отбор (комплектация)",
            "shipping", "Отгрузка");
    /**
 * Палитра светлой темы (зеркало SCHEMA_LIGHT клиента).
 */
    private static final Map<String, String> LIGHT = Map.ofEntries(
            Map.entry("canvasFill", "#f8fafc"),
            Map.entry("canvasStroke", "#cbd5e1"),
            Map.entry("title", "#0f172a"),
            Map.entry("subtitle", "#64748b"),
            Map.entry("grid", "#e2e8f0"),
            Map.entry("zoneTitle", "#334155"),
            Map.entry("zoneSub", "#64748b"),
            Map.entry("zoneStroke", "#94a3b8"),
            Map.entry("bottleneckStroke", "#dc2626"),
            Map.entry("bottleneckText", "#dc2626"),
            Map.entry("badgeFill", "#fef2f2"),
            Map.entry("badgeStroke", "#fecaca"),
            Map.entry("plateFill", "#ffffff"),
            Map.entry("plateStroke", "#e2e8f0"),
            Map.entry("shelfFill", "#bfdbfe"),
            Map.entry("shelfStroke", "#60a5fa"),
            Map.entry("rackFill", "#e2e8f0"),
            Map.entry("rackStroke", "#94a3b8"),
            Map.entry("pickFill", "#fde68a"),
            Map.entry("pickStroke", "#d97706"),
            Map.entry("shipFill", "#bbf7d0"),
            Map.entry("shipStroke", "#4ade80"),
            Map.entry("stationFill", "#a7f3d0"),
            Map.entry("stationStroke", "#10b981"),
            Map.entry("arrow", "#64748b"),
            Map.entry("arrowGreen", "#10b981"),
            Map.entry("route", "#94a3b8"),
            Map.entry("moving", "#2563eb"),
            Map.entry("handling", "#d97706"),
            Map.entry("idle", "#94a3b8"),
            Map.entry("chargingRing", "#d97706"),
            Map.entry("legendText", "#475569"),
            Map.entry("legendTitle", "#334155"),
            Map.entry("shadowColor", "#0f172a"),
            Map.entry("robotHalo", "#f8fafc"));
    /**
 * Палитра тёмной темы (зеркало SCHEMA_DARK клиента).
 */
    private static final Map<String, String> DARK = Map.ofEntries(
            Map.entry("canvasFill", "#0f172a"),
            Map.entry("canvasStroke", "#334155"),
            Map.entry("title", "#f1f5f9"),
            Map.entry("subtitle", "#94a3b8"),
            Map.entry("grid", "#1e293b"),
            Map.entry("zoneTitle", "#cbd5e1"),
            Map.entry("zoneSub", "#94a3b8"),
            Map.entry("zoneStroke", "#475569"),
            Map.entry("bottleneckStroke", "#f87171"),
            Map.entry("bottleneckText", "#f87171"),
            Map.entry("badgeFill", "#450a0a"),
            Map.entry("badgeStroke", "#7f1d1d"),
            Map.entry("plateFill", "#1e293b"),
            Map.entry("plateStroke", "#334155"),
            Map.entry("shelfFill", "#1e3a8a"),
            Map.entry("shelfStroke", "#3b82f6"),
            Map.entry("rackFill", "#334155"),
            Map.entry("rackStroke", "#64748b"),
            Map.entry("pickFill", "#451a03"),
            Map.entry("pickStroke", "#f59e0b"),
            Map.entry("shipFill", "#14532d"),
            Map.entry("shipStroke", "#4ade80"),
            Map.entry("stationFill", "#064e3b"),
            Map.entry("stationStroke", "#34d399"),
            Map.entry("arrow", "#94a3b8"),
            Map.entry("arrowGreen", "#34d399"),
            Map.entry("route", "#475569"),
            Map.entry("moving", "#3b82f6"),
            Map.entry("handling", "#f59e0b"),
            Map.entry("idle", "#64748b"),
            Map.entry("chargingRing", "#f59e0b"),
            Map.entry("legendText", "#94a3b8"),
            Map.entry("legendTitle", "#cbd5e1"),
            Map.entry("shadowColor", "#000000"),
            Map.entry("robotHalo", "#0f172a"));

    private SchemaSvgRenderer() {
    }

    /**
 * Собрать SVG-схему по данным результата имитации.
 *
 * @param kpi разобранный kpi_json (зоны, robots, movingPct,
 * handlingPct, chargingStations, scenarioName)
 * @param dark тема: true - тёмная, false - светлая
 * @return строка SVG (viewBox 0 0 960 480)
 */
    public static String render(Map<String, Object> kpi, boolean dark) {
        Map<String, String> c = dark ? DARK : LIGHT;
        List<Map<String, Object>> zones = zonesOf(kpi);
        String bottleneck = bottleneckOf(zones);
        int robots = intOf(kpi.get("robots"));
        double moveShare = doubleOf(kpi.get("movingPct")) / 100.0;
        double handleShare = doubleOf(kpi.get("handlingPct")) / 100.0;
        int stations = Math.max(1, intOf(kpi.get("chargingStations")));

        StringBuilder svg = new StringBuilder(16 * 1024);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" "
                + "data-theme=\"" + (dark ? "dark" : "light") + "\" "
                + "viewBox=\"0 0 960 480\" width=\"960\" height=\"480\">");
        defs(svg, c);
        canvas(svg, c, kpi, stations);
        for (String code : ZONE_ORDER) {
            zone(svg, c, code, zoneByCode(zones, code), bottleneck,
                    stations);
        }
        flows(svg, c);
        route(svg, c);
        robots(svg, c, robots, moveShare, handleShare);
        legend(svg, c);
        svg.append("</svg>");
        return svg.toString();
    }

    // ==================================================================
    // defs / холст
    // ==================================================================

    private static void defs(StringBuilder svg, Map<String, String> c) {
        svg.append("<defs>")
                .append("<pattern id=\"schema-grid\" width=\"20\" "
                        + "height=\"20\" patternUnits=\"userSpaceOnUse\">")
                .append("<path d=\"M 20 0 L 0 0 0 20\" fill=\"none\" "
                        + "stroke=\"").append(c.get("grid"))
                .append("\" stroke-width=\"0.6\"/></pattern>")
                .append("<marker id=\"flow-arrow\" viewBox=\"0 0 10 10\" "
                        + "refX=\"8\" refY=\"5\" markerWidth=\"7\" "
                        + "markerHeight=\"7\" "
                        + "orient=\"auto-start-reverse\">")
                .append("<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"")
                .append(c.get("arrow")).append("\"/></marker>")
                .append("<marker id=\"charge-arrow\" viewBox=\"0 0 10 10\" "
                        + "refX=\"8\" refY=\"5\" markerWidth=\"7\" "
                        + "markerHeight=\"7\" "
                        + "orient=\"auto-start-reverse\">")
                .append("<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"")
                .append(c.get("arrowGreen")).append("\"/></marker>")
                .append("<filter id=\"schema-shadow\" x=\"-40%\" "
                        + "y=\"-40%\" width=\"180%\" height=\"180%\">")
                .append("<feDropShadow dx=\"0\" dy=\"1.5\" "
                        + "stdDeviation=\"2\" flood-color=\"")
                .append(c.get("shadowColor"))
                .append("\" flood-opacity=\"")
                .append(dark(c) ? "0.45" : "0.2").append("\"/>")
                .append("</filter></defs>");
    }

    private static void canvas(StringBuilder svg, Map<String, String> c,
                               Map<String, Object> kpi, int stations) {
        svg.append("<rect x=\"10\" y=\"10\" width=\"940\" height=\"460\" "
                        + "rx=\"14\" fill=\"").append(c.get("canvasFill"))
                .append("\" stroke=\"").append(c.get("canvasStroke"))
                .append("\" stroke-width=\"1.2\"/>")
                .append("<rect x=\"13\" y=\"13\" width=\"934\" "
                        + "height=\"454\" rx=\"12\" "
                        + "fill=\"url(#schema-grid)\"/>")
                .append("<text x=\"30\" y=\"36\" font-size=\"15\" "
                        + "font-weight=\"700\" fill=\"")
                .append(c.get("title"))
                .append("\">Склад - 2D-схема</text>")
                .append("<text x=\"30\" y=\"52\" font-size=\"10.5\" "
                        + "fill=\"").append(c.get("subtitle"))
                .append("\">Сценарий «").append(escape(textOf(
                        kpi.get("scenarioName"))))
                .append("» · Роботов: ").append(intOf(kpi.get("robots")))
                .append(" · Зарядных станций: ").append(stations)
                .append("</text>");
    }

    // ==================================================================
    // зоны
    // ==================================================================

    private static void zone(StringBuilder svg, Map<String, String> c,
                             String code, Map<String, Object> zone,
                             String bottleneck, int stations) {
        double[] box = ZONE_BOXES.get(code);
        boolean charging = "charging".equals(code);
        boolean isBottleneck = code.equals(bottleneck) && zone != null;
        double load = zone == null ? 0 : doubleOf(
                zone.get("robotTimeSharePct"));
        String zoneName = charging ? "Зарядные станции"
                : ZONE_LABELS.getOrDefault(code, code);
        double nameWidth = Math.min(box[2] - 16,
                zoneName.length() * 7.4 + 20);

        if (isBottleneck) {
            svg.append("<rect x=\"").append(box[0] - 4).append("\" y=\"")
                    .append(box[1] - 4).append("\" width=\"")
                    .append(box[2] + 8).append("\" height=\"")
                    .append(box[3] + 8)
                    .append("\" rx=\"12\" fill=\"none\" stroke=\"")
                    .append(c.get("bottleneckStroke"))
                    .append("\" stroke-width=\"1.6\" "
                            + "stroke-dasharray=\"7 4\"/>");
        }
        String fill = charging ? c.get("stationFill")
                : isBottleneck ? "#ef4444"
                  : load > 20 ? "#f59e0b" : c.get("zoneStroke");
        double opacity = charging ? 0.16
                : isBottleneck ? 0.7
                  : load > 20 ? 0.6
                    : dark(c) ? 0.5 : 0.45;
        svg.append("<rect x=\"").append(box[0]).append("\" y=\"")
                .append(box[1]).append("\" width=\"").append(box[2])
                .append("\" height=\"").append(box[3])
                .append("\" rx=\"10\" fill=\"").append(fill)
                .append("\" fill-opacity=\"").append(opacity)
                .append("\" stroke=\"").append(isBottleneck
                        ? c.get("bottleneckStroke") : c.get("zoneStroke"))
                .append("\" stroke-width=\"").append(isBottleneck
                        ? 2 : 1.2).append("\"/>");

        // плашка имени + текст
        svg.append("<rect x=\"").append(box[0] + 10).append("\" y=\"")
                .append(box[1] + 10).append("\" width=\"")
                .append(nameWidth).append("\" height=\"20\" rx=\"6\" "
                        + "fill=\"")
                .append(c.get("plateFill")).append("\" stroke=\"")
                .append(c.get("plateStroke")).append("\"/>")
                .append("<text x=\"").append(box[0] + 20).append("\" y=\"")
                .append(box[1] + 24).append("\" font-size=\"12\" "
                        + "font-weight=\"700\" fill=\"")
                .append(c.get("zoneTitle")).append("\">")
                .append(escape(zoneName)).append("</text>");

        if (!charging && zone != null) {
            // одна строка: поток и загрузка (бейдж узкого места ниже
            // не пересекается с текстом - bbox-проверка e2e)
            svg.append("<text x=\"").append(box[0] + 20).append("\" y=\"")
                    .append(box[1] + 44).append("\" font-size=\"10\" "
                            + "fill=\"").append(c.get("zoneSub"))
                    .append("\">")
                    .append(intOf(zone.get("flowPerHour")))
                    .append(" п/ч · загрузка ")
                    .append(fmtPct(load)).append("%</text>");
        }

        if (isBottleneck) {
            String badgeText = box[2] >= 200
                    ? "Узкое место: " + fmtPct(load) + "%"
                    : "Узкое: " + fmtPct(load) + "%";
            double badgeY = box[1] + (zone == null ? 40 : 56);
            boolean oneLine = box[2] - 20
                    >= badgeText.length() * 6.1 + 32;
            double badgeW = oneLine
                    ? badgeText.length() * 6.1 + 32 : box[2] - 20;
            svg.append("<rect x=\"").append(box[0] + 10).append("\" y=\"")
                    .append(badgeY).append("\" width=\"").append(badgeW)
                    .append("\" height=\"18\" rx=\"6\" fill=\"")
                    .append(c.get("badgeFill")).append("\" stroke=\"")
                    .append(c.get("badgeStroke")).append("\"/>")
                    .append("<g transform=\"translate(")
                    .append(box[0] + 18).append(", ").append(badgeY + 3)
                    .append(")\"><path d=\"M 7 0 L 14 12 L 0 12 Z\" "
                            + "fill=\"")
                    .append(c.get("bottleneckText"))
                    .append("\"/><rect x=\"6.1\" y=\"4\" width=\"1.8\" "
                            + "height=\"4.6\" rx=\"0.9\" fill=\"")
                    .append(c.get("badgeFill"))
                    .append("\"/><rect x=\"6.1\" y=\"9.4\" width=\"1.8\" "
                            + "height=\"1.8\" rx=\"0.9\" fill=\"")
                    .append(c.get("badgeFill"))
                    .append("\"/></g>")
                    .append("<text x=\"").append(box[0] + 38)
                    .append("\" y=\"").append(badgeY + 12.5)
                    .append("\" font-size=\"10\" font-weight=\"700\" "
                            + "fill=\"")
                    .append(c.get("bottleneckText")).append("\">")
                    .append(escape(badgeText)).append("</text>");
        }

        // точки операций - узнаваемые иконки (как у клиента)
        switch (code) {
            case "receiving" -> {
                for (int i = 0; i < 3; i += 1) {
                    svg.append("<g transform=\"translate(")
                            .append(box[0] + 18).append(", ")
                            .append(box[1] + 80 + i * 56).append(")\">")
                            .append(shelf(c)).append("</g>");
                }
            }
            case "storage" -> {
                for (int i = 0; i < 8; i += 1) {
                    svg.append("<g transform=\"translate(")
                            .append(box[0] + 20 + (i % 4) * 82)
                            .append(", ")
                            .append(box[1] + 80 + (i / 4) * 92)
                            .append(")\">").append(rack(c))
                            .append("</g>");
                }
            }
            case "picking" -> {
                for (int i = 0; i < 6; i += 1) {
                    svg.append("<g transform=\"translate(")
                            .append(box[0] + 44 + i * 52).append(", ")
                            .append(box[1] + 74).append(")\">")
                            .append(basket(c)).append("</g>");
                }
            }
            case "shipping" -> {
                for (int i = 0; i < 3; i += 1) {
                    svg.append("<g transform=\"translate(")
                            .append(box[0] + box[2] - 44).append(", ")
                            .append(box[1] + 80 + i * 56).append(")\">")
                            .append(shipBox(c)).append("</g>");
                }
            }
            case "charging" -> {
                int count = Math.min(8, Math.max(1, stations));
                for (int i = 0; i < count; i += 1) {
                    svg.append("<g transform=\"translate(")
                            .append(box[0] + 20 + (i % 4) * 38)
                            .append(", ")
                            .append(box[1] + 48 + (i / 4) * 44)
                            .append(")\">").append(station(c))
                            .append("</g>");
                }
            }
            default -> {
                // неизвестная зона - без иконок
            }
        }
    }

    /**
 * Ближайшая точка операции к позиции на маршруте.
 */
    private static double[] nearestOperationPoint(double[] pos) {
        double[] best = OPERATION_POINTS[0];
        double bestDist = Double.POSITIVE_INFINITY;
        for (double[] point : OPERATION_POINTS) {
            double dist = (point[0] - pos[0]) * (point[0] - pos[0])
                    + (point[1] - pos[1]) * (point[1] - pos[1]);
            if (dist < bestDist) {
                bestDist = dist;
                best = point;
            }
        }
        return best;
    }

    private static String shelf(Map<String, String> c) {
        return "<rect width=\"26\" height=\"22\" rx=\"3\" fill=\""
                + c.get("shelfFill")
                + "\" fill-opacity=\"0.85\" stroke=\""
                + c.get("shelfStroke")
                + "\" stroke-width=\"1.2\"/>"
                + "<path d=\"M 2 8 H 24 M 13 8 V 20\" fill=\"none\" "
                + "stroke=\"" + c.get("shelfStroke")
                + "\" stroke-width=\"1\"/>";
    }

    private static String rack(Map<String, String> c) {
        return "<rect width=\"64\" height=\"20\" rx=\"3\" fill=\""
                + c.get("rackFill") + "\" fill-opacity=\"0.8\" stroke=\""
                + c.get("rackStroke") + "\" stroke-width=\"1.1\"/>"
                + "<path d=\"M 4 7 H 60 M 4 14 H 60\" fill=\"none\" "
                + "stroke=\"" + c.get("rackStroke")
                + "\" stroke-width=\"1\"/>";
    }

    private static String basket(Map<String, String> c) {
        return "<rect width=\"16\" height=\"12\" rx=\"3\" fill=\""
                + c.get("pickFill") + "\" fill-opacity=\"0.9\" stroke=\""
                + c.get("pickStroke") + "\" stroke-width=\"1.2\"/>"
                + "<path d=\"M 3 3 Q 8 -3 13 3\" fill=\"none\" stroke=\""
                + c.get("pickStroke") + "\" stroke-width=\"1.2\"/>";
    }

    private static String shipBox(Map<String, String> c) {
        return "<rect width=\"26\" height=\"22\" rx=\"3\" fill=\""
                + c.get("shipFill") + "\" fill-opacity=\"0.85\" stroke=\""
                + c.get("shipStroke") + "\" stroke-width=\"1.2\"/>"
                + "<path d=\"M 2 8 H 24 M 13 8 V 20\" fill=\"none\" "
                + "stroke=\"" + c.get("shipStroke")
                + "\" stroke-width=\"1\"/>"
                + "<path d=\"M 8 3 L 13 -1 L 18 3\" fill=\"none\" "
                + "stroke=\"" + c.get("shipStroke")
                + "\" stroke-width=\"1.1\"/>";
    }

    private static String station(Map<String, String> c) {
        return "<rect width=\"26\" height=\"26\" rx=\"6\" fill=\""
                + c.get("stationFill") + "\" fill-opacity=\"0.9\" "
                + "stroke=\"" + c.get("stationStroke")
                + "\" stroke-width=\"1.2\"/>"
                + "<path d=\"M 14.5 5 L 9 14.5 H 12.5 L 11 21 L 17 11.5 "
                + "H 13.5 Z\" fill=\"" + c.get("stationStroke") + "\"/>";
    }

    // ==================================================================
    // потоки / маршрут
    // ==================================================================

    private static void flows(StringBuilder svg, Map<String, String> c) {
        // подписи паллет - ПОД иконками операций, внутри своих зон
        // (не пересекаются с маршрутом, роботами и другими подписями)
        svg.append("<line x1=\"176\" y1=\"152\" x2=\"206\" y2=\"152\" "
                        + "stroke=\"").append(c.get("arrow"))
                .append("\" stroke-width=\"2.2\" "
                        + "marker-end=\"url(#flow-arrow)\"/>")
                .append("<line x1=\"380\" y1=\"314\" x2=\"380\" "
                        + "y2=\"346\" stroke=\"").append(c.get("arrow"))
                .append("\" stroke-width=\"2.2\" "
                        + "marker-end=\"url(#flow-arrow)\"/>")
                .append("<polyline points=\"552,392 640,392 660,314\" "
                        + "fill=\"none\" stroke=\"").append(c.get("arrow"))
                .append("\" stroke-width=\"2.2\" "
                        + "marker-end=\"url(#flow-arrow)\"/>")
                .append("<g data-testid=\"pallet-label\" data-pallet-zone=\"receiving\">")
                .append("<rect x=\"40\" y=\"286\" width=\"118\" "
                        + "height=\"18\" rx=\"5\" fill=\"")
                .append(c.get("plateFill"))
                .append("\" fill-opacity=\"0.92\" stroke=\"")
                .append(c.get("plateStroke")).append("\"/>")
                .append("<text x=\"49\" y=\"298.5\" font-size=\"9.5\" "
                        + "fill=\"").append(c.get("zoneSub"))
                .append("\">Входящие паллеты</text></g>")
                .append("<g data-testid=\"pallet-label\" data-pallet-zone=\"shipping\">")
                .append("<rect x=\"600\" y=\"286\" width=\"122\" "
                        + "height=\"18\" rx=\"5\" fill=\"")
                .append(c.get("plateFill"))
                .append("\" fill-opacity=\"0.92\" stroke=\"")
                .append(c.get("plateStroke")).append("\"/>")
                .append("<text x=\"609\" y=\"298.5\" font-size=\"9.5\" "
                        + "fill=\"").append(c.get("zoneSub"))
                .append("\">Исходящие паллеты</text></g>")
                .append("<polyline points=\"736,152 770,136\" "
                        + "fill=\"none\" stroke=\"")
                .append(c.get("arrowGreen"))
                .append("\" stroke-width=\"2.2\" stroke-dasharray=\"5 4\" "
                        + "marker-end=\"url(#charge-arrow)\"/>");
    }

    private static void route(StringBuilder svg, Map<String, String> c) {
        StringBuilder points = new StringBuilder();
        for (double[] p : LOOP) {
            if (points.length() > 0) {
                points.append(' ');
            }
            points.append((int) p[0]).append(',').append((int) p[1]);
        }
        svg.append("<polyline points=\"").append(points)
                .append("\" fill=\"none\" stroke=\"").append(c.get("route"))
                .append("\" stroke-width=\"2.5\" stroke-dasharray=\"8 6\" "
                        + "stroke-linecap=\"round\"/>");
        for (double fraction : new double[]{0.07, 0.27, 0.47, 0.67,
                0.87}) {
            double[] point = loopAt(fraction);
            double[] ahead = loopAt(fraction + 0.015);
            double angle = Math.toDegrees(Math.atan2(ahead[1] - point[1],
                    ahead[0] - point[0]));
            svg.append("<path transform=\"translate(")
                    .append(fmt(point[0])).append(", ")
                    .append(fmt(point[1])).append(") rotate(")
                    .append(fmt(angle))
                    .append(")\" d=\"M -3 -4.5 L 4 0 L -3 4.5 Z\" "
                            + "fill=\"")
                    .append(c.get("route")).append("\"/>");
        }
    }

    // ==================================================================
    // роботы
    // ==================================================================

    private static void robots(StringBuilder svg, Map<String, String> c,
                               int robots, double moveShare,
                               double handleShare) {
        for (int i = 0; i < robots; i += 1) {
            double phase = robots > 0 ? (double) i / robots : 0;
            String status = phase >= moveShare + handleShare ? "idle"
                    : phase >= moveShare ? "handling" : "moving";
            String color = "idle".equals(status) ? c.get("idle")
                    : "handling".equals(status) ? c.get("handling")
                      : c.get("moving");
            double[] pos = loopAt(phase * 0.999);
            double[] next = loopAt(phase * 0.999 + 0.01);
            // погрузка/разгрузка - строго в точке операции (ближайшая
            // к маршрутной позиции фазы), как в клиентской анимации
            if ("handling".equals(status)) {
                pos = nearestOperationPoint(pos);
                next = pos;
            }
            double angle = Math.toDegrees(Math.atan2(next[1] - pos[1],
                    next[0] - pos[0]));
            svg.append("<g transform=\"translate(").append(fmt(pos[0]))
                    .append(", ").append(fmt(pos[1])).append(")\">")
                    .append("<g filter=\"url(#schema-shadow)\">")
                    .append("<rect x=\"-11.5\" y=\"-7.5\" width=\"23\" "
                            + "height=\"15\" rx=\"5\" fill=\"")
                    .append(c.get("robotHalo")).append("\"/>")
                    .append("<rect x=\"-10\" y=\"-6\" width=\"20\" "
                            + "height=\"12\" rx=\"4\" fill=\"")
                    .append(color).append("\" fill-opacity=\"0.92\" "
                            + "stroke=\"")
                    .append(color).append("\" stroke-width=\"1.6\"/>")
                    .append("<rect x=\"3.5\" y=\"-3.5\" width=\"4.5\" "
                            + "height=\"7\" rx=\"2\" fill=\"#ffffff\" "
                            + "fill-opacity=\"0.55\"/></g>")
                    .append("<g transform=\"rotate(").append(fmt(angle))
                    .append(")\"><path d=\"M 12.5 -3.5 L 18 0 L 12.5 3.5 "
                            + "Z\" fill=\"").append(color)
                    .append("\" stroke=\"").append(c.get("robotHalo"))
                    .append("\" stroke-width=\"0.8\"/></g>");
            if ("moving".equals(status)) {
                // след движения - в rotate-группе: позади робота ПО ХОДУ
                // (на вертикальных участках не вылетают влево и не
                // задевают подписи зон)
                svg.append("<path transform=\"rotate(").append(fmt(angle))
                        .append(")\" d=\"M -6 -3.5 L -9 0 L -6 3.5\" "
                                + "fill=\"none\" stroke=\"")
                        .append(color)
                        .append("\" stroke-width=\"1.7\" "
                                + "stroke-linecap=\"round\"/>");
            }
            switch (status) {
                case "handling" -> svg.append(
                                "<circle r=\"13\" fill=\"none\" stroke=\"")
                        .append(c.get("chargingRing"))
                        .append("\" stroke-width=\"1.5\" "
                                + "stroke-dasharray=\"3 2\"/>")
                        .append("<rect x=\"-4.5\" y=\"-15\" "
                                + "width=\"10\" height=\"7.5\" rx=\"1.5\" "
                                + "fill=\"")
                        .append(color)
                        .append("\" fill-opacity=\"0.4\" stroke=\"")
                        .append(color)
                        .append("\" stroke-width=\"1\"/>");
                default -> svg.append(
                                "<g stroke=\"").append(color)
                        .append("\" stroke-width=\"1.8\" "
                                + "stroke-linecap=\"round\">")
                        .append("<line x1=\"-3.5\" y1=\"-3.5\" "
                                + "x2=\"-3.5\" y2=\"3.5\"/>")
                        .append("<line x1=\"2.5\" y1=\"-3.5\" "
                                + "x2=\"2.5\" y2=\"3.5\"/></g>");
            }
            svg.append("</g>");
        }
    }

    // ==================================================================
    // легенда
    // ==================================================================

    private static void legend(StringBuilder svg, Map<String, String> c) {
        svg.append("<rect x=\"765\" y=\"248\" width=\"170\" "
                        + "height=\"212\" rx=\"9\" fill=\"")
                .append(c.get("plateFill")).append("\" fill-opacity=\"0.95\" "
                        + "stroke=\"")
                .append(c.get("plateStroke")).append("\"/>")
                .append("<text x=\"778\" y=\"266\" font-size=\"10.5\" "
                        + "font-weight=\"700\" fill=\"")
                .append(c.get("legendTitle")).append("\">Легенда</text>");
        String[][] statuses = {
                {"moving", "Движется", "moving"},
                {"handling", "Загружается", "handling"},
                {"idle", "Простаивает (зарядка)", "idle"}};
        for (int i = 0; i < statuses.length; i += 1) {
            String color = c.get(statuses[i][2]);
            svg.append("<g transform=\"translate(778, ")
                    .append(280 + i * 20).append(")\">")
                    .append("<rect x=\"0\" y=\"-5\" width=\"13\" "
                            + "height=\"9\" rx=\"3\" fill=\"")
                    .append(color).append("\" fill-opacity=\"0.92\" "
                            + "stroke=\"")
                    .append(color).append("\" stroke-width=\"1.2\"/>")
                    .append("<text x=\"21\" y=\"3\" font-size=\"9.5\" "
                            + "fill=\"")
                    .append(c.get("legendText")).append("\">")
                    .append(escape(statuses[i][1])).append("</text></g>");
        }
        svg.append("<line x1=\"778\" y1=\"347\" x2=\"922\" y2=\"347\" "
                        + "stroke=\"").append(c.get("plateStroke"))
                .append("\" stroke-width=\"1\"/>")
                .append("<g transform=\"translate(778, 361)\">")
                .append("<rect width=\"13\" height=\"11\" rx=\"2\" "
                        + "fill=\"").append(c.get("shelfFill"))
                .append("\" fill-opacity=\"0.85\" stroke=\"")
                .append(c.get("shelfStroke"))
                .append("\" stroke-width=\"1\"/>")
                .append("<text x=\"21\" y=\"8\" font-size=\"9.5\" "
                        + "fill=\"").append(c.get("legendText"))
                .append("\">точка операции</text></g>")
                .append("<g transform=\"translate(778, 381)\">")
                .append("<rect width=\"13\" height=\"13\" rx=\"3\" "
                        + "fill=\"").append(c.get("stationFill"))
                .append("\" fill-opacity=\"0.9\" stroke=\"")
                .append(c.get("stationStroke"))
                .append("\" stroke-width=\"1\"/>")
                .append("<path transform=\"scale(0.5)\" d=\"M 14.5 5 "
                        + "L 9 14.5 H 12.5 L 11 21 L 17 11.5 H 13.5 Z\" "
                        + "fill=\"").append(c.get("stationStroke"))
                .append("\"/>")
                .append("<text x=\"21\" y=\"9\" font-size=\"9.5\" "
                        + "fill=\"").append(c.get("legendText"))
                .append("\">зарядная станция</text></g>")
                .append("<g transform=\"translate(778, 401)\">")
                .append("<rect width=\"13\" height=\"10\" rx=\"3\" "
                        + "fill=\"").append(c.get("pickFill"))
                .append("\" fill-opacity=\"0.9\" stroke=\"")
                .append(c.get("pickStroke"))
                .append("\" stroke-width=\"1\"/>")
                .append("<text x=\"21\" y=\"8\" font-size=\"9.5\" "
                        + "fill=\"").append(c.get("legendText"))
                .append("\">точка отбора</text></g>")
                .append("<line x1=\"778\" y1=\"415\" x2=\"922\" "
                        + "y2=\"415\" stroke=\"")
                .append(c.get("plateStroke"))
                .append("\" stroke-width=\"1\"/>")
                .append("<g transform=\"translate(778, 429)\">")
                .append("<line x1=\"0\" y1=\"0\" x2=\"34\" y2=\"0\" "
                        + "stroke=\"").append(c.get("route"))
                .append("\" stroke-width=\"2\" stroke-dasharray=\"6 4\"/>")
                .append("<path d=\"M 32 -3.5 L 38 0 L 32 3.5 Z\" "
                        + "fill=\"").append(c.get("route")).append("\"/>")
                .append("<text x=\"45\" y=\"3.5\" font-size=\"9.5\" "
                        + "fill=\"").append(c.get("legendText"))
                .append("\">маршрут роботов</text></g>")
                .append("<g transform=\"translate(778, 449)\">")
                .append("<rect x=\"0\" y=\"-7\" width=\"38\" "
                        + "height=\"13\" rx=\"3\" fill=\"none\" stroke=\"")
                .append(c.get("bottleneckStroke"))
                .append("\" stroke-width=\"1.2\" stroke-dasharray="
                        + "\"4 3\"/>")
                .append("<text x=\"45\" y=\"3.5\" font-size=\"9.5\" "
                        + "fill=\"").append(c.get("legendText"))
                .append("\">узкое место</text></g>");
    }

    // ==================================================================
    // утилиты
    // ==================================================================

    /**
 * Точка на кольцевом маршруте по доле [0..1).
 */
    private static double[] loopAt(double fraction) {
        double f = ((fraction % 1) + 1) % 1;
        double[] segs = new double[LOOP.length];
        double total = 0;
        for (int i = 0; i < LOOP.length; i += 1) {
            double[] a = LOOP[i];
            double[] b = LOOP[(i + 1) % LOOP.length];
            segs[i] = Math.hypot(b[0] - a[0], b[1] - a[1]);
            total += segs[i];
        }
        double dist = f * total;
        for (int i = 0; i < segs.length; i += 1) {
            if (dist <= segs[i]) {
                double[] a = LOOP[i];
                double[] b = LOOP[(i + 1) % LOOP.length];
                double t = segs[i] == 0 ? 0 : dist / segs[i];
                return new double[]{a[0] + (b[0] - a[0]) * t,
                        a[1] + (b[1] - a[1]) * t};
            }
            dist -= segs[i];
        }
        return LOOP[0];
    }

    private static boolean dark(Map<String, String> c) {
        return c == DARK;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> zonesOf(
            Map<String, Object> kpi) {
        Object raw = kpi.get("zones");
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    private static Map<String, Object> zoneByCode(
            List<Map<String, Object>> zones, String code) {
        for (Map<String, Object> zone : zones) {
            if (code.equals(zone.get("code"))) {
                return zone;
            }
        }
        return null;
    }

    private static String bottleneckOf(List<Map<String, Object>> zones) {
        String code = null;
        double share = -1;
        for (Map<String, Object> zone : zones) {
            double load = doubleOf(zone.get("robotTimeSharePct"));
            if (load > share) {
                share = load;
                code = textOf(zone.get("code"));
            }
        }
        return share > 0 ? code : null;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double doubleOf(Object value) {
        return value instanceof Number number
                ? number.doubleValue() : 0.0;
    }

    private static String textOf(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
 * Нагрузка зоны - с одним знаком после запятой (как в UI).
 */
    private static String fmtPct(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (rounded == Math.floor(rounded)) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }

    private static String fmt(double value) {
        return String.valueOf(Math.round(value * 10) / 10.0);
    }

    /**
 * XML-экранирование текстовых узлов.
 */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
