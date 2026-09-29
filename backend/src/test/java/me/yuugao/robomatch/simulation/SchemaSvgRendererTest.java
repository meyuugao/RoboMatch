package me.yuugao.robomatch.simulation;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit-тест серверного рендера 2D-схемы (SVG): разметка валидна и
 * самодостаточна, обе темы отличаются, элементы схемы (зоны, роботы,
 * маршрут, легенда, точки операций) присутствуют; санитизацию хранилища
 * разметка проходит (никаких скриптов/on*-атрибутов/внешних URI).
 */
class SchemaSvgRendererTest {

    /**
 * KPI-словарь живого результата имитации (зоны + парк + доли).
 */
    private static Map<String, Object> kpi() {
        Map<String, Object> kpi = new LinkedHashMap<>();
        List<Map<String, Object>> zones = new ArrayList<>();
        zones.add(zone("receiving", "Приёмка", 180, 18.0));
        zones.add(zone("storage", "Хранение", 320, 42.5));
        zones.add(zone("picking", "Отбор (комплектация)", 140, 22.0));
        zones.add(zone("shipping", "Отгрузка", 180, 17.5));
        kpi.put("zones", zones);
        kpi.put("robots", 5);
        kpi.put("movingPct", 62.0);
        kpi.put("handlingPct", 25.0);
        kpi.put("chargingStations", 2);
        kpi.put("scenarioName", "Покупка оборудования");
        return kpi;
    }

    private static Map<String, Object> zone(String code, String name,
                                            int flow, double load) {
        Map<String, Object> zone = new LinkedHashMap<>();
        zone.put("code", code);
        zone.put("name", name);
        zone.put("flowPerHour", flow);
        zone.put("robotTimeSharePct", load);
        return zone;
    }

    @Test
    void rendersValidSvg_rootAndNamespace() {
        String svg = SchemaSvgRenderer.render(kpi(), false);
        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"");
        assertThat(svg).endsWith("</svg>");
        assertThat(svg).contains("viewBox=\"0 0 960 480\"");
    }

    @Test
    void schemaElementsPresent_zonesRobotsRouteLegend() {
        String svg = SchemaSvgRenderer.render(kpi(), false);
        assertThat(svg).contains("Приёмка");
        assertThat(svg).contains("Хранение");
        assertThat(svg).contains("Отбор (комплектация)");
        assertThat(svg).contains("Отгрузка");
        assertThat(svg).contains("Зарядные станции");
        assertThat(svg).contains("Сценарий «Покупка оборудования»");
        assertThat(svg).contains("Роботов: 5");
        assertThat(svg).contains("Зарядных станций: 2");
        // маршрут + направление
        assertThat(svg).contains("stroke-dasharray=\"8 6\"");
        // точки операций: полки приёмки, стеллажи, корзины, доки, станции
        assertThat(svg).contains("M 2 8 H 24 M 13 8 V 20"); // полка/док
        assertThat(svg).contains("M 4 7 H 60 M 4 14 H 60"); // стеллаж
        assertThat(svg).contains("Q 8 -3 13 3"); // корзина отбора
        assertThat(svg).contains("M 14.5 5 L 9 14.5"); // молния станции
        // легенда
        assertThat(svg).contains("Легенда");
        assertThat(svg).contains("маршрут роботов");
        assertThat(svg).contains("узкое место");
        // узкое место — максимальная загрузка (storage 42,5%); формат
        // «Узкое место: X%» (в узких зонах — «Узкое: X%»)
        assertThat(svg).contains("Узкое место: 42.5%");
    }

    @Test
    void robotsRendered_perStatusShares() {
        // 5 роботов, фазы i/5 = 0; 0,2; 0,4; 0,6; 0,8;
        // moveShare=0.62, handleShare=0.25 → 0.2 handling, 0.4 idle…
        String svg = SchemaSvgRenderer.render(kpi(), false);
        // корпус-капсула каждого робота
        assertThat(svg).contains("rx=\"4\"");
        // знаки статусов: след скорости (moving — короткая галочка
        // в rotate-группе), пунктирное кольцо (handling), пауза (idle)
        assertThat(svg).contains("M -6 -3.5 L -9 0 L -6 3.5");
        assertThat(svg).contains("stroke-dasharray=\"3 2\"");
        assertThat(svg).contains("stroke-linecap=\"round\"");
    }

    @Test
    void themesDiffer_andBothValid() {
        String light = SchemaSvgRenderer.render(kpi(), false);
        String dark = SchemaSvgRenderer.render(kpi(), true);
        assertThat(light).isNotEqualTo(dark);
        assertThat(light).contains("#f8fafc"); // светлый холст
        assertThat(dark).contains("#0f172a"); // тёмный холст
        assertThat(dark).contains("#3b82f6"); // робот moving тёмной темы
        assertThat(dark).endsWith("</svg>");
    }

    @Test
    void sanitizationSafe_noScriptsOrHandlersOrExternalUris() {
        for (String svg : new String[]{SchemaSvgRenderer.render(kpi(), false),
                SchemaSvgRenderer.render(kpi(), true)}) {
            String lower = svg.toLowerCase();
            assertThat(lower).doesNotContain("<script");
            assertThat(lower).doesNotContain("onload");
            assertThat(lower).doesNotContain("onmouseover");
            assertThat(lower).doesNotContain("foreignobject");
            assertThat(lower).doesNotContain("javascript:");
            assertThat(lower).doesNotContain("http://evil");
            assertThat(lower).doesNotContain("style=");
            // feDropShadow разрешён (декоративная статика; Batik
            // растеризует через препроцессор)
            assertThat(svg).contains("feDropShadow");
        }
    }

    @Test
    void emptyZones_renderedWithoutBottleneckBadge() {
        Map<String, Object> kpi = kpi();
        kpi.put("zones", List.of());
        String svg = SchemaSvgRenderer.render(kpi, false);
        assertThat(svg).doesNotContain("Узкое место — загрузка");
        // каркас схемы остаётся целым
        assertThat(svg).contains("Склад — 2D-схема");
        assertThat(svg).contains("Легенда");
    }

    @Test
    void escaping_scenarioNameWithMarkupIsNeutralized() {
        Map<String, Object> kpi = kpi();
        kpi.put("scenarioName", "Покупка \"<script>alert(1)</script>\"");
        String svg = SchemaSvgRenderer.render(kpi, false);
        assertThat(svg).doesNotContain("<script>alert");
        assertThat(svg).contains("&lt;script&gt;");
    }
}
