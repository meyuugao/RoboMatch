package me.yuugao.robomatch.export;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.AssumptionDto;
import me.yuugao.robomatch.economics.AssumptionService;
import me.yuugao.robomatch.economics.InterpretationFormatter;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.repository.*;
import me.yuugao.robomatch.service.SimulationStorage;
import me.yuugao.robomatch.simulation.SchemaSvgRenderer;
import me.yuugao.robomatch.simulation.SimulationService;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

/**
 * Сборка модели отчёта из данных проекта. ГЛАВНОЕ ПРАВИЛО:
 * экономика читается из ПОСЛЕДНИХ РАСЧЁТОВ сценариев (append-only,
 * — метрики, версии данных/модели, calculated_at) и НЕ
 * пересчитывается; источник правды формул — economic_model.md.
 *
 * <p>СОСТАВ:
 * <ul>
 * <li>параметры объекта — эффективные значения (пользователь/дефолт)
 * с единицами и источником (раздел 2);</li>
 * <li>выбранные решения — снимок состава metrics_json последнего
 * расчёта + вендор каталога (раздел 3), пометка ручного
 * добавления;</li>
 * <li>состав оборудования — selectedRobots/nInfra снимка (раздел 4);</li>
 * <li>экономика — колонки расчёта + Δ к базовому + paybackHuman
 * (InterpretationFormatter, economic_model.md §4) + флаги
 * underpowered/overpowered + предупреждения (разделы 5, 7, 9);</li>
 * <li>имитация — последний completed проекта: 6 KPI + схема
 * (SVG хранилища,; раздел 6);</li>
 * <li>допущения — снимок effectiveAssumptions последнего расчёта с
 * метаданными каталога (раздел 8);</li>
 * <li>источники — провенанс использованных решений: ТТХ
 * solution_characteristic с source_kind/url/date (раздел 8,
 *).</li>
 * </ul>
 *
 * <p>Ограничения (раздел 7): формулировки economic_model.md §8 и
 * assumptions.md §22 — новых допущений отчёт не вводит.
 */
@Component
@RequiredArgsConstructor
public class ReportDataBuilder {

    /**
 * Пометка — на титуле и в конце каждого отчёта.
 */
    static final String PRELIMINARY_NOTE =
            "Предварительная оценка, требует верификации при обследовании "
                    + "объекта";
    /**
 * Общие ограничения — economic_model.md §8 + assumptions.md §22.
 */
    static final List<String> COMMON_LIMITATIONS = List.of(
            "Платформа не заменяет детальное проектирование и обследование "
                    + "объекта.",
            "Результат — предварительная оценка, требует верификации.",
            "Точность зависит от полноты входных данных.",
            "Расчёт не учитывает налоговые режимы, кроме НДС в ценах.",
            "Базовый сценарий учитывает только ФОТ целевых групп "
                    + "(отборщики и операторы погрузчиков) с начислениями — "
                    + "прочие текущие расходы базового контура не включены "
                    + "(каталог допущений, раздел 22).",
            "Формулы расчёта — методика экономической модели, разделы "
                    + "2.1–2.13; коэффициенты — каталог допущений "
                    + "(разделы 22–23).");
    /**
 * Локальный Jackson 2 (не Spring-бин) — как в расчётных сервисах.
 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
 * Порядок сценариев в отчёте — как в таблице сравнения (§2.13).
 */
    private static final List<String> SCENARIO_ORDER =
            List.of("base", "purchase", "raas");
    private final ProjectRepository projectRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final ObjectTypeParameterRepository objectTypeParameterRepository;
    private final ParameterTypeRepository parameterTypeRepository;
    private final ProjectParameterValueRepository valueRepository;
    private final UserRepository userRepository;
    private final ScenarioRepository scenarioRepository;
    private final ScenarioSolutionRepository scenarioSolutionRepository;
    private final CalculationRepository calculationRepository;
    private final SolutionRepository solutionRepository;
    private final VendorRepository vendorRepository;
    private final SimulationResultRepository simulationRepository;
    private final SolutionCharacteristicRepository characteristicRepository;
    private final CharacteristicTypeRepository characteristicTypeRepository;
    private final AssumptionService assumptionService;
    private final SvgRasterizer svgRasterizer;
    private final SimulationStorage simulationStorage;
    private final SimulationService simulationService;

