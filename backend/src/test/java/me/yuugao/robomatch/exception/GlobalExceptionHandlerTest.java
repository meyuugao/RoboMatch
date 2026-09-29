package me.yuugao.robomatch.exception;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.dto.ImportErrorDto;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit-тест GlobalExceptionHandler на ограничение списка ошибок импорта:
 * в тело 400-ответа уходит максимум ImportException.MAX_ERRORS_IN_RESPONSE
 * строк, остальные остаются в серверном логе (;
 * случай >20 ошибок покрывается здесь).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void importErrorList_cappedAtTwenty() {
        List<ImportErrorDto> errors = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            errors.add(new ImportErrorDto(i, 1, "code_" + i, "ошибка " + i));
        }
        ImportException exception = new ImportException("Файл содержит ошибки (25)", errors);

        ResponseEntity<ImportFailureResponse> response = handler.handleImport(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errors()).hasSize(ImportException.MAX_ERRORS_IN_RESPONSE);
        // первые ошибки, не хвост
        assertThat(response.getBody().errors().get(0).parameterCode()).isEqualTo("code_1");
    }

    @Test
    void importErrorList_smallPassesThrough() {
        List<ImportErrorDto> errors = List.of(new ImportErrorDto(2, 3, "shifts_per_day",
                "Введите число от 1 до 4."));
        ResponseEntity<ImportFailureResponse> response =
                handler.handleImport(new ImportException("Файл содержит ошибки (1)", errors));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().errors()).hasSize(1);
        assertThat(response.getBody().errors().get(0).row()).isEqualTo(2);
    }
}
