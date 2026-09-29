package me.yuugao.robomatch.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Результат массового удаления: удалённые идентификаторы и причины
 * отказов по каждой позиции. Удаление каждой записи независимо
 * (RESTRICT-ссылки из проектов - data_model.md §11 - дают отказ
 * с причиной, а не rollback всей партии).
 */
@Schema(description = "Результат массового удаления")
@Getter
@AllArgsConstructor
public class BulkDeleteResultDto {

    @Schema(description = "Удалённые идентификаторы", example = "[1, 2]")
    private final List<Long> deleted;

    @Schema(description = "Отказы: идентификатор и причина",
            nullable = true, example = "[{\"id\": 3, \"reason\": \"Решение "
            + "«X1» добавлено в сценарии проектов\"}]")
    private final List<FailureDto> failed;

    /**
 * Отказ по одной записи массового удаления.
 */
    @Schema(description = "Отказ по одной записи")
    @Getter
    @AllArgsConstructor
    public static class FailureDto {

        @Schema(description = "Идентификатор записи", example = "3")
        private final Long id;

        @Schema(description = "Причина отказа (ссылки из проектов, "
                + "запись не найдена)", example = "Решение «X1» добавлено "
                + "в сценарии проектов")
        private final String reason;
    }
}