    private static String valueOf(ProjectParameterValue value) {
        if (value.getValueNumeric() != null) {
            return value.getValueNumeric().stripTrailingZeros()
                    .toPlainString();
        }
        if (value.getValueText() != null) {
            return value.getValueText();
        }
        if (value.getValueBool() != null) {
            return value.getValueBool() ? "да" : "нет";
        }
        return null;
    }

    // ==================================================================
    // Раздел 2: параметры объекта
    // ==================================================================

    private static String defaultValueOf(ObjectTypeParameter definition) {
        if (definition.getDefaultValueNumeric() != null) {
            return definition.getDefaultValueNumeric().stripTrailingZeros()
                    .toPlainString();
        }
        if (definition.getDefaultValueText() != null) {
            return definition.getDefaultValueText();
        }
        if (definition.getDefaultValueBool() != null) {
            return definition.getDefaultValueBool() ? "да" : "нет";
        }
        return null;
    }

    private static List<String> warningsOf(Map<String, Object> metrics) {
        Object raw = metrics.get("warnings");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
 * Горизонт расчёта — metrics_json.horizonYears свежайшего расчёта.
 */
    @Nullable
    private static Integer horizonOf(Map<Long, Calculation> calcs) {
        return calcs.values().stream()
                .filter(calc -> calc.getCalculatedAt() != null)
                .max((a, b) -> a.getCalculatedAt()
                        .compareTo(b.getCalculatedAt()))
                .map(calc -> intOf(parseJson(calc.getMetricsJson())
                        .get("horizonYears")))
                .orElse(null);
    }

    // ==================================================================
    // Разделы 3–5, 9: сценарии с последним расчётом
    // ==================================================================

    /**
 * Свежайший завершённый результат имитации истории.
 */
    @Nullable
    private static SimulationResult latestCompleted(
            List<SimulationResult> history) {
        return history.stream()
                .filter(row -> "completed".equals(row.getStatus()))
                .findFirst().orElse(null);
    }

    private static String bottleneckOf(Map<String, Object> kpi) {
        Object raw = kpi.get("zones");
        if (!(raw instanceof List<?> zones) || zones.isEmpty()) {
            return "—";
        }
        String name = null;
        BigDecimal share = null;
        for (Object item : zones) {
            if (!(item instanceof Map<?, ?> zone)) {
                continue;
            }
            BigDecimal load = bigOf(zone.get("robotTimeSharePct"));
            if (load != null && (share == null
                    || load.compareTo(share) > 0)) {
                share = load;
                name = stringOf(zone.get("name"));
            }
        }
        return name == null ? "—"
                : name + " — "
                  + ReportFormats.formatDecimalTrim(share, 1)
                  + " %";
    }

    private static String kpiValue(Object value, String unit, String empty) {
        BigDecimal number = bigOf(value);
        // десятичная ЗАПЯТАЯ, как во всём отчёте
        // (раньше toPlainString давал «810.0»)
        return number == null ? empty
                : ReportFormats.formatDecimalTrim(number, 1) + unit;
    }

    private static String rangeOf(java.math.BigDecimal min,
                                  java.math.BigDecimal max) {
        if (min == null && max == null) {
            return "—";
        }
        String low = min == null ? "" : min.stripTrailingZeros()
                .toPlainString();
        String high = max == null ? "" : max.stripTrailingZeros()
                .toPlainString();
        return (low + "…" + high).isEmpty() ? "—" : low + "…" + high;
    }

    // ==================================================================
    // Раздел 6: имитация 2D + KPI — схема ОБЯЗАТЕЛЬНА
    // (автозапуск имитации и автосохранение схемы при необходимости)
    // ==================================================================

    private static Map<String, Object> parseJson(String json) {
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

    private static Map<String, Object> castMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new HashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    @Nullable
    private static BigDecimal bigOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(value.toString().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Nullable
    private static Integer intOf(Object value) {
        BigDecimal number = bigOf(value);
        return number == null ? null : number.intValue();
    }

    @Nullable
    private static Long longOf(Object value) {
        BigDecimal number = bigOf(value);
        return number == null ? null : number.longValue();
    }

    // ==================================================================
    // Раздел 7: ограничения
    // ==================================================================

    private static String stringOf(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    // ==================================================================
    // Раздел 8: допущения (снимок) и источники
    // ==================================================================

    @Nullable
    private static BigDecimal subtractNullable(BigDecimal left,
                                               BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return left.subtract(right);
    }

    private static String emptyToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String characteristicValueOf(SolutionCharacteristic c) {
        if (c.getValueNumeric() != null) {
            return c.getValueNumeric().stripTrailingZeros().toPlainString();
        }
        if (c.getValueText() != null) {
            return c.getValueText();
        }
        if (c.getValueBool() != null) {
            return c.getValueBool() ? "да" : "нет";
        }
        if (c.getValueDate() != null) {
            return c.getValueDate().toString();
        }
        return "—";
    }

    // ------------------------------------------------------------------
    // Загрузка справочников и парсинг (Jackson 2, как в сервисах)
    // ------------------------------------------------------------------

    /**
 * Собрать модель отчёта проекта. Владелец/гейт уже проверены
 * вызывающим (ExportService). Сценарий без расчёта попадает в модель
 * с calculated=false и причиной из calcFailures (если автозапуск
 * перед отчётом не удался) — раздел экономики помечается
 * «Расчёт не выполнен: причина».
 *
 * @param project проект (владелец проверен вызывающим)
 * @param objectType тип объекта (склад; гейт уже проверен)
 * @param userId автор отчёта (для титульного листа)
 * @param dark тема PDF (true — тёмная)
 * @param calcFailures сценарийId → причина неудачи автозапуска
 * расчёта (пусто — всё рассчитано)
 * @param schemaTheme тема 2D-схемы: ui (как сохранена в
 * интерфейсе) | light | dark — при отличии
 * сохранённой схемы от выбранной темы схема
 * перерисовывается сервером
 * @return модель отчёта (готовые метрики, без пересчёта)
 */
    public ReportModel build(Project project, ObjectType objectType,
                             Long userId, boolean dark,
                             Map<Long, String> calcFailures,
                             String schemaTheme) {
        User author = userRepository.findById(userId).orElse(null);
        Map<String, Scenario> byType = new LinkedHashMap<>();
        for (Scenario scenario : scenarioRepository
                .findAllByProjectIdOrderByIdAsc(project.getId())) {
            byType.putIfAbsent(scenario.getType().name().toLowerCase(),
                    scenario);
        }
        List<Scenario> ordered = SCENARIO_ORDER.stream()
                .map(byType::get).filter(Objects::nonNull).toList();

        // последние расчёты всех сценариев + базовый (для Δ)
        Map<Long, Calculation> latestCalcByScenario = new HashMap<>();
        for (Scenario scenario : ordered) {
            calculationRepository
                    .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                            scenario.getId())
                    .ifPresent(calc -> latestCalcByScenario
                            .put(scenario.getId(), calc));
        }
        Calculation baseCalc = byType.keySet().stream()
                .filter("base"::equals).map(byType::get)
                .map(scenario -> latestCalcByScenario.get(scenario.getId()))
                .filter(Objects::nonNull).findFirst().orElse(null);

        Map<Long, Solution> solutionsById = loadSolutions(ordered);
        Map<Long, String> vendorNames = loadVendorNames(solutionsById);

        List<ReportModel.ScenarioReport> scenarioReports = new ArrayList<>();
        for (Scenario scenario : ordered) {
            scenarioReports.add(scenarioReport(scenario,
                    latestCalcByScenario.get(scenario.getId()),
                    solutionsById, vendorNames, baseCalc,
                    calcFailures.get(scenario.getId())));
        }
        return new ReportModel(
                project.getId(),
                project.getName(),
                objectType.getName(),
                Instant.now(),
                author == null ? "—" : author.getLogin(),
                horizonOf(latestCalcByScenario),
                parameters(project),
                scenarioReports,
                simulationReport(project, userId, dark, schemaTheme),
                limitations(scenarioReports),
                assumptions(project, userId, latestCalcByScenario, byType),
                sources(ordered, solutionsById, vendorNames),
                PRELIMINARY_NOTE);
    }

    /**
 * Эффективные параметры с источником: пользователь или дефолт.
 */
    private List<ReportModel.ParameterRow> parameters(Project project) {
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
        Map<Long, ProjectParameterValue> values = valueRepository
                .findAllByProjectId(project.getId()).stream()
                .collect(Collectors.toMap(
                        ProjectParameterValue::getObjectTypeParameterId,
                        Function.identity()));
        List<ReportModel.ParameterRow> rows = new ArrayList<>();
        for (ObjectTypeParameter definition : definitions) {
            ParameterType type = types.get(definition.getParameterTypeId());
            if (type == null) {
                continue;
            }
            ProjectParameterValue value = values.get(definition.getId());
            boolean fromUser = value != null
                    && (value.getValueNumeric() != null
                    || value.getValueText() != null
                    || value.getValueBool() != null);
            String display = fromUser ? valueOf(value)
                    : defaultValueOf(definition);
            if (display == null) {
                continue; // значения нет ни у пользователя, ни в дефолте
            }
            rows.add(new ReportModel.ParameterRow(
                    type.getCode(), type.getName(), display,
                    type.getUnit() == null ? "—" : type.getUnit(),
                    fromUser ? "пользователь" : "по умолчанию"));
        }
        return rows;
    }

    private ReportModel.ScenarioReport scenarioReport(
            Scenario scenario, @Nullable Calculation calc,
            Map<Long, Solution> solutionsById,
            Map<Long, String> vendorNames, @Nullable Calculation baseCalc,
            @Nullable String calcFailureReason) {
        Map<String, Object> metrics = parseJson(
                calc == null ? null : calc.getMetricsJson());
        // причины ручных добавлений — только в живых строках
        // состава (metrics_json их не хранит); берем для сверки с снимком
        Map<Long, String> manualReasons = new HashMap<>();
        for (ScenarioSolution row : scenarioSolutionRepository
                .findAllByScenarioId(scenario.getId())) {
            if (Boolean.TRUE.equals(row.getIsManual())
                    && row.getManualReason() != null) {
                manualReasons.put(row.getSolutionId(),
                        row.getManualReason());
            }
        }
        boolean calculated = calc != null;
        BigDecimal capex = calc == null ? null : calc.getTotalCapex();
        BigDecimal tco = calc == null ? null : calc.getTcoRub();
        BigDecimal effect = calc == null ? null : calc.getEffectYear();
        BigDecimal payback = calc == null ? null : calc.getPaybackYears();
        // Δ к базовому — разность колонок готовых расчётов (не пересчёт)
        BigDecimal capexDelta = calculated && baseCalc != null
                ? subtractNullable(capex, baseCalc.getTotalCapex()) : null;
        BigDecimal tcoDelta = calculated && baseCalc != null
                ? subtractNullable(tco, baseCalc.getTcoRub()) : null;
        BigDecimal effectDelta = calculated && baseCalc != null
                ? subtractNullable(effect, baseCalc.getEffectYear()) : null;
        return new ReportModel.ScenarioReport(
                scenario.getType().name().toLowerCase(),
                scenario.getName(),
                calculated,
                calc == null ? null : calc.getCalculatedAt(),
                calc == null ? null : calc.getVersionData(),
                calc == null ? null : calc.getVersionModel(),
                intOf(metrics.get("selectedRobots")),
                intOf(metrics.get("requiredRobots")),
                intOf(metrics.get("nInfra")),
                capex,
                calc == null ? null : calc.getTotalOpex(),
                calc == null ? null : calc.getOpexDeltaRub(),
                bigOf(metrics.get("deltaFot")),
                effect,
                payback,
                InterpretationFormatter.formatPayback(payback),
                calc == null ? null : calc.getRoiPct(),
                tco,
                capexDelta,
                tcoDelta,
                effectDelta,
                Boolean.TRUE.equals(metrics.get("underpowered")),
                Boolean.TRUE.equals(metrics.get("overpowered")),
                compositionLines(metrics, solutionsById, vendorNames,
                        manualReasons),
                warningsOf(metrics),
                calcFailureReason);
    }

    /**
 * Состав из СНИМКА последнего расчёта (metrics_json.composition);
 * вендор — из каталога по vendorId решения (раньше карта вендоров
 * искалась по id решения — показывала «—» или
 * чужого производителя при совпадении id); причина ручного
 * добавления — из живой строки состава (в снимке её нет).
 */
    private List<ReportModel.SolutionLine> compositionLines(
            Map<String, Object> metrics, Map<Long, Solution> solutionsById,
            Map<Long, String> vendorNames, Map<Long, String> manualReasons) {
        List<ReportModel.SolutionLine> lines = new ArrayList<>();
        Object raw = metrics.get("composition");
        if (!(raw instanceof List<?> list)) {
            return lines;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> line)) {
                continue;
            }
            Long solutionId = longOf(line.get("solutionId"));
            Solution solution = solutionId == null ? null
                    : solutionsById.get(solutionId);
            Integer quantity = intOf(line.get("quantity"));
            BigDecimal price = bigOf(line.get("priceRub"));
            BigDecimal total = price == null || quantity == null ? null
                    : price.multiply(BigDecimal.valueOf(quantity));
            boolean manual = Boolean.TRUE.equals(line.get("manual"));
            lines.add(new ReportModel.SolutionLine(
                    solution == null ? "—"
                            : vendorNames.getOrDefault(
                            solution.getVendorId(), "—"),
                    stringOf(line.get("solutionName")),
                    quantity,
                    price,
                    total,
                    manual,
                    manual ? manualReasons.get(solutionId) : null));
        }
        return lines;
    }

    @Nullable
    private ReportModel.SimulationReport simulationReport(Project project,
                                                          Long userId,
                                                          boolean dark,
                                                          String schemaTheme) {
        List<SimulationResult> history = simulationRepository
                .findAllByProjectIdAndUserIdOrderByIdDesc(project.getId(),
                        userId);
        SimulationResult freshest = latestCompleted(history);
        String note = null;
        if (freshest == null) {
            // имитация не запускалась — запускаем для сценария со свежим
            // расчётом (или первого непустого purchase/raas) перед отчётом
            Scenario target = simulationTarget(project);
            if (target == null) {
                return new ReportModel.SimulationReport(null, null,
                        List.of(), null,
                        "Схема не сохранена: нет роботизированного "
                                + "сценария с составом решений — запустите "
                                + "подбор и повторите экспорт.");
            }
            try {
                simulationService.run(userId, project.getId(),
                        target.getId());
                freshest = latestCompleted(simulationRepository
                        .findAllByProjectIdAndUserIdOrderByIdDesc(
                                project.getId(), userId));
            } catch (BadRequestException | ConflictException ex) {
                return new ReportModel.SimulationReport(null, null,
                        List.of(), null,
                        "Схема не сохранена: " + ex.getMessage());
            }
            if (freshest == null) {
                return new ReportModel.SimulationReport(null, null,
                        List.of(), null,
                        "Схема не сохранена: имитация не завершилась "
                                + "успешно.");
            }
        }
        Map<String, Object> kpi = parseJson(freshest.getKpiJson());
        List<ReportModel.KpiRow> rows = List.of(
                new ReportModel.KpiRow(
                        "Заявленная производительность парка",
                        kpiValue(kpi.get("declaredThroughputPerHour"),
                                " оп/час", "не задана (P_nominal не указан)")),
                new ReportModel.KpiRow(
                        "Фактическая производительность модели",
                        kpiValue(kpi.get("actualThroughputPerHour"),
                                " оп/час", "—")),
                new ReportModel.KpiRow("Загрузка роботов",
                        kpiValue(kpi.get("utilizationPct"), " %", "—")),
                new ReportModel.KpiRow("Простои (зарядка, ожидание)",
                        kpiValue(kpi.get("idlePct"), " %", "—")),
                new ReportModel.KpiRow("Узкое место (зоны)",
                        bottleneckOf(kpi)),
                new ReportModel.KpiRow(
                        "Достижимость заявленной производительности",
                        kpiValue(kpi.get("achievabilityPct"), " %",
                                "не рассчитана (нет P_nominal)")));
        String scenarioName = stringOf(kpi.get("scenarioName"));
        // Схема обязательна: файл этой строки есть — берём (тема схемы
        // должна совпадать с выбранной пользователем на странице
        // экспорта; иначе перерисовываем сервером); нет — строим
        // серверным рендером и сохраняем в хранилище (последующая
        // выгрузка SVG скачивается по exportUrl). «ui» — как сохранено
        // в интерфейсе (тема не проверяется).
        byte[] png = null;
        String svg = simulationStorage.readSchema(
                project.getId() + "/" + freshest.getId() + ".svg");
        boolean chosenDark = "dark".equals(schemaTheme)
                || ("ui".equals(schemaTheme) && dark);
        if (svg != null && !"ui".equals(schemaTheme)) {
            String wanted = chosenDark ? "dark" : "light";
            if (!svg.contains("data-theme=\"" + wanted + "\"")) {
                svg = null;
            }
        }
        if (svg == null) {
            try {
                svg = SchemaSvgRenderer.render(kpi, chosenDark);
                simulationStorage.saveSchema(project.getId(),
                        freshest.getId(), svg);
                simulationService.attachExportUrl(freshest,
                        "/api/projects/" + project.getId() + "/simulations/"
                                + freshest.getId() + "/export");
            } catch (IllegalArgumentException | IllegalStateException ex) {
                return new ReportModel.SimulationReport(scenarioName,
                        freshest.getStartedAt(), rows, null,
                        "Схема не сохранена: " + ex.getMessage());
            }
        }
        try {
            png = svgRasterizer.rasterize(svg);
        } catch (Exception ex) {
            note = "Сохранённая 2D-схема не может быть отображена "
                    + "в отчёте.";
        }
        return new ReportModel.SimulationReport(scenarioName,
                freshest.getStartedAt(), rows, png, note);
    }

    /**
 * Сценарий для автозапуска имитации: со свежим расчётом; при
 * отсутствии расчётов — первый непустой purchase/raas.
 */
    @Nullable
    private Scenario simulationTarget(Project project) {
        List<Scenario> scenarios = scenarioRepository
                .findAllByProjectIdOrderByIdAsc(project.getId());
        Scenario best = null;
        for (Scenario scenario : scenarios) {
            if (scenario.getType() == me.yuugao.robomatch.domain.ScenarioType.BASE) {
                continue;
            }
            boolean hasCalc = calculationRepository
                    .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                            scenario.getId()).isPresent();
            boolean hasComposition = !scenarioSolutionRepository
                    .findAllByScenarioId(scenario.getId()).isEmpty();
            if (hasCalc && hasComposition) {
                return scenario; // первый со свежим расчётом (порядок id)
            }
            if (best == null && hasComposition
                    && (scenario.getType()
                    == me.yuugao.robomatch.domain.ScenarioType.PURCHASE
                    || scenario.getType()
                    == me.yuugao.robomatch.domain.ScenarioType.RAAS)) {
                best = scenario;
            }
        }
        return best;
    }

