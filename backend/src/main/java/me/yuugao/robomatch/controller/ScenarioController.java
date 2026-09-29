package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.security.CurrentUser;
import me.yuugao.robomatch.selection.ScenarioService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
 * REST-контроллер сценариев (; сущность -
 * data_model.md §10.5–10.6). Только аутентифицированные (SecurityConfig:
 * /api/** - authenticated) и только над СВОИМИ проектами (изоляция 
 * 4.4.3: чужой - 404, не 403; userId - из JWT). Контроллер тонкий.
 */
@RestController
@RequestMapping("/api/projects/{id}/scenarios")
@RequiredArgsConstructor
@Tag(name = "Сценарии расчёта",
        description = "Сценарии сравнения (базовый/покупка/RaaS) и состав "
                + "оборудования: правка количества, ручное добавление решений, "
                + "удаление")
@SecurityRequirement(name = "bearer-jwt")
public class ScenarioController {

    private final ScenarioService scenarioService;

    /**
 * Сценарии проекта с составом оборудования. GET /scenarios
 *
 * @param id идентификатор проекта
 * @return список ScenarioDetailsDto со строками состава
 * @throws NotFoundException 404 - проект чужой или не найден
 */
    @GetMapping
    @Operation(summary = "Сценарии проекта с составом",
            description = "Все сценарии проекта и состав оборудования "
                    + "каждого (результат подбора + ручные добавления). "
                    + "Чужой проект - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Сценарии с составом"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<List<ScenarioDetailsDto>> list(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(scenarioService.listWithSolutions(
                CurrentUser.requireUserId(), id));
    }

    /**
 * Создание сценария (или восстановление недостающих). POST /scenarios
 *
 * @param id идентификатор проекта
 * @param request тип и имя сценария (null - создать недостающие дефолты)
 * @return 201 + список ScenarioDetailsDto созданных сценариев
 * @throws BadRequestException 400 - невалидный тип или тело
 * @throws NotFoundException 404 - проект чужой или не найден
 * @throws ConflictException 409 - сценарий с таким именем уже есть
 */
    @PostMapping
    @Operation(summary = "Создать сценарий",
            description = "Тело с type - конкретный сценарий (возможно "
                    + "несколько purchase с разным составом); "
                    + "без type - создать недостающие сценарии по "
                    + "умолчанию (base/purchase/raas; например, если проект "
                    + "создан без сценариев). 201; дубликат имени - 409; "
                    + "невалидный тип - 400; чужой проект - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Сценарии созданы"),
            @ApiResponse(responseCode = "400", description = "Невалидный тип/тело"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден"),
            @ApiResponse(responseCode = "409", description = "Дубликат имени")
    })
    public ResponseEntity<List<ScenarioDetailsDto>> create(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Valid @RequestBody(required = false)
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"type\": \"purchase\", "
                                            + "\"name\": \"Покупка с расширенным парком\"}")))
            ScenarioCreateRequest request) {
        return ResponseEntity.status(201).body(scenarioService.create(
                CurrentUser.requireUserId(), id, request));
    }

    /**
 * Правка имени и/или состава сценария. PUT /scenarios/{scenarioId}
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @param request новое имя и/или состав (замещается целиком)
 * @return обновлённый ScenarioDetailsDto
 * @throws BadRequestException 400 - пустое тело, состав в base, дубликаты
 * @throws NotFoundException 404 - проект или сценарий не найдены
 * @throws ConflictException 409 - дубликат имени
 */
    @PutMapping("/{scenarioId}")
    @Operation(summary = "Изменить сценарий",
            description = "Правка имени и/или состава оборудования "
                    + "(состав замещается целиком; ручные строки требуют "
                    + "причину). Для base непустой состав - 400 "
                    + "(базовый сценарий не содержит решений по определению). "
                    + "200; дубликат имени - 409; невалидное тело - 400; "
                    + "чужой проект/сценарий - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Сценарий обновлён"),
            @ApiResponse(responseCode = "400", description = "Невалидное тело/состав"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект или сценарий не найдены"),
            @ApiResponse(responseCode = "409", description = "Дубликат имени")
    })
    public ResponseEntity<ScenarioDetailsDto> update(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария", example = "2")
            @PathVariable Long scenarioId,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"name\": \"Покупка 2027\", "
                                            + "\"solutions\": [{\"solutionId\": 42, "
                                            + "\"quantity\": 3}]}")))
            ScenarioUpdateRequest request) {
        return ResponseEntity.ok(scenarioService.update(
                CurrentUser.requireUserId(), id, scenarioId, request));
    }

    /**
 * Изменение количества решения в составе.
 * PUT /scenarios/{scenarioId}/solutions/{solutionId}
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @param solutionId идентификатор решения каталога
 * @param request новое количество (1-10000)
 * @return обновлённая строка состава ScenarioSolutionDto
 * @throws BadRequestException 400 - количество вне 1-10000
 * @throws NotFoundException 404 - позиция не в составе или чужой проект/сценарий
 */
    @PutMapping("/{scenarioId}/solutions/{solutionId}")
    @Operation(summary = "Изменить количество решения в сценарии",
            description = "Точечная правка строки сценария: "
                    + "тело {quantity} 1-10000; исторические "
                    + "расчёты не трогаются (append-only) - новый "
                    + "расчёт пойдёт с новым составом. 200 (обновлённая "
                    + "строка); quantity вне 1-10000 - 400; позиция не в "
                    + "составе или чужой проект/сценарий - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Строка состава обновлена"),
            @ApiResponse(responseCode = "400", description = "Количество вне 1-10000"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Позиция/сценарий/проект не найдены")
    })
    public ResponseEntity<ScenarioSolutionDto> updateQuantity(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария", example = "2")
            @PathVariable Long scenarioId,
            @Parameter(description = "Идентификатор решения каталога",
                    example = "42")
            @PathVariable Long solutionId,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"quantity\": 3}")))
            SolutionQuantityRequest request) {
        return ResponseEntity.ok(scenarioService.updateQuantity(
                CurrentUser.requireUserId(), id, scenarioId, solutionId,
                request.quantity()));
    }

    /**
 * Ручное добавление решения в сценарий.
 * POST /scenarios/{scenarioId}/solutions
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария проекта
 * @param request решение, количество и обязательная причина
 * @return 201 No Content
 * @throws BadRequestException 400 - base-сценарий или нет причины
 * @throws NotFoundException 404 - проект/сценарий/решение не найдены
 * @throws ConflictException 409 - решение уже в составе
 */
    @PostMapping("/{scenarioId}/solutions")
    @Operation(summary = "Добавить решение в сценарий вручную",
            description = "Добавляет решение в сценарий, даже "
                    + "если оно не вошло в автоматическую подборку, с отображением "
                    + "предупреждения. Причина manualReason обязательна - она "
                    + "попадёт в отчёт. В base - 400 (базовый "
                    + "сценарий не содержит решений по определению). "
                    + "Дубликат - 409; чужой проект/сценарий или несуществующее "
                    + "решение - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Решение добавлено в состав"),
            @ApiResponse(responseCode = "400", description = "Base-сценарий или нет причины"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект/сценарий/решение не найдены"),
            @ApiResponse(responseCode = "409", description = "Решение уже в составе")
    })
    public ResponseEntity<Void> addManually(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария проекта", example = "2")
            @PathVariable Long scenarioId,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"solutionId\": 42, \"quantity\": 2, "
                                            + "\"manualReason\": \"Планируем расширение "
                                            + "проходов до 3 м\"}")))
            ManualAddRequest request) {
        scenarioService.addSolutionManually(CurrentUser.requireUserId(), id,
                scenarioId, request);
        return ResponseEntity.status(201).build();
    }

    /**
 * Удаление решения из состава.
 * DELETE /scenarios/{scenarioId}/solutions/{solutionId}
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @param solutionId идентификатор решения каталога
 * @return 204 No Content
 * @throws NotFoundException 404 - позиции нет в составе или чужой проект/сценарий
 */
    @DeleteMapping("/{scenarioId}/solutions/{solutionId}")
    @Operation(summary = "Убрать решение из состава сценария",
            description = "Удаляет ТОЛЬКО строку scenario_solution; "
                    + "исторические расчёты не трогаются (append-only) - "
                    + "старый расчёт воспроизводим со старым "
                    + "составом. 204; позиции нет в составе или чужой "
                    + "проект/сценарий - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Решение убрано из состава"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Позиция/сценарий/проект не найдены")
    })
    public ResponseEntity<Void> removeSolution(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария", example = "2")
            @PathVariable Long scenarioId,
            @Parameter(description = "Идентификатор решения каталога",
                    example = "42")
            @PathVariable Long solutionId) {
        scenarioService.removeSolution(CurrentUser.requireUserId(), id,
                scenarioId, solutionId);
        return ResponseEntity.noContent().build();
    }

    /**
 * Удаление сценария. DELETE /scenarios/{scenarioId}
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @return 204 No Content
 * @throws NotFoundException 404 - проект или сценарий не найдены
 */
    @DeleteMapping("/{scenarioId}")
    @Operation(summary = "Удалить сценарий",
            description = "Удаляет сценарий с составом и расчётами "
                    + "(каскадно). 204; чужой проект/сценарий - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Сценарий удалён"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект или сценарий не найдены")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария", example = "2")
            @PathVariable Long scenarioId) {
        scenarioService.delete(CurrentUser.requireUserId(), id, scenarioId);
        return ResponseEntity.noContent().build();
    }
}
