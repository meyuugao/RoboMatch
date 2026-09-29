package me.yuugao.robomatch.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Результат имитации сценария (,
 * 3.6.1–3.6.3, 4.3.3). Зеркалит kpi_json simulation_result (§10.11,
 * снимок переменного состава) + метаданные выполнения.
 *
 * <p>6 KPI
 * загрузка роботов, простои, узкие места (зоны), достижимость
 * заявленной производительности. Разбивки (время цикла, маршруты,
 * зарядные станции, статусные доли роботов) - для 2D-схемы.
 *
 * @param id идентификатор simulation_result
 * @param scenarioId идентификатор сценария
 * @param scenarioName название сценария
 * @param status статус выполнения: running | completed | failed
 * @param startedAt начало выполнения (ISO-8601)
 * @param finishedAt окончание (ISO-8601); null у выполняющегося
 * @param durationMs длительность расчёта, мс
 * @param declaredThroughputPerHour KPI 1: заявленная производительность парка, оп/час
 * @param actualThroughputPerHour KPI 2: фактическая производительность модели, оп/час
 * @param utilizationPct KPI 3: загрузка роботов, %
 * @param idlePct KPI 4: простои, %
 * @param zones KPI 5: узкие места - загрузка зон склада
 * @param achievabilityPct KPI 6: достижимость заявленной производительности, %
 * @param fleetSpeedMs средневзвешенная скорость состава, м/с
 * @param capacityPerRobotPerHour мощность робота при непрерывной работе, оп/час
 * @param effectivePerRobotPerHour P_effective, оп/час
 * @param peakDemandPerHour пиковая потребность, оп/час
 * @param avgCycleTimeSec среднее время цикла операции, с
 * @param inboundCycleTimeSec время цикла приёмки, с
 * @param outboundCycleTimeSec время цикла отгрузки, с
 * @param dailyTravelKmPerRobot суточный пробег робота, км
 * @param chargingStations зарядные станции
 * @param robots роботов в сценарии
 * @param movingPct доля «в пути», % робот-времени
 * @param handlingPct доля «погрузка/разгрузка», %
 * @param composition состав сценария (для схемы)
 * @param inputs входы модели (допущения и параметры)
 * @param warnings предупреждения модели
 * @param exportUrl ссылка на сохранённую схему
 * @param error причина провала (status=failed)
 * @param version версия KPI-модели
 */
