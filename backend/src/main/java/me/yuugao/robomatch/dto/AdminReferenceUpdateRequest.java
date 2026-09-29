package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Правка записи справочника: PUT /api/admin/references/{dictCode}/{id}.
 * <p>
 * Код менять можно (уникальность проверит сервис): конвенция кодов —
 * assumptions.md §20. Для process — переключение is_active
 * (деактивация вместо удаления).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Правка записи справочника (PUT /api/admin/references/{dictCode}/{id})")
public class AdminReferenceUpdateRequest {

    @Schema(description = "Код записи: латиница, snake_case, с буквы. "
            + "Обязателен для всех справочников, кроме vendor (у производителей "
            + "только название)", example = "medical_logistics", nullable = true)
    @Pattern(regexp = "^[a-z][a-z0-9_]*$",
            message = "Код: латинские буквы/цифры/подчёркивание, начинается с буквы")
    @Size(min = 1, max = 64, message = "Код: до 64 символов")
    private String code;

    @Schema(description = "Отображаемое имя", example = "Медицинская логистика")
    @NotBlank(message = "Название обязательно")
    @Size(min = 1, max = 256, message = "Название: до 256 символов")
    private String name;

    @Schema(description = "Активен (только для справочника процессов)",
            nullable = true, example = "true")
    private Boolean isActive;

    @Schema(description = "Группа (только для типов характеристик): identification | "
            + "technical | infrastructure | economic | applicability | data_quality",
            nullable = true, example = "technical",
            allowableValues = {"identification", "technical", "infrastructure",
                    "economic", "applicability", "data_quality"})
    private String groupCode;

    @Schema(description = "Тип значения (только для типов характеристик): "
            + "number | text | boolean | date", nullable = true, example = "number",
            allowableValues = {"number", "text", "boolean", "date"})
    private String dataType;

    @Schema(description = "Единица измерения (только для типов характеристик)",
            nullable = true, example = "кг")
    @Size(max = 32, message = "Единица измерения: до 32 символов")
    private String unit;
}
