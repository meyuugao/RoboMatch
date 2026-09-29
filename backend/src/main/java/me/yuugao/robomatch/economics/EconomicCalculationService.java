package me.yuugao.robomatch.economics;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.*;
import me.yuugao.robomatch.economics.EconomicModel.*;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.EconomicValidationException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

/**
 * Сервис расчёта экономики (; формулы — чистый
 * движок {@link EconomicModel}, источник правды — docs/economic_model.md).
 *
 * <p>СКВОЗНОЙ ПУТЬ: вход (параметры объекта + состав сценария + допущения)
 * → валидация (26 обязательных параметров экономики склада, assumptions.md
 * §2; горизонт не менее 5 лет —) → расчёт §2.1-2.13 → запись
 * (append-only calculation + снимок calculation_assumption) → отображение
 * (сравнение, чувствительность, формулы в UI).
 *
 * <p>APPEND-ONLY: каждый вызов calculate — НОВАЯ строка;
 * version_data = SHA-256 канонического снимка входов (одинаковые входы —
 * одинаковая версия: воспроизведение проверяемо), version_model — версия
 * формул. Ручная корректировка — тоже новый расчёт; запись о
 * вмешательстве (что/было/стало/почему/кто/когда) — в manual_adjustment.
 *
 * <p>СИНХРОННО: расчёт выполняется в потоке запроса, цель
 * не более 10 с — вычисления в памяти поверх 5-6 SQL-запросов; решение
 * «фон vs синхронно» — architecture.md раздел 11.
 *
 * <p>ИЗОЛЯЦИЯ: userId из JWT; чужой проект/сценарий/расчёт —
 * 404, не 403. Гейт типа объекта: is_calc_enabled = false (не склад) —
 * 400 с пояснением (data_model.md §10.8).
 */
@Service
@RequiredArgsConstructor
public class EconomicCalculationService {

    /**
 * 26 обязательных параметров экономики склада (assumptions.md §2).
 * Производные (active_zone_area, picking_units_per_day) пересчитываются
 * из обязательных источников автоматически (assumptions.md §27) —
 * проверяются через их источники.
 */
    private static final List<String> REQUIRED_ECONOMY_CODES = List.of(
            "total_warehouse_area", "active_zone_area", "shifts_per_day",
            "working_days_per_year", "shift_duration", "peak_load_factor",
            "inbound_pallets_per_day", "outbound_pallets_per_day",
            "picking_lines_per_day", "picking_units_per_day", "racking_type",
            "pallet_positions", "active_sku_count", "pallet_unit_weight",
            "pallet_dimensions", "total_warehouse_staff", "pickers_count",
            "forklift_operators_count", "picker_throughput_lines_per_hour",
            "picker_salary_gross", "forklift_operator_salary_gross",
            "payroll_insurance_contributions_rate", "main_aisle_width",
            "rack_aisle_width", "storage_zone_ceiling_height",
            "floor_flatness_deviation");
    /**
 * Локальный Jackson 2 (не Spring-бин): в Boot 4 контейнер работает на
 * Jackson 3 (tools.jackson), бина com.fasterxml ObjectMapper нет; для
 * (де)сериализации metrics_json достаточно собственного экземпляра.
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
    private final SolutionCharacteristicRepository characteristicRepository;
    private final CharacteristicTypeRepository characteristicTypeRepository;
    private final CalculationRepository calculationRepository;
    private final CalculationAssumptionRepository calculationAssumptionRepository;
    private final ManualAdjustmentRepository manualAdjustmentRepository;
    private final UserRepository userRepository;
    private final AssumptionService assumptionService;
    private final SensitivityService sensitivityService;

    // ==================================================================
    // Расчёт
    // ==================================================================

    /**
 * Окупаемость в месяцах при сроке < 0,5 года —
 * round(payback × 12, 1) (§4); иначе null. Только отображение:
 * логика расчёта payback в EconomicModel не меняется.
 */
    private static BigDecimal paybackMonths(BigDecimal payback) {
        if (payback == null
                || payback.compareTo(BigDecimal.valueOf(0.5)) >= 0) {
            return null;
        }
        return payback.multiply(BigDecimal.valueOf(12))
                .setScale(1, RoundingMode.HALF_UP);
    }

    private static ScenarioKind kindOf(ScenarioType type) {
        return switch (type) {
            case BASE -> ScenarioKind.BASE;
            case RAAS -> ScenarioKind.RAAS;
            default -> ScenarioKind.PURCHASE;
        };
    }

    private static BigDecimal num(String raw) {
        return raw == null ? null : new BigDecimal(raw.replace(',', '.'));
    }

    private static BigDecimal num(Object raw) {
        return raw == null ? null
                : raw instanceof BigDecimal bd ? bd
                  : new BigDecimal(raw.toString());
    }

    // ==================================================================
    // Сравнение сценариев
    // ==================================================================

    private static Integer intOf(Object raw) {
        if (raw == null) {
            return null;
        }
        return raw instanceof Number n ? n.intValue()
                : Integer.parseInt(raw.toString());
    }

    // ==================================================================
    // Сбор входов (параметры + состав + допущения)
    // ==================================================================

    private static BigDecimal delta(BigDecimal value, BigDecimal base) {
        if (value == null || base == null) {
            return null;
        }
        return value.subtract(base);
    }

    static String defaultName(ScenarioType type) {
        return switch (type) {
            case BASE -> "Текущий процесс без роботизации";
            case PURCHASE -> "Покупка оборудования";
            case RAAS -> "Роботы как услуга";
        };
    }

    // ==================================================================
    // Персистентность (append-only)
    // ==================================================================

    /**
 * Расчёт сценария: валидация входов → формулы §2.1-2.13 → новая строка
 * calculation + снимок допущений. Синхронно.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @return детальный вид нового расчёта
 */
    @Transactional
    public CalculationFullDto calculate(Long userId, Long projectId,
                                        Long scenarioId) {
        Project project = requireOwnedProject(userId, projectId);
        Scenario scenario = requireOwnedScenario(projectId, scenarioId);
        InputBundle bundle = loadInputs(project, scenario);
        EconomicsResult result =
                EconomicModel.calculate(bundle.input());

        Map<String, Object> details = buildDetails(bundle, result);
        Calculation saved = persistNewCalculation(scenario, bundle, result,
                details);
        return toFullView(saved, bundle, details,
                calculationAssumptionRepository
                        .findAllByCalculationIdOrderByIdAsc(saved.getId()));
    }

    /**
 * История расчётов сценария, свежие сверху.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param scenarioId идентификатор сценария
 * @return расчёты сценария свежими сверху
 */
    @Transactional(readOnly = true)
    public List<CalculationDto> history(Long userId, Long projectId,
                                        Long scenarioId) {
        requireOwnedProject(userId, projectId);
        requireOwnedScenario(projectId, scenarioId);
        return calculationRepository
                .findAllByScenarioIdOrderByCalculatedAtDescIdDesc(scenarioId)
                .stream().map(this::toShortView).toList();
    }

