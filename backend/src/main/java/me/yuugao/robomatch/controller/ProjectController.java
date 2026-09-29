package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.security.CurrentUser;
import me.yuugao.robomatch.service.ProjectService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST-контроллер проектов (; user-flow.md шаги
 * 1–2): список, создание, карточка, редактирование, копирование,
 * удаление — только для аутентифицированных (SecurityConfig: /api/**
 * authenticated) и только над СВОИМИ проектами (изоляция).
 * <p>
 * userId не приходит из запроса НИКОГДА — только из JWT
 * (CurrentUser.requireUserId: fail-fast 401 вместо молчаливого null).
 * Контроллер тонкий: приём HTTP, валидация тела (@Valid), вызов сервиса,
 * ответ DTO. Чужой проект неотличим от несуществующего — 404 (не 403).
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "Проекты", description = "CRUD, копирование и список проектов пользователя")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectController {

    private final ProjectService projectService;

    /**
 * Список проектов текущего пользователя. GET /api/projects
 *
 * @param page номер страницы, начиная с 0 (null — 0)
 * @param size размер страницы, 1-100 (null — 20)
 * @return страница ProjectSummaryDto, свежие сверху
 * @throws BadRequestException 400 — page/size вне диапазона
 */
    @GetMapping
    @Operation(summary = "Список моих проектов",
            description = "Проекты текущего пользователя (изоляция,), "
                    + "сортировка по дате изменения (свежие сверху), пагинация. "
                    + "Чужие проекты в ответе невозможны.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Страница проектов"),
            @ApiResponse(responseCode = "400", description = "page/size вне диапазона"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен")
    })
    public PageResponse<ProjectSummaryDto> list(
            @Parameter(description = "Страница, начиная с 0", example = "0")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Размер страницы, 1-100 (по умолчанию 20)",
                    example = "20")
            @RequestParam(required = false) Integer size) {
        return projectService.listProjects(CurrentUser.requireUserId(), page, size);
    }

    /**
 * Создание проекта. POST /api/projects
 *
 * @param request имя, описание и тип объекта
 * @return 201 + карточка созданного проекта ProjectFullDto (статус draft)
 * @throws BadRequestException 400 — невалидное тело или неизвестный тип объекта
 * @throws ConflictException 409 — имя уже занято у пользователя
 */
    @PostMapping
    @Operation(summary = "Создать проект",
            description = "Имя уникально в рамках пользователя: дубликат — 409. "
                    + "Неизвестный objectTypeId — 400. Новый проект получает "
                    + "статус draft. 201 с карточкой созданного проекта.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Проект создан"),
            @ApiResponse(responseCode = "400", description = "Невалидное тело/тип объекта"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "409", description = "Дубликат имени")
    })
    public ResponseEntity<ProjectFullDto> create(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"name\": \"Склад в Химках\", "
                                            + "\"description\": \"Центральный склад, 3 зоны хранения\", "
                                            + "\"objectTypeId\": 1}")))
            ProjectCreateRequest request) {
        ProjectFullDto created =
                projectService.createProject(CurrentUser.requireUserId(), request);
        return ResponseEntity
                .created(URI.create("/api/projects/" + created.getId()))
                .body(created);
    }

    /**
 * Карточка проекта. GET /api/projects/{id}
 *
 * @param id идентификатор проекта
 * @return карточка ProjectFullDto с типом объекта
 * @throws NotFoundException 404 — проект чужой или не найден
 */
    @GetMapping("/{id}")
    @Operation(summary = "Карточка проекта",
            description = "Метаданные проекта с типом объекта. Чужой проект "
                    + "и несуществующий отвечают одинаково — 404 (изоляция).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Карточка проекта"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<ProjectFullDto> get(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(
                projectService.getProject(CurrentUser.requireUserId(), id));
    }

    /**
 * Редактирование проекта. PUT /api/projects/{id}
 *
 * @param id идентификатор проекта
 * @param request новое имя и описание (тип объекта не меняется)
 * @return обновлённая карточка ProjectFullDto
 * @throws BadRequestException 400 — невалидное тело
 * @throws NotFoundException 404 — проект чужой или не найден
 * @throws ConflictException 409 — дубликат имени
 */
    @PutMapping("/{id}")
    @Operation(summary = "Изменить проект",
            description = "Имя и описание. Тип объекта не меняется: он задаёт "
                    + "набор параметров проекта. Дубликат имени — 409, "
                    + "чужой проект — 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Проект обновлён"),
            @ApiResponse(responseCode = "400", description = "Невалидное тело"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден"),
            @ApiResponse(responseCode = "409", description = "Дубликат имени")
    })
    public ResponseEntity<ProjectFullDto> update(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"name\": \"Склад в Химках, зона А\", "
                                            + "\"description\": \"Центральный склад, "
                                            + "3 зоны хранения, новая линия\"}")))
            ProjectUpdateRequest request) {
        return ResponseEntity.ok(
                projectService.updateProject(CurrentUser.requireUserId(), id, request));
    }

    /**
 * Копирование проекта. POST /api/projects/{id}/copy
 *
 * @param id идентификатор исходного проекта
 * @return 201 + карточка копии ProjectFullDto (статус draft)
 * @throws NotFoundException 404 — проект чужой или не найден
 */
    @PostMapping("/{id}/copy")
    @Operation(summary = "Скопировать проект",
            description = "Копия с именем «<имя> (копия)», при конфликте — "
                    + "«<имя> (копия N)». Метаданные копируются, статус — draft. "
                    + "201 с карточкой копии.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Копия создана"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<ProjectFullDto> copy(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        ProjectFullDto copy = projectService.copyProject(CurrentUser.requireUserId(), id);
        return ResponseEntity
                .created(URI.create("/api/projects/" + copy.getId()))
                .body(copy);
    }

    /**
 * Удаление проекта. DELETE /api/projects/{id}
 *
 * @param id идентификатор проекта
 * @return 204 No Content
 * @throws NotFoundException 404 — проект чужой или не найден
 */
    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить проект",
            description = "Каскадно с дочерними данными: параметры, вложения, "
                    + "сценарии, подборы, расчёты, экспорты. "
                    + "204, чужой проект — 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Проект удалён"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        projectService.deleteProject(CurrentUser.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
