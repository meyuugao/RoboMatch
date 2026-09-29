package me.yuugao.robomatch.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import me.yuugao.robomatch.simulation.SimulationModel.RobotLine;
import me.yuugao.robomatch.simulation.SimulationModel.SimulationInput;
import me.yuugao.robomatch.simulation.SimulationModel.SimulationKpi;
import me.yuugao.robomatch.simulation.SimulationModel.ZoneLoad;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unit-тесты чистого движка KPI-модели (,
 * 3.6.2). Формулы — javadoc SimulationModel; допущения —
 * assumptions.md §22-бис (sim_*).
 *
 * <p>Базовая фикстура (данные датасета «Склад»):
 * 1000+1000 паллет/сутки, 2 смены × 11 ч, пик ×1,5 → пик
 * 136,36 оп/час; Ronavi H1500: P_nominal 90 оп/ч, скорость 1,5 м/с;
 * маршрут 60 м, погрузка-разгрузка 40 с.
 */
class SimulationModelTest {

    private static final BigDecimal SPEED = bd("1.5");
    private static final BigDecimal ROUTE = bd("60");
    private static final BigDecimal PICK_DROP = bd("40");

    /**
 * Базовый вход: N роботов, стандартный склад, P_nominal 90.
 */
    private static SimulationInput input(int robots, BigDecimal pNominal) {
        return new SimulationInput(robots,
                null, // peak считается из суточных объёмов
                bd("1000"), bd("1000"), bd("22"), bd("1.5"),
                pNominal, bd("0.75"), bd("1.0"), bd("0.35"),
                SPEED, ROUTE, PICK_DROP,
                List.of(new RobotLine(1L, "Ronavi H1500", robots,
                        SPEED)),
                null, null, null); // снимка расчёта экономики нет
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ------------------------------------------------------------------
    // KPI для 1 / 5 / 9 роботов
    // ------------------------------------------------------------------

    @Test
    void kpiForSingleRobot_loadsItFully() {
        // 1 робот: мощность 30 оп/ч << пик 136,4 — загрузка 100%,
        // фактическая = мощность парка, простои 0, дефицит с предупрежд.
        SimulationKpi kpi = SimulationModel.calculate(input(1, bd("90")));
        assertThat(kpi.utilizationPct()).isEqualByComparingTo("100.0");
        assertThat(kpi.idlePct()).isEqualByComparingTo("0.0");
        // cycleIn = 40 + 60/1.5 = 80 c; cycleOut = 2×40 + 2×40 = 160 c;
        // avg = 0.5×80 + 0.5×160 = 120 c → мощность 30 оп/ч
        assertThat(kpi.avgCycleTimeSec()).isEqualByComparingTo("120.0");
        assertThat(kpi.capacityPerRobotPerHour())
                .isEqualByComparingTo("30.0");
        assertThat(kpi.actualThroughputPerHour())
                .isEqualByComparingTo("30.0");
        // достижимость: 30 / 90 = 33,3%
        assertThat(kpi.achievabilityPct()).isEqualByComparingTo("33.3");
        assertThat(kpi.warnings()).anyMatch(w -> w.contains("превышает"));
    }

    @Test
    void kpiForFiveRobots_partialLoad() {
        // 5 роботов: мощность 150 > пик 136,36 — фактическая = пик
        SimulationKpi kpi = SimulationModel.calculate(input(5, bd("90")));
        assertThat(kpi.actualThroughputPerHour())
                .isEqualByComparingTo("136.4");
        // загрузка = 136,36 × 120 / 3600 / 5 = 90,9%
        assertThat(kpi.utilizationPct()).isEqualByComparingTo("90.9");
        assertThat(kpi.idlePct()).isEqualByComparingTo("9.1");
        // заявленная: 90 × 5 = 450
        assertThat(kpi.declaredThroughputPerHour())
                .isEqualByComparingTo("450.0");
        // зарядные станции: ceil(5 × 0.35) = 2 (§2.2)
        assertThat(kpi.chargingStations()).isEqualTo(2);
    }

    @Test
    void kpiForNineRobots_lowUtilization() {
        // 9 роботов: загрузка = 136,36×120/3600/9 = 50,5%, простои 49,5%
        SimulationKpi kpi = SimulationModel.calculate(input(9, bd("90")));
        assertThat(kpi.utilizationPct()).isEqualByComparingTo("50.5");
        assertThat(kpi.idlePct()).isEqualByComparingTo("49.5");
        // ceil(9 × 0.35) = ceil(3.15) = 4 станции
        assertThat(kpi.chargingStations()).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // Узкие места
    // ------------------------------------------------------------------

    @Test
    void bottleneckIsPickingZone_onOutboundHeavyFlows() {
        // асимметрия: отгрузка 1500 > приёмки 500 — отбор (2 точки на
        // исходящую паллету) строго доминирует над хранением
        SimulationInput in = new SimulationInput(5, null,
                bd("500"), bd("1500"), bd("22"), bd("1.5"),
                bd("90"), bd("0.75"), bd("1.0"), bd("0.35"),
                SPEED, ROUTE, PICK_DROP,
                List.of(new RobotLine(1L, "Ronavi H1500", 5, SPEED)),
                null, null, null);
        SimulationKpi kpi = SimulationModel.calculate(in);
        var byCode = kpi.zones().stream().collect(Collectors.toMap(
                ZoneLoad::code, z -> z));
        assertThat(byCode).containsOnlyKeys("receiving", "storage",
                "picking", "shipping");
        ZoneLoad max = kpi.zones().stream()
                .max(java.util.Comparator.comparing(
                        ZoneLoad::robotTimeSharePct))
                .orElseThrow();
        assertThat(max.code()).isEqualTo("picking");
    }

    @Test
    void bottleneckTie_onSymmetricFlows_resolvesStably() {
        // приёмка = in×20 c, хранение = (in+out)×20, отбор = out×40,
        // отгрузка = out×20: при in=out хранение = отбору (тай);
        // детерминированный тай-брейк — первая зона по порядку (storage)
        SimulationKpi kpi = SimulationModel.calculate(input(5, bd("90")));
        var byCode = kpi.zones().stream().collect(Collectors.toMap(
                ZoneLoad::code, z -> z));
        assertThat(byCode.get("storage").robotTimeSharePct())
                .isEqualByComparingTo(byCode.get("picking")
                        .robotTimeSharePct());
        ZoneLoad max = kpi.zones().stream()
                .max(java.util.Comparator.comparing(
                        ZoneLoad::robotTimeSharePct))
                .orElseThrow();
        assertThat(max.code()).isIn("storage", "picking");
    }

    @Test
    void zoneLoads_allWithinParkCapacity() {
        // все зоны ≤ 100% робот-времени парка (согласованность долей)
        SimulationKpi kpi = SimulationModel.calculate(input(5, bd("90")));
        assertThat(kpi.zones()).allSatisfy(z ->
                assertThat(z.robotTimeSharePct().doubleValue())
                        .isLessThanOrEqualTo(100.0));
    }

    // ------------------------------------------------------------------
    // Достижимость и сверка с экономикой
    // ------------------------------------------------------------------

    @Test
    void shortRoute_achievabilityAndDivergenceWarning() {
        // маршрут 5 м: cycleIn = 43,3 c, cycleOut = 86,7 c,
        // avg = 65 c → 55,4 оп/ч; достижимость 61,5%
        // БЕЗ снимка расчёта экономики предупреждение сверки не пишется
        // (сравнивать модель не с чем — сервис отдельно предупреждает,
        // что экономика не рассчитана); достижимость — к текущему P_nominal
        SimulationInput in = new SimulationInput(5, null,
                bd("1000"), bd("1000"), bd("22"), bd("1.5"),
                bd("90"), bd("0.75"), bd("1.0"), bd("0.35"),
                SPEED, bd("5"), PICK_DROP,
                List.of(new RobotLine(1L, "Ronavi H1500", 5, SPEED)),
                null, null, null);
        SimulationKpi kpi = SimulationModel.calculate(in);
        assertThat(kpi.achievabilityPct()).isEqualByComparingTo("61.5");
        assertThat(kpi.warnings())
                .noneMatch(w -> w.contains("расходится с заложенной"));

        // СО СНИМКОМ расчёта (P_nominal=90, K_load=0.75 — как в расчёте):
        // P_effective = 67,5 против 55,4 оп/ч модели — предупреждение
        SimulationInput withSnapshot = new SimulationInput(5, null,
                bd("1000"), bd("1000"), bd("22"), bd("1.5"),
                bd("90"), bd("0.75"), bd("1.0"), bd("0.35"),
                SPEED, bd("5"), PICK_DROP,
                List.of(new RobotLine(1L, "Ronavi H1500", 5, SPEED)),
                bd("90"), bd("0.75"), bd("1.0"));
        SimulationKpi kpiSnapshot = SimulationModel.calculate(withSnapshot);
        assertThat(kpiSnapshot.warnings())
                .anyMatch(w -> w.contains("расходится с заложенной")
                        && w.contains("67.5"));
    }

    @Test
    void nominalNotSet_declaredAndAchievabilityNull_warning() {
        SimulationKpi kpi = SimulationModel.calculate(input(5, null));
        assertThat(kpi.declaredThroughputPerHour()).isNull();
        assertThat(kpi.achievabilityPct()).isNull();
        assertThat(kpi.warnings()).anyMatch(w -> w.contains("P_nominal"));
    }

    @Test
    void statusSharesSumUpWithUtilization() {
        // moving + handling = загрузка (9 роботов: 50,5%)
        SimulationKpi kpi = SimulationModel.calculate(input(9, bd("90")));
        BigDecimal busy = kpi.movingPct().add(kpi.handlingPct());
        assertThat(busy.doubleValue()).isBetween(50.0, 51.0);
    }

    // ------------------------------------------------------------------
    // Границы (economic_model.md §6: понятные ошибки)
    // ------------------------------------------------------------------

    @Test
    void emptyComposition_throwsWithHumanMessage() {
        assertThatThrownBy(() -> SimulationModel.calculate(input(0, bd("90"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Состав сценария пуст");
    }

    @Test
    void zeroSpeed_throws() {
        SimulationInput in = new SimulationInput(5, null,
                bd("1000"), bd("1000"), bd("22"), bd("1.5"),
                bd("90"), bd("0.75"), bd("1.0"), bd("0.35"),
                bd("0"), ROUTE, PICK_DROP,
                List.of(new RobotLine(1L, "R", 5, bd("0"))),
                null, null, null);
        assertThatThrownBy(() -> SimulationModel.calculate(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Скорость робота не задана");
    }

    @Test
    void zeroDailyVolume_throws() {
        SimulationInput in = new SimulationInput(5, null,
                bd("0"), bd("0"), bd("22"), bd("1.5"),
                bd("90"), bd("0.75"), bd("1.0"), bd("0.35"),
                SPEED, ROUTE, PICK_DROP,
                List.of(new RobotLine(1L, "R", 5, SPEED)),
                null, null, null);
        assertThatThrownBy(() -> SimulationModel.calculate(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Суточный объём операций равен нулю");
    }

    @Test
    void deterministic_sameInputSameKpi() {
        // (воспроизведение): одинаковые входы — одинаковые KPI
        SimulationKpi first = SimulationModel.calculate(input(5, bd("90")));
        SimulationKpi second = SimulationModel.calculate(input(5, bd("90")));
        assertThat(first).isEqualTo(second);
        assertThat(first.version())
                .isEqualTo(SimulationModel.MODEL_VERSION);
    }

    @Test
    void fleetSpeed_weightedAverage() {
        // 2 × 1,5 м/с + 1 × 3,0 м/с → средневзвешенная 2,0
        BigDecimal fleet = SimulationModel.fleetSpeedOf(List.of(
                new RobotLine(1L, "A", 2, bd("1.5")),
                new RobotLine(2L, "B", 1, bd("3.0"))), null);
        assertThat(fleet).isEqualByComparingTo("2.000");
        // без ТТХ и без фолбэка — null (скорость не определена)
        assertThat(SimulationModel.fleetSpeedOf(List.of(
                new RobotLine(1L, "A", 2, null)), null)).isNull();
    }

    @Test
    void fleetSpeed_mixedComposition_usesFallbackPerLine() {
        // смешанный состав: строки без ТТХ получают допущение (1,5),
        // средневзвешенная — по всему парку: (2×2,0 + 3×1,5)/5 = 1,7
        // (раньше строки без ТТХ молча выпадали из среднего — и роботы
        // без ТТХ неявно получали скорость чужих решений, вопреки
        // assumptions §22-бис «фолбэк, если у решения нет ТТХ»)
        BigDecimal fleet = SimulationModel.fleetSpeedOf(List.of(
                new RobotLine(1L, "A", 2, bd("2.0")),
                new RobotLine(2L, "B", 3, null)), bd("1.5"));
        assertThat(fleet).isEqualByComparingTo("1.700");
        // весь парк без ТТХ — чистое допущение
        assertThat(SimulationModel.fleetSpeedOf(List.of(
                new RobotLine(1L, "A", 4, null)), bd("1.5")))
                .isEqualByComparingTo("1.500");
    }
}
