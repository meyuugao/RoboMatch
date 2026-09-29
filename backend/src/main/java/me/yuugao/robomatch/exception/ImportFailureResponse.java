package me.yuugao.robomatch.exception;

import me.yuugao.robomatch.dto.ImportErrorDto;

import java.time.Instant;
import java.util.List;

/**
 * Тело 400-ответа при ошибках валидации файла импорта:
 * единый каркас ErrorResponse + построчный список ошибок errors[].
 * Ошибки собираются по ВСЕМУ файлу до записи в БД — ничего не
 * сохраняется, пока файл не валиден целиком (атомарность импорта).
 *
 * @param timestamp момент отклонения файла на сервере
 * @param status HTTP-код (400)
 * @param error короткое имя кода ("Bad Request")
 * @param message итоговое описание — что с файлом не так
 * @param errors построчные ошибки (до 20 в теле, остальные — в лог)
 */
public record ImportFailureResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<ImportErrorDto> errors
) {
}
