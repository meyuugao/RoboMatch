package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.economics.AssumptionService;
import me.yuugao.robomatch.economics.EconomicCalculationService;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.security.CurrentUser;

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
 * REST-контроллер экономики (;
 * формулы - docs/economic_model.md). Все эндпоинты authenticated
 * (SecurityConfig), изоляция как у проектов: userId из JWT, чужой - 404.
 *
 * <p>Расчёт выполняется синхронно (
 * проекта; решение «фон vs синхронно» - architecture.md
 * раздел 11): клиент показывает индикатор выполнения на кнопке.
 */
@RestController
@RequestMapping("/api/projects/{id}")
@RequiredArgsConstructor
@Tag(name = "Экономика и сценарии",
        description = "Допущения, расчёт сценариев с "
                + "версионированием, сравнение сценариев, "
                + "чувствительность, ручная "
                + "корректировка")
@SecurityRequirement(name = "bearer-jwt")
public class EconomicsController {

    private final AssumptionService assumptionService;
    private final EconomicCalculationService calculationService;

    // ------------------------------------------------------------------
    // Допущения
    // ------------------------------------------------------------------

    /**
 * Текущие допущения проекта. GET /assumptions
 *
 * @param id идентификатор проекта
 * @return список AssumptionDto (глобальные дефолты + переопределения)
 * @throws NotFoundException 404 - проект чужой или не найден
 */
    @GetMapping("/assumptions")
    @Operation(summary = "Допущения экономики проекта",
            description = "Каталог допущений с текущими значениями: "
                    + "глобальные дефолты + "
                    + "переопределения пользователя. Источник, диапазон "
                    + "и влияние - в каждом элементе.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Каталог допущений"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<List<AssumptionDto>> assumptions(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(assumptionService.list(
                CurrentUser.requireUserId(), id));
    }

    /**
 * Изменить допущения. PUT /assumptions
 *
 * @param id идентификатор проекта
 * @param request карта «код → значение|null» (null - сброс в дефолт)
 * @return список AssumptionDto с новыми значениями
 * @throws BadRequestException 400 - неизвестный код или значение вне диапазона
 * @throws NotFoundException 404 - проект чужой или не найден
 * @throws ConflictException 409 - допущения параллельно изменены
 */
    @PutMapping("/assumptions")
    @Operation(summary = "Изменить допущения",
            description = "Карта «код → значение|null» (null - "
                    + "сброс в дефолт). Новое значение попадает в снимок "
                    + "следующего расчёта (append-only). 200; "
                    + "неизвестный код / вне диапазона - 400; чужой - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Допущения обновлены"),
            @ApiResponse(responseCode = "400", description = "Неизвестный код/вне диапазона"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден"),
            @ApiResponse(responseCode = "409", description = "Параллельное изменение "
                    + "допущений")
    })
    public ResponseEntity<List<AssumptionDto>> updateAssumptions(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"values\": {\"k_load\": \"0.8\", "
                                            + "\"tariff_rub_kwh\": \"7.5\"}}")))
            AssumptionUpdateRequest request) {
        return ResponseEntity.ok(assumptionService.update(
                CurrentUser.requireUserId(), id, request));
    }

    // ------------------------------------------------------------------
    // Расчёты
    // ------------------------------------------------------------------

    /**
 * Расчёт сценария. POST /scenarios/{scenarioId}/calculate
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @return CalculationFullDto - метрики, снимок допущений, чувствительность
 * @throws BadRequestException 400 - не склад, нет обязательных параметров,
 * нулевые знаменатели (EconomicValidationException)
 * @throws NotFoundException 404 - проект или сценарий не найдены
 */
    @PostMapping("/scenarios/{scenarioId}/calculate")
    @Operation(summary = "Рассчитать сценарий",
            description = "Сквозной расчёт: "
                    + "CAPEX/OPEX/TCO/эффект/окупаемость/ROI + "
                    + "чувствительность. Каждый вызов - НОВАЯ строка "
                    + "calculation (append-only) со снимком "
                    + "допущений. Синхронно, не более 10 с. "
                    + "200; нет обязательных параметров / нулевые "
                    + "знаменатели - 400; чужой проект/сценарий - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Расчёт выполнен"),
            @ApiResponse(responseCode = "400", description = "Нет обязательных "
                    + "параметров/нулевые знаменатели"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект или сценарий не найдены")
    })
    public ResponseEntity<CalculationFullDto> calculate(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария", example = "2")
            @PathVariable Long scenarioId) {
        return ResponseEntity.ok(calculationService.calculate(
                CurrentUser.requireUserId(), id, scenarioId));
    }

    /**
 * История расчётов сценария. GET /scenarios/{scenarioId}/calculations
 *
 * @param id идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @return список CalculationDto с версиями, свежие сверху
 * @throws NotFoundException 404 - проект или сценарий не найдены
 */
    @GetMapping("/scenarios/{scenarioId}/calculations")
    @Operation(summary = "История расчётов сценария",
            description = "Все расчёты сценария с версиями данных/модели "
                    + ", свежие сверху. Чужой - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "История расчётов"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект или сценарий не найдены")
    })
    public ResponseEntity<List<CalculationDto>> history(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор сценария", example = "2")
            @PathVariable Long scenarioId) {
        return ResponseEntity.ok(calculationService.history(
                CurrentUser.requireUserId(), id, scenarioId));
    }

    /**
 * Детальный расчёт. GET /calculations/{calculationId}
 *
 * @param id идентификатор проекта
 * @param calculationId идентификатор расчёта
 * @return CalculationFullDto с разбивками и корректировками
 * @throws NotFoundException 404 - расчёт или проект не найдены
 */
    @GetMapping("/calculations/{calculationId}")
    @Operation(summary = "Детальный расчёт",
            description = "Метрики + снимок допущений + разбивка CAPEX/OPEX "
                    + "+ чувствительность + корректировки "
                    + "(воспроизведение по версиям). Чужой - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Детальный расчёт"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Расчёт или проект не найдены")
    })
    public ResponseEntity<CalculationFullDto> detail(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор расчёта", example = "7")
            @PathVariable Long calculationId) {
        return ResponseEntity.ok(calculationService.detail(
                CurrentUser.requireUserId(), id, calculationId));
    }

    /**
 * Ручная корректировка метрики. POST /calculations/{id}/adjust
 *
 * @param id идентификатор проекта
 * @param calculationId идентификатор расчёта
 * @param request метрика, новое значение, причина
 * @return новый CalculationFullDto, порождённый корректировкой
 * @throws BadRequestException 400 - совпадающее значение или неизвестная метрика
 * @throws NotFoundException 404 - расчёт или проект не найдены
 */
    @PostMapping("/calculations/{calculationId}/adjust")
    @Operation(summary = "Скорректировать метрику расчёта",
            description = "Фиксация «что / было / стало / почему / "
                    + "кто / когда». Порождает НОВЫЙ расчёт (append-only), "
                    + "запись о вмешательстве - в manual_adjustment. "
                    + "200; совпадающее значение / неизвестная метрика - 400; "
                    + "чужой - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Новый расчёт с корректировкой"),
            @ApiResponse(responseCode = "400", description = "Совпадающее значение/неизвестная метрика"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Расчёт или проект не найдены")
    })
    public ResponseEntity<CalculationFullDto> adjust(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Идентификатор расчёта", example = "7")
            @PathVariable Long calculationId,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"metricName\": \"effect_year\", "
                                            + "\"newValue\": 145000000, "
                                            + "\"reason\": \"Согласована скидка вендора 8% на сервис\"}")))
            ManualAdjustRequest request) {
        return ResponseEntity.ok(calculationService.adjust(
                CurrentUser.requireUserId(), id, calculationId, request));
    }

    // ------------------------------------------------------------------
    // Сравнение
    // ------------------------------------------------------------------

    /**
 * Таблица сравнения сценариев. GET /compare
 *
 * @param id идентификатор проекта
 * @return ComparisonDto - базовый/покупка/RaaS в одной таблице
 * @throws NotFoundException 404 - проект чужой или не найден
 */
    @GetMapping("/compare")
    @Operation(summary = "Сравнение сценариев",
            description = "Базовый + покупка + RaaS "
                    + "в одной таблице (N_robots, CAPEX, OPEX, dOPEX, dFOT, "
                    + "эффект, окупаемость, ROI, TCO) с дельтой к базовому, "
                    + "интерпретацией окупаемости и "
                    + "чувствительностью. Берёт последний расчёт "
                    + "каждого сценария. Чужой - 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Таблица сравнения"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен"),
            @ApiResponse(responseCode = "404", description = "Проект чужой или не найден")
    })
    public ResponseEntity<ComparisonDto> compare(
            @Parameter(description = "Идентификатор проекта", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(calculationService.compare(
                CurrentUser.requireUserId(), id));
    }
}
