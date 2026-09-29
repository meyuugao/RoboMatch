package me.yuugao.robomatch.exception;

/**
 * Некорректные параметры запроса (400): невалидный sortBy, отрицательная
 * страница, диапазон min > max и т.п. Сообщение готово для показа
 * пользователю и попадает в ErrorResponse как есть.
 */
public class BadRequestException extends RuntimeException {

    /**
 * Создаёт исключение с готовым текстом ошибки.
 *
 * @param message готовый для показа пользователю текст
 */
    public BadRequestException(String message) {
        super(message);
    }
}
