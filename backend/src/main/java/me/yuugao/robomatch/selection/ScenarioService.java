package me.yuugao.robomatch.selection;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

/**
 * Сценарии проекта (data_model.md §10.5–10.6): bootstrap трёх сценариев
 * при первом запуске подбора, ручное добавление решений и CRUD
 * (
 * проекта;).
 *
 * <p>ИЗОЛЯЦИЯ: как везде — userId из JWT
 * (CurrentUser.requireUserId), чужой проект/сценарий неотличимы от
 * несуществующих (404, не 403). Сценарий чужого проекта = «не найден»
 * в рамках этого project id.
 *
 * <p>BOOTSTRAP: (не менее трёх сценариев) + уточнения организатора (базовый,
 * покупка, RaaS). Создаётся ПЕРВЫМ запуском подбора (SelectionService.run);
 * POST /scenarios без type — восстановление недостающих (например, проект
 * создан до появления функции подбора). Гонка двух параллельных запусков: оба
 * видят «сценариев нет» → оба вставляют → UNIQUE (project_id, name)
 * нарушается у второго → DataIntegrityViolationException перехватывается
 * на уровне SelectionService.run и отдаётся 409 (повтор безопасен и
 * идемпотентен).
 *
 * <p>ИНВАРИАНТ BASE: scenario.type = base — «текущий
 * процесс без роботизации» (data_model.md §10.5) — не
 * содержит решений ни при каком пути записи: ручное добавление
 * и замена состава PUT-ом отклоняются 400. Иначе теряется смысл
 * ΔOPEX = OPEX_year_rob − OPEX_year_base (economic_model.md §2.5).
 *
 * <p>ТОЧЕЧНОЕ ИЗМЕНЕНИЕ СОСТАВА: DELETE/PUT одной
 * строки scenario_solution — без удаления/пересоздания состава целиком;
 * исторические расчёты НЕ трогаются (append-only, data_model.md §10.8):
 * старый расчёт воспроизводим со старым составом, новый пойдёт с новым.
 * Признак «состав изменён с момента расчёта» — сравнение текущего состава
 * со снимком metrics_json последнего расчёта (lastCalculatedAt/
 * compositionChanged в ScenarioDetailsDto).
 */
@Service
@RequiredArgsConstructor
public class ScenarioService {

    /**
 * Сообщение-инвариант: base не содержит решений.
 */
    static final String BASE_NO_SOLUTIONS_MESSAGE =
            "Базовый сценарий не содержит решений по определению "
                    + "(текущий процесс без роботизации). Добавляйте "
                    + "решения в сценарии «покупка» или «RaaS».";
    /**
 * Локальный Jackson 2 (Boot 4 — Jackson 3): парсинг metrics_json.
 */
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProjectRepository projectRepository;
    private final ScenarioRepository scenarioRepository;
    private final ScenarioSolutionRepository scenarioSolutionRepository;
    private final SolutionRepository solutionRepository;
    private final VendorRepository vendorRepository;
    private final CalculationRepository calculationRepository;

    /**
 * Дефолтное имя сценария по типу (data_model.md §10.5).
 */
    private static String defaultName(ScenarioType type) {
        return switch (type) {
            case BASE -> "Текущий процесс без роботизации";
            case PURCHASE -> "Покупка оборудования";
            case RAAS -> "Роботы как услуга";
        };
    }

    /**
 * Bootstrap сценариев: если у проекта их нет — создать три
 * (base/purchase/raas, имена — data_model.md §10.5). Вызывается
 * ТОЛЬКО из SelectionService.run внутри его транзакции.
 */
    void ensureDefaultScenarios(Long projectId) {
        if (scenarioRepository.existsByProjectId(projectId)) {
            return;
        }
        scenarioRepository.saveAll(List.of(
                Scenario.builder().projectId(projectId).type(ScenarioType.BASE)
                        .name(defaultName(ScenarioType.BASE)).build(),
                Scenario.builder().projectId(projectId).type(ScenarioType.PURCHASE)
                        .name(defaultName(ScenarioType.PURCHASE)).build(),
                Scenario.builder().projectId(projectId).type(ScenarioType.RAAS)
                        .name(defaultName(ScenarioType.RAAS)).build()));
    }

