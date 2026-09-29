package me.yuugao.robomatch.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Элемент списка проектов (GET /api/projects).
 * <p>
 * Зеркало SolutionSummaryDto: «голых» object_type_id в списке нет —
 * только русское имя справочника; сортировка списка — по updatedAt
 * (последние изменённые сверху).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Проект в списке")
public class ProjectSummaryDto {

    @Schema(description = "Идентификатор проекта", example = "1")
    private Long id;

    @Schema(description = "Имя проекта (уникально в рамках пользователя)",
            example = "Склад в Химках")
    private String name;

    @Schema(description = "Описание", example = "Центральный склад, 3 зоны хранения")
    private String description;

    @Schema(description = "Название типа объекта", example = "Склад")
    private String objectTypeName;

    @Schema(description = "Статус: draft | active | archived", example = "draft",
            allowableValues = {"draft", "active", "archived"})
    private String status;

    @Schema(description = "Когда создан", example = "2026-09-26T10:15:30Z")
    private Instant createdAt;

    @Schema(description = "Когда последний раз изменён", example = "2026-09-26T12:00:00Z")
    private Instant updatedAt;
}
