package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.domain.SimulationResult;
import me.yuugao.robomatch.dto.SimulationExportRequest;
import me.yuugao.robomatch.dto.SimulationRunDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.security.CurrentUser;
import me.yuugao.robomatch.service.SimulationStorage;
import me.yuugao.robomatch.simulation.SimulationService;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST-контроллер имитации (,
 * 2.1.4, 3.7.4, 4.3.3). Все эндпоинты authenticated (SecurityConfig),
 * изоляция как у проектов/экономики: userId из JWT, чужой - 404
 *.
 *
 * <p>Расчёт синхронный:
 * статус выполнения (running → completed/failed) виден через GET
 * последнего результата и историю.
 */
@RestController
@RequestMapping("/api/projects/{id}")
@RequiredArgsConstructor
@Tag(name = "Имитация 2D и KPI",
        description = "Запуск имитации сценария, KPI модели: "
                + "достижимость заявленной производительности, "
                + "загрузка, простои, узкие места; сохранение 2D-схемы")
@SecurityRequirement(name = "bearer-jwt")
public class SimulationController {

    private final SimulationService simulationService;
    private final SimulationStorage simulationStorage;

    /**
 * Запуск имитации. POST /scenarios/{scenarioId}/simulation/run
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария (purchase/raas)
 * @return SimulationRunDto с KPI и статусом расчёта
 * @throws BadRequestException 400 - не склад, base-сценарий, пустой состав
 * @throws NotFoundException 404 - проект или сценарий не найдены
 * @throws ConflictException 409 - имитация уже выполняется
 */
    @PostMapping("/scenarios/{scenarioId}/simulation/run")
    @Operation(summary = "Запустить имитацию сценария",
            description = "KPI-модель по составу сценария и параметрам "
                    + "склада: 6 KPI - заявленная и фактическая "
                    + "производительность, загрузка роботов, простои, узкие "
                    + "места по зонам, достижимость. Синхронно "
                    + "(до 60 с, статус running → completed/failed). "
                    + "400 - не склад / сценарий base / пустой состав; "
                    + "409 - имитация уже выполняется.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Результат имитации с KPI"),
            @ApiResponse(responseCode = "400", description = "Не склад/base/пустой состав/параметры"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект или сценарий не найдены"),
            @ApiResponse(responseCode = "409", description = "Имитация уже выполняется")
    })
    public ResponseEntity<SimulationRunDto> run(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария (purchase/raas)",
                    example = "34")
            @PathVariable Long scenarioId) {
        return ResponseEntity.ok(simulationService.run(
                CurrentUser.requireUserId(), id, scenarioId));
    }

    /**
 * Последний результат имитации сценария. GET .../simulation
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @return SimulationRunDto последнего запуска (любой статус)
 * @throws NotFoundException 404 - имитация не запускалась или чужой проект
 */
    @GetMapping("/scenarios/{scenarioId}/simulation")
    @Operation(summary = "Последний результат имитации сценария",
            description = "Свежий simulation_result сценария (любого "
                    + "статуса) с KPI из kpi_json; 404 - имитация ещё не "
                    + "запускалась.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Последний результат"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Имитация не запускалась/не найдено")
    })
    public ResponseEntity<SimulationRunDto> latest(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария", example = "34")
            @PathVariable Long scenarioId) {
        return ResponseEntity.ok(simulationService.latest(
                CurrentUser.requireUserId(), id, scenarioId));
    }

    /**
 * История имитаций проекта. GET /simulations
 *
 * @param id идентификатор проекта
 * @return список SimulationRunDto, свежие сверху
 * @throws NotFoundException 404 - проект чужой или не найден
 */
    @GetMapping("/simulations")
    @Operation(summary = "История имитаций проекта",
            description = "Все запуски имитаций проекта, свежие сверху "
                    + "(отладка и отчёт,).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "История имитаций"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<List<SimulationRunDto>> history(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(simulationService.history(
                CurrentUser.requireUserId(), id));
    }

    /**
 * Сохранить 2D-схему. POST /simulations/{simulationId}/export
 *
 * @param id идентификатор проекта
 * @param simulationId идентификатор результата имитации
 * @param request SVG-разметка текущей схемы
 * @return карта {exportUrl, file} со ссылкой на сохранённый файл
 * @throws NotFoundException 404 - нет результата имитации или чужой проект
 */
    @PostMapping("/simulations/{simulationId}/export")
    @Operation(summary = "Сохранить 2D-схему имитации",
            description = "SVG текущей схемы сохраняется в хранилище "
                    + "data/simulations/{projectId}/{simulationId}.svg, "
                    + "ссылка - в kpi_json.exportUrl этого результата "
                    + "имитации. Симметрично скачиванию: тот же путь, "
                    + "GET отдаёт файл. Сервер санитизирует разметку "
                    + "(без скриптов).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Схема сохранена, ссылка"),
            @ApiResponse(responseCode = "400", description = "Имитация не завершена или битый SVG"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Результата имитации нет")
    })
    public ResponseEntity<Map<String, String>> exportSchema(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор simulation_result",
                    example = "12")
            @PathVariable Long simulationId,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"svg\": \"<svg xmlns=\\\"http://www.w3.org/2000/svg\\\" "
                                            + "width=\\\"800\\\" height=\\\"600\\\">"
                                            + "<rect x=\\\"40\\\" y=\\\"40\\\" width=\\\"720\\\" "
                                            + "height=\\\"520\\\" fill=\\\"#eef\\\"/></svg>\"}")))
            SimulationExportRequest request) {
        Long userId = CurrentUser.requireUserId();
        // сохраняется схема указанного результата имитации
        SimulationResult row = simulationService.requireOwnedResult(userId,
                id, simulationId);
        if (!"completed".equals(row.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Имитация не завершена успешно - "
                            + "запустите её и повторите сохранение схемы"));
        }
        String relative;
        try {
            relative = simulationStorage.saveSchema(id, row.getId(),
                    request.svg());
        } catch (IllegalArgumentException ex) {
            // битая разметка (не XML) - 400 понятной
            // ошибкой, не 500
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "Схема не является корректным SVG (XML): "
                            + ex.getMessage()));
        }
        String exportUrl = "/api/projects/" + id + "/simulations/"
                + row.getId() + "/export";
        simulationService.attachExportUrl(row, exportUrl);
        return ResponseEntity.ok(Map.of(
                "exportUrl", exportUrl,
                "file", relative));
    }

    /**
 * Скачать сохранённую схему. GET /simulations/{simulationId}/export
 *
 * @param id идентификатор проекта
 * @param simulationId идентификатор simulation_result
 * @return SVG-файл (image/svg+xml, attachment)
 * @throws NotFoundException 404 - схема не сохранялась или чужой проект
 */
    @GetMapping("/simulations/{simulationId}/export")
    @Operation(summary = "Скачать сохранённую 2D-схему",
            description = "Файл data/simulations/{projectId}/"
                    + "{simulationId}.svg; 404 - схема не "
                    + "сохранялась или чужой проект.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SVG-файл схемы"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Схема не сохранялась/не найдена")
    })
    public ResponseEntity<String> downloadSchema(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор simulation_result",
                    example = "12")
            @PathVariable Long simulationId) {
        SimulationResult row = simulationService.requireOwnedResult(
                CurrentUser.requireUserId(), id, simulationId);
        String relative = id + "/" + row.getId() + ".svg";
        // до первого сохранения файла - 404, не 500
        String svg = simulationStorage.readSchema(relative);
        if (svg == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("image/svg+xml"))
                .header("Content-Disposition",
                        "attachment; filename=simulation-" + row.getId()
                                + ".svg")
                .body(svg);
    }
}
