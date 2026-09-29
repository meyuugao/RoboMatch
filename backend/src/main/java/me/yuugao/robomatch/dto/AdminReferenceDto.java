package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Запись справочника в админке: универсальный вид всех 7 справочников.
 * code — NULL у vendor (у производителей только имя); isActive — только
 * у process; groupCode/dataType/unit — только у characteristic_type.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Запись справочника в админке (все 7 справочников)")
public class AdminReferenceDto {

    @Schema(description = "Идентификатор записи", example = "42")
    private Long id;

    @Schema(description = "Технический код (латиница snake_case); нет у vendor",
            example = "medical_logistics", nullable = true)
    private String code;

    @Schema(description = "Отображаемое имя", example = "Медицинская логистика")
    private String name;

    @Schema(description = "Активен (только process)", nullable = true, example = "true")
    private Boolean isActive;

    @Schema(description = "Группа (только characteristic_type)", nullable = true,
            example = "technical",
            allowableValues = {"identification", "technical", "infrastructure",
                    "economic", "applicability", "data_quality"})
    private String groupCode;

    @Schema(description = "Тип значения (только characteristic_type)", nullable = true,
            example = "number", allowableValues = {"number", "text", "boolean", "date"})
    private String dataType;

    @Schema(description = "Единица измерения (только characteristic_type)",
            nullable = true, example = "кг")
    private String unit;
}
