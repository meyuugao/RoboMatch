package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Одна ошибка импорта Excel/CSV: где именно в файле
 * ошибка (строка/колонка), какой параметр и что исправить. Координаты -
 * человеческие, с 1 (как в Excel).
 *
 * @param row номер строки файла (с 1, как в Excel)
 * @param column номер колонки файла (с 1)
 * @param parameterCode код параметра (заголовок колонки)
 * @param message что не так и как исправить
 */
@Schema(description = "Ошибка импорта: строка, колонка, параметр, причина")
public record ImportErrorDto(

        @Schema(description = "Номер строки файла (с 1, как в Excel)", example = "2")
        int row,

        @Schema(description = "Номер колонки файла (с 1)", example = "3")
        int column,

        @Schema(description = "Код параметра (заголовок колонки)", example = "shifts_per_day",
                nullable = true)
        String parameterCode,

        @Schema(description = "Что не так и как исправить",
                example = "Значение не число: ожидался numeric")
        String message
) {
}
