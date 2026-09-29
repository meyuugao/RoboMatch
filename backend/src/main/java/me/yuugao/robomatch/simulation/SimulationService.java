package me.yuugao.robomatch.simulation;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.SimulationRunDto;
import me.yuugao.robomatch.economics.AssumptionService;
import me.yuugao.robomatch.economics.AssumptionService.EffectiveAssumptions;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

/**
 * Сервис имитации (;
 * движок KPI - {@link SimulationModel}, требования -
 * requirements/simulation.md).
 *
 * <p>СКВОЗНОЙ ПУТЬ: вход (параметры склада + состав сценария + ТТХ
 * скорости + допущения) → валидация (гейт склада, не base, состав
 * непустой) → расчёт KPI (детерминированная модель, миллисекунды) →
 * запись simulation_result (running → completed/failed) → KPI-панель
 * и 2D-схема на клиенте.
 *
 * <p>СИНХРОННО: расчёт в памяти поверх 5-6 SQL-запросов -
 * укладывается в секунды; статус running фиксируется в БД ДО расчёта
 * отдельной транзакцией TransactionTemplate (как ProjectService при
 * удалении), чтобы статус выполнения был виден пользователю.
 * Превышение 60 с - статус failed с предупреждением (практически
 * недостижимо; решение «синхронно vs очередь» - architecture.md).
 *
 * <p>ИДЕМПОТЕНТНОСТЬ И ГОНКА: повторный запуск после completed - новая
 * строка истории с теми же KPI (модель детерминирована); запуск при
 * живом running - 409. Зависший running (старше 15 минут) помечается
 * failed при следующем запуске (технический порог, не экономический
 * коэффициент - задокументирован в architecture.md).
 *
 * <p>СВЕРКА С ЭКОНОМИКОЙ:
 * KPI модели пишутся рядом с последним расчётом экономики сценария
 * (selectedRobots, requiredRobots из metrics_json) с предупреждением
 * при расхождении состава или недоборе парка.
 *
 * <p>ИЗОЛЯЦИЯ: userId из JWT; чужой проект/сценарий/результат
 * - 404, не 403. Гейт типа объекта: is_calc_enabled (MVP: склад).
 */
@Service
@RequiredArgsConstructor
public class SimulationService {

    /**
 * Технический порог живого running: запуск старше 15 минут считается
 * зависшим (процесс убит) и помечается failed при следующем запуске.
 */
    static final Duration RUNNING_TTL = Duration.ofMinutes(15);

    /**
 * Порог производительности.
 */
    static final Duration MAX_DURATION = Duration.ofSeconds(60);