    private List<String> limitations(
            List<ReportModel.ScenarioReport> scenarios) {
        List<String> rows = new ArrayList<>(COMMON_LIMITATIONS);
        for (ReportModel.ScenarioReport scenario : scenarios) {
            for (String warning : scenario.warnings()) {
                rows.add("Сценарий «" + scenario.name() + "»: " + warning);
            }
        }
        return rows;
    }

    /**
 * Допущения: метаданные каталога (AssumptionService) +
 * ЗНАЧЕНИЯ из снимка последнего расчёта (metrics_json
 * .effectiveAssumptions, — отчёт читает расчёт). Расчёта
 * нет — текущие эффективные значения.
 */
    private List<ReportModel.AssumptionRow> assumptions(Project project,
                                                        Long userId, Map<Long, Calculation> latestCalcByScenario,
                                                        Map<String, Scenario> byType) {
        Calculation freshest = latestCalcByScenario.values().stream()
                .filter(calc -> calc.getCalculatedAt() != null)
                .max((a, b) -> a.getCalculatedAt()
                        .compareTo(b.getCalculatedAt()))
                .orElse(null);
        Map<String, Object> effective = freshest == null ? Map.of()
                : castMap(parseJson(freshest.getMetricsJson())
                .get("effectiveAssumptions"));
        String sourceNote = freshest == null ? "текущие значения"
                : "снимок расчёта от " + ReportFormats.formatDateTime(
                freshest.getCalculatedAt());
        List<ReportModel.AssumptionRow> rows = new ArrayList<>();
        for (AssumptionDto item : assumptionService.list(userId,
                project.getId())) {
            String value = effective.containsKey(item.name())
                    ? stringOf(effective.get(item.name()))
                    : (item.value() == null ? "—" : item.value());
            rows.add(new ReportModel.AssumptionRow(
                    item.name(), item.title(), value,
                    item.unit() == null ? "—" : item.unit(),
                    rangeOf(item.min(), item.max()), sourceNote));
        }
        return rows;
    }

