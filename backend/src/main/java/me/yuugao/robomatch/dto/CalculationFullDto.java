package me.yuugao.robomatch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Детальный расчёт (
 * чувствительность; — воспроизведение по версиям). details —
 * разобранный metrics_json: разбивки CAPEX/OPEX по статьям, состав,
 * чувствительность, предупреждения.
 *
 * @param id идентификатор расчёта
 * @param scenarioId идентификатор сценария
 * @param versionData версия исходных данных
 * @param versionModel версия расчётной модели
 * @param calculatedAt дата и время расчёта
 * @param totalCapex CAPEX, руб.
 * @param totalOpex годовой OPEX, руб.
 * @param opexDeltaRub изменение OPEX к базовому, руб.
 * @param effectYear годовой эффект, руб.
 * @param paybackYears окупаемость, лет
 * @param roiPct ROI, %
 * @param tcoRub TCO, руб.
 * @param details разбивка расчёта (metrics_json)
 * @param assumptions снимок допущений расчёта
 * @param adjustments ручные корректировки этого расчёта
 */
@Schema(description = "Детальный расчёт экономики")
public record CalculationFullDto(
        @Schema(description = "Идентификатор расчёта", example = "7")
        Long id,

        @Schema(description = "Идентификатор сценария", example = "2")
        Long scenarioId,

        @Schema(description = "Версия исходных данных", example = "3f2a9c1d8e4b")
        String versionData,

        @Schema(description = "Версия расчётной модели", example = "economic-model-1.0")
        String versionModel,

        @Schema(description = "Дата и время расчёта", example = "2026-09-26T10:15:30Z")
        Instant calculatedAt,

        @Schema(description = "CAPEX, руб.", example = "10000000")
        BigDecimal totalCapex,

        @Schema(description = "Годовой OPEX, руб.", example = "4200000")
        BigDecimal totalOpex,

        @Schema(description = "Изменение OPEX к базовому, руб.",
                example = "-158000000")
        BigDecimal opexDeltaRub,

        @Schema(description = "Годовой эффект, руб.", example = "150000000")
        BigDecimal effectYear,

        @Schema(description = "Окупаемость, лет", example = "0.1")
        BigDecimal paybackYears,

        @Schema(description = "ROI, %", example = "75.0")
        BigDecimal roiPct,

        @Schema(description = "TCO, руб.", example = "31000000")
        BigDecimal tcoRub,

        @Schema(description = "Разбивка расчёта (metrics_json): статьи "
                + "CAPEX/OPEX, состав, чувствительность, предупреждения",
                example = "{\"capex_items\": [{\"title\": \"Роботы\", "
                        + "\"amount\": 10000000}]}")
        Map<String, Object> details,

        @Schema(description = "Снимок допущений расчёта",
                example = "[{\"name\": \"k_load\", \"value\": \"0.75\"}]")
        List<AssumptionSnapshotDto> assumptions,

        @Schema(description = "Ручные корректировки этого расчёта",
                example = "[{\"metricName\": \"effect_year\", "
                        + "\"newValue\": 145000000}]")
        List<AdjustmentDto> adjustments
) {

    /**
 * Строка снимка допущения (что / значение / источник / влияние).
 *
 * @param name код допущения
 * @param title название для пользователя
 * @param value значение в расчёте
 * @param unit единица
 * @param sourceKind источник: organizer_catalog | manual
 * @param impactNote влияние на результат
 */
    @Schema(description = "Допущение из снимка расчёта")
    public record AssumptionSnapshotDto(
            @Schema(description = "Код допущения", example = "k_load")
            String name,

            @Schema(description = "Название для пользователя",
                    example = "Коэффициент загрузки робота (K_load)")
            String title,

            @Schema(description = "Значение в расчёте", example = "0.75")
            String value,

            @Schema(description = "Единица", example = "доля")
            String unit,

            @Schema(description = "Источник: organizer_catalog | manual",
                    example = "organizer_catalog",
                    allowableValues = {"organizer_catalog", "manual"})
            String sourceKind,

            @Schema(description = "Влияние на результат",
                    example = "Влияет на годовой эффект и окупаемость")
            String impactNote
    ) {
    }

    /**
 * Запись о ручной корректировке.
 *
 * @param metricName метрика
 * @param originalValue было
 * @param newValue стало
 * @param reason причина
 * @param authorLogin автор (пользователь)
 * @param createdAt когда
 */
    @Schema(description = "Ручная корректировка: что/было/стало/почему/кто/когда")
    public record AdjustmentDto(
            @Schema(description = "Метрика", example = "effect_year")
            String metricName,

            @Schema(description = "Было", example = "150000000")
            BigDecimal originalValue,

            @Schema(description = "Стало", example = "145000000")
            BigDecimal newValue,

            @Schema(description = "Причина", example = "Скидка вендора")
            String reason,

            @Schema(description = "Автор (пользователь)", example = "demo")
            String authorLogin,

            @Schema(description = "Когда", example = "2026-09-26T10:15:30Z")
            Instant createdAt
    ) {
    }
}
