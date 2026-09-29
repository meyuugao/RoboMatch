package me.yuugao.robomatch.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Результат импорта таблицы каталога организатора
 *: счётчики добавлено/обновлено/пропущено по каждой сущности.
 * <p>
 * «Пропущено» — строка уже в БД с теми же значениями (идемпотентность:
 * повторный импорт того же файла даёт 0 добавлений, всё — skipped).
 * <p>
 * entities: solutions, applications, cases, case_links, vendors,
 * industries, regions, solution_types, solution_subtypes, processes.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Сводка импорта таблицы каталога организатора")
public class CatalogImportSummaryDto {

    @Schema(description = "Идентификатор записи истории импортов", example = "1")
    private Long importId;

    @Schema(description = "Имя файла, как его загрузил администратор", example = "catalog.csv")
    private String fileName;

    @Schema(description = "Статус импорта: completed | failed", example = "completed",
            allowableValues = {"completed", "failed"})
    private String status;

    @Schema(description = "Когда импорт начат", example = "2026-09-26T10:15:30Z")
    private Instant startedAt;

    @Schema(description = "Когда импорт завершён", example = "2026-09-26T10:15:31Z")
    private Instant finishedAt;

    @Schema(description = "Строк в файле (зерно = решение x применение)", example = "224")
    private int csvRows;

    @Schema(description = "Уникальных решений в файле (после дедупликации по id)", example = "187")
    private int solutions;

    @Schema(description = "Групп дублей (несколько строк на одно решение)", example = "23")
    private int dupGroups;

    @Schema(description = "Счётчики по сущностям: added/updated/skipped",
            example = "{\"solutions\": {\"added\": 0, \"updated\": 187, \"skipped\": 0}}")
    private Map<String, EntityCountersDto> entities;

    @Schema(description = "Предупреждения (не помешали импорту): конфликты цен, "
            + "объединение написаний", nullable = true,
            example = "[\"Цена отличается от предыдущего импорта: РТ-01\"]")
    private List<String> warnings;

    /**
 * Счётчики одной сущности.
 */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Счётчики одной сущности импорта")
    public static class EntityCountersDto {

        @Schema(description = "Добавлено новых строк", example = "0")
        private int added;

        @Schema(description = "Обновлено существующих (значения изменились)", example = "187")
        private int updated;

        @Schema(description = "Пропущено: строка уже актуальна", example = "0")
        private int skipped;

        /**
 * Увеличивает счётчик добавленных строк на 1.
 */
        public void incAdded() {
            added++;
        }

        /**
 * Увеличивает счётчик обновлённых строк на 1.
 */
        public void incUpdated() {
            updated++;
        }

        /**
 * Увеличивает счётчик пропущенных (актуальных) строк на 1.
 */
        public void incSkipped() {
            skipped++;
        }
    }
}
