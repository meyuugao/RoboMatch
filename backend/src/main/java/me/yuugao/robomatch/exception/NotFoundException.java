package me.yuugao.robomatch.exception;

/**
 * Исключение «не найдено». Бросается в сервисе, а переводится в HTTP 404
 * глобальным обработчиком GlobalExceptionHandler.
 * <p>
 * Своё исключение вместо ResponseNotFoundException из Spring — чтобы код
 * не зависел от web-классов в сервисном слое (слои не должны знать про HTTP).
 */
public class NotFoundException extends RuntimeException {

    /**
 * Создаёт исключение «не найдено».
 *
 * @param message что именно не найдено (готово для ErrorResponse)
 */
    public NotFoundException(String message) {
        super(message);
    }
}
