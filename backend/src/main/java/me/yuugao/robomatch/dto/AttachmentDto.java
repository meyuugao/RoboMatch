package me.yuugao.robomatch.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Вложение проекта в списке (GET /parameters/attachments): загруженные
 * исходники параметров (Excel/CSV) с размером и датой.
 * Путь в хранилище наружу НЕ отдаётся — деталь инфраструктуры.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Вложение проекта (исходник параметров)")
public class AttachmentDto {

    @Schema(description = "Идентификатор вложения", example = "7")
    private Long id;

    @Schema(description = "Имя файла при загрузке", example = "склад_параметры.xlsx")
    private String fileName;

    @Schema(description = "MIME-тип", example = "text/csv", nullable = true)
    private String mimeType;

    @Schema(description = "Размер в байтах", example = "20480", nullable = true)
    private Long sizeBytes;

    @Schema(description = "Когда загружено", example = "2026-09-26T10:15:30Z")
    private Instant uploadedAt;
}
