package me.yuugao.robomatch.exception;

import me.yuugao.robomatch.dto.ImportErrorDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Глобальный обработчик ошибок (@RestControllerAdvice).
 * <p>
 * Одна точка, где исключения переводятся в HTTP-ответы единого формата
 * ErrorResponse (timestamp/status/error/message) -
 * понятным языком.
 * <p>
 * Карта кодов:
 * - 400 MethodArgumentNotValidException - невалидное тело (@Valid);
 * - 400 BadRequestException - невалидные параметры запроса
 * (белый список sortBy, диапазоны, границы страницы);
 * - 400 EconomicValidationException - расчёт экономики: недостающие
 * обязательные параметры/допущения, нулевые знаменатели
 * (economic_model.md §6);
 * - 400 MethodArgumentTypeMismatchException - параметр не распарсился
 * (например, typeId=abc); без хендлера уходил бы в 500;
 * - 401 UnauthorizedException - неверный логин/пароль, нет токена;
 * - 403 AccessDeniedException - роли не хватает (метод-секьюрити);
 * - 404 NotFoundException - сущность не найдена;
 * - 404 NoResourceFoundException - путь не существует (без этого
 * хендлера catch-all вернул бы 500 - особенность Boot 4);
 * - 409 ConflictException - логин занят;
 * - 400 ImportException - ошибки валидации файла импорта параметров
 * (построчный список, до 20 в теле -);
 * - 413 PayloadTooLargeException / MaxUploadSizeExceededException - файл
 * импорта больше лимита (сервисная и servlet-проверка);
 * - 500 прочее - без внутренностей наружу.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
 * Серверный лог: тип и стектрейс - для разработчиков, не для ответа.
 */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message);
        return ResponseEntity.status(status).body(body);
    }

    /**
 * 400: невалидное тело @Valid - сообщения полей через «; ».
 *
 * @param ex исключение валидации тела запроса
 * @return 400 с ErrorResponse
 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // Первое нарушение: сообщение конкретного поля (задано в DTO),
        // без технического шума вроде "default message".
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Некорректные данные запроса";
        }
        return respond(HttpStatus.BAD_REQUEST, message);
    }

    /**
 * 400: невалидные параметры запроса (BadRequestException).
 *
 * @param ex исключение с готовым текстом
 * @return 400 с ErrorResponse
 */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
 * 400: недостающие данные расчёта экономики (economic_model.md §6).
 *
 * @param ex исключение со списком проблем
 * @return 400 с ErrorResponse
 */
    @ExceptionHandler(EconomicValidationException.class)
    public ResponseEntity<ErrorResponse> handleEconomicValidation(
            EconomicValidationException ex) {
        // Расчёт экономики: нет обязательных параметров/допущений,
        // нулевые знаменатели (economic_model.md §6) - 400 со списком
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
 * 400: значение параметра пути/запроса не привести к типу.
 *
 * @param ex исключение приведения типа
 * @return 400 с именем параметра и ожидаемым типом
 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // Например, /api/solutions?typeId=abc - значение не привести к Long
        return respond(HttpStatus.BAD_REQUEST,
                "Некорректное значение параметра " + ex.getName()
                        + ": ожидается " + (ex.getRequiredType() == null
                        ? "другой тип" : ex.getRequiredType().getSimpleName()));
    }

    /**
 * 401: неверный логин/пароль или недействительный токен.
 *
 * @param ex исключение аутентификации
 * @return 401 с ErrorResponse
 */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex) {
        return respond(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
 * 403: метод-секьюрити не пустил (роли не хватает).
 *
 * @param ex исключение доступа Spring Security
 * @return 403 с ErrorResponse
 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        // Из контроллеров/метод-секьюрити; фильтровый путь 403 обслуживает
        // accessDeniedHandler в SecurityConfig (тот же формат тела).
        return respond(HttpStatus.FORBIDDEN, "Недостаточно прав");
    }

    /**
 * 404: сущность не найдена (сообщение сервиса как есть).
 *
 * @param ex исключение «не найдено»
 * @return 404 с ErrorResponse
 */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
 * 404: путь не существует (иначе catch-all дал бы 500).
 *
 * @param ex исключение статического ресурса
 * @return 404 с ErrorResponse
 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, "Эндпоинт не найден");
    }

    /**
 * 405: метод не поддерживается этим эндпоинтом.
 *
 * @param ex исключение неподдерживаемого метода
 * @return 405 с ErrorResponse
 */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        // 405: неподдерживаемый метод раньше падал в catch-all Exception
        // -> 500 «Внутренняя ошибка сервера» (например, GET на пути, где
        // есть только PUT/POST); пользователю - понятный код и текст
        return respond(HttpStatus.METHOD_NOT_ALLOWED,
                "Метод не поддерживается этим эндпоинтом: "
                        + ex.getMessage());
    }

    /**
 * 409: конфликт состояния (логин занят и т.п.).
 *
 * @param ex исключение конфликта
 * @return 409 с ErrorResponse
 */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        return respond(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
 * 409: гонка конкурентных запросов (UNIQUE/FK) - повтор безопасен.
 *
 * @param ex нарушение целостности БД
 * @return 409 с ErrorResponse
 */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex) {
        // Гонки конкурентных запросов (UNIQUE/FK): calculate ∥ DELETE
        // сценария, PUT состава ∥ PUT состава, PUT допущений ∥ PUT
        // - без хендлера уходило бы в 500;
        // повтор запроса безопасен (расчёты append-only, PUT идемпотентен)
        log.warn("Гонка конкурентных запросов (DataIntegrityViolation): {}",
                ex.getMostSpecificCause().toString());
        return respond(HttpStatus.CONFLICT, "Данные параллельно изменены "
                + "другим запросом - повторите ещё раз");
    }

    /**
 * 400: построчные ошибки файла импорта - ImportFailureResponse.
 *
 * @param ex исключение со списком ошибок валидации файла
 * @return 400 с построчным списком (до 20 строк в теле)
 */
    @ExceptionHandler(ImportException.class)
    public ResponseEntity<ImportFailureResponse> handleImport(ImportException ex) {
        // Полный список - в лог (по и ограничению объёма ответа
        // в тело уходит максимум 20 строк; пользователь правит файл и
        // повторяет загрузку - ничего в БД не менялось)
        List<ImportErrorDto> errors = ex.getErrors();
        if (errors.size() > ImportException.MAX_ERRORS_IN_RESPONSE) {
            log.warn("Ошибки импорта (первые {} из {}): {}",
                    ImportException.MAX_ERRORS_IN_RESPONSE, errors.size(),
                    errors.stream().map(Object::toString).toList());
            errors = errors.subList(0, ImportException.MAX_ERRORS_IN_RESPONSE);
        }
        ImportFailureResponse body = new ImportFailureResponse(Instant.now(),
                HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
 * 413: сервисная проверка размера файла импорта.
 *
 * @param ex исключение превышения размера
 * @return 413 с ErrorResponse
 */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePayloadTooLarge(PayloadTooLargeException ex) {
        // 413; в Spring 7 константа переименована по RFC 9110
        // («Payload Too Large» -> «Content Too Large»)
        return respond(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
    }

    /**
 * 413: servlet multipart отклонил запрос до контроллера.
 *
 * @param ex исключение лимита загрузки servlet-слоя
 * @return 413 с ErrorResponse
 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(MaxUploadSizeExceededException ex) {
        // Слой servlet multipart отклонил запрос ещё до контроллера
        // (spring.servlet.multipart.max-file-size); текст - тот же смысл,
        // что и у сервисной проверки AttachmentStorage
        return respond(HttpStatus.CONTENT_TOO_LARGE,
                "Файл слишком большой. Уменьшите размер файла и повторите загрузку.");
    }

    /**
 * 500: непредвиденное - детали только в лог, наружу без внутренностей.
 *
 * @param ex непредвиденное исключение
 * @return 500 с ErrorResponse
 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Детали (тип, стектрейс) - только в серверный лог; наружу - единое
        // сообщение без имён классов и внутреннихностей.
        log.error("Необработанная ошибка API: {}", ex.toString(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера");
    }
}