@Schema(description = "Результат имитации сценария (KPI + метаданные)")
public record SimulationRunDto(
        @Schema(description = "Идентификатор simulation_result",
                example = "12")
        Long id,

        @Schema(description = "Идентификатор сценария", example = "34")
        Long scenarioId,

        @Schema(description = "Название сценария",
                example = "Покупка оборудования")
        String scenarioName,

        @Schema(description = "Статус выполнения: running | completed | "
                + "failed", example = "completed",
                allowableValues = {"running", "completed", "failed"})
        String status,

        @Schema(description = "Начало выполнения (ISO-8601)",
                example = "2026-09-26T10:15:30Z")
        String startedAt,

        @Schema(description = "Окончание (ISO-8601); null у выполняющегося",
                example = "2026-09-26T10:15:31Z")
        String finishedAt,

        @Schema(description = "Длительность расчёта, мс",
                example = "58")
        Long durationMs,

        // ---------------- 6 KPI -----------------------------

        @Schema(description = "KPI 1. Заявленная производительность "
                + "парка, оп/час (P_nominal × роботов); null - P_nominal "
                + "не задан", example = "450.0")
        @Nullable
        BigDecimal declaredThroughputPerHour,

        @Schema(description = "KPI 2. Фактическая производительность "
                + "модели, оп/час: min(пиковая нагрузка, мощность парка)",
                example = "136.4")
        BigDecimal actualThroughputPerHour,

        @Schema(description = "KPI 3. Загрузка роботов, % робот-времени " +
                "(полезная работа при пиковой нагрузке)", example = "91.0")
        BigDecimal utilizationPct,

        @Schema(description = "KPI 4. Простои, % (зарядка, ожидание)",
                example = "9.0")
        BigDecimal idlePct,

        @Schema(description = "KPI 5. Узкие места - загрузка зон склада " +
                "(максимум - узкое место)",
                example = "[{\"code\": \"storage\", \"robotTimeSharePct\": 15.2}]")
        List<ZoneLoadDto> zones,

        @Schema(description = "KPI 6. Достижимость заявленной "
                + "производительности, % (фактическая на робота к P_nominal)",
                example = "33.3")
        @Nullable
        BigDecimal achievabilityPct,

        // ---------------- разбивки для 2D-схемы -----------------------

        @Schema(description = "Средневзвешенная скорость состава, м/с " +
                "(ТТХ speed_m_s)", example = "1.500")
        @Nullable
        BigDecimal fleetSpeedMs,

        @Schema(description = "Мощность робота при непрерывной работе, " +
                "оп/час (3600 / время цикла)", example = "33.3")
        BigDecimal capacityPerRobotPerHour,

        @Schema(description = "Заложенная в расчёт экономики "
                + "производительность робота P_effective = P_nominal × "
                + "K_load × K_availability, оп/час", example = "67.5")
        @Nullable
        BigDecimal effectivePerRobotPerHour,

        @Schema(description = "Пиковая потребность, оп/час",
                example = "136.4")
        BigDecimal peakDemandPerHour,

        @Schema(description = "Среднее время цикла операции, с",
                example = "108.0")
        BigDecimal avgCycleTimeSec,

        @Schema(description = "Время цикла приёмки (приёмка → хранение), с",
                example = "100.0")
        BigDecimal inboundCycleTimeSec,

        @Schema(description = "Время цикла отгрузки (хранение → отбор → "
                + "отгрузка), с", example = "116.0")
        BigDecimal outboundCycleTimeSec,

        @Schema(description = "Суточный пробег робота, км (справочно)",
                example = "18.5")
        BigDecimal dailyTravelKmPerRobot,

        @Schema(description = "Зарядные станции "
                + "(ceil(роботы × ratio_infra))", example = "2")
        int chargingStations,

        @Schema(description = "Роботов в сценарии (sum(quantity) состава)",
                example = "5")
        int robots,

        @Schema(description = "Статусные доли для 2D-схемы: в пути, % " +
                "робот-времени", example = "70.0")
        BigDecimal movingPct,

        @Schema(description = "Статусные доли для 2D-схемы: погрузка/"
                + "разгрузка, %", example = "21.0")
        BigDecimal handlingPct,

        @Schema(description = "Состав сценария (для схемы)",
                example = "[{\"solutionId\": 7, \"quantity\": 2}]")
        List<ScenarioCompositionLineDto> composition,

        @Schema(description = "Входы модели (допущения и параметры)",
                example = "{\"routeLengthM\": 60, \"speedMs\": 1.5}")
        SimulationInputsDto inputs,

        @Schema(description = "Предупреждения модели (сверка "
                + "с расчётом экономики; понятным языком)",
                example = "[\"Заявленная производительность не задана\"]")
        List<String> warnings,

        @Schema(description = "Ссылка на сохранённую схему; "
                + "null - схема не сохранялась",
                example = "/data/simulations/1/12.svg")
        @Nullable
        String exportUrl,

        @Nullable
        @Schema(description = "Причина провала (status=failed)",
                example = "Не задано время цикла операций")
        String error,

        @Schema(description = "Версия KPI-модели",
                example = "simulation-model-1.0")
        String version
) {

    /**
 * Загрузка зоны склада (узкие места).
 *
 * @param code код зоны: receiving | storage | picking | shipping
 * @param name название зоны
 * @param flowPerHour пиковый поток зоны (паллет-точки), оп/час
 * @param robotTimeSharePct доля робот-времени парка в зоне, %
 */
    @Schema(description = "Зона склада с загрузкой")
    public record ZoneLoadDto(
            @Schema(description = "Код зоны: receiving | storage | "
                    + "picking | shipping", example = "storage",
                    allowableValues = {"receiving", "storage", "picking", "shipping"})
            String code,
            @Schema(description = "Название зоны", example = "Хранение")
            String name,
            @Schema(description = "Пиковый поток зоны (паллет-точки), оп/час",
                    example = "136.4")
            BigDecimal flowPerHour,
            @Schema(description = "Доля робот-времени парка в зоне, %",
                    example = "15.2")
            BigDecimal robotTimeSharePct
    ) {
    }

    /**
 * Строка состава сценария (для 2D-схемы).
 *
 * @param solutionId идентификатор решения
 * @param name название
 * @param quantity количество, ед.
 * @param speedMs скорость из ТТХ, м/с
 */
    @Schema(description = "Решение в составе сценария")
    public record ScenarioCompositionLineDto(
            @Schema(description = "Идентификатор решения", example = "7")
            long solutionId,
            @Schema(description = "Название", example = "Ronavi H1500")
            String name,
            @Schema(description = "Количество, ед.", example = "2")
            int quantity,
            @Nullable
            @Schema(description = "Скорость из ТТХ, м/с (null - нет "
                    + "в ТТХ, применяется допущение)", example = "1.5")
            BigDecimal speedMs
    ) {
    }

    /**
 * Входы модели (для отображения и воспроизведения).
 *
 * @param routeLengthM средняя длина маршрута за перемещение, м
 * @param pickDropSec время погрузки и разгрузки паллеты, с
 * @param speedMs скорость модели, м/с
 * @param hoursPerDay часов работы в сутки
 * @param inboundPerDay приёмка, паллет/сутки
 * @param outboundPerDay отгрузка, паллет/сутки
 * @param peakFactor пиковый коэффициент
 */
    @Schema(description = "Входы KPI-модели")
    public record SimulationInputsDto(
            @Schema(description = "Средняя длина маршрута за перемещение, " +
                    "м (sim_avg_route_length_m)", example = "60")
            BigDecimal routeLengthM,
            @Schema(description = "Время погрузки и разгрузки паллеты, с " +
                    "(sim_pick_drop_sec)", example = "40")
            BigDecimal pickDropSec,
            @Schema(description = "Скорость модели, м/с (ТТХ состава или "
                    + "sim_avg_robot_speed_m_s)", example = "1.5")
            BigDecimal speedMs,
            @Schema(description = "Часов работы в сутки", example = "22")
            BigDecimal hoursPerDay,
            @Schema(description = "Приёмка, паллет/сутки", example = "1000")
            BigDecimal inboundPerDay,
            @Schema(description = "Отгрузка, паллет/сутки",
                    example = "1000")
            BigDecimal outboundPerDay,
            @Schema(description = "Пиковый коэффициент", example = "1.5")
            BigDecimal peakFactor
    ) {
    }
}
