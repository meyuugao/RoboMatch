package me.yuugao.robomatch.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Выгрузка в истории проекта: элемент ответа
 * GET /api/projects/{id}/exports и результат POST-генерации.
 * Файл отдаёт GET /api/projects/{id}/exports/{exportId}
 * (downloadUrl, Content-Disposition attachment).
 *
 * @param id идентификатор выгрузки
 * @param format формат: pdf | xlsx | csv
 * @param fileName имя файла для скачивания
 * @param sizeBytes размер файла, байт
 * @param createdAt момент генерации
 * @param createdByLogin логин автора выгрузки
 * @param downloadUrl ссылка скачивания (BFF-путь)
 */
@Schema(description = "Выгрузка отчёта")
public record ExportDto(
        @Schema(description = "Идентификатор выгрузки", example = "3")
        Long id,

        @Schema(description = "Формат: pdf | xlsx | csv", example = "pdf",
                allowableValues = {"pdf", "xlsx", "csv"})
        String format,

        @Schema(description = "Имя файла для скачивания",
                example = "RoboMatch_Мой склад_20260924-1530.pdf")
        String fileName,

        @Schema(description = "Размер файла, байт", example = "148320")
        long sizeBytes,

        @Schema(description = "Момент генерации", example = "2026-09-26T15:30:00Z")
        Instant createdAt,

        @Schema(description = "Логин автора выгрузки", example = "user")
        String createdByLogin,

        @Schema(description = "Ссылка скачивания (BFF-путь)",
                example = "/api/projects/3/exports/7")
        String downloadUrl
) {
}