    // ==================================================================
    // Метрики (metrics_json) и отображения
    // ==================================================================

    /**
 * Детальный расчёт: метрики + снимок допущений + разбивка.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param calculationId идентификатор расчёта
 * @return детальный вид расчёта с разбивкой
 */
    @Transactional(readOnly = true)
    public CalculationFullDto detail(Long userId, Long projectId,
                                     Long calculationId) {
        Calculation calculation = requireOwnedCalculation(userId, projectId,
                calculationId);
        List<CalculationAssumption> snapshot =
                calculationAssumptionRepository
                        .findAllByCalculationIdOrderByIdAsc(calculation.getId());
        Map<String, Object> details = parseDetails(calculation.getMetricsJson());
        return toFullView(calculation, null, details, snapshot);
    }

    /**
 * Ручная корректировка метрики: новый расчёт с заменённой
 * метрикой (append-only), запись о вмешательстве — в manual_adjustment,
 * привязана к ИСХОДНОМУ расчёту (что корректировалось).
 *
 * <p>Зависимые метрики пересчитываются автоматически —
 * скорректированы total_capex или effect_year → payback_years =
 * CAPEX / Effect_year и roi_pct = Effect_year × Горизонт / CAPEX × 100
 * (горизонт — из metrics_json.horizonYears; округление §4 — до
 * десятых). payback_years и roi_pct связаны через CAPEX/Effect
 * неоднозначно — при корректировке одного другой НЕ пересчитывается,
 * в adjustedFrom добавляется пометка «зависимые метрики не
 * пересчитаны».
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param calculationId идентификатор исходного расчёта
 * @param request имя метрики и новое значение
 * @return детальный вид скорректированного расчёта
 */
    @Transactional
    public CalculationFullDto adjust(Long userId, Long projectId,
                                     Long calculationId,
                                     ManualAdjustRequest request) {
        Calculation source = requireOwnedCalculation(userId, projectId,
                calculationId);
        Scenario scenario = scenarioRepository
                .findById(source.getScenarioId())
                .orElseThrow(() -> new NotFoundException(
                        "Сценарий расчёта не найден"));
        BigDecimal original = metricOf(source, request.metricName());
        if (original != null && original.compareTo(request.newValue()) == 0) {
            throw new BadRequestException("Новое значение совпадает "
                    + "с текущим — корректировка бессмысленна.");
        }
        Map<String, Object> sourceDetails = parseDetails(
                source.getMetricsJson());

        // --- пересчёт зависимых метрик ----------------------------------
        BigDecimal capex = replaceMetric(request, "total_capex",
                source.getTotalCapex());
        BigDecimal effect = replaceMetric(request, "effect_year",
                source.getEffectYear());
        BigDecimal payback = replaceMetric(request, "payback_years",
                source.getPaybackYears());
        BigDecimal roi = replaceMetric(request, "roi_pct",
                source.getRoiPct());
        List<String> recalculated = new ArrayList<>();
        String dependentsNote = null;
        switch (request.metricName()) {
            case "total_capex", "effect_year" -> {
                Integer horizon = intOf(sourceDetails.get("horizonYears"));
                // Payback = CAPEX / Effect (при положительных; иначе null —
                // «не окупается», §2.7). Cap 1 млн лет — как в модели
                if (capex != null && capex.signum() > 0
                        && effect != null && effect.signum() > 0) {
                    payback = capex.divide(effect, MathContext.DECIMAL128)
                            .setScale(1, RoundingMode.HALF_UP);
                    if (payback.compareTo(
                            BigDecimal.valueOf(1_000_000)) > 0) {
                        payback = null;
                    }
                } else {
                    payback = null;
                }
                // ROI = Effect × Горизонт / CAPEX × 100 (эффект может быть
                // отрицательным; CAPEX = 0 — не определён, §2.8)
                roi = null;
                if (capex != null && capex.signum() > 0
                        && effect != null && horizon != null) {
                    BigDecimal rawRoi = effect
                            .multiply(BigDecimal.valueOf(horizon),
                                    MathContext.DECIMAL128)
                            .divide(capex, MathContext.DECIMAL128)
                            .multiply(BigDecimal.valueOf(100),
                                    MathContext.DECIMAL128);
                    if (rawRoi.abs().compareTo(
                            BigDecimal.valueOf(1_000_000_000)) <= 0) {
                        roi = rawRoi.setScale(1, RoundingMode.HALF_UP);
                    }
                }
                recalculated.add("payback_years");
                if (horizon != null) {
                    recalculated.add("roi_pct");
                }
            }
            case "payback_years", "roi_pct" ->
                // Однозначной обратной связи нет (см. javadoc) — метрики
                // не пересчитаны, фиксируем это явно
                    dependentsNote = "зависимые метрики не пересчитаны: "
                            + "payback_years и roi_pct связаны через "
                            + "CAPEX/Effect неоднозначно";
            default -> {
                // total_opex / opex_delta_rub / tco_rub — зависимых нет
            }
        }

        // Новый расчёт: копия метрик с заменой одной метрики
        // (+ пересчитанные зависимые)
        Calculation adjusted = Calculation.builder()
                .scenarioId(source.getScenarioId())
                .versionData(source.getVersionData())
                .versionModel(source.getVersionModel())
                .calculatedAt(Instant.now())
                .totalCapex(replaceMetric(request, "total_capex",
                        source.getTotalCapex()))
                .totalOpex(replaceMetric(request, "total_opex",
                        source.getTotalOpex()))
                .opexDeltaRub(replaceMetric(request, "opex_delta_rub",
                        source.getOpexDeltaRub()))
                .effectYear(replaceMetric(request, "effect_year",
                        source.getEffectYear()))
                .paybackYears(payback)
                .roiPct(roi)
                .tcoRub(replaceMetric(request, "tco_rub", source.getTcoRub()))
                .metricsJson(source.getMetricsJson())
                .build();
        Calculation saved = calculationRepository.save(adjusted);
        // Снимок допущений копируется в новый расчёт (те же входы)
        for (CalculationAssumption row : calculationAssumptionRepository
                .findAllByCalculationIdOrderByIdAsc(source.getId())) {
            calculationAssumptionRepository.save(CalculationAssumption.builder()
                    .calculationId(saved.getId()).name(row.getName())
                    .value(row.getValue()).unit(row.getUnit())
                    .sourceKind(row.getSourceKind())
                    .sourceUrl(row.getSourceUrl())
                    .sourceDate(row.getSourceDate())
                    .impactNote(row.getImpactNote()).build());
        }
        // Запись о вмешательстве — на исходном расчёте
        String authorLogin = userRepository.findById(userId)
                .map(u -> u.getLogin()).orElse("unknown");
        manualAdjustmentRepository.save(ManualAdjustment.builder()
                .calculationId(source.getId())
                .metricName(request.metricName())
                .originalValue(original)
                .newValue(request.newValue())
                .reason(request.reason())
                .authorUserId(userId)
                .build());
        // В metrics_json нового расчёта — чем он отличается от исходного
        Map<String, Object> details = parseDetails(saved.getMetricsJson());
        Map<String, Object> adjustedFrom = new LinkedHashMap<>();
        adjustedFrom.put("sourceCalculationId", source.getId());
        adjustedFrom.put("metricName", request.metricName());
        adjustedFrom.put("originalValue", original);
        adjustedFrom.put("newValue", request.newValue());
        adjustedFrom.put("reason", request.reason());
        adjustedFrom.put("authorLogin", authorLogin);
        adjustedFrom.put("createdAt", Instant.now().toString());
        // Что пересчитано автоматически (или почему нет)
        if (!recalculated.isEmpty()) {
            adjustedFrom.put("recalculatedMetrics",
                    List.copyOf(recalculated));
        }
        if (dependentsNote != null) {
            adjustedFrom.put("dependentsNote", dependentsNote);
        }
        details.put("adjustedFrom", adjustedFrom);
        // Синхронизация зеркальных полей details: детальный
        // просмотр не должен противоречить заголовочной метрике
        syncAdjustedMetric(details, request.metricName(), request.newValue());
        if (!recalculated.isEmpty()) {
            // пересчитанные зависимые — тоже зеркала колонок
            syncAdjustedMetric(details, "payback_years", payback);
            if (recalculated.contains("roi_pct")) {
                syncAdjustedMetric(details, "roi_pct", roi);
            }
        }
        saved.setMetricsJson(writeJson(details));
        calculationRepository.save(saved);
        return toFullView(saved, null, details,
                calculationAssumptionRepository
                        .findAllByCalculationIdOrderByIdAsc(saved.getId()));
    }

