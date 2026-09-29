package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Запрос создания проекта: POST /api/projects.
 * <p>
 * Границы полей (не заданы конкурсными материалами, зафиксированы как допущение команды -
 * имя 1–128, описание до 1024): согласованы с ограничениями UI и
 * здравым смыслом списка проектов. Статус в запросе НЕТ - новый проект
 * всегда draft (assumptions.md §18); владелец берётся из JWT, а не из
 * тела - прокинуть чужой user_id нельзя.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Запрос создания проекта")
public class ProjectCreateRequest {

    @Schema(description = "Имя проекта, 1-128 символов", example = "Склад в Химках")
    @NotBlank(message = "Имя проекта обязательно")
    @Size(min = 1, max = 128, message = "Имя проекта: до 128 символов")
    private String name;

    @Schema(description = "Описание, до 1024 символов",
            example = "Центральный склад, 3 зоны хранения")
    @Size(max = 1024, message = "Описание: до 1024 символов")
    private String description;

    @Schema(description = "Тип объекта (id из /api/filters, поле objectTypes)", example = "1")
    @NotNull(message = "Тип объекта обязателен")
    private Long objectTypeId;
}
