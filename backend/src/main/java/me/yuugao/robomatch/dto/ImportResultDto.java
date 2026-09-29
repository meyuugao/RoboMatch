package me.yuugao.robomatch.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Успешный результат импорта параметров (POST /parameters/import):
 * сколько значений записано, каким вложением и предупреждения
 * (например, лишние строки файла или обязательные параметры, всё ещё
 * оставшиеся без значения).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Результат импорта параметров проекта")
public class ImportResultDto {

    @Schema(description = "Сколько значений параметров записано", example = "12")
    private int importedCount;

    @Schema(description = "Идентификатор созданного вложения (исходник в истории)",
            example = "7")
    private Long attachmentId;

    @Schema(description = "Предупреждения (не помешали импорту)", nullable = true,
            example = "[\"Колонка extra_col проигнорирована: нет такого параметра\"]")
    private List<String> warnings;
}
