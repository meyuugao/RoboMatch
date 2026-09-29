package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Типизированное значение параметра в ответах API: {kind, value}.
 * kind повторяет parameter_type.value_type (number/text/bool), value —
 * само значение (number | string | boolean; null — значение не задано).
 * <p>
 * Единая форма для defaultValue и currentValue: клиенту не нужно знать,
 * в какой из трёх колонок EAV лежит значение (конвенция data_model.md
 * §10.3 «ровно одно value_* NOT NULL» наружу не протекает).
 *
 * @param kind тип значения: number | text | bool
 * @param value само значение (null — значение не задано)
 */
@Schema(description = "Типизированное значение параметра")
public record TypedValueDto(
        @Schema(description = "Тип значения", example = "number",
                allowableValues = {"number", "text", "bool"})
        String kind,

        @Schema(description = "Значение (null — не задано)", example = "5000")
        Object value
) {
}
