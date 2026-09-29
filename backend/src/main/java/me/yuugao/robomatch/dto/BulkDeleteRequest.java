package me.yuugao.robomatch.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Массовое удаление записей по списку идентификаторов:
 * POST /api/admin/solutions/bulk-delete и
 * POST /api/admin/references/{dictCode}/bulk-delete.
 * <p>
 * Каждая позиция обрабатывается независимо: отказ одной записи
 * (ссылки из проектов, отсутствие) не останавливает остальные;
 * дубликаты идентификаторов игнорируются.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Массовое удаление: список идентификаторов")
public class BulkDeleteRequest {

    @Schema(description = "Идентификаторы записей (до 100 за один запрос)",
            example = "[1, 2, 3]")
    @NotEmpty(message = "Список идентификаторов не может быть пустым")
    @Size(max = 100, message = "За один запрос - не более 100 идентификаторов")
    private List<Long> ids;
}
