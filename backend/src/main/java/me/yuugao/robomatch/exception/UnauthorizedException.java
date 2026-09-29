package me.yuugao.robomatch.exception;

/**
 * Не авторизован (HTTP 401): неверный логин/пароль или недействительный
 * токен. Сообщение единое и не раскрывает, что именно неверно.
 */
public class UnauthorizedException extends RuntimeException {

    /**
 * Создаёт исключение аутентификации.
 *
 * @param message текст ошибки (не раскрывает, что именно неверно)
 */
    public UnauthorizedException(String message) {
        super(message);
    }
}
