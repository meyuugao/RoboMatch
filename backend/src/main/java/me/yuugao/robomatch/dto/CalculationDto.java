package me.yuugao.robomatch.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Расчёт в списке истории (
 * сортировка calculated_at DESC).
 *
 * @param id идентификатор расчёта
 * @param scenarioId идентификатор сценария
 * @param versionData версия исходных данных (SHA-256 снимка)
 * @param versionModel версия расчётной модели
 * @param calculatedAt дата и время расчёта
 * @param totalCapex CAPEX, руб.
 * @param totalOpex годовой OPEX, руб.
 * @param opexDeltaRub изменение OPEX к базовому, руб. (отрицательное - экономия)
 * @param effectYear годовой эффект, руб.
 * @param paybackYears срок окупаемости, лет (null - не окупается)
 * @param roiPct ROI за горизонт, %
 * @param tcoRub TCO на горизонте, руб.
 * @param adjusted скорректирован вручную
 */
@Schema(description = "Расчёт экономики (строка истории)")
public record CalculationDto(
        @Schema(description = "Идентификатор расчёта", example = "7")
        Long id,

        @Schema(description = "Идентификатор сценария", example = "2")
        Long scenarioId,

        @Schema(description = "Версия исходных данных (SHA-256 снимка)",
                example = "3f2a9c1d8e4b")
        String versionData,

        @Schema(description = "Версия расчётной модели", example = "economic-model-1.0")
        String versionModel,

        @Schema(description = "Дата и время расчёта", example = "2026-09-26T10:15:30Z")
        Instant calculatedAt,

        @Schema(description = "CAPEX, руб.", example = "10000000")
        BigDecimal totalCapex,

        @Schema(description = "Годовой OPEX, руб.", example = "4200000")
        BigDecimal totalOpex,

        @Schema(description = "Изменение OPEX к базовому, руб. "
                + "(отрицательное - экономия)", example = "-158000000")
        BigDecimal opexDeltaRub,

        @Schema(description = "Годовой эффект, руб.", example = "150000000")
        BigDecimal effectYear,

        @Schema(description = "Срок окупаемости, лет (null - не окупается "
                + "или не определён)", example = "0.1")
        BigDecimal paybackYears,

        @Schema(description = "ROI за горизонт, %", example = "75.0")
        BigDecimal roiPct,

        @Schema(description = "TCO на горизонте, руб.", example = "31000000")
        BigDecimal tcoRub,

        @Schema(description = "Скорректирован вручную",
                example = "false")
        boolean adjusted
) {
}
