package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Допущение экономики в ответе: текущее значение =
 * переопределение пользователя или дефолт каталога (assumptions.md
 * §22-23); диапазон и провенанс — для формы редактора.
 *
 * @param name код допущения
 * @param title название для пользователя
 * @param value текущее значение (переопределение или дефолт)
 * @param defaultValue дефолт каталога
 * @param unit единица измерения
 * @param kind тип значения: number | enum | boolean
 * @param min минимум диапазона (если задан)
 * @param max максимум диапазона (если задан)
 * @param editable редактируется пользователем
 * @param sourceKind источник: organizer_catalog | manual
 * @param impactNote влияние на результат (уточнения организатора)
 */
@Schema(description = "Допущение расчёта экономики")
public record AssumptionDto(
        @Schema(description = "Код допущения", example = "k_load")
        String name,

        @Schema(description = "Название для пользователя",
                example = "Коэффициент загрузки робота (K_load)")
        String title,

        @Schema(description = "Текущее значение (переопределение или дефолт)",
                example = "0.75")
        String value,

        @Schema(description = "Дефолт каталога допущений",
                example = "0.75")
        String defaultValue,

        @Schema(description = "Единица измерения", example = "доля")
        String unit,

        @Schema(description = "Тип значения: number | enum | boolean",
                example = "number", allowableValues = {"number", "enum", "boolean"})
        String kind,

        @Schema(description = "Минимум диапазона (если задан)", example = "0.7")
        BigDecimal min,

        @Schema(description = "Максимум диапазона (если задан)", example = "0.85")
        BigDecimal max,

        @Schema(description = "Допущение редактируется пользователем "
                + "(reserve_rate зафиксирован организатором)", example = "true")
        boolean editable,

        @Schema(description = "Источник: organizer_catalog | manual",
                example = "organizer_catalog",
                allowableValues = {"organizer_catalog", "manual"})
        String sourceKind,

        @Schema(description = "Влияние на результат (уточнения организатора)",
                example = "Влияет на годовой эффект и окупаемость")
        String impactNote
) {
}
