package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Запрос редактирования проекта: PUT /api/projects/{id}.
 * <p>
 * Тип объекта при редактировании НЕ меняется: тип определяет весь
 * набор параметров проекта (object_type_parameter), смена типа после
 * создания обесценила бы уже введённые значения — правильнее скопировать
 * проект и выбрать новый тип. Статус тоже не
 * меняется этим эндпоинтом (переключение lifecycle — отдельная операция).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Запрос правки проекта")
public class ProjectUpdateRequest {

    @Schema(description = "Имя проекта, 1-128 символов", example = "Склад в Химках, зона А")
    @NotBlank(message = "Имя проекта обязательно")
    @Size(min = 1, max = 128, message = "Имя проекта: до 128 символов")
    private String name;

    @Schema(description = "Описание, до 1024 символов",
            example = "Центральный склад, 3 зоны хранения, новая линия")
    @Size(max = 1024, message = "Описание: до 1024 символов")
    private String description;
}
