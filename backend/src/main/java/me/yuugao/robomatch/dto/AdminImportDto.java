package me.yuugao.robomatch.dto;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Строка истории импортов каталога (V7 admin_import_log).
 * <p>
 * summary - разобранный summary_json (у completed-импортов); у failed -
 * null, причина сбора ошибок отдавалась в момент 400-ответа.
 */
@Schema(description = "Строка истории импортов каталога")
@Getter
@AllArgsConstructor
public class AdminImportDto {

    @Schema(description = "Идентификатор импорта", example = "1")
    private final Long id;

    @Schema(description = "Имя файла", example = "catalog.csv")
    private final String fileName;

    @Schema(description = "Размер файла, байт", example = "215000")
    private final Long sizeBytes;

    @Schema(description = "Когда импорт начат", example = "2026-09-26T10:15:30Z")
    private final Instant startedAt;

    @Schema(description = "Когда импорт завершён (null у running)", nullable = true,
            example = "2026-09-26T10:15:31Z")
    private final Instant finishedAt;

    @Schema(description = "running | completed | failed", example = "completed",
            allowableValues = {"running", "completed", "failed"})
    private final String status;

    @Schema(description = "Счётчики импорта (из summary_json)", nullable = true,
            example = "{\"solutions\": 187, \"vendors\": 12}")
    private final Map<String, Object> summary;
}
