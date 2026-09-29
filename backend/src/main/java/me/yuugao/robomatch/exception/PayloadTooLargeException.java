package me.yuugao.robomatch.exception;

/**
 * Тело запроса больше допустимого лимита (413). Отдельное исключение,
 * а не переиспользование BadRequestException: код 413 требует и
 * -паттерн понятных ошибок (пользователю нужно «уменьшите файл», а не
 * «некорректный запрос»), и слой servlet multipart (MaxUploadSize
 * ExceededException) маппится в тот же код — единый смысл «слишком
 * много данных».
 */
public class PayloadTooLargeException extends RuntimeException {

    /**
 * Создаёт исключение превышения размера.
 *
 * @param message подсказка — как уменьшить объём запроса
 */
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