    /**
 * Локальный Jackson 2 (не Spring-бин): как в EconomicCalculationService
 * - контейнер Boot 4 работает на Jackson 3.
 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectRepository projectRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final ObjectTypeParameterRepository objectTypeParameterRepository;
    private final ParameterTypeRepository parameterTypeRepository;
    private final ProjectParameterValueRepository valueRepository;
    private final ScenarioRepository scenarioRepository;
    private final ScenarioSolutionRepository scenarioSolutionRepository;
    private final SolutionRepository solutionRepository;
    private final SimulationResultRepository simulationRepository;
    private final CalculationRepository calculationRepository;
    private final AssumptionService assumptionService;
    private final TransactionTemplate transactionTemplate;

    // ==================================================================
    // Запуск
    // ==================================================================

    private static Integer intOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal num(String value) {
        return value == null ? null
                : new BigDecimal(value.replace(',', '.'));
    }

    /**
 * Запуск имитации сценария: валидация → строка running → расчёт →
 * completed/failed. Возвращает представление результата с KPI.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария (purchase/raas)
 * @return результат запуска (KPI или причина неудачи)
 */
    public SimulationRunDto run(Long userId, Long projectId,
                                Long scenarioId) {
        Project project = requireOwnedProject(userId, projectId);
        Scenario scenario = requireOwnedScenario(projectId, scenarioId);
        requireWarehouse(project);
        if (scenario.getType() == ScenarioType.BASE) {
            throw new BadRequestException("Базовый сценарий не содержит "
                    + "решений - имитация запускается для покупки или RaaS "
                    + "(инвариант base)");
        }
        List<ScenarioSolution> composition = scenarioSolutionRepository
                .findAllByScenarioId(scenario.getId());
        int selectedRobots = composition.stream()
                .mapToInt(ScenarioSolution::getQuantity).sum();
        if (selectedRobots <= 0) {
            throw new BadRequestException("Состав сценария пуст - добавьте "
                    + "решения (подбором или вручную) и повторите запуск "
                    + "имитации");
        }

        // Гонка повторного запуска: живой running - 409; зависший - failed
        transactionTemplate.executeWithoutResult(tx ->
                simulationRepository
                        .findFirstByScenarioIdAndStatusOrderByIdDesc(
                                scenario.getId(), "running")
                        .ifPresent(running -> {
                            if (running.getStartedAt() != null
                                    && running.getStartedAt().isAfter(
                                    Instant.now().minus(RUNNING_TTL))) {
                                throw new ConflictException(
                                        "Имитация сценария уже выполняется - "
                                                + "дождитесь завершения или "
                                                + "повторите через "
                                                + RUNNING_TTL.toMinutes()
                                                + " минут");
                            }
                            failRow(running, "Запуск прерван (истёк "
                                    + RUNNING_TTL.toMinutes()
                                    + "-минутный порог выполнения)");
                        }));

        // Статус выполнения виден пользователю: running
        // фиксируется ОТДЕЛЬНОЙ транзакцией до расчёта
        SimulationResult row = transactionTemplate.execute(tx ->
                simulationRepository.save(SimulationResult.builder()
                        .scenarioId(scenario.getId())
                        .startedAt(Instant.now())
                        .status("running")
                        .build()));

        Instant startedAt = Instant.now();
        try {
            Map<String, Object> kpi = computeKpi(project, scenario,
                    composition, selectedRobots);
            long elapsedMs = Duration.between(startedAt, Instant.now())
                    .toMillis();
            if (elapsedMs > MAX_DURATION.toMillis()) {
                throw new IllegalStateException("Модель не уложилась в "
                        + MAX_DURATION.getSeconds() + " секунд - "
                        + "упростите состав сценария");
            }
            row.setKpiJson(writeJson(kpi));
            row.setStatus("completed");
            row.setFinishedAt(Instant.now());
            SimulationResult saved = transactionTemplate.execute(tx ->
                    simulationRepository.save(row));
            return readKpi(saved);
        } catch (IllegalArgumentException ex) {
            return failRow(row, ex.getMessage());
        } catch (Exception ex) {
            return failRow(row, "Ошибка выполнения модели: "
                    + ex.getMessage());
        }
    }

    /**
 * Последний результат сценария (любого статуса); 404 если нет.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @return последний результат имитации сценария
 */
    @Transactional(readOnly = true)
    public SimulationRunDto latest(Long userId, Long projectId,
                                   Long scenarioId) {
        requireOwnedProject(userId, projectId);
        requireOwnedScenario(projectId, scenarioId);
        SimulationResult row = simulationRepository
                .findFirstByScenarioIdOrderByIdDesc(scenarioId)
                .orElseThrow(() -> new NotFoundException(
                        "Имитация ещё не запускалась"));
        return readKpi(row);
    }

    /**
 * История имитаций проекта (свежие сверху; изоляция по проекту).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return имитации проекта свежими сверху
 */
    @Transactional(readOnly = true)
    public List<SimulationRunDto> history(Long userId, Long projectId) {
        requireOwnedProject(userId, projectId);
        return simulationRepository
                .findAllByProjectIdAndUserIdOrderByIdDesc(projectId, userId)
                .stream().map(this::readKpi).toList();
    }

    // ==================================================================
    // Сбор входов и расчёт
    // ==================================================================

    /**
 * Результат по id (для выгрузки сохранённой схемы, изоляция 404).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param simulationId идентификатор результата имитации
 * @return строка результата имитации (сущность SimulationResult)
 */
    @Transactional(readOnly = true)
    public SimulationResult requireOwnedResult(Long userId, Long projectId,
                                               Long simulationId) {
        return simulationRepository
                .findByIdAndProjectIdAndUserId(simulationId, projectId,
                        userId)
                .orElseThrow(() -> new NotFoundException(
                        "Результат имитации не найден"));
    }

    /**
 * Записать exportUrl сохранённой схемы в kpi_json результата
 *.
 *
 * @param row строка результата имитации (сущность SimulationResult)
 * @param exportUrl ссылка на выгруженную схему
 */
    public void attachExportUrl(SimulationResult row, String exportUrl) {
        transactionTemplate.executeWithoutResult(tx -> {
            Map<String, Object> kpi = parseKpi(row.getKpiJson());
            kpi.put("exportUrl", exportUrl);
            row.setKpiJson(writeJson(kpi));
            simulationRepository.save(row);
        });
    }

