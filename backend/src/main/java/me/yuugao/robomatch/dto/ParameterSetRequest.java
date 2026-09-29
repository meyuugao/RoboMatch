package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Тело PUT /api/projects/{id}/parameters/{parameterId}: {"value": ...}.
 * <p>
 * value — нетипизированный JSON (число | строка | логическое | null):
 * конкретный ожидаемый тип знает parameter_type.value_type, и сервис
 * валидирует соответствие. null для обязательного параметра —
 * 400 с подсказкой; для сброса значения есть DELETE.
 *
 * @param value значение параметра (тип — как у параметра в списке)
 */
@Schema(description = "Установка значения параметра")
public record ParameterSetRequest(

        @Schema(description = "Значение параметра (тип — как у параметра в списке); "
                + "null — отклоняется, для сброса используйте DELETE",
                example = "5000", nullable = true)
        Object value
) {
}
