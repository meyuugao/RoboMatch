package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/**
 * Тело POST /api/projects/{id}/calculations/{calculationId}/adjust —
 * ручная корректировка метрики (
 * почему / кто / когда»; автор и время — сервер из JWT и clock).
 *
 * <p>Порождает НОВЫЙ расчёт (append-only, data_model.md §10.10):
 * скорректированное значение фиксируется в новом calculation, исходный
 * остаётся в истории; сама запись о вмешательстве — в manual_adjustment.
 *
 * @param metricName код метрики
 * @param newValue новое значение метрики
 * @param reason причина корректировки (обязательна)
 */
@Schema(description = "Ручная корректировка метрики расчёта")
public record ManualAdjustRequest(
        @Schema(description = "Код метрики", example = "effect_year",
                allowableValues = {"total_capex", "total_opex",
                        "opex_delta_rub", "effect_year", "payback_years",
                        "roi_pct", "tco_rub"})
        @NotNull(message = "Укажите метрику (metricName)")
        @Pattern(regexp = "total_capex|total_opex|opex_delta_rub|effect_year"
                + "|payback_years|roi_pct|tco_rub",
                message = "Метрика: total_capex, total_opex, opex_delta_rub, "
                        + "effect_year, payback_years, roi_pct или tco_rub")
        String metricName,

        @Schema(description = "Новое значение метрики", example = "145000000")
        @NotNull(message = "Укажите новое значение (newValue)")
        @DecimalMin(value = "0", message = "Значение метрики — неотрицательное")
        @Digits(integer = 16, fraction = 4,
                message = "Значение метрики — до 16 целых разрядов "
                        + "и 4 дробных")
        BigDecimal newValue,

        @Schema(description = "Причина корректировки (обязательна,)",
                example = "Согласована скидка вендора 8% на сервис")
        @NotBlank(message = "Укажите причину корректировки — она попадёт в отчёт")
        @Size(max = 1024, message = "Причина — до 1024 символов")
        String reason
) {
}
