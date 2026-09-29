package me.yuugao.robomatch.exception;

/**
 * Конфликт состояния: логин уже занят (HTTP 409).
 * <p>
 * Бросается сервисом при регистрации, в том числе когда гонку двух
 * одновременных регистраций ловит UNIQUE-индекс БД
 * (DataIntegrityViolationException -> это исключение).
 */
public class ConflictException extends RuntimeException {

    /**
 * Создаёт исключение конфликта состояния.
 *
 * @param message готовый для показа пользователю текст конфликта
 */
    public ConflictException(String message) {
        super(message);
    }
}
