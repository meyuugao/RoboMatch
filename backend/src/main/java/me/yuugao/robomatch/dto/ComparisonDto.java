package me.yuugao.robomatch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Таблица сравнения сценариев (; economic_model.md
 * §2.13): строки - показатели, колонки - base / purchase / raas; Δ к
 * базовому, интерпретация окупаемости и чувствительность
 *. Берёт последний расчёт каждого сценария; не рассчитанный
 * сценарий отмечен calculated=false.
 *
 * @param scenarios колонки сценариев в порядке base/purchase/raas
 * @param horizonYears горизонт расчёта, лет
 * @param versionModel версия модели последнего расчёта
 */
@Schema(description = "Сравнение сценариев экономики")
public record ComparisonDto(
        @Schema(description = "Сценарии в порядке base/purchase/raas "
                + "(первый каждого типа)",
                example = "[{\"scenarioId\": 1, \"type\": \"base\"}]")
        List<ScenarioColumnDto> scenarios,

        @Schema(description = "Горизонт расчёта, лет", example = "5")
        Integer horizonYears,

        @Schema(description = "Версия модели последнего расчёта",
                example = "economic-model-1.0")
        String versionModel
) {

    /**
 * Колонка сценария: показатели последнего расчёта + Δ к базовому.
 *
 * @param scenarioId идентификатор сценария
 * @param calculationId идентификатор последнего расчёта
 * @param type тип: base | purchase | raas
 * @param name название сценария
 * @param calculated есть расчёт
 * @param calculatedAt дата последнего расчёта
 * @param selectedRobots выбранный состав (sum(quantity)), ед.
 * @param requiredRobots требуемое количество по пиковой нагрузке, ед.
 * @param underpowered парк меньше требуемого
 * @param overpowered парк превышает требуемый
 * @param nInfra вспомогательное оборудование, ед.
 * @param capex CAPEX, руб.
 * @param opexYear годовой OPEX, руб.
 * @param opexDelta ΔOPEX к базовому, руб.
 * @param deltaFot ΔFOT (экономия ФОТ), руб./год
 * @param effectYear годовой эффект, руб.
 * @param payback интерпретация окупаемости
 * @param roiPct ROI за горизонт, %
 * @param tco TCO на горизонте, руб.
 * @param capexDeltaToBase Δ CAPEX к базовому, руб.
 * @param tcoDeltaToBase Δ TCO к базовому, руб.
 * @param effectDeltaToBase Δ годового эффекта к базовому, руб.
 * @param sensitivity чувствительность сценария
 * @param warnings предупреждения расчёта
 * @param versionData версия данных последнего расчёта
 */
    @Schema(description = "Колонка сценария в таблице сравнения")
    public record ScenarioColumnDto(
            @Schema(description = "Идентификатор сценария", example = "2")
            Long scenarioId,

            @Schema(description = "Идентификатор последнего расчёта "
                    + "(для ручной корректировки)", example = "7")
            Long calculationId,

            @Schema(description = "Тип: base | purchase | raas",
                    example = "purchase", allowableValues = {"base", "purchase", "raas"})
            String type,

            @Schema(description = "Название сценария",
                    example = "Покупка оборудования")
            String name,

            @Schema(description = "Есть расчёт", example = "true")
            boolean calculated,

            @Schema(description = "Дата последнего расчёта",
                    example = "2026-09-26T10:15:30Z")
            Instant calculatedAt,

            @Schema(description = "Выбранный состав сценария "
                    + "(sum(quantity)), ед. - вся экономика считается "
                    + "по нему", example = "12")
            Integer selectedRobots,

            @Schema(description = "Требуемое количество по пиковой "
                    + "нагрузке, ед.", example = "10")
            Integer requiredRobots,

            @Schema(description = "Парк меньше требуемого: "
                    + "расчёт не отражает достижение заявленной "
                    + "производительности", example = "false")
            boolean underpowered,

            @Schema(description = "Парк превышает требуемый по пиковой "
                    + "нагрузке - нейтральный флаг (не ошибка): "
                    + "возможна переплата за избыточный парк", example = "false")
            boolean overpowered,

            @Schema(description = "Вспомогательное оборудование, ед.",
                    example = "5")
            Integer nInfra,

            @Schema(description = "CAPEX, руб.", example = "10000000")
            BigDecimal capex,

            @Schema(description = "Годовой OPEX, руб.", example = "4200000")
            BigDecimal opexYear,

            @Schema(description = "ΔOPEX к базовому, руб.",
                    example = "-158000000")
            BigDecimal opexDelta,

            @Schema(description = "ΔFOT (экономия ФОТ), руб./год",
                    example = "160000000")
            BigDecimal deltaFot,

            @Schema(description = "Годовой эффект, руб.",
                    example = "150000000")
            BigDecimal effectYear,

            @Schema(description = "Интерпретация окупаемости",
                    example = "{\"paybackYears\": 0.1, \"category\": \"fast\"}")
            InterpretationDto payback,

            @Schema(description = "ROI за горизонт, %", example = "75.0")
            BigDecimal roiPct,

            @Schema(description = "TCO на горизонте, руб.",
                    example = "31000000")
            BigDecimal tco,

            @Schema(description = "Δ CAPEX к базовому, руб.",
                    example = "10000000")
            BigDecimal capexDeltaToBase,

            @Schema(description = "Δ TCO к базовому, руб.",
                    example = "-69000000")
            BigDecimal tcoDeltaToBase,

            @Schema(description = "Δ годового эффекта к базовому, руб.",
                    example = "150000000")
            BigDecimal effectDeltaToBase,

            @Schema(description = "Чувствительность сценария",
                    example = "[{\"parameter\": \"equipment\", \"deltaPct\": -20}]")
            List<SensitivityRowDto> sensitivity,

            @Schema(description = "Предупреждения расчёта",
                    example = "[\"Парк меньше требуемого - расчёт условный\"]")
            List<String> warnings,

            @Schema(description = "Версия данных последнего расчёта",
                    example = "3f2a9c1d8e4b")
            String versionData
    ) {
    }
}
