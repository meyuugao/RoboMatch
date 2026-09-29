package me.yuugao.robomatch.exception;

import me.yuugao.robomatch.dto.ImportErrorDto;

import java.util.List;

import lombok.Getter;

/**
 * Ошибки валидации файла импорта параметров: список по всем
 * некорректным ячейкам. Глобальный обработчик отвечает 400 с телом
 * ImportFailureResponse (до 20 ошибок в теле, остальные - в лог).
 * Бросается ДО любых изменений БД - атомарность импорта.
 */
@Getter
public class ImportException extends RuntimeException {

    /**
 * Ограничение списка ошибок в теле ответа (остальное - в лог).
 */
    public static final int MAX_ERRORS_IN_RESPONSE = 20;

    /**
 * Построчные ошибки валидации (копия, неизменяемый список).
 */
    private final List<ImportErrorDto> errors;

    /**
 * Создаёт исключение по итогам валидации файла (до записи в БД).
 *
 * @param message итоговое описание - что с файлом не так
 * @param errors построчные ошибки (список копируется, ссылка не хранится)
 */
    public ImportException(String message, List<ImportErrorDto> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }
}
