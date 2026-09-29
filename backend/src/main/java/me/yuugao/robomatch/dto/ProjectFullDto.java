package me.yuugao.robomatch.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Карточка проекта (GET /api/projects/{id}).
 * <p>
 * Списочных полей (summary-поля) + тип объекта с кодом и признаком
 * isCalcEnabled (гейт расчёта — MVP: только склад, data_model.md §11).
 * Счётчики параметров и сценариев НЕ включены — постоянный 0
 * вводил бы в заблуждение (карточка дочерние сущности не агрегирует).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Карточка проекта")
public class ProjectFullDto {

    @Schema(description = "Идентификатор проекта", example = "1")
    private Long id;

    @Schema(description = "Имя проекта", example = "Склад в Химках")
    private String name;

    @Schema(description = "Описание", example = "Центральный склад, 3 зоны хранения")
    private String description;

    @Schema(description = "Идентификатор типа объекта", example = "1")
    private Long objectTypeId;

    @Schema(description = "Название типа объекта", example = "Склад")
    private String objectTypeName;

    @Schema(description = "Код типа объекта: warehouse | airport | hospital",
            example = "warehouse", allowableValues = {"warehouse", "airport", "hospital"})
    private String objectTypeCode;

    @Schema(description = "Разрешён ли расчёт экономики для этого типа объекта (в текущей версии — склад)",
            example = "true")
    private Boolean objectTypeIsCalcEnabled;

    @Schema(description = "Статус: draft | active | archived", example = "draft",
            allowableValues = {"draft", "active", "archived"})
    private String status;

    @Schema(description = "Когда создан", example = "2026-09-26T10:15:30Z")
    private Instant createdAt;

    @Schema(description = "Когда последний раз изменён", example = "2026-09-26T12:00:00Z")
    private Instant updatedAt;
}
