package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.admin.AdminCharacteristicService;
import me.yuugao.robomatch.admin.AdminReferenceService;
import me.yuugao.robomatch.admin.AdminSolutionService;
import me.yuugao.robomatch.admin.CatalogImporter;
import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.exception.PayloadTooLargeException;
import me.yuugao.robomatch.repository.AdminImportRepository;
import me.yuugao.robomatch.security.CurrentUser;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST-контроллер админки.
 * <p>
 * ДОСТУП: весь /api/admin/** закрыт hasRole('ADMIN') в SecurityConfig -
 * контроллер не дублирует проверки роли, только принимает и отвечает
 * (слои: controller -> service -> repository,. Демо-аккаунт
 * admin/admin создаётся seed-ом.
 * <p>
 * Состав:
 * - CRUD каталога решений (+ EAV-ТТХ с провенансом);
 * - CRUD 7 справочников;
 * - импорт таблицы организатора CSV/XLSX с идемпотентностью и summary
 *, история импортов (V7) и обновление по запросу.
 */
@Tag(name = "Админка", description = "Управление каталогом решений и справочниками "
        + "(только роль admin)")
@SecurityRequirement(name = "bearer-jwt")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminSolutionService solutionService;
    private final AdminCharacteristicService characteristicService;
    private final AdminReferenceService referenceService;
    private final CatalogImporter catalogImporter;
    private final AdminImportRepository importRepository;

    public AdminController(AdminSolutionService solutionService,
                           AdminCharacteristicService characteristicService,
                           AdminReferenceService referenceService,
                           CatalogImporter catalogImporter,
                           AdminImportRepository importRepository) {
        this.solutionService = solutionService;
        this.characteristicService = characteristicService;
        this.referenceService = referenceService;
        this.catalogImporter = catalogImporter;
        this.importRepository = importRepository;
    }

    // ==================================================================
    // Решения каталога
    // ==================================================================

    /**
 * Список решений каталога для админки (поиск + «требуют проверки»).
 * GET /api/admin/solutions
 *
 * @param q подстрока названия (null - без поиска)
 * @param needsCheck только внесённые вручную (source_kind=manual)
 * @param page номер страницы, начиная с 0
 * @param size размер страницы (по умолчанию 20)
 * @return страница SolutionSummaryDto
 * @throws BadRequestException 400 - page/size вне допустимых границ
 */
    @Operation(summary = "Список решений для админки",
            description = "Поиск по названию + фильтр «требуют проверки» "
                    + "(внесены вручную, source_kind=manual,).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Страница решений"),
            @ApiResponse(responseCode = "400", description = "page/size вне допустимых границ"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin")
    })
    @GetMapping("/solutions")
    public PageResponse<SolutionSummaryDto> listSolutions(
            @Parameter(description = "Поиск по названию") @RequestParam(required = false)
            String q,
            @Parameter(description = "Только требующие проверки (manual)")
            @RequestParam(defaultValue = "false") boolean needsCheck,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return solutionService.list(q, needsCheck, page, size);
    }

    /**
 * Создание решения вручную. POST /api/admin/solutions
 *
 * @param request поля карточки нового решения
 * @return созданная карточка SolutionFullDto (201)
 * @throws BadRequestException 400 - невалидные поля/ссылки справочников
 * @throws ConflictException 409 - дубликат названия у производителя
 */
    @Operation(summary = "Создать решение вручную",
            description = "Провенанс: source_kind=manual, не подтверждено "
                    + "источником. 409 - дубликат названия у того же "
                    + "производителя (UNIQUE (vendor_id, name)).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Создано"),
            @ApiResponse(responseCode = "400", description = "Невалидные поля/ссылки"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "409", description = "Дубликат названия")
    })
    @PostMapping("/solutions")
    public ResponseEntity<SolutionFullDto> createSolution(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"name\": \"Мобильный робот складской X1\", "
                                            + "\"vendorId\": 12, \"productClass\": \"brs\", "
                                            + "\"solutionTypeId\": 3, \"solutionSubtypeId\": 7, "
                                            + "\"regionId\": 1, \"status\": \"operation\", "
                                            + "\"description\": \"Мобильный складской робот для перевозки паллет\", "
                                            + "\"priceRub\": 2700000.00, \"trl\": 8, "
                                            + "\"marketPotential\": 4.0, \"payloadKg\": 1500, "
                                            + "\"massKg\": 620, \"lengthMm\": 1850, "
                                            + "\"widthMm\": 800, \"heightMm\": 2050, "
                                            + "\"positioningAccuracyMm\": 10, \"speedMs\": 2.0, "
                                            + "\"chargingPowerKw\": 5.5, \"noiseLevelDba\": 65}")))
            AdminSolutionCreateRequest request) {
        SolutionFullDto created = solutionService.create(request);
        return ResponseEntity.status(201).body(created);
    }

    /**
 * Полное обновление карточки решения (PUT-семантика).
 * PUT /api/admin/solutions/{id}
 *
 * @param id идентификатор решения
 * @param request полное новое состояние карточки
 * @return обновлённая карточка SolutionFullDto
 * @throws BadRequestException 400 - невалидные поля/ссылки
 * @throws NotFoundException 404 - решение не найдено
 * @throws ConflictException 409 - дубликат названия
 */
    @Operation(summary = "Изменить решение",
            description = "PUT-семантика: передаётся полное состояние карточки. "
                    + "Провенанс правки - manual.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Обновлено"),
            @ApiResponse(responseCode = "400", description = "Невалидные поля/ссылки"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "404", description = "Решение не найдено"),
            @ApiResponse(responseCode = "409", description = "Дубликат названия")
    })
    @PutMapping("/solutions/{id}")
    public SolutionFullDto updateSolution(@PathVariable Long id,
                                          @Valid @RequestBody
                                          @io.swagger.v3.oas.annotations.parameters.RequestBody(
                                                  content = @io.swagger.v3.oas.annotations.media.Content(
                                                          examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                                  value = "{\"name\": \"Мобильный робот складской X1\", "
                                                                          + "\"vendorId\": 12, "
                                                                          + "\"productClass\": \"brs\", "
                                                                          + "\"status\": \"operation\", "
                                                                          + "\"priceRub\": 2700000, "
                                                                          + "\"solutionTypeId\": 3, "
                                                                          + "\"solutionSubtypeId\": 7, "
                                                                          + "\"regionId\": 1, \"trl\": 8, "
                                                                          + "\"payloadKg\": 1500}")))
                                          AdminSolutionUpdateRequest request) {
        return solutionService.update(id, request);
    }

    /**
 * Удаление решения из каталога. DELETE /api/admin/solutions/{id}
 *
 * @param id идентификатор решения
 * @return 204 No Content
 * @throws NotFoundException 404 - решение не найдено
 * @throws ConflictException 409 - на решение ссылаются сценарии/результаты
 */
    @Operation(summary = "Удалить решение",
            description = "409, пока решение участвует в сценариях или результатах "
                    + "подбора (RESTRICT - не рушим чужой контекст). "
                    + "Применения/ТТХ/кейсы решения удаляются каскадно.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Удалено"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "404", description = "Решение не найдено"),
            @ApiResponse(responseCode = "409", description = "Есть ссылки из проектов")
    })
    @DeleteMapping("/solutions/{id}")
    public ResponseEntity<Void> deleteSolution(@PathVariable Long id) {
        solutionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
 * Массовое удаление решений по списку идентификаторов.
 * POST /api/admin/solutions/bulk-delete
 *
 * @param request список идентификаторов (до 100)
 * @return удалённые идентификаторы и причины отказов
 * @throws BadRequestException 400 - пустой список или null в списке
 */
    @Operation(summary = "Массово удалить решения",
            description = "Каждое решение удаляется независимо: записи со "
                    + "ссылками из проектов дают отказ с причиной (как 409 "
                    + "одиночного удаления), остальные удаляются. Дубликаты "
                    + "идентификаторов игнорируются.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Результат по каждой позиции"),
            @ApiResponse(responseCode = "400", description = "Пустой список, "
                    + "null-элемент или больше 100 идентификаторов"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin")
    })
    @PostMapping("/solutions/bulk-delete")
    public BulkDeleteResultDto bulkDeleteSolutions(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"ids\": [101, 102, 103]}")))
            BulkDeleteRequest request) {
        return solutionService.bulkDelete(request.getIds());
    }

    // ==================================================================
    // ТТХ решения (EAV)
    // ==================================================================

    /**
 * Upsert значения ТТХ решения (EAV).
 * PUT /api/admin/solutions/{id}/characteristics/{characteristicTypeId}
 *
 * @param id идентификатор решения
 * @param characteristicTypeId идентификатор типа ТТХ
 * @param request значение с типом и провенансом
 * @return сохранённая строка ТТХ SolutionCharacteristicDto
 * @throws BadRequestException 400 - тип значения не соответствует типу ТТХ
 * @throws NotFoundException 404 - решение или тип ТТХ не найдены
 */
    @Operation(summary = "Upsert значения ТТХ решения",
            description = "Тип значения обязан соответствовать типу характеристики "
                    + "(number/text/boolean/date). Провенанс: manual или open_source "
                    + "(тогда sourceUrl+sourceDate обязательны, isConfirmed=true, "
                    + "/уточнение организатора). 9 технических ТТХ синхронизируют "
                    + "материализованные колонки solution.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Сохранено"),
            @ApiResponse(responseCode = "400", description = "Несоответствие типа"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "404", description = "Решение или тип ТТХ "
                    + "не найдены")
    })
    @PutMapping("/solutions/{id}/characteristics/{characteristicTypeId}")
    public SolutionCharacteristicDto upsertCharacteristic(
            @PathVariable Long id,
            @PathVariable Long characteristicTypeId,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"valueNumeric\": 1500.5, "
                                            + "\"sourceKind\": \"manual\"}")))
            AdminCharacteristicUpsertRequest request) {
        return characteristicService.upsert(id, characteristicTypeId, request);
    }

    /**
 * Удаление значения ТТХ решения.
 * DELETE /api/admin/solutions/{id}/characteristics/{characteristicTypeId}
 *
 * @param id идентификатор решения
 * @param characteristicTypeId идентификатор типа ТТХ
 * @return 204 No Content
 * @throws NotFoundException 404 - значения нет
 */
    @Operation(summary = "Удалить значение ТТХ решения",
            description = "Удаляет EAV-строку; материализованная колонка solution "
                    + "(для 9 технических ТТХ) обнуляется.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Удалено"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "404", description = "Значения нет")
    })
    @DeleteMapping("/solutions/{id}/characteristics/{characteristicTypeId}")
    public ResponseEntity<Void> deleteCharacteristic(@PathVariable Long id,
                                                     @PathVariable
                                                     Long characteristicTypeId) {
        characteristicService.delete(id, characteristicTypeId);
        return ResponseEntity.noContent().build();
    }

    // ==================================================================
    // Справочники
    // ==================================================================

    /**
 * Счётчики записей по всем справочникам. GET /api/admin/references
 *
 * @return карта dictCode → количество записей
 */
    @Operation(summary = "Счётчики записей справочников",
            description = "dictCode -> количество записей (плитки страницы "
                    + "справочников).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Счётчики справочников"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin")
    })
    @GetMapping("/references")
    public Map<String, Long> referenceCounts() {
        return referenceService.counts();
    }

    /**
 * Список записей одного справочника. GET /api/admin/references/{dictCode}
 *
 * @param dictCode код справочника (industry, process, vendor, region, ...)
 * @param q подстрока названия/кода (null - без поиска)
 * @param page номер страницы, начиная с 0
 * @param size размер страницы (по умолчанию 50)
 * @return страница AdminReferenceDto
 * @throws BadRequestException 400 - неизвестный справочник, page/size
 */
    @Operation(summary = "Список записей справочника",
            description = "Пагинация + поиск по названию/коду. dictCode: industry, "
                    + "process, vendor, region, solution_type, solution_subtype, "
                    + "characteristic_type.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Страница записей"),
            @ApiResponse(responseCode = "400", description = "Неизвестный справочник"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin")
    })
    @GetMapping("/references/{dictCode}")
    public PageResponse<AdminReferenceDto> listReferences(
            @PathVariable String dictCode,
            @Parameter(description = "Поиск по названию/коду")
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return referenceService.list(dictCode, q, page, size);
    }

    /**
 * Создание записи справочника. POST /api/admin/references/{dictCode}
 *
 * @param dictCode код справочника
 * @param request название и (кроме vendor) код записи
 * @return созданная запись AdminReferenceDto (201)
 * @throws BadRequestException 400 - невалидные поля
 * @throws ConflictException 409 - дубликат кода/названия
 */
    @Operation(summary = "Создать запись справочника",
            description = "Код - латиница snake_case; "
                    + "у vendor кода нет. 409 - дубликат кода или названия.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Создано"),
            @ApiResponse(responseCode = "400", description = "Невалидные поля"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "409", description = "Дубликат кода/названия")
    })
    @PostMapping("/references/{dictCode}")
    public ResponseEntity<AdminReferenceDto> createReference(
            @PathVariable String dictCode,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"code\": \"medical_logistics\", "
                                            + "\"name\": \"Медицинская логистика\", "
                                            + "\"isActive\": true, \"groupCode\": \"technical\", "
                                            + "\"dataType\": \"number\", \"unit\": \"кг\"}")))
            AdminReferenceCreateRequest request) {
        return ResponseEntity.status(201)
                .body(referenceService.create(dictCode, request));
    }

    /**
 * Изменение записи справочника.
 * PUT /api/admin/references/{dictCode}/{id}
 *
 * @param dictCode код справочника
 * @param id идентификатор записи
 * @param request новые название/код
 * @return обновлённая запись AdminReferenceDto
 * @throws BadRequestException 400 - невалидные поля
 * @throws NotFoundException 404 - запись не найдена
 * @throws ConflictException 409 - дубликат кода/названия
 */
    @Operation(summary = "Изменить запись справочника",
            description = "Обновляет название/код записи по коду справочника и id;\n"
                    + "занятые записями решений не удаляются - см. DELETE. Требует роль admin")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Обновлено"),
            @ApiResponse(responseCode = "400", description = "Невалидные поля"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "404", description = "Запись не найдена"),
            @ApiResponse(responseCode = "409", description = "Дубликат кода/названия")
    })
    @PutMapping("/references/{dictCode}/{id}")
    public AdminReferenceDto updateReference(@PathVariable String dictCode,
                                             @PathVariable Long id,
                                             @Valid @RequestBody
                                             @io.swagger.v3.oas.annotations.parameters.RequestBody(
                                                     content = @io.swagger.v3.oas.annotations.media.Content(
                                                             examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                                     value = "{\"code\": \"medical_logistics\", "
                                                                             + "\"name\": \"Медицинская логистика\", "
                                                                             + "\"isActive\": true}")))
                                             AdminReferenceUpdateRequest request) {
        return referenceService.update(dictCode, id, request);
    }

    /**
 * Удаление записи справочника.
 * DELETE /api/admin/references/{dictCode}/{id}
 *
 * @param dictCode код справочника
 * @param id идентификатор записи
 * @return 204 No Content
 * @throws NotFoundException 404 - запись не найдена
 * @throws ConflictException 409 - на запись есть FK-ссылки
 */
    @Operation(summary = "Удалить запись справочника",
            description = "409, если на запись ссылаются решения/применения/типы "
                    + "объектов/пары «тип-подтип»/значения ТТХ (FK). "
                    + "Для процессов вместо удаления используйте is_active=false.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Удалено"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "404", description = "Запись не найдена"),
            @ApiResponse(responseCode = "409", description = "Есть FK-ссылки")
    })
    @DeleteMapping("/references/{dictCode}/{id}")
    public ResponseEntity<Void> deleteReference(@PathVariable String dictCode,
                                                @PathVariable Long id) {
        referenceService.delete(dictCode, id);
        return ResponseEntity.noContent().build();
    }

    /**
 * Массовое удаление записей справочника.
 * POST /api/admin/references/{dictCode}/bulk-delete
 *
 * @param dictCode код справочника
 * @param request список идентификаторов (до 100)
 * @return удалённые идентификаторы и причины отказов
 * @throws BadRequestException 400 - неизвестный справочник, пустой
 * список или null в списке
 */
    @Operation(summary = "Массово удалить записи справочника",
            description = "Каждая запись удаляется независимо: записи с "
                    + "FK-ссылками из каталога дают отказ с причиной (как 409 "
                    + "одиночного удаления), остальные удаляются. Дубликаты "
                    + "идентификаторов игнорируются.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Результат по каждой позиции"),
            @ApiResponse(responseCode = "400", description = "Неизвестный "
                    + "справочник, пустой список, null-элемент или больше "
                    + "100 идентификаторов"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin")
    })
    @PostMapping("/references/{dictCode}/bulk-delete")
    public BulkDeleteResultDto bulkDeleteReferences(
            @PathVariable String dictCode,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"ids\": [5, 9]}")))
            BulkDeleteRequest request) {
        return referenceService.bulkDelete(dictCode, request.getIds());
    }

    // ==================================================================
    // Импорт каталога организатора
    // ==================================================================

    /**
 * Импорт таблицы каталога организатора (CSV/XLSX).
 * POST /api/admin/catalog/import
 *
 * @param file таблица каталога CSV/XLSX
 * @return сводка импорта CatalogImportSummaryDto
 * @throws BadRequestException 400 - ошибки в файле (список до 20 строк)
 * @throws ConflictException 409 - импорт уже выполняется
 * @throws PayloadTooLargeException 413 - файл больше 50 МБ
 */
    @Operation(summary = "Загрузить таблицу каталога",
            description = "CSV/XLSX таблица каталога (15 колонок, "
                    + "разделитель «;»). Расширенный формат: "
                    + "плюс колонки ТТХ - код characteristic_type как заголовок "
                    + "(или с префиксом «char:»), значения идут в EAV "
                    + "solution_characteristic с провенансом organizer_catalog "
                    + "и синхронизируют 9 зеркальных ТТХ-колонок solution. "
                    + "Правила нормализации: дедуп по id, автоправки, разрез "
                    + "«Сценарий». Идемпотентно: повтор того же файла - 0 изменений. "
                    + "Summary: добавлено/обновлено/пропущено по сущностям "
                    + "(включая characteristics). "
                    + "Лимит 50 МБ (413); 409, если импорт уже выполняется; 400 - "
                    + "битые ячейки (список до 20) или неизвестная колонка.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Импорт завершён, summary"),
            @ApiResponse(responseCode = "400", description = "Ошибки в файле"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "409", description = "Импорт уже идёт"),
            @ApiResponse(responseCode = "413", description = "Файл больше 50 МБ")
    })
    @PostMapping("/catalog/import")
    public CatalogImportSummaryDto importCatalog(
            @Parameter(description = "Таблица каталога CSV/XLSX", required = true)
            @RequestPart(name = "file") MultipartFile file) {
        return catalogImporter.importFile(file, CurrentUser.requireUserId());
    }

    /**
 * История импортов каталога. GET /api/admin/catalog/import/history
 *
 * @param limit сколько последних записей показать (1-50, по умолчанию 10)
 * @return список AdminImportDto, свежие сверху
 */
    @Operation(summary = "История импортов каталога",
            description = "V7 admin_import_log: последние импорты со статусами "
                    + "running/completed/failed и счётчиками (summary_json).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "История импортов"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin")
    })
    @GetMapping("/catalog/import/history")
    public List<AdminImportDto> importHistory(
            @RequestParam(defaultValue = "10") int limit) {
        int safe = Math.clamp(limit, 1, 50);
        return importRepository
                .findAllByOrderByStartedAtDesc(PageRequest.of(0, safe))
                .stream()
                .map(row -> new AdminImportDto(row.getId(), row.getFileName(),
                        row.getSizeBytes(), row.getStartedAt(), row.getFinishedAt(),
                        row.getStatus(), me.yuugao.robomatch.admin.CatalogJson
                        .toMap(row.getSummaryJson())))
                .toList();
    }

    /**
 * Повторный импорт последнего загруженного файла каталога.
 * POST /api/admin/catalog/refresh
 *
 * @return сводка обновления CatalogImportSummaryDto
 * @throws BadRequestException 400 - файла для обновления ещё нет
 * @throws ConflictException 409 - импорт уже выполняется
 */
    @Operation(summary = "Обновить каталог по запросу",
            description = "Повторный импорт последнего загруженного файла. 400, "
                    + "если файлов ещё нет или копия недоступна. Плановое "
                    + "автообновление не предусмотрено.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Обновление завершено"),
            @ApiResponse(responseCode = "400", description = "Нет файла для обновления"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "403", description = "Роль не admin"),
            @ApiResponse(responseCode = "409", description = "Импорт уже идёт")
    })
    @PostMapping("/catalog/refresh")
    public CatalogImportSummaryDto refreshCatalog() {
        return catalogImporter.refreshLast(CurrentUser.requireUserId());
    }
}
