package me.yuugao.robomatch.exception;

import java.util.List;

/**
 * Ошибка валидации входных данных расчёта экономики (economic_model.md
 * §6: отсутствие обязательных данных - расчёт не выполняется, выводится
 * список недостающего; - понятным языком со способом исправления).
 *
 * <p>Обрабатывается в GlobalExceptionHandler: HTTP 400, message - список
 * проблем через «; ».
 */
public class EconomicValidationException extends RuntimeException {

    /**
 * Создаёт исключение валидации данных расчёта.
 *
 * @param problems список недостающего/некорректного; склеивается
 * в message через «; » с подсказкой о исправлении
 */
    public EconomicValidationException(List<String> problems) {
        super(String.join("; ", problems)
                + (problems.isEmpty() ? "" : ". Заполните недостающие данные "
                                             + "и повторите расчёт."));
    }
}
