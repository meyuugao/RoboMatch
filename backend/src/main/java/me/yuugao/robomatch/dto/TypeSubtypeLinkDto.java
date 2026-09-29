package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Пара «тип-подтип» из solution_type_subtype_mapping: допустимые
 * сочетания для зависимых списков фильтра каталога.
 */
@Getter
@AllArgsConstructor
@Schema(description = "Допустимое сочетание тип-подтип")
public class TypeSubtypeLinkDto {

    @Schema(description = "Идентификатор типа", example = "1")
    private final Long typeId;

    @Schema(description = "Идентификатор подтипа, уместного для этого типа", example = "3")
    private final Long subtypeId;
}