    /**
 * Таблица сравнения: base + purchase + raas (первый сценарий каждого
 * типа), последний расчёт каждого; Δ к базовому, интерпретация
 * окупаемости, чувствительность.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return таблица сравнения трёх сценариев
 */
    @Transactional(readOnly = true)
    public ComparisonDto compare(Long userId, Long projectId) {
        Project project = requireOwnedProject(userId, projectId);
        List<Scenario> scenarios = scenarioRepository
                .findAllByProjectIdOrderByIdAsc(projectId);
        Map<ScenarioType, Scenario> firstByType = new LinkedHashMap<>();
        for (Scenario s : scenarios) {
            firstByType.putIfAbsent(s.getType(), s);
        }
        List<ComparisonDto.ScenarioColumnDto> columns = new ArrayList<>();
        ComparisonDto.ScenarioColumnDto baseColumn = null;
        Integer horizon = null;
        String versionModel = null;
        for (ScenarioType type : List.of(ScenarioType.BASE, ScenarioType.PURCHASE,
                ScenarioType.RAAS)) {
            Scenario scenario = firstByType.get(type);
            if (scenario == null) {
                columns.add(emptyColumn(type));
                continue;
            }
            Calculation latest = calculationRepository
                    .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                            scenario.getId()).orElse(null);
            if (latest == null) {
                columns.add(notCalculatedColumn(scenario));
                continue;
            }
            Map<String, Object> details =
                    parseDetails(latest.getMetricsJson());
            BigDecimal capex = latest.getTotalCapex();
            BigDecimal tco = latest.getTcoRub();
            BigDecimal effect = latest.getEffectYear();
            // оба поля парка раздельно + флаг недобора
            Integer selectedRobots = intOf(details.get("selectedRobots"));
            Integer requiredRobots = intOf(details.get("requiredRobots"));
            boolean underpowered = Boolean.TRUE.equals(
                    details.get("underpowered"));
            // Превышение парка — нейтральный
            // флаг (старые расчёты без поля читаются как false)
            boolean overpowered = Boolean.TRUE.equals(
                    details.get("overpowered"));
            Integer nInfra = intOf(details.get("nInfra"));
            BigDecimal deltaFot = num(details.get("deltaFot"));
            List<SensitivityRowDto> sensitivity = sensitivityOf(details);
            List<String> warnings = warningsOf(details);
            // При недоборе парка Payback/ROI не
            // показываются числами — единая формулировка причины
            // («Н/Д — …» и для окупаемости, и для ROI), ROI скрыт (числа
            // остаются в детальном расчёте и истории); условие 3 —
            // предупреждение в warnings (см. EconomicModel)
            InterpretationDto paybackView = underpowered
                    ? new InterpretationDto(null, null, null, "underpowered",
                    "Н/Д — парк меньше требуемого "
                    + "по пиковой нагрузке")
                    : interpret(selectedRobots, latest.getPaybackYears(),
                    effect, capex, type);
            ComparisonDto.ScenarioColumnDto column =
                    new ComparisonDto.ScenarioColumnDto(scenario.getId(),
                            latest.getId(), type.name().toLowerCase(),
                            scenario.getName(),
                            true, latest.getCalculatedAt(), selectedRobots,
                            requiredRobots, underpowered, overpowered,
                            nInfra,
                            capex, latest.getTotalOpex(),
                            latest.getOpexDeltaRub(), deltaFot, effect,
                            paybackView,
                            underpowered ? null : latest.getRoiPct(), tco,
                            null, null, null,
                            sensitivity, warnings, latest.getVersionData());
            if (type == ScenarioType.BASE) {
                baseColumn = column;
                horizon = intOf(details.get("horizonYears"));
            }
            columns.add(column);
            if (versionModel == null) {
                versionModel = latest.getVersionModel();
            }
        }
        // Δ к базовому (после сборки колонок)
        List<ComparisonDto.ScenarioColumnDto> withDeltas = new ArrayList<>();
        for (ComparisonDto.ScenarioColumnDto column : columns) {
            if (baseColumn != null && column.calculated()
                    && baseColumn.calculated() && !column.equals(baseColumn)) {
                withDeltas.add(new ComparisonDto.ScenarioColumnDto(
                        column.scenarioId(), column.calculationId(),
                        column.type(), column.name(),
                        column.calculated(), column.calculatedAt(),
                        column.selectedRobots(), column.requiredRobots(),
                        column.underpowered(), column.overpowered(),
                        column.nInfra(), column.capex(),
                        column.opexYear(), column.opexDelta(),
                        column.deltaFot(), column.effectYear(),
                        column.payback(), column.roiPct(), column.tco(),
                        delta(column.capex(), baseColumn.capex()),
                        delta(column.tco(), baseColumn.tco()),
                        delta(column.effectYear(), baseColumn.effectYear()),
                        column.sensitivity(), column.warnings(),
                        column.versionData()));
            } else {
                withDeltas.add(column);
            }
        }
        if (horizon == null) {
            horizon = effectiveHorizon(project);
        }
        return new ComparisonDto(withDeltas, horizon, versionModel);
    }

    /**
 * Загрузка и валидация входов расчёта. Параметры — эффективные
 * значения (строка проекта или дефолт датасета); состав —
 * scenario_solution (подбор + ручные добавления); допущения — каталог
 * + переопределения.
 */
    private InputBundle loadInputs(Project project, Scenario scenario) {
        ObjectType objectType = objectTypeRepository
                .findById(project.getObjectTypeId())
                .orElseThrow(() -> new NotFoundException(
                        "Тип объекта не найден"));
        if (!Boolean.TRUE.equals(objectType.getIsCalcEnabled())) {
            throw new BadRequestException("Расчёт экономики недоступен "
                    + "для типа объекта «" + objectType.getName() + "» "
                    + "(MVP: склад).");
        }
        Map<String, String> params = effectiveParams(project);
        // Снимок эффективных допущений ОДНИМ запросом:
        // неизменяем в рамках расчёта — без torn read и ~50 SELECT
        Long pid = project.getId();
        AssumptionService.EffectiveAssumptions eff =
                assumptionService.effectiveSnapshot(pid);
        List<String> missing = REQUIRED_ECONOMY_CODES.stream()
                .filter(code -> !"active_zone_area".equals(code)
                        && !"picking_units_per_day".equals(code))
                .filter(code -> params.get(code) == null)
                .collect(Collectors.toList());
        // производные параметры пересчитываются из обязательных источников
        // (assumptions.md §27): источники уже проверены выше
        if (!missing.isEmpty()) {
            List<String> readable = missing.stream()
                    .map(code -> "«" + code + "»").toList();
            throw new EconomicValidationException(List.of(
                    "Не заполнены обязательные параметры экономики склада: "
                            + String.join(", ", readable)));
        }

        // --- производные входы формул (economic_model.md §1.1) ----------
        BigDecimal shifts = num(params.get("shifts_per_day"));
        BigDecimal shiftDuration = num(params.get("shift_duration"));
        BigDecimal workingDays = num(params.get("working_days_per_year"));
        BigDecimal peakFactor = num(params.get("peak_load_factor"));
        BigDecimal inbound = num(params.get("inbound_pallets_per_day"));
        BigDecimal outbound = num(params.get("outbound_pallets_per_day"));
        BigDecimal pickers = num(params.get("pickers_count"));
        BigDecimal forklifts = num(params.get("forklift_operators_count"));
        BigDecimal pickerSalary = num(params.get("picker_salary_gross"));
        BigDecimal forkliftSalary = num(params.get("forklift_operator_salary_gross"));
        BigDecimal payrollRate = num(params
                .get("payroll_insurance_contributions_rate"));

        int horizon = effectiveHorizon(project, params);
        if (horizon < 5) {
            throw new EconomicValidationException(List.of(
                    "Горизонт расчёта " + horizon + " лет меньше минимального "
                            + "(TCO на горизонте не менее 5 лет). "
                            + "Увеличьте «Горизонт расчёта окупаемости»."));
        }

        BigDecimal dailyOps = inbound.add(outbound);
        BigDecimal hoursPerDay = shifts.multiply(shiftDuration);
        if (hoursPerDay.signum() <= 0) {
            throw new EconomicValidationException(List.of(
                    "Режим работы даёт 0 часов в сутки — проверьте сменность "
                            + "и продолжительность смены (нулевой знаменатель)."));
        }
        // Peak_demand: (приёмка + отгрузка)/часы × пиковый коэффициент
        BigDecimal peakDemand = dailyOps.divide(hoursPerDay, 6,
                RoundingMode.HALF_UP).multiply(peakFactor);
        BigDecimal hoursYear = hoursPerDay.multiply(workingDays);
        BigDecimal operationsYear = dailyOps.multiply(workingDays);
        // ФОТ контура роботизации (целевые группы, датасет) с начислениями
        BigDecimal contourSalarySum = pickerSalary.multiply(pickers)
                .add(forkliftSalary.multiply(forklifts));
        BigDecimal fotBase = contourSalarySum.multiply(BigDecimal.valueOf(12))
                .multiply(payrollRate);
        BigDecimal staffCount = pickers.add(forklifts);
        BigDecimal salaryYear = staffCount.signum() > 0
                ? contourSalarySum.divide(staffCount, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(12))
                : BigDecimal.ZERO;

        // --- состав сценария + ТТХ (срок службы, мощность зарядки) ------
        List<ScenarioSolution> composition = scenarioSolutionRepository
                .findAllByScenarioId(scenario.getId());
        Map<Long, Solution> solutions = solutionRepository
                .findAllById(composition.stream()
                        .map(ScenarioSolution::getSolutionId).toList())
                .stream().collect(Collectors.toMap(Solution::getId,
                        Function.identity()));
        // Коды ТТХ для экономики: срок службы и мощность зарядки (§1.2);
        // метаданные — одним запросом, без N+1
        Map<String, Long> charTypeIdByCode = characteristicTypeRepository
                .findAll().stream()
                .filter(t -> "lifecycle_years".equals(t.getCode())
                        || "charging_power_kw".equals(t.getCode()))
                .collect(Collectors.toMap(CharacteristicType::getCode,
                        CharacteristicType::getId));
        Long lifetimeTypeId = charTypeIdByCode.get("lifecycle_years");
        Long chargingTypeId = charTypeIdByCode.get("charging_power_kw");
        Map<Long, List<SolutionCharacteristic>> charsBySolution =
                solutions.isEmpty() ? Map.of()
                        : characteristicRepository
                        .findBySolutionIdIn(List.copyOf(
                                solutions.keySet()))
                        .stream().collect(Collectors.groupingBy(
                                SolutionCharacteristic::getSolutionId));
        Map<Long, BigDecimal> lifetimeBySolution = new HashMap<>();
        Map<Long, BigDecimal> chargingBySolution = new HashMap<>();
        for (Map.Entry<Long, List<SolutionCharacteristic>> entry
                : charsBySolution.entrySet()) {
            for (SolutionCharacteristic c : entry.getValue()) {
                if (c.getValueNumeric() == null) {
                    continue;
                }
                if (lifetimeTypeId != null
                        && lifetimeTypeId.equals(c.getCharacteristicTypeId())) {
                    lifetimeBySolution.put(entry.getKey(), c.getValueNumeric());
                } else if (chargingTypeId != null
                        && chargingTypeId.equals(c.getCharacteristicTypeId())) {
                    chargingBySolution.put(entry.getKey(), c.getValueNumeric());
                }
            }
        }

        List<SolutionLine> lines = new ArrayList<>();
        List<String> inputWarnings = new ArrayList<>();
        BigDecimal explicitConsumption = eff.numeric(
                "robot_power_consumption_kw");
        // Фолбэк-цепочка потребляемой мощности (data_model.md §5.8: EAV
        // поверх зеркальных колонок решения): допущение → ТТХ EAV
        // «мощность зарядки» (консервативная верхняя оценка) → зеркальная
        // колонка solution.charging_power_kw → 0 с предупреждением
        BigDecimal eavCharging = weightedAverage(chargingBySolution,
                composition);
        Map<Long, BigDecimal> mirrorCharging = new HashMap<>();
        for (ScenarioSolution row : composition) {
            Solution solution = solutions.get(row.getSolutionId());
            if (solution != null && solution.getChargingPowerKw() != null) {
                mirrorCharging.put(row.getSolutionId(),
                        solution.getChargingPowerKw());
            }
        }
        BigDecimal mirrorChargingAvg = weightedAverage(mirrorCharging,
                composition);
        BigDecimal chargingFallback = eavCharging != null ? eavCharging
                : mirrorChargingAvg;
        BigDecimal powerConsumption = explicitConsumption != null
                ? explicitConsumption : chargingFallback;
        if (powerConsumption == null && !composition.isEmpty()) {
            inputWarnings.add("Потребляемая мощность не задана (ни допущением, "
                    + "ни ТТХ «мощность зарядки») — затраты на электроэнергию "
                    + "приняты нулевыми.");
        }
        for (ScenarioSolution row : composition) {
            Solution solution = solutions.get(row.getSolutionId());
            if (solution == null) {
                continue;
            }
            if (solution.getPriceRub() == null) {
                // Решение без цены: вклад в CAPEX/OPEX — нулевой,
                // предупреждение
                inputWarnings.add("У решения «" + solution.getName()
                        + "» не указана цена — его вклад в CAPEX "
                        + "и TCO принят нулевым.");
            }
            lines.add(new SolutionLine(solution.getId(), solution.getName(),
                    row.getQuantity(),
                    solution.getPriceRub() == null ? BigDecimal.ZERO
                            : solution.getPriceRub(),
                    lifetimeBySolution.get(solution.getId()),
                    chargingBySolution.get(solution.getId())));
        }

        // --- допущения (каталог + переопределения) ------------
        BigDecimal kLoad = eff.require("k_load");
        BigDecimal kReserve = eff.require("k_reserve");
        BigDecimal reserveRate = eff.require("reserve_rate");
        RaasTerms raas = new RaasTerms(
                eff.raw("raas_payment_model"),
                eff.require("raas_rate_month_pct"),
                eff.require("raas_contract_years").intValue(),
                Boolean.parseBoolean(eff.raw("raas_buyout")),
                eff.numeric("raas_usage_rate_rub"));
        LoanTerms loan = new LoanTerms(
                eff.numeric("loan_amount_rub"),
                eff.numeric("loan_rate_pct"),
                eff.numeric("loan_term_years") == null ? null
                        : eff.numeric("loan_term_years").intValue());
        if (loan.amountRub() != null && loan.amountRub().signum() > 0
                && (loan.ratePctYear() == null || loan.termYears() == null)) {
            throw new EconomicValidationException(List.of(
                    "Для заёмного финансирования задайте ставку "
                            + "(loan_rate_pct) и срок (loan_term_years)."));
        }
        // Нулевые/отрицательные ставка и срок при заданной сумме — нулевой
        // знаменатель аннуитета (§2.10): блокировка расчёта понятной
        // ошибкой вместо 500 (каталог допущений требует "от 0.1"
        // и "1-15", но значения могут прийти извне API)
        if (loan.amountRub() != null && loan.amountRub().signum() > 0
                && ((loan.ratePctYear() != null && loan.ratePctYear().signum() <= 0)
                || (loan.termYears() != null && loan.termYears() < 1))) {
            throw new EconomicValidationException(List.of(
                    "Для заёмного финансирования ставка должна быть "
                            + "положительной (loan_rate_pct, % год.), "
                            + "срок — не менее 1 года (loan_term_years)."));
        }

        EconomicsInput input = new EconomicsInput(
                kindOf(scenario.getType()), peakDemand, hoursYear,
                operationsYear, fotBase, salaryYear, payrollRate, horizon,
                kLoad, kReserve,
                eff.numeric("k_availability"),
                eff.numeric("robot_nominal_productivity_per_hour"),
                powerConsumption,
                eff.require("tariff_rub_kwh"), reserveRate,
                eff.require("ratio_infra"),
                eff.require("c_infra_pct"), eff.require("c_software_pct"),
                eff.require("c_integration_pct"),
                eff.require("c_commissioning_pct"),
                eff.require("c_training_pct"),
                eff.require("c_service_pct"), eff.require("c_licenses_pct"),
                eff.require("c_communication_rub_year"),
                eff.require("c_consumables_pct"), eff.require("c_repair_pct"),
                eff.numeric("amortization_years"),
                eff.require("equipment_cost_factor_pct"),
                eff.numeric("other_annual_effect_rub"),
                eff.numeric("exploitation_staff_count") == null ? 0
                        : eff.numeric("exploitation_staff_count").intValue(),
                raas, loan, lines);

        return new InputBundle(input, params, eff.values(), composition,
                solutions, horizon, inputWarnings);
    }

    private Calculation persistNewCalculation(Scenario scenario,
                                              InputBundle bundle,
                                              EconomicsResult result,
                                              Map<String, Object> details) {
        Calculation row = Calculation.builder()
                .scenarioId(scenario.getId())
                .versionData(versionData(bundle))
                .versionModel(EconomicModel.MODEL_VERSION)
                .calculatedAt(Instant.now())
                .totalCapex(result.capexTotal())
                .totalOpex(result.opexTotal())
                .opexDeltaRub(result.opexDelta())
                .effectYear(result.effectYear())
                .paybackYears(result.paybackYears())
                .roiPct(result.roiPct())
                .tcoRub(result.tcoRub())
                .metricsJson(writeJson(details))
                .build();
        Calculation saved = calculationRepository.save(row);
        // Снимок допущений (§10.9)
        for (AssumptionService.CatalogItem item
                : assumptionService.catalogItems()) {
            String value = bundle.effectiveAssumptions().get(item.name());
            if (value == null) {
                continue; // необязательное допущение не задано
            }
            calculationAssumptionRepository.save(CalculationAssumption.builder()
                    .calculationId(saved.getId()).name(item.name())
                    .value(value).unit(item.unit())
                    .sourceKind(item.sourceKind())
                    .sourceUrl(null).sourceDate(
                            "organizer_catalog".equals(item.sourceKind())
                                    ? LocalDate.of(2026, 9, 17) : null)
                    .impactNote(item.impactNote()).build());
        }
        return saved;
    }

    /**
 * version_data: SHA-256 канонической строки входов
 * (отсортированные «код=значение» параметров и допущений + состав
 * с ЦЕНАМИ и ТТХ решений — всё, что влияет на результат),
 * первые 12 hex-символов. Одинаковые входы — одинаковая
 * версия; скорректированный расчёт наследует version_data
 * исходного (входы те же, вмешательство — в adjustedFrom).
 */
    private String versionData(InputBundle bundle) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(bundle.effectiveParams()).forEach((code, value) ->
                canonical.append("p:").append(code).append('=')
                        .append(value).append(';'));
        new TreeMap<>(bundle.effectiveAssumptions()).forEach((code, value) ->
                canonical.append("a:").append(code).append('=')
                        .append(value).append(';'));
        // Эффективная потребляемая мощность: её фолбэк-цепочка
        // (допущение -> EAV -> зеркальная колонка решения) влияла на результат
        // раньше, чем эта строка появилась здесь — смена зеркальной колонки
        // меняла расчёт, не меняя версию данных
        canonical.append("e:power=")
                .append(bundle.input().powerConsumptionKw()).append(';');
        bundle.input().lines().stream()
                .sorted(Comparator.comparing(SolutionLine::solutionId))
                .forEach(line -> canonical.append("s:")
                        .append(line.solutionId()).append(':')
                        .append(line.quantity()).append(':')
                        .append(line.priceRub().stripTrailingZeros()
                                .toPlainString()).append(':')
                        .append(line.lifetimeYears()).append(':')
                        .append(line.chargingPowerKw()).append(';'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString()
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 недоступен", ex);
        }
    }

    // ==================================================================
    // Вспомогательные
    // ==================================================================

    /**
 * Разбивка расчёта для metrics_json (data_model.md §10.8, §12).
 */
    private Map<String, Object> buildDetails(InputBundle bundle,
                                             EconomicsResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("modelVersion", EconomicModel.MODEL_VERSION);
        // Оба показателя парка — раздельно (§2.1): selected —
        // фактический состав (вся экономика по нему), required —
        // рекомендация по пиковой нагрузке. Флаг недобора.
        details.put("selectedRobots", result.selectedRobots());
        details.put("nInfra", result.nInfra());
        if (result.requiredRobots() != null) {
            details.put("requiredRobots", result.requiredRobots());
        }
        details.put("underpowered", result.underpowered());
        // Превышение парка — зеркалится в metrics_json для compare
        details.put("overpowered", result.overpowered());
        details.put("horizonYears", bundle.horizonYears());
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("peakDemandPerHour", bundle.input().peakDemandPerHour());
        inputs.put("hoursYear", bundle.input().hoursYear());
        inputs.put("operationsYear", bundle.input().operationsYear());
        // Эффективная потребляемая мощность — какой именно
        // набор значений использован (допущение или фолбэк ТТХ зарядки),
        // виден пользователю
        inputs.put("powerConsumptionKw", bundle.input().powerConsumptionKw());
        inputs.put("fotBaseYear", bundle.input().fotBaseYear());
        inputs.put("salaryYearYear", bundle.input().salaryYearYear());
        inputs.put("payrollRate", bundle.input().payrollRate());
        // Все эффективные параметры объекта ( —
        // воспроизведение расчёта по сохранённым входам, а не только хеш)
        inputs.put("parameters", new TreeMap<>(bundle.effectiveParams()));
        details.put("inputs", inputs);
        // Эффективные допущения (персонал эксплуатации в UI;
        // — воспроизведение по сохранённым входам)
        details.put("effectiveAssumptions",
                new TreeMap<>(bundle.effectiveAssumptions()));
        Map<String, Object> capex = new LinkedHashMap<>();
        // RaaS: разбивка CAPEX покупки не относится к сценарию (CAPEX_raas —
        // только выкуп); полная стоимость парка — информативно в raas-блоке
        // (fleetCostRub), а сумма статей капекса согласована с итогом = 0
        // (раньше equipment показывал весь парк при total = 0)
        boolean raasKind = bundle.input().kind() == ScenarioKind.RAAS;
        capex.put("equipment", raasKind ? BigDecimal.ZERO
                : result.cEquipment());
        capex.put("infra", result.cInfra());
        capex.put("software", result.cSoftware());
        capex.put("integration", result.cIntegration());
        capex.put("commissioning", result.cCommissioning());
        capex.put("training", result.cTraining());
        capex.put("reserve", result.cReserve());
        capex.put("total", result.capexTotal());
        details.put("capex", capex);
        Map<String, Object> opex = new LinkedHashMap<>();
        opex.put("service", result.cService());
        opex.put("licenses", result.cLicenses());
        opex.put("electricity", result.cElectricity());
        opex.put("communication", result.cCommunication());
        opex.put("consumables", result.cConsumables());
        opex.put("repair", result.cRepair());
        opex.put("staff", result.cStaff());
        opex.put("total", result.opexTotal());
        details.put("opex", opex);
        details.put("opexBase", result.opexBase());
        details.put("fotBase", result.fotBase());
        details.put("fotRob", result.fotRob());
        details.put("deltaFot", result.deltaFot());
        details.put("deltaOther", result.deltaOther());
        details.put("effectGross", result.effectGross());
        details.put("amortYear", result.amortYear());
        // Итоговые метрики — зеркала колонок: adjust синхронизирует их
        // в metrics_json, чтобы детальный просмотр не
        // противоречил заголовочным значениям
        details.put("opexDelta", result.opexDelta());
        details.put("effectYear", result.effectYear());
        details.put("paybackYears", result.paybackYears());
        details.put("roiPct", result.roiPct());
        details.put("tcoRub", result.tcoRub());
        details.put("replacements", result.replacementsRub());
        details.put("avgRobotPrice", result.avgRobotPrice());
        if (result.raas() != null) {
            Map<String, Object> raas = new LinkedHashMap<>();
            raas.put("paymentModel", result.raas().paymentModel());
            raas.put("rateMonthRub", result.raas().rateMonthRub());
            raas.put("paymentYear", result.raas().paymentYear());
            raas.put("paymentYears", result.raas().paymentYears());
            raas.put("buyoutValue", result.raas().buyoutValue());
            raas.put("capexRaas", result.raas().capexRaas());
            // полная стоимость парка (справочно, вне CAPEX сценария)
            raas.put("fleetCostRub", result.cEquipment());
            details.put("raas", raas);
        }
        if (result.loan() != null) {
            Map<String, Object> loan = new LinkedHashMap<>();
            loan.put("debtPaymentYear", result.loan().debtPaymentYear());
            // оба значения — до и после обслуживания долга
            // (Effect_year расчёта = after; payback/roi считаны от него)
            loan.put("effectYearBeforeDebt",
                    result.loan().effectYearBeforeDebt());
            loan.put("effectYearAfterDebt",
                    result.loan().effectYearAfterDebt());
            details.put("loan", loan);
        }
        List<Map<String, Object>> compositionLines = new ArrayList<>();
        bundle.composition().forEach(row -> {
            Solution solution = bundle.solutions().get(row.getSolutionId());
            if (solution == null) {
                return;
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("solutionId", solution.getId());
            line.put("solutionName", solution.getName());
            line.put("quantity", row.getQuantity());
            line.put("priceRub", solution.getPriceRub());
            line.put("manual", row.getIsManual());
            compositionLines.add(line);
        });
        details.put("composition", compositionLines);
        // Чувствительность — только роботизированные сценарии (§2.12):
        // у базового эффект тождественно 0
        if (bundle.input().kind() != ScenarioKind.BASE) {
            details.put("sensitivity", sensitivityService.sensitivity(
                    bundle.input(), result));
        }
        List<String> warnings = new ArrayList<>(bundle.inputWarnings());
        warnings.addAll(result.warnings());
        details.put("warnings", warnings);
        return details;
    }

    private CalculationDto toShortView(Calculation row) {
        boolean adjusted = parseDetails(row.getMetricsJson())
                .get("adjustedFrom") != null;
        return new CalculationDto(row.getId(), row.getScenarioId(),
                row.getVersionData(), row.getVersionModel(),
                row.getCalculatedAt(), row.getTotalCapex(),
                row.getTotalOpex(), row.getOpexDeltaRub(),
                row.getEffectYear(), row.getPaybackYears(), row.getRoiPct(),
                row.getTcoRub(), adjusted);
    }

    private CalculationFullDto toFullView(Calculation row, InputBundle bundle,
                                          Map<String, Object> details,
                                          List<CalculationAssumption> snapshot) {
        List<CalculationFullDto.AssumptionSnapshotDto> assumptions =
                snapshot.stream().map(a -> {
                    AssumptionService.CatalogItem item =
                            assumptionService.catalogItem(a.getName());
                    return new CalculationFullDto.AssumptionSnapshotDto(
                            a.getName(),
                            item == null ? a.getName() : item.title(),
                            a.getValue(), a.getUnit(), a.getSourceKind(),
                            a.getImpactNote());
                }).toList();
        List<CalculationFullDto.AdjustmentDto> adjustments =
                manualAdjustmentRepository
                        .findAllByCalculationIdOrderByIdAsc(row.getId())
                        .stream().map(a -> new CalculationFullDto
                                .AdjustmentDto(a.getMetricName(),
                                a.getOriginalValue(), a.getNewValue(),
                                a.getReason(), authorLogin(a),
                                a.getCreatedAt())).toList();
        return new CalculationFullDto(row.getId(), row.getScenarioId(),
                row.getVersionData(), row.getVersionModel(),
                row.getCalculatedAt(), row.getTotalCapex(),
                row.getTotalOpex(), row.getOpexDeltaRub(), row.getEffectYear(),
                row.getPaybackYears(), row.getRoiPct(), row.getTcoRub(),
                details, assumptions, adjustments);
    }

    private String authorLogin(ManualAdjustment adjustment) {
        return userRepository.findById(adjustment.getAuthorUserId())
                .map(u -> u.getLogin()).orElse("unknown");
    }

    /**
 * Интерпретация окупаемости: интервалы вместо порога.
 * RaaS с CAPEX = 0 (без выкупа) — «Н/П» с явным указанием
 * причины и способа сравнения (годовой эффект и TCO).
 * Пустой состав — ветка сразу после BASE
 * (приоритет выше проверки Effect_year <= 0): настоящая причина
 * неокупаемости — не отрицательный эффект, а отсутствие решений.
 * Базовый сценарий — ПЕРВАЯ ветка: он
 * пуст по определению (инвариант base, data_model.md §10.5) — текст
 * «Состав пуст» про base вводил в заблуждение; окупаемость к base
 * неприменима в принципе (сравнение по OPEX и TCO).
 * Срок < 0,5 года — paybackMonths для
 * отображения в месяцах (только форматирование, расчёт не меняется).
 */
    InterpretationDto interpret(Integer selectedRobots,
                                        BigDecimal payback,
                                        BigDecimal effectYear,
                                        BigDecimal capex,
                                        ScenarioType type) {
        // Базовый сценарий — окупаемость не применяется (не инвестиции,
        // а точка сравнения); пустой состав base — норма, не «Состав пуст»
        if (type == ScenarioType.BASE) {
            return new InterpretationDto(null, null, null, "not_applicable",
                    "Не применяется — базовый сценарий сравнивается "
                            + "по годовому OPEX и TCO");
        }
        // пустой состав — «Состав пуст — добавьте решения»
        if (selectedRobots != null && selectedRobots == 0) {
            return new InterpretationDto(null, null, null, "empty_composition",
                    "Состав пуст — добавьте решения в сценарий");
        }
        if (payback != null) {
            // человекочитаемая строка по правилу §4
            String paybackHuman = InterpretationFormatter.formatPayback(payback);
            InterpretationDto view = payback.compareTo(
                    BigDecimal.valueOf(3)) <= 0
                    ? new InterpretationDto(payback,
                    paybackMonths(payback), paybackHuman, "fast",
                    "Быстрая окупаемость — до 3 лет")
                    : payback.compareTo(BigDecimal.valueOf(5)) <= 0
                      ? new InterpretationDto(payback,
                    paybackMonths(payback), paybackHuman, "moderate",
                    "Умеренная окупаемость — от 3 до 5 лет")
                      : new InterpretationDto(payback,
                    paybackMonths(payback), paybackHuman, "long",
                    "Долгая окупаемость — более 5 лет, требует обоснования");
            return view;
        }
        if (effectYear != null && effectYear.signum() <= 0) {
            return new InterpretationDto(null, null, null, "none",
                    "Не окупается на текущих допущениях — годовой эффект "
                            + "не положителен");
        }
        // эффект положителен, но Payback не рассчитан: либо CAPEX = 0,
        // либо сверхдолгая окупаемость за порогом расчёта (cap 1 млн лет)
        // — различаем эти случаи
        if (capex != null && capex.signum() > 0) {
            return new InterpretationDto(null, null, null, "long",
                    "Не окупается в разумный срок — эффект положителен, "
                            + "но срок окупаемости за пределами расчётного "
                            + "порога");
        }
        if (type == ScenarioType.RAAS) {
            // RaaS без выкупа — сравнение по эффекту и TCO
            return new InterpretationDto(null, null, null, "undefined",
                    "Н/П — CAPEX = 0 (RaaS без выкупа): сравнивайте "
                            + "по годовому эффекту и TCO");
        }
        return new InterpretationDto(null, null, null, "undefined",
                "Окупаемость не определена (CAPEX = 0) — сравнивайте по "
                        + "годовому эффекту и TCO");
    }

    /**
 * Эффективные параметры проекта: код → строковое значение.
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
        Map<String, String> nameByCode = new HashMap<>();
        for (ObjectTypeParameter definition : definitions) {
            ParameterType type = types.get(definition.getParameterTypeId());
            if (type == null) {
                continue;
            }
            definitionByCode.put(type.getCode(), definition);
            nameByCode.put(type.getCode(), type.getName());
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

    /**
 * Горизонт: параметр «Горизонт расчёта окупаемости», иначе 5 (§22).
 *
 * <p>Исправлено (2026-09-23): значение приходит из
 * project_parameter_value.value_numeric numeric(16,4) — строка вида
 * «10.0000», на которой Integer.parseInt падал и горизонт МОЛЧА
 * дефолтился в 5 при любом пользовательском значении (
 * «горизонт расчёта» игнорировался, ROI/TCO §2.8–2.9 считались на 5
 * лет). Теперь — BigDecimal-парсинг (как везде в этом сервисе) с
 * округлением до целых лет.
 */
    private int effectiveHorizon(Project project, Map<String, String> params) {
        String raw = params.get("payback_horizon");
        if (raw != null) {
            try {
                int parsed = num(raw).setScale(0, RoundingMode.HALF_UP)
                        .intValueExact();
                if (parsed > 0) {
                    return parsed;
                }
                // Некорректное значение (<= 0) больше
                // не подменяется молча дефолтом — блокировка расчёта
                // понятной ошибкой (§6: отрицательные значения — блокировка)
                throw new EconomicValidationException(List.of(
                        "Параметр «Горизонт расчёта окупаемости» должен быть "
                                + "положительным (получено: " + raw
                                + "); минимум для расчёта — 5 лет."));
            } catch (NumberFormatException | ArithmeticException ex) {
                // не число / не целое — тоже блокировка, а не молчаливый
                // дефолт 5 (значение задано, но нераспарсимо)
                throw new EconomicValidationException(List.of(
                        "Параметр «Горизонт расчёта окупаемости» задан "
                                + "некорректно: «" + raw
                                + "». Укажите целое число лет."));
            }
        }
        return 5;
    }

    private int effectiveHorizon(Project project) {
        return effectiveHorizon(project, effectiveParams(project));
    }

    private BigDecimal weightedAverage(Map<Long, BigDecimal> values,
                                       List<ScenarioSolution> composition) {
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal acc = BigDecimal.ZERO;
        for (ScenarioSolution row : composition) {
            BigDecimal value = values.get(row.getSolutionId());
            if (value != null) {
                acc = acc.add(value.multiply(
                        BigDecimal.valueOf(row.getQuantity())));
                weight = weight.add(BigDecimal.valueOf(row.getQuantity()));
            }
        }
        return weight.signum() > 0
                ? acc.divide(weight, 6, RoundingMode.HALF_UP) : null;
    }

    /**
 * Замена зеркального поля в details (metrics_json) при adjust:
 * метрика колонки и её копия в разбивке обязаны совпадать.
 * Разбивки по статьям остаются от исходного расчёта — вмешательство
 * отмечено блоком adjustedFrom (осознанное решение).
 */
    @SuppressWarnings("unchecked")
    private void syncAdjustedMetric(Map<String, Object> details,
                                    String metricName, BigDecimal newValue) {
        switch (metricName) {
            case "total_capex" -> {
                Object capex = details.get("capex");
                if (capex instanceof Map<?, ?> map) {
                    ((Map<String, Object>) map).put("total", newValue);
                }
            }
            case "total_opex" -> {
                Object opex = details.get("opex");
                if (opex instanceof Map<?, ?> map) {
                    ((Map<String, Object>) map).put("total", newValue);
                }
            }
            case "opex_delta_rub" -> details.put("opexDelta", newValue);
            case "effect_year" -> details.put("effectYear", newValue);
            case "payback_years" -> details.put("paybackYears", newValue);
            case "roi_pct" -> details.put("roiPct", newValue);
            case "tco_rub" -> details.put("tcoRub", newValue);
            default -> {
                // неизвестная метрика отсеяна @Pattern ранее
            }
        }
    }

    private BigDecimal metricOf(Calculation calculation, String metric) {
        return switch (metric) {
            case "total_capex" -> calculation.getTotalCapex();
            case "total_opex" -> calculation.getTotalOpex();
            case "opex_delta_rub" -> calculation.getOpexDeltaRub();
            case "effect_year" -> calculation.getEffectYear();
            case "payback_years" -> calculation.getPaybackYears();
            case "roi_pct" -> calculation.getRoiPct();
            case "tco_rub" -> calculation.getTcoRub();
            default -> throw new BadRequestException("Неизвестная метрика: "
                    + metric);
        };
    }

    private BigDecimal replaceMetric(ManualAdjustRequest request, String metric,
                                     BigDecimal current) {
        return request.metricName().equals(metric) ? request.newValue()
                : current;
    }

    private Map<String, Object> parseDetails(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (Exception ex) {
            throw new IllegalStateException("Не сериализуется metrics_json",
                    ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<SensitivityRowDto> sensitivityOf(Map<String, Object> details) {
        Object raw = details.get("sensitivity");
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        List<SensitivityRowDto> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            result.add(new SensitivityRowDto(
                    String.valueOf(map.get("parameter")),
                    num(map.get("deltaPct")), num(map.get("effectYear")),
                    num(map.get("effectDelta")), num(map.get("paybackYears")),
                    num(map.get("roiPct")),
                    map.get("note") == null ? null
                            : String.valueOf(map.get("note"))));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> warningsOf(Map<String, Object> details) {
        Object raw = details.get("warnings");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private ComparisonDto.ScenarioColumnDto emptyColumn(ScenarioType type) {
        return new ComparisonDto.ScenarioColumnDto(null, null, type.name()
                .toLowerCase(), defaultName(type), false, null, null, null,
                false, false, null,
                null, null, null, null, null, null, null, null, null, null,
                null, List.of(), List.of(), null);
    }

    private ComparisonDto.ScenarioColumnDto notCalculatedColumn(
            Scenario scenario) {
        return new ComparisonDto.ScenarioColumnDto(scenario.getId(), null,
                scenario.getType().name().toLowerCase(), scenario.getName(), false,
                null, null, null, false, false, null,
                null, null, null, null, null, null, null, null, null, null,
                null, List.of(), List.of(), null);
    }

    private Project requireOwnedProject(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new NotFoundException("Проект не найден");
        }
        return project;
    }

    private Scenario requireOwnedScenario(Long projectId, Long scenarioId) {
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElse(null);
        if (scenario == null || !scenario.getProjectId().equals(projectId)) {
            throw new NotFoundException("Сценарий не найден в этом проекте");
        }
        return scenario;
    }

    private Calculation requireOwnedCalculation(Long userId, Long projectId,
                                                Long calculationId) {
        requireOwnedProject(userId, projectId);
        Calculation calculation = calculationRepository
                .findById(calculationId).orElse(null);
        if (calculation == null) {
            throw new NotFoundException("Расчёт не найден");
        }
        Scenario scenario = scenarioRepository
                .findById(calculation.getScenarioId()).orElse(null);
        if (scenario == null || !scenario.getProjectId().equals(projectId)) {
            throw new NotFoundException("Расчёт не найден в этом проекте");
        }
        return calculation;
    }

    /**
 * Промежуточный пакет: вход движка + данные для снимка/метрик.
 */
    private record InputBundle(EconomicsInput input,
                               Map<String, String> effectiveParams,
                               Map<String, String> effectiveAssumptions,
                               List<ScenarioSolution> composition,
                               Map<Long, Solution> solutions,
                               int horizonYears, List<String> inputWarnings) {
    }
}
