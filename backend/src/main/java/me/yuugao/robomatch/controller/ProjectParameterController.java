package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.AttachmentDto;
import me.yuugao.robomatch.dto.ImportResultDto;
import me.yuugao.robomatch.dto.ParameterDto;
import me.yuugao.robomatch.dto.ParameterSetRequest;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.exception.PayloadTooLargeException;
import me.yuugao.robomatch.security.CurrentUser;
import me.yuugao.robomatch.service.ParameterTemplateService.TemplateFile;
import me.yuugao.robomatch.service.ProjectParameterService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * REST-контроллер параметров объекта (
 * 3.2.2–3.2.5; user-flow.md шаг 2). Только аутентифицированные
 * (SecurityConfig: /api/** authenticated) и только над СВОИМИ
 * проектами (изоляция).
 * <p>
 * Контроллер тонкий: приём HTTP, вызов сервиса, ответ DTO. Схема
 * сохранения - per-field PUT: форма отправляет только
 * изменённые параметры, по одному запросу - merge-логика без
 * last-write-wins по всей форме, inline-ошибка относится к конкретному
 * полю.
 */
@RestController
@RequestMapping("/api/projects/{id}/parameters")
@RequiredArgsConstructor
@Tag(name = "Параметры объекта",
        description = "Ручной ввод, импорт Excel/CSV, вложения и шаблон параметров проекта")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectParameterController {

    private final ProjectParameterService parameterService;

    /**
 * Список параметров проекта с метаданными и значениями. GET /parameters
 *
 * @param id идентификатор проекта
 * @return список ParameterDto (значение null - не введено)
 * @throws NotFoundException 404 - проект чужой или не найден
 */
    @GetMapping
    @Operation(summary = "Параметры проекта",
            description = "Все параметры типа объекта проекта: метаданные (тип поля, "
                    + "единица, обязательность, диапазон, значение по умолчанию, источник "
                    + "норматива) и текущее значение (null - не введено). "
                    + "Чужой проект - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Параметры проекта"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<List<ParameterDto>> list(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(
                parameterService.getParameters(CurrentUser.requireUserId(), id));
    }

    /**
 * Установка значения параметра вручную. PUT /parameters/{parameterId}
 *
 * @param id идентификатор проекта
 * @param parameterId идентификатор параметра (object_type_parameter.id)
 * @param request значение параметра
 * @return ParameterDto с сохранённым значением (source=manual)
 * @throws BadRequestException 400 - тип/диапазон/обязательность не пройдены
 * @throws NotFoundException 404 - проект или параметр не найдены
 */
    @PutMapping("/{parameterId}")
    @Operation(summary = "Установить значение параметра",
            description = "Валидация по метаданным: тип значения, диапазон, "
                    + "обязательность. Идемпотентный UPSERT, source=manual. Ошибки - 400 "
                    + "с понятным текстом и способом исправления.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Значение сохранено"),
            @ApiResponse(responseCode = "400", description = "Значение не прошло валидацию"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект или параметр не найдены")
    })
    public ResponseEntity<ParameterDto> set(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор параметра (object_type_parameter.id "
                    + "из списка)", example = "5")
            @PathVariable Long parameterId,
            @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"value\": 5000}")))
            ParameterSetRequest request) {
        return ResponseEntity.ok(parameterService.setParameterValue(
                CurrentUser.requireUserId(), id, parameterId, request));
    }

    /**
 * Сброс значения параметра. DELETE /parameters/{parameterId}
 *
 * @param id идентификатор проекта
 * @param parameterId идентификатор параметра
 * @return 204 No Content
 * @throws NotFoundException 404 - проект или параметр не найдены
 */
    @DeleteMapping("/{parameterId}")
    @Operation(summary = "Сбросить значение параметра",
            description = "Удаляет введённое значение; в форме снова показывается "
                    + "значение по умолчанию из метаданных. 204, чужой проект - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Значение сброшено"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект или параметр не найдены")
    })
    public ResponseEntity<Void> reset(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор параметра", example = "5")
            @PathVariable Long parameterId) {
        parameterService.deleteParameterValue(CurrentUser.requireUserId(), id, parameterId);
        return ResponseEntity.noContent().build();
    }

    /**
 * Импорт параметров из Excel/CSV. POST /parameters/import
 *
 * @param id идентификатор проекта
 * @param file файл Excel/CSV по шаблону
 * @return ImportResultDto - счётчики сохранённых значений и вложение
 * @throws BadRequestException 400 - ошибки в файле (список строк)
 * @throws NotFoundException 404 - проект чужой или не найден
 * @throws PayloadTooLargeException 413 - файл больше 10 МБ
 */
    @PostMapping("/import")
    @Operation(summary = "Импортировать параметры из файла",
            description = "Excel (.xlsx, .xls) или CSV по шаблону. Файл "
                    + "валидируется целиком ДО записи: ошибка хотя бы в одной "
                    + "ячейке - 400 со списком строк/колонок, ничего не сохранено. Успех - "
                    + "одна транзакция: вложение + все значения (source=import). Лимит "
                    + "размера - 10 МБ (413).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Импорт выполнен"),
            @ApiResponse(responseCode = "400", description = "Ошибки в файле (ничего не сохранено)"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден"),
            @ApiResponse(responseCode = "413", description = "Файл больше 10 МБ")
    })
    public ResponseEntity<ImportResultDto> importFile(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Файл Excel/CSV по шаблону", required = true)
            @RequestPart(name = "file")
            MultipartFile file) {
        return ResponseEntity.ok(parameterService.importParameters(
                CurrentUser.requireUserId(), id, file));
    }

    /**
 * История загрузок (вложения) проекта. GET /parameters/attachments
 *
 * @param id идентификатор проекта
 * @return список AttachmentDto - файлы с размером и датой
 * @throws NotFoundException 404 - проект чужой или не найден
 */
    @GetMapping("/attachments")
    @Operation(summary = "Вложения проекта",
            description = "Загруженные исходники параметров (Excel/CSV) с размером "
                    + "и датой - история загрузок. Чужой проект - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Список вложений"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<List<AttachmentDto>> attachments(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(
                parameterService.listAttachments(CurrentUser.requireUserId(), id));
    }

    /**
 * Удаление вложения (файл + строка истории).
 * DELETE /parameters/attachments/{attachmentId}
 *
 * @param id идентификатор проекта
 * @param attachmentId идентификатор вложения
 * @return 204 No Content
 * @throws NotFoundException 404 - проект или вложение не найдены
 */
    @DeleteMapping("/attachments/{attachmentId}")
    @Operation(summary = "Удалить вложение",
            description = "Удаляет файл из хранилища и строку из истории. 204, чужой "
                    + "проект - 404, значения параметров не меняются.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Вложение удалено"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект или вложение не найдены")
    })
    public ResponseEntity<Void> deleteAttachment(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор вложения", example = "7")
            @PathVariable Long attachmentId) {
        parameterService.deleteAttachment(CurrentUser.requireUserId(), id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    /**
 * Шаблон импорта. GET /parameters/template?format=xlsx|csv
 *
 * @param id идентификатор проекта
 * @param format формат шаблона: xlsx (по умолчанию) или csv
 * @return байты файла-шаблона (attachment, Content-Type формата)
 * @throws BadRequestException 400 - формат не xlsx/csv
 * @throws NotFoundException 404 - проект чужой или не найден
 */
    @GetMapping("/template")
    @Operation(summary = "Скачать шаблон импорта",
            description = "Шаблон с кодами параметров типа объекта проекта: строка 1 - "
                    + "коды, строка 2 - значения. XLSX - второй лист «Параметры» с "
                    + "переводом кодов, единицами, диапазонами и источниками нормативов; "
                    + "CSV - строка кодов. format: xlsx (по умолчанию) или csv.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Файл шаблона"),
            @ApiResponse(responseCode = "400", description = "Формат не xlsx/csv"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<byte[]> template(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Формат шаблона: xlsx или csv", example = "xlsx")
            @RequestParam(name = "format", required = false) String format) {
        String safeFormat = format == null || format.isBlank() ? "xlsx" : format.trim();
        if (!"xlsx".equalsIgnoreCase(safeFormat) && !"csv".equalsIgnoreCase(safeFormat)) {
            throw new BadRequestException("Формат шаблона - xlsx или csv.");
        }
        TemplateFile template = parameterService.buildTemplate(
                CurrentUser.requireUserId(), id, safeFormat);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + template.fileName() + "\"")
                .contentType(MediaType.parseMediaType(template.mimeType()))
                .body(template.content());
    }
}