    /**
 * Сценарии проекта для ответа (без bootstrap — чтение ничего не меняет).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return сценарии проекта по порядку id
 */
    @Transactional(readOnly = true)
    public List<ScenarioDto> listScenarios(Long userId, Long projectId) {
        requireOwnedProject(userId, projectId);
        return scenarioRepository.findAllByProjectIdOrderByIdAsc(projectId).stream()
                .map(s -> new ScenarioDto(s.getId(), s.getType().name().toLowerCase(),
                        s.getName()))
                .toList();
    }

    /**
 * Сценарии проекта с составом оборудования (страница сценариев,
 * страница экономики): состав — из подбора и ручных добавлений.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return сценарии с составами и датами последнего расчёта
 */
    @Transactional(readOnly = true)
    public List<ScenarioDetailsDto> listWithSolutions(Long userId,
                                                      Long projectId) {
        requireOwnedProject(userId, projectId);
        List<Scenario> scenarios = scenarioRepository
                .findAllByProjectIdOrderByIdAsc(projectId);
        return scenarios.stream()
                .map(scenario -> {
                    List<ScenarioSolutionDto> solutions =
                            compositionViews(scenario.getId());
                    // один запрос последнего расчёта на сценарий
                    // (было два — lastCalculatedAt и сверка состава
                    // каждый тянул metrics_json заново)
                    Calculation latest = calculationRepository
                            .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                                    scenario.getId()).orElse(null);
                    return new ScenarioDetailsDto(scenario.getId(),
                            scenario.getType().name().toLowerCase(),
                            scenario.getName(), solutions,
                            latest == null ? null : latest.getCalculatedAt(),
                            compositionChangedSinceCalc(latest, solutions));
                })
                .toList();
    }

    /**
 * Состав сценария изменён с момента последнего расчёта? (для
 * автозапуска расчёта перед экспортом отчёта: устаревший расчёт
 * пересчитывается). Нет расчёта — false вызову наружу не нужно
 * (отсутствие считается «требует расчёта» вызывающим).
 *
 * @param scenarioId идентификатор сценария
 * @param latest последний расчёт сценария (null — нет)
 * @return true — состав отличается от снимка metrics_json
 */
    public boolean compositionChanged(Long scenarioId,
                                      @Nullable Calculation latest) {
        List<ScenarioSolutionDto> solutions = compositionViews(scenarioId);
        return compositionChangedSinceCalc(latest, solutions);
    }

    /**
 * Создание сценария: тело с type — конкретный сценарий
 * (например, второй purchase с другим составом — data_model §10.5);
 * тело без type — восстановить все недостающие сценарии по умолчанию
 * (проект создан до появления функции подбора / сценарий удалён).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param request type/name нового сценария (null — восстановление)
 * @return сценарии проекта с составами после создания
 */
    @Transactional
    public List<ScenarioDetailsDto> create(Long userId, Long projectId,
                                           ScenarioCreateRequest request) {
        requireOwnedProject(userId, projectId);
        try {
            if (request == null || request.type() == null
                    || request.type().isBlank()) {
                // восстановление недостающих сценариев по умолчанию
                List<Scenario> created = new java.util.ArrayList<>();
                for (ScenarioType type : ScenarioType.values()) {
                    if (scenarioRepository
                            .findAllByProjectIdOrderByIdAsc(projectId).stream()
                            .noneMatch(s -> s.getType() == type)) {
                        created.add(scenarioRepository.save(Scenario.builder()
                                .projectId(projectId).type(type)
                                .name(defaultName(type)).build()));
                    }
                }
                if (created.isEmpty()) {
                    throw new BadRequestException("Все три сценария уже "
                            + "существуют — укажите type, чтобы создать "
                            + "дополнительный (например, второй purchase).");
                }
            } else {
                ScenarioType type = ScenarioType
                        .valueOf(request.type().toUpperCase());
                String name = request.name() == null
                        || request.name().isBlank()
                        ? defaultName(type) : request.name().trim();
                scenarioRepository.save(Scenario.builder()
                        .projectId(projectId).type(type).name(name).build());
            }
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Сценарий с таким именем уже есть "
                    + "в проекте (имена уникальны). Измените имя.");
        }
        return listWithSolutions(userId, projectId);
    }

    /**
 * Правка сценария: имя и/или состав (состав замещается целиком —
 * источник правды сценария). Ручные строки требуют причину
 *. Дубликат имени — 409 (UNIQUE project_id+name, V1).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @param request новое имя и/или новый состав (name/solutions)
 * @return сценарий с обновлённым составом
 */
    @Transactional
    public ScenarioDetailsDto update(Long userId, Long projectId,
                                     Long scenarioId,
                                     ScenarioUpdateRequest request) {
        requireOwnedProject(userId, projectId);
        Scenario scenario = requireOwnedScenario(projectId, scenarioId);
        if (request == null || (request.name() == null
                && request.solutions() == null)) {
            throw new BadRequestException("Передайте новое имя или новый "
                    + "состав сценария (name / solutions).");
        }
        if (request.name() != null && !request.name().isBlank()) {
            scenario.setName(request.name().trim());
            try {
                scenarioRepository.saveAndFlush(scenario);
            } catch (DataIntegrityViolationException ex) {
                throw new ConflictException("Сценарий с таким именем уже есть "
                        + "в проекте. Измените имя.");
            }
        }
        if (request.solutions() != null) {
            replaceComposition(scenario, request);
        }
        List<ScenarioSolutionDto> solutions =
                compositionViews(scenario.getId());
        Calculation latest = calculationRepository
                .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                        scenario.getId()).orElse(null);
        return new ScenarioDetailsDto(scenario.getId(),
                scenario.getType().name().toLowerCase(), scenario.getName(),
                solutions, latest == null ? null : latest.getCalculatedAt(),
                compositionChangedSinceCalc(latest, solutions));
    }

    /**
 * Удаление сценария (каскад состава и расчётов — схемой V1).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария
 */
    @Transactional
    public void delete(Long userId, Long projectId, Long scenarioId) {
        requireOwnedProject(userId, projectId);
        Scenario scenario = requireOwnedScenario(projectId, scenarioId);
        scenarioRepository.delete(scenario);
    }

    private void replaceComposition(Scenario scenario,
                                    ScenarioUpdateRequest request) {
        List<ScenarioUpdateRequest.ScenarioCompositionItemDto> items = request.solutions();
        // Инвариант BASE: непустой состав в base — 400
        if (scenario.getType() == ScenarioType.BASE && !items.isEmpty()) {
            throw new BadRequestException(BASE_NO_SOLUTIONS_MESSAGE);
        }
        // Батч-загрузка решений одним запросом (без N+1
        // findById на каждый элемент до валидации)
        Map<Long, Solution> solutionsById = solutionRepository
                .findAllById(items.stream()
                        .map(ScenarioUpdateRequest.ScenarioCompositionItemDto::solutionId)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(
                        Solution::getId, solution -> solution));
        for (ScenarioUpdateRequest.ScenarioCompositionItemDto item : items) {
            if (!solutionsById.containsKey(item.solutionId())) {
                throw new NotFoundException(
                        "Решение " + item.solutionId() + " не найдено");
            }
            boolean manual = Boolean.TRUE.equals(item.manual());
            if (manual && (item.manualReason() == null
                    || item.manualReason().isBlank())) {
                throw new BadRequestException("Для ручного добавления решения "
                        + item.solutionId() + " укажите причину — "
                        + "она попадёт в отчёт.");
            }
        }
        // дубликаты solutionId в теле — 400
        long distinct = items.stream()
                .map(ScenarioUpdateRequest.ScenarioCompositionItemDto::solutionId).distinct()
                .count();
        if (distinct != items.size()) {
            throw new BadRequestException("В составе есть дубликаты решений — "
                    + "каждое решение должно встречаться один раз.");
        }
        scenarioSolutionRepository.deleteAllByScenarioId(scenario.getId());
        for (ScenarioUpdateRequest.ScenarioCompositionItemDto item : items) {
            scenarioSolutionRepository.save(ScenarioSolution.builder()
                    .scenarioId(scenario.getId())
                    .solutionId(item.solutionId())
                    .quantity(item.quantity() == null ? 1 : item.quantity())
                    .isManual(Boolean.TRUE.equals(item.manual()))
                    .manualReason(item.manualReason() == null ? null
                            : item.manualReason().trim())
                    .build());
        }
    }

    private List<ScenarioSolutionDto> compositionViews(Long scenarioId) {
        List<ScenarioSolution> rows = scenarioSolutionRepository
                .findAllByScenarioId(scenarioId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Solution> solutions = solutionRepository
                .findAllById(rows.stream().map(ScenarioSolution::getSolutionId)
                        .toList()).stream()
                .collect(Collectors.toMap(Solution::getId,
                        Function.identity()));
        Map<Long, Vendor> vendors = vendorRepository
                .findAllById(solutions.values().stream()
                        .map(Solution::getVendorId).distinct().toList())
                .stream().collect(Collectors.toMap(Vendor::getId,
                        Function.identity()));
        return rows.stream().map(row -> {
            Solution solution = solutions.get(row.getSolutionId());
            if (solution == null) {
                return new ScenarioSolutionDto(row.getSolutionId(),
                        "Решение " + row.getSolutionId(), null,
                        row.getQuantity(), null, null,
                        Boolean.TRUE.equals(row.getIsManual()),
                        row.getManualReason());
            }
            Vendor vendor = vendors.get(solution.getVendorId());
            return new ScenarioSolutionDto(solution.getId(),
                    solution.getName(),
                    vendor == null ? null : vendor.getName(), row.getQuantity(),
                    solution.getPriceRub(),
                    solution.getPriceRub() == null ? null
                            : solution.getPriceRub().multiply(
                            java.math.BigDecimal.valueOf(
                                    row.getQuantity())),
                    Boolean.TRUE.equals(row.getIsManual()),
                    row.getManualReason());
        }).toList();
    }

    /**
 * Ручное добавление решения в сценарий: is_manual=true,
 * причина обязательна (ManualAddRequest), дубликат — 409. Решение
 * может быть ЛЮБЫМ из каталога — «даже если оно не вошло в
 * автоматическую подборку».
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария (не base)
 * @param request решение, количество и причина добавления
 * @return добавленная строка состава (сущность ScenarioSolution)
 */
    @Transactional
    public ScenarioSolution addSolutionManually(Long userId, Long projectId,
                                                Long scenarioId,
                                                ManualAddRequest request) {
        requireOwnedProject(userId, projectId);
        Scenario scenario = requireOwnedScenario(projectId, scenarioId);
        // Инвариант BASE: базовый сценарий без решений — 400
        if (scenario.getType() == ScenarioType.BASE) {
            throw new BadRequestException(BASE_NO_SOLUTIONS_MESSAGE);
        }
        if (request.solutionId() == null) {
            throw new NotFoundException("Решение не найдено");
        }
        var solution = solutionRepository.findById(request.solutionId())
                .orElseThrow(() -> new NotFoundException("Решение не найдено"));
        if (scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(scenarioId, request.solutionId())
                .isPresent()) {
            throw new ConflictException("Решение «" + solution.getName()
                    + "» уже добавлено в сценарий «" + scenario.getName() + "».");
        }
        ScenarioSolution row = ScenarioSolution.builder()
                .scenarioId(scenarioId)
                .solutionId(request.solutionId())
                .quantity(request.quantity() == null ? 1 : request.quantity())
                .isManual(true)
                .manualReason(request.manualReason() == null ? null
                        : request.manualReason().trim())
                .build();
        try {
            return scenarioSolutionRepository.save(row);
        } catch (DataIntegrityViolationException ex) {
            // гонка двух одинаковых добавлений — второе видит дубликат
            throw new ConflictException("Решение уже добавлено в сценарий "
                    + "(параллельное действие). Обновите страницу.");
        }
    }

    /**
 * Удаление решения из состава сценария: удаляет ТОЛЬКО строку
 * scenario_solution (каскадно — ничего, история расчётов не трогается:
 * append-only, data_model.md §10.8 — старый расчёт воспроизводим со
 * старым составом, новый расчёт пойдёт с новым составом).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @param solutionId идентификатор решения в составе
 */
    @Transactional
    public void removeSolution(Long userId, Long projectId, Long scenarioId,
                               Long solutionId) {
        requireOwnedProject(userId, projectId);
        Scenario scenario = requireOwnedScenario(projectId, scenarioId);
        ScenarioSolution row = scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(scenario.getId(), solutionId)
                .orElseThrow(() -> new NotFoundException(
                        "Решение не найдено в составе сценария"));
        scenarioSolutionRepository.delete(row);
    }

    /**
 * Изменение количества единиц решения в составе: quantity > 0
 * (CHECK V1 + валидация приложения — понятная ошибка вместо 500).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @param solutionId идентификатор решения в составе
 * @param quantity новое количество (1..10 000)
 * @return строка состава с новой суммой
 */
    @Transactional
    public ScenarioSolutionDto updateQuantity(Long userId, Long projectId,
                                              Long scenarioId,
                                              Long solutionId,
                                              Integer quantity) {
        requireOwnedProject(userId, projectId);
        Scenario scenario = requireOwnedScenario(projectId, scenarioId);
        if (quantity == null || quantity <= 0 || quantity > 10_000) {
            throw new BadRequestException("Количество должно быть от 1 "
                    + "до 10 000 единиц.");
        }
        ScenarioSolution row = scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(scenario.getId(), solutionId)
                .orElseThrow(() -> new NotFoundException(
                        "Решение не найдено в составе сценария"));
        row.setQuantity(quantity);
        try {
            scenarioSolutionRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            throw new BadRequestException("Количество должно быть от 1 "
                    + "до 10 000 единиц (CHECK scenario_solution.quantity).");
        }
        // параллельное удаление строки между чтением и flush даёт UPDATE
        // 0 rows молча — перепроверяем наличие
        if (scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(scenario.getId(), solutionId)
                .isEmpty()) {
            throw new NotFoundException("Решение удалено из состава "
                    + "параллельным действием — обновите страницу.");
        }
        Solution solution = solutionRepository.findById(solutionId)
                .orElseThrow(() -> new NotFoundException("Решение не найдено"));
        Vendor vendor = solution.getVendorId() == null ? null
                : vendorRepository.findById(solution.getVendorId())
                .orElse(null);
        return new ScenarioSolutionDto(solution.getId(), solution.getName(),
                vendor == null ? null : vendor.getName(), row.getQuantity(),
                solution.getPriceRub(),
                solution.getPriceRub() == null ? null
                        : solution.getPriceRub().multiply(
                        java.math.BigDecimal.valueOf(row.getQuantity())),
                Boolean.TRUE.equals(row.getIsManual()), row.getManualReason());
    }

    /**
 * Состав изменён с момента последнего расчёта? Сравнение текущего
 * состава со снимком metrics_json.composition последнего расчёта
 * (пары решение+количество; порядок не важен). Нет расчёта — false
 * (сравнивать не с чем). Скорректированные расчёты наследуют снимок —
 * сравнение корректно и для них. Расчёт передаётся снаружи (одно
 * чтение на сценарий).
 */
    private boolean compositionChangedSinceCalc(Calculation latest,
                                                List<ScenarioSolutionDto> current) {
        if (latest == null) {
            return false;
        }
        String metricsJson = latest.getMetricsJson();
        if (metricsJson == null || metricsJson.isBlank()) {
            return !current.isEmpty();
        }
        try {
            Map<String, Object> details = JSON.readValue(metricsJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object composition = details.get("composition");
            if (!(composition instanceof List<?> snapshot)) {
                return !current.isEmpty();
            }
            // пары (solutionId, quantity) из снимка
            Map<Long, Integer> snapshotPairs = new java.util.HashMap<>();
            for (Object item : snapshot) {
                if (item instanceof Map<?, ?> line
                        && line.get("solutionId") instanceof Number id) {
                    int qty = line.get("quantity") instanceof Number q
                            ? q.intValue() : 0;
                    snapshotPairs.put(id.longValue(), qty);
                }
            }
            if (snapshotPairs.size() != current.size()) {
                return true;
            }
            for (ScenarioSolutionDto row : current) {
                Integer snapQty = snapshotPairs.get(row.solutionId());
                if (snapQty == null || snapQty != row.quantity()) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            // битый metrics_json трактуем как «изменён» при непустом составе
            return !current.isEmpty();
        }
    }

    /**
 * Проект пользователя или 404 (изоляция: чужой = несуществующий).
 */
    private void requireOwnedProject(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new NotFoundException("Проект не найден");
        }
    }

    private Scenario requireOwnedScenario(Long projectId, Long scenarioId) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElse(null);
        if (scenario == null || !scenario.getProjectId().equals(projectId)) {
            throw new NotFoundException("Сценарий не найден в этом проекте");
        }
        return scenario;
    }
}
