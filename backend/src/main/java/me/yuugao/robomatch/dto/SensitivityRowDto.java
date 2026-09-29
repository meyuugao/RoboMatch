package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Строка таблицы чувствительности:
 * «Δ параметра → Δ показателя» для одного параметра и одного шага Δ%.
 *
 * @param parameter параметр: equipment | operations | labor
 * @param deltaPct изменение параметра, %
 * @param effectYear годовой эффект при этом Δ, руб.
 * @param effectDelta изменение эффекта относительно базового расчёта, руб.
 * @param paybackYears окупаемость, лет
 * @param roiPct ROI, %
 * @param note примечание к строке
 */
@Schema(description = "Строка чувствительности: Δ параметра → показатели")
public record SensitivityRowDto(
        @Schema(description = "Параметр: equipment | operations | labor",
                example = "equipment",
                allowableValues = {"equipment", "operations", "labor"})
        String parameter,

        @Schema(description = "Изменение параметра, %", example = "-20")
        BigDecimal deltaPct,

        @Schema(description = "Годовой эффект при этом Δ, руб.",
                example = "132000000")
        BigDecimal effectYear,

        @Schema(description = "Изменение эффекта относительно базового "
                + "расчёта, руб.", example = "-18000000")
        BigDecimal effectDelta,

        @Schema(description = "Окупаемость, лет (null - не окупается)",
                example = "0.1")
        BigDecimal paybackYears,

        @Schema(description = "ROI, % (null - не определён)", example = "660.0")
        BigDecimal roiPct,

        @Schema(description = "Примечание к строке (например, линейное "
                + "масштабирование парка при незаданной номинальной "
                + "производительности)", nullable = true,
                example = "Парк масштабирован линейно - P_nominal не задан")
        String note
) {
}
