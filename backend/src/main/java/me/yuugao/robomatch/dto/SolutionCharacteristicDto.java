package me.yuugao.robomatch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * ТТХ решения из EAV (solution_characteristic + characteristic_type).
 * <p>
 *
 * подтверждённости. Значение приходит в одном из полей value*
 * (тип определяет characteristic_type.data_type), UI выбирает
 * непустое. Метаданные (typeName, unit) — из справочника типов.
 */
@Getter
@Setter
@AllArgsConstructor
@Builder
@Schema(description = "ТТХ решения из EAV (с провенансом)")
public class SolutionCharacteristicDto {

    @Schema(description = "Идентификатор решения, к которому относится ТТХ", example = "42")
    private Long solutionId;

    @Schema(description = "Код типа характеристики", example = "payload_kg")
    private String typeCode;

    @Schema(description = "Название характеристики", example = "Грузоподъёмность")
    private String typeName;

    @Schema(description = "Группа характеристики", example = "technical",
            allowableValues = {"identification", "technical", "infrastructure",
                    "economic", "applicability", "data_quality"})
    private String groupCode;

    @Schema(description = "Единица измерения", example = "кг")
    private String unit;

    @Schema(description = "Тип значения: number | text | boolean | date", example = "number",
            allowableValues = {"number", "text", "boolean", "date"})
    private String dataType;

    @Schema(description = "Числовое значение (для data_type=number)", example = "1500")
    private BigDecimal valueNumeric;

    @Schema(description = "Текстовое значение (для data_type=text)",
            example = "Литиевая батарея")
    private String valueText;

    @Schema(description = "Булево значение (для data_type=boolean)", example = "true")
    private Boolean valueBool;

    @Schema(description = "Дата (для data_type=date)", example = "2026-09-26")
    private LocalDate valueDate;

    @Schema(description = "Источник данных ТТХ", example = "open_source",
            allowableValues = {"organizer_catalog", "open_source", "manual"})
    private String sourceKind;

    @Schema(description = "Ссылка на источник",
            example = "https://example.com/datasheet.pdf")
    private String sourceUrl;

    @Schema(description = "Дата актуальности источника", example = "2026-09-26")
    private LocalDate sourceDate;

    @Schema(description = "Подтверждена ли характеристика производителем", example = "true")
    private Boolean isConfirmed;
}
