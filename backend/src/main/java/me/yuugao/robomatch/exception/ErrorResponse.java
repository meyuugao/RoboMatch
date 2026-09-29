package me.yuugao.robomatch.exception;

import java.time.Instant;

/**
 * Единая форма тела ошибки API. Клиент всегда видит одинаковую структуру:
 * {"timestamp": "...", "status": 404, "error": "Not Found", "message": "..."}.
 * <p>
 * Такой контракт упрощает обработку ошибок на фронтенде и логирование.
 *
 * @param timestamp момент ошибки на сервере
 * @param status HTTP-код (404, 409, 500...)
 * @param error короткое имя кода ("Not Found")
 * @param message понятное пользователю описание
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message
) {
}
