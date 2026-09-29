package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.SelectionRunDto;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.security.CurrentUser;
import me.yuugao.robomatch.selection.SelectionService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * REST-контроллер подбора (; user-flow.md
 * шаг 3). Только аутентифицированные (SecurityConfig: /api/** -
 * authenticated) и только над СВОИМИ проектами (изоляция).
 * <p>
 * Контроллер тонкий: приём HTTP, вызов сервиса, ответ DTO.
 */
@RestController
@RequestMapping("/api/projects/{id}/selection")
@RequiredArgsConstructor
@Tag(name = "Подбор решений",
        description = "Фильтрация по ТТХ и инфраструктурным ограничениям, статусы "
                + "fit/needs_check/excluded, объяснимое ранжирование")
@SecurityRequirement(name = "bearer-jwt")
public class SelectionController {

    private final SelectionService selectionService;

    /**
 * Запуск подбора. POST /selection/run
 *
 * @param id идентификатор проекта
 * @return SelectionRunDto - сценарии и решения с объяснениями
 * @throws NotFoundException 404 - проект чужой или не найден
 * @throws ConflictException 409 - параллельный запуск подбора проекта
 */
    @PostMapping("/run")
    @Operation(summary = "Запустить подбор решений",
            description = "Сквозной алгоритм: "
                    + "отрасли объекта → пул применимых решений → 8 обязательных "
                    + "ТТХ → статусы fit/needs_check/excluded → ранжирование fit "
                    + "с весами и вкладом критериев → UPSERT "
                    + "selection_result. Первый запуск создаёт 3 сценария "
                    + "(base/purchase/raas). Повторный запуск идемпотентен. "
                    + "Чужой проект - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Результаты подбора"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден"),
            @ApiResponse(responseCode = "409", description = "Подбор уже выполняется")
    })
    public ResponseEntity<SelectionRunDto> run(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(
                selectionService.run(CurrentUser.requireUserId(), id));
    }

    /**
 * Результаты подбора с объяснениями. GET /selection
 *
 * @param id идентификатор проекта
 * @return SelectionRunDto (пусто, если подбор не запускался)
 * @throws NotFoundException 404 - проект чужой или не найден
 */
    @GetMapping
    @Operation(summary = "Результаты подбора",
            description = "Сценарии проекта и все применимые решения с "
                    + "объяснениями: статус, причина включения/"
                    + "исключения, ранг, Score, вклад критериев (число + "
                    + "диаграмма), недостающие данные. Пусто, если "
                    + "подбор ещё не запускался. Чужой проект - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Результаты подбора"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<SelectionRunDto> results(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(
                selectionService.getResults(CurrentUser.requireUserId(), id));
    }

}