    private Map<String, Object> computeKpi(Project project,
                                           Scenario scenario,
                                           List<ScenarioSolution> composition,
                                           int selectedRobots) {
        Map<String, String> params = effectiveParams(project);
        EffectiveAssumptions eff =
                assumptionService.effectiveSnapshot(project.getId());

        BigDecimal shifts = num(params.get("shifts_per_day"));
        BigDecimal shiftDuration = num(params.get("shift_duration"));
        BigDecimal peakFactor = num(params.get("peak_load_factor"));
        BigDecimal inbound = num(params.get("inbound_pallets_per_day"));
        BigDecimal outbound = num(params.get("outbound_pallets_per_day"));
        if (shifts == null || shiftDuration == null || peakFactor == null
                || inbound == null || outbound == null) {
            throw new BadRequestException("Не заполнены параметры склада, "
                    + "нужные имитации (сменность, продолжительность смены, "
                    + "пиковый коэффициент, приёмка/отгрузка паллет в сутки) "
                    + "- заполните параметры объекта");
        }
        BigDecimal hoursPerDay = shifts.multiply(shiftDuration);
        if (hoursPerDay.signum() <= 0) {
            throw new BadRequestException("Режим работы даёт 0 часов в "
                    + "сутки - проверьте сменность и продолжительность "
                    + "смены");
        }
        BigDecimal dailyOps = inbound.add(outbound);
        if (dailyOps.signum() <= 0) {
            throw new BadRequestException("Суточный объём операций равен "
                    + "нулю - проверьте приёмку и отгрузку (паллет/сутки)");
        }
        BigDecimal peakDemand = dailyOps.divide(hoursPerDay, 6,
                RoundingMode.HALF_UP).multiply(peakFactor);

        // --- состав: скорость из ТТХ (зеркало solution.speed_m_s) -----
        Map<Long, Solution> solutions = solutionRepository
                .findAllById(composition.stream()
                        .map(ScenarioSolution::getSolutionId).toList())
                .stream().collect(Collectors.toMap(Solution::getId,
                        Function.identity()));
        List<SimulationModel.RobotLine> lines = new ArrayList<>();
        List<String> inputWarnings = new ArrayList<>();
        for (ScenarioSolution rowLine : composition) {
            Solution solution = solutions.get(rowLine.getSolutionId());
            if (solution == null) {
                continue;
            }
            if (solution.getSpeedMs() == null) {
                inputWarnings.add("У решения «" + solution.getName()
                        + "» нет ТТХ «скорость» (speed_m_s) - применяется "
                        + "допущение sim_avg_robot_speed_m_s");
            }
            lines.add(new SimulationModel.RobotLine(solution.getId(),
                    solution.getName(), rowLine.getQuantity(),
                    solution.getSpeedMs()));
        }
        // скорость модели: средневзвешенная по парку - строки без ТТХ
        // получают фолбэк sim_avg_robot_speed_m_s (assumptions §22-бис:
        // «фолбэк, если у решения в составе нет ТТХ»), поэтому смешанный
        // состав честно усредняется с допущением, а не выпадает из среднего
        BigDecimal speed = SimulationModel.fleetSpeedOf(lines,
                eff.numeric("sim_avg_robot_speed_m_s"));

        // СНИМОК допущений последнего расчёта экономики: сверка
        // «подтверждает расчёт» - P_effective берётся из metrics_json,
        // а не из текущих допущений проекта); null - расчёта нет
        Map<String, Object> econMetrics = latestMetrics(scenario.getId());
        Map<String, String> econAssumptions = econAssumptionsOf(econMetrics);
        // Ratio_infra зарядных станций - из того же снимка расчёта
        // (консистентность числа станций с nInfra последнего расчёта);
        // расчёта нет или в снимке нет - текущее допущение проекта
        BigDecimal ratioInfra = decimalOf(
                econAssumptions.get("ratio_infra"));
        if (ratioInfra == null) {
            ratioInfra = eff.numeric("ratio_infra");
        }

        SimulationModel.SimulationInput input =
                new SimulationModel.SimulationInput(
                        selectedRobots, peakDemand, inbound, outbound,
                        hoursPerDay, peakFactor,
                        eff.numeric("robot_nominal_productivity_per_hour"),
                        eff.numeric("k_load"),
                        eff.numeric("k_availability"),
                        ratioInfra,
                        speed,
                        eff.numeric("sim_avg_route_length_m"),
                        eff.numeric("sim_pick_drop_sec"),
                        lines,
                        decimalOf(econAssumptions.get(
                                "robot_nominal_productivity_per_hour")),
                        decimalOf(econAssumptions.get("k_load")),
                        decimalOf(econAssumptions.get("k_availability")));
        SimulationModel.SimulationKpi kpi = SimulationModel.calculate(input);

        Map<String, Object> result = jsonMap(kpi);
        result.put("scenarioId", scenario.getId());
        result.put("scenarioName", scenario.getName());
        result.put("robots", selectedRobots);
        // состав для схемы (HashMap: Map.of не допускает null-скорости)
        List<Map<String, Object>> compositionView = new ArrayList<>();
        for (SimulationModel.RobotLine line : lines) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("solutionId", line.solutionId());
            entry.put("name", line.name());
            entry.put("quantity", line.quantity());
            entry.put("speedMs", line.speedMs());
            compositionView.add(entry);
        }
        result.put("composition", compositionView);
        Map<String, Object> inputsView = new HashMap<>();
        inputsView.put("routeLengthM", input.routeLengthM());
        inputsView.put("pickDropSec", input.pickDropSec());
        inputsView.put("speedMs", speed);
        inputsView.put("hoursPerDay", hoursPerDay);
        inputsView.put("inboundPerDay", inbound);
        inputsView.put("outboundPerDay", outbound);
        inputsView.put("peakFactor", peakFactor);
        result.put("inputs", inputsView);
        List<String> warnings = new ArrayList<>(inputWarnings);
        warnings.addAll(kpi.warnings());
        // сверка с расчётом экономики
        warnings.addAll(economicsCrossCheck(scenario, selectedRobots,
                econMetrics));
        result.put("warnings", warnings);
        return result;
    }

    /**
 * Сверка с последним расчётом экономики (
 * подтверждает расчёт): состав и рекомендация по парку - из
 * metrics_json последнего расчёта сценария (передаётся вычисленным -
 * без повторного запроса к БД).
 */
    private List<String> economicsCrossCheck(Scenario scenario,
                                             int selectedRobots,
                                             Map<String, Object> metrics) {
        List<String> warnings = new ArrayList<>();
        if (metrics == null) {
            warnings.add("Экономика сценария ещё не "
                    + "рассчитана - сравнение KPI имитации с расчётом "
                    + "недоступно (сначала «Рассчитать и перейти к "
                    + "дашборду»).");
            return warnings;
        }
        Integer econRobots = intOf(metrics.get("selectedRobots"));
        if (econRobots != null && econRobots != selectedRobots) {
            warnings.add(String.format(
                    "Состав изменён с момента последнего расчёта "
                            + "экономики (расчёт: %d ед., "
                            + "имитация: %d ед.) - пересчитайте "
                            + "экономику для согласованных "
                            + "показателей.",
                    econRobots, selectedRobots));
        }
        Integer required = intOf(metrics.get("requiredRobots"));
        if (required != null && selectedRobots < required) {
            warnings.add(String.format(
                    "Парк (%d ед.) меньше требуемого по пиковой "
                            + "нагрузке (%d ед.) - пиковая нагрузка может не "
                            + "покрываться.",
                    selectedRobots, required));
        }
        return warnings;
    }

    /**
 * metrics_json последнего расчёта сценария (null - расчёта нет).
 */
    private Map<String, Object> latestMetrics(Long scenarioId) {
        return calculationRepository
                .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(scenarioId)
                .map(calculation -> parseKpi(calculation.getMetricsJson()))
                .orElse(null);
    }

    // ==================================================================
    // Статусы и персистентность
    // ==================================================================

    /**
 * effectiveAssumptions из metrics_json расчёта (код -> значение).
 */
    @SuppressWarnings("unchecked")
    private Map<String, String> econAssumptionsOf(
            Map<String, Object> metrics) {
        if (metrics == null || !(metrics.get("effectiveAssumptions")
                instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<Object, Object>) raw).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()),
                        String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    // ==================================================================
    // Внутренние
    // ==================================================================

    /**
 * Число из строки-значения допущения снимка (null при мусоре).
 */
    private BigDecimal decimalOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
 * Провал выполнения: статус failed + причина в kpi_json.
 */
    private SimulationRunDto failRow(SimulationResult row, String reason) {
        return transactionTemplate.execute(tx -> {
            row.setStatus("failed");
            row.setFinishedAt(Instant.now());
            Map<String, Object> kpi = parseKpi(row.getKpiJson());
            kpi.put("scenarioId", row.getScenarioId());
            kpi.put("error", reason);
            row.setKpiJson(writeJson(kpi));
            SimulationResult saved = simulationRepository.save(row);
            return readKpi(saved);
        });
    }

    /**
 * Представление результата для API: метаданные + KPI из jsonb
 * (kpi_json - снимок переменного состава, §10.11).
 */
    private SimulationRunDto readKpi(SimulationResult row) {
        Map<String, Object> view = new LinkedHashMap<>(parseKpi(
                row.getKpiJson()));
        view.put("id", row.getId());
        view.put("status", row.getStatus());
        view.put("startedAt", row.getStartedAt() == null ? null
                : row.getStartedAt().toString());
        view.put("finishedAt", row.getFinishedAt() == null ? null
                : row.getFinishedAt().toString());
        if (row.getStartedAt() != null && row.getFinishedAt() != null) {
            view.put("durationMs", Duration.between(row.getStartedAt(),
                    row.getFinishedAt()).toMillis());
        }
        return JSON.convertValue(view, SimulationRunDto.class);
    }

    private Map<String, Object> jsonMap(SimulationModel.SimulationKpi kpi) {
        // record → Map с полным набором полей (имена стабильны для
        // клиента, отчёта и OpenAPI)
        return JSON.convertValue(kpi,
                new TypeReference<Map<String, Object>>() {
                });
    }

    private Map<String, Object> parseKpi(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return JSON.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception ex) {
            return new HashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Не удалось сериализовать KPI имитации", ex);
        }
    }

    /**
 * Эффективные параметры проекта: код → строковое значение (значение
 * проекта или дефолт метаданных - тот же алгоритм, что в
 * EconomicCalculationService.effectiveParams, §1.1).
 */
    private Map<String, String> effectiveParams(Project project) {
        List<ObjectTypeParameter> definitions =
                objectTypeParameterRepository
                        .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(
                                project.getObjectTypeId());
        Map<Long, ParameterType> types = parameterTypeRepository
                .findAllById(definitions.stream()
                        .map(ObjectTypeParameter::getParameterTypeId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(ParameterType::getId,
                        Function.identity()));
        Map<String, ObjectTypeParameter> definitionByCode = new HashMap<>();
        for (ObjectTypeParameter definition : definitions) {
            ParameterType type = types.get(definition.getParameterTypeId());
            if (type != null) {
                definitionByCode.put(type.getCode(), definition);
            }
        }
        Map<Long, ProjectParameterValue> values = valueRepository
                .findAllByProjectId(project.getId()).stream()
                .collect(Collectors.toMap(
                        ProjectParameterValue::getObjectTypeParameterId,
                        Function.identity()));
        Map<String, String> effective = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectTypeParameter> entry
                : definitionByCode.entrySet()) {
            ObjectTypeParameter definition = entry.getValue();
            ProjectParameterValue row = values.get(definition.getId());
            String value = null;
            if (row != null) {
                if (row.getValueNumeric() != null) {
                    value = row.getValueNumeric().toPlainString();
                } else if (row.getValueText() != null) {
                    value = row.getValueText();
                } else if (row.getValueBool() != null) {
                    value = row.getValueBool().toString();
                }
            }
            if (value == null) {
                if (definition.getDefaultValueNumeric() != null) {
                    value = definition.getDefaultValueNumeric()
                            .toPlainString();
                } else if (definition.getDefaultValueText() != null) {
                    value = definition.getDefaultValueText();
                } else if (definition.getDefaultValueBool() != null) {
                    value = definition.getDefaultValueBool().toString();
                }
            }
            if (value != null) {
                effective.put(entry.getKey(), value);
            }
        }
        return effective;
    }

    private Project requireOwnedProject(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElse(null);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new NotFoundException("Проект не найден");
        }
        return project;
    }

    private Scenario requireOwnedScenario(Long projectId, Long scenarioId) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElse(null);
        if (scenario == null || !scenario.getProjectId().equals(projectId)) {
            throw new NotFoundException("Сценарий не найден");
        }
        return scenario;
    }

    private void requireWarehouse(Project project) {
        ObjectType objectType = objectTypeRepository
                .findById(project.getObjectTypeId())
                .orElseThrow(() -> new NotFoundException(
                        "Тип объекта не найден"));
        if (!Boolean.TRUE.equals(objectType.getIsCalcEnabled())) {
            throw new BadRequestException("Имитация доступна только для склада - "
                    + "тип объекта «" + objectType.getName() + "» не "
                    + "поддерживается.");
        }
    }
}
