package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.domain.Export;
import me.yuugao.robomatch.dto.ExportCreateRequest;
import me.yuugao.robomatch.dto.ExportDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.export.ExportService;
import me.yuugao.robomatch.security.CurrentUser;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST-контроллер экспорта ( шаг 8,
 * 3.7.1–3.7.5). Все эндпоинты authenticated (SecurityConfig; —
 * выгружает зарегистрированный пользователь), изоляция как у проектов/
 * экономики/имитации: userId из JWT, чужой — 404.
 *
 * <p>Коды: POST — 201 (создан) / 400 (не склад; битое
 * тело) / 404 (чужой проект) / 409 (параллельная генерация того же
 * формата); GET списка — 200/404; GET файла — 200 (Content-Type,
 * Content-Disposition с filename* для кириллицы)/404; DELETE — 204/404
 * (файл + строка истории; удаление проекта чистит каталог —).
 */
@RestController
@RequestMapping("/api/projects/{id}")
@RequiredArgsConstructor
@Tag(name = "Экспорт отчётов",
        description = "Генерация и скачивание итогового отчёта: "
                + "PDF — человекочитаемый документ, Excel/CSV — расчётные "
                + "таблицы; отчёт включает состав сценария и пометку "
                + "о предварительной оценке")
@SecurityRequirement(name = "bearer-jwt")
public class ExportController {

    private final ExportService exportService;

    private static String mediaType(String format) {
        return switch (format == null ? "" : format) {
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            case "xlsx" -> "application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet";
            case "csv" -> "text/csv; charset=UTF-8";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    /**
 * Сгенерировать отчёт. POST /exports
 *
 * @param id идентификатор проекта
 * @param request формат (pdf/xlsx/csv), тема (light/dark) и тема
 * схемы (ui/light/dark)
 * @return 201 + ExportDto созданной выгрузки
 * @throws BadRequestException 400 — не склад, битое тело
 * @throws NotFoundException 404 — проект чужой или не найден
 * @throws ConflictException 409 — генерация того же формата уже идёт
 */
    @PostMapping("/exports")
    @Operation(summary = "Сгенерировать отчёт",
            description = "Тело {format, theme?}: pdf — итоговый отчёт (10 разделов, "
                    + "пометка о предварительной оценке на титуле и в конце), "
                    + "xlsx — 7 листов расчётных таблиц, csv — плоская "
                    + "таблица (UTF-8 BOM, «;»). Тема: light — "
                    + "белые страницы (по умолчанию), dark — тёмные со "
                    + "светлым текстом; выбор на странице экспорта, не "
                    + "зависит от темы браузера; на xlsx/csv не влияет. "
                    + "Тема 2D-схемы (schemaTheme): ui — как сохранена "
                    + "в интерфейсе (по умолчанию), light/dark — схема "
                    + "перерисовывается сервером в выбранной теме. "
                    + "Только склад (is_calc_enabled); требует хотя бы "
                    + "одного расчёта экономики. 201/400/404/409 "
                    + "(повторная генерация того же формата).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Отчёт сгенерирован"),
            @ApiResponse(responseCode = "400", description = "Не склад/битое тело"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден"),
            @ApiResponse(responseCode = "409", description = "Генерация того же формата уже идёт")
    })
    public ResponseEntity<ExportDto> create(
            @Parameter(description = "Идентификатор проекта", example = "3")
            @PathVariable Long id,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"format\": \"pdf\", \"theme\": \"light\", \"schemaTheme\": \"ui\"}")))
            ExportCreateRequest request) {
        ExportDto view = exportService.create(CurrentUser.requireUserId(),
                id, request.format(), request.theme(),
                request.schemaTheme());
        return ResponseEntity.status(201).body(view);
    }

    /**
 * История выгрузок проекта. GET /exports
 *
 * @param id идентификатор проекта
 * @return список ExportDto, свежие сверху
 * @throws NotFoundException 404 — проект чужой или не найден
 */
    @GetMapping("/exports")
    @Operation(summary = "История выгрузок проекта",
            description = "Все сгенерированные отчёты проекта, свежие "
                    + "сверху: формат, имя файла, размер, автор, дата "
                    + "(выгрузка для обсуждения; история — "
                    + "воспроизведение).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "История выгрузок"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<List<ExportDto>> history(
            @Parameter(description = "Идентификатор проекта", example = "3")
            @PathVariable Long id) {
        return ResponseEntity.ok(exportService.history(
                CurrentUser.requireUserId(), id));
    }

    /**
 * Скачать файл выгрузки. GET /exports/{exportId}
 *
 * @param id идентификатор проекта
 * @param exportId идентификатор выгрузки
 * @return байты файла (attachment, Content-Type формата)
 * @throws NotFoundException 404 — выгрузка чужая или файл потерян
 */
    @GetMapping("/exports/{exportId}")
    @Operation(summary = "Скачать файл выгрузки",
            description = "Байты файла с Content-Type формата и "
                    + "Content-Disposition: attachment (filename* — "
                    + "кириллица имени проекта). 404 — чужая выгрузка "
                    + "или файл потерян.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Файл отчёта"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Выгрузка не найдена/файл потерян")
    })
    public ResponseEntity<byte[]> download(
            @Parameter(description = "Идентификатор проекта", example = "3")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор выгрузки", example = "7")
            @PathVariable Long exportId) {
        Long userId = CurrentUser.requireUserId();
        Export row = exportService.requireOwnedExport(userId, id, exportId);
        byte[] content = exportService.contentOf(row);
        String fileName = exportService.fileNameOf(row,
                exportService.projectName(userId, id));
        String encoded = URLEncoder.encode(fileName,
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        mediaType(row.getFormat()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"RoboMatch-report-"
                                + row.getId() + "." + row.getFormat()
                                + "\"; filename*=UTF-8''" + encoded)
                .body(content);
    }

    /**
 * Удалить выгрузку (файл + строка). DELETE /exports/{exportId}
 *
 * @param id идентификатор проекта
 * @param exportId идентификатор выгрузки
 * @return 204 No Content
 * @throws NotFoundException 404 — выгрузка чужая или не найдена
 */
    @DeleteMapping("/exports/{exportId}")
    @Operation(summary = "Удалить выгрузку",
            description = "Файл хранилища + строка истории. 204/404.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Выгрузка удалена"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Выгрузка не найдена")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Идентификатор проекта", example = "3")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор выгрузки", example = "7")
            @PathVariable Long exportId) {
        exportService.delete(CurrentUser.requireUserId(), id, exportId);
        return ResponseEntity.noContent().build();
    }
}