    /**
 * Источники: провенанс использованных решений + их ТТХ (EAV).
 */
    private List<ReportModel.SourceRow> sources(List<Scenario> scenarios,
                                                Map<Long, Solution> solutionsById,
                                                Map<Long, String> vendorNames) {
        // использованные решения — union составов сценариев (base пуст
        // по инварианту, но фильтруем и его)
        Map<Long, Integer> usage = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            if ("BASE".equalsIgnoreCase(scenario.getType().name())) {
                continue;
            }
            for (ScenarioSolution row : scenarioSolutionRepository
                    .findAllByScenarioId(scenario.getId())) {
                usage.merge(row.getSolutionId(), row.getQuantity(),
                        Integer::sum);
            }
        }
        if (usage.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SolutionCharacteristic>> charsBySolution =
                characteristicRepository
                        .findBySolutionIdIn(List.copyOf(usage.keySet()))
                        .stream().collect(Collectors.groupingBy(
                                SolutionCharacteristic::getSolutionId));
        Map<Long, CharacteristicType> types = characteristicTypeRepository
                .findAll().stream().collect(Collectors.toMap(
                        CharacteristicType::getId, Function.identity()));
        List<ReportModel.SourceRow> rows = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : usage.entrySet()) {
            Solution solution = solutionsById.get(entry.getKey());
            if (solution == null) {
                continue;
            }
            List<ReportModel.CharacteristicRow> characteristicRows =
                    new ArrayList<>();
            for (SolutionCharacteristic c : charsBySolution
                    .getOrDefault(entry.getKey(), List.of())) {
                CharacteristicType type = types.get(
                        c.getCharacteristicTypeId());
                if (type == null) {
                    continue;
                }
                characteristicRows.add(new ReportModel.CharacteristicRow(
                        type.getCode(), type.getName(),
                        characteristicValueOf(c),
                        type.getUnit() == null ? "—" : type.getUnit(),
                        emptyToDash(c.getSourceKind()),
                        emptyToDash(c.getSourceUrl()),
                        c.getSourceDate() == null ? "—"
                                : c.getSourceDate().toString()));
            }
            rows.add(new ReportModel.SourceRow(
                    vendorNames.getOrDefault(solution.getVendorId(), "—"),
                    solution.getName(),
                    emptyToDash(solution.getSourceKind()),
                    emptyToDash(solution.getSourceUrl()),
                    solution.getSourceDate() == null ? "—"
                            : solution.getSourceDate().toString(),
                    characteristicRows));
        }
        return rows;
    }

    private Map<Long, Solution> loadSolutions(List<Scenario> scenarios) {
        List<Long> ids = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            scenarioSolutionRepository.findAllByScenarioId(scenario.getId())
                    .forEach(row -> ids.add(row.getSolutionId()));
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return solutionRepository
                .findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Solution::getId,
                        Function.identity()));
    }

    private Map<Long, String> loadVendorNames(
            Map<Long, Solution> solutionsById) {
        List<Long> vendorIds = solutionsById.values().stream()
                .map(Solution::getVendorId).filter(Objects::nonNull)
                .distinct().toList();
        if (vendorIds.isEmpty()) {
            return Map.of();
        }
        return vendorRepository.findAllById(vendorIds).stream()
                .collect(Collectors.toMap(Vendor::getId, Vendor::getName));
    }
}
