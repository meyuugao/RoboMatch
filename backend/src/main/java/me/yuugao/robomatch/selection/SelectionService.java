package me.yuugao.robomatch.selection;

import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.CriterionContributionDto;
import me.yuugao.robomatch.dto.ScenarioDto;
import me.yuugao.robomatch.dto.SelectionResultDto;
import me.yuugao.robomatch.dto.SelectionRunDto;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

/**
 * Модуль подбора решений (; алгоритм —
 * docs/selection_algorithm.md, требования — requirements/selection.md).
 * <p>
 * Порядок run(projectId) — строго по §9 документа:
 * <ol>
 * <li>getIndustries(objectTypeId) — отрасли объекта (object_type_industry,
 * assumptions.md §1);</li>
 * <li>getSolutionsByIndustries(industryIds) — пул применимых решений
 * (solution_application);</li>
 * <li>validateObligatorySpecs — сбор 8 обязательных ТТХ решения (EAV
 * solution_characteristic поверх зеркальных колонок solution,
 * assumptions.md §21) и эффективных значений параметров объекта
 * (строка проекта или дефолт-константа — worst-case, assumptions.md
 * §25–28);</li>
 * <li>classifySolutions — fit / needs_check / excluded (§5: критическое
 * ограничение — исключение; нехватка данных — «требует проверки»,
 * — это не исключение);</li>
 * <li>rankFitSolutions — веса и нормализация по §6 (склад/аэропорт/
 * медицина), Score и вклад критериев — объяснимость;</li>
 * <li>saveResults — UPSERT по UNIQUE (project_id, solution_id),
 * несостоявшиеся пары удаляются (data_model.md §10.7). Первый
 * запуск создаёт 3 сценария (base/purchase/raas, ScenarioService).</li>
 * </ol>
 * <p>
 * ИЗОЛЯЦИЯ: userId только из JWT; чужой проект = 404, не 403.
 * <p>
 * SCORE И ВКЛАД НЕ ХРАНЯТСЯ: в selection_result — status/reason/rank
 * (инварианты запуска); Score и вклад критериев — «снимки переменного
 * состава», пересчитываются на лету при чтении по текущему каталогу
 * (data_model.md §12). Параметры или каталог изменились — перезапустите
 * подбор (UI подсказывает).
 * <p>
 * ЕДИНИЦЫ (assumptions.md §3): ТТХ решений — кг/мм/кВт/дБА; параметры
 * объекта — м (потолки/проезды/проходы/коридоры). Единственная конвертация —
 * м → мм на границе сравнения габаритов (× 1000), зафиксирована в
 * selection_algorithm.md §4 (подтверждённое расхождение с «никакой
 * конвертации в момент сравнения»: параметры объекта в датасете хранятся
 * в метрах, ТТХ — в миллиметрах).
 */
@Service
@RequiredArgsConstructor
public class SelectionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SelectionService.class);
    /**
 * Маппинг «проверка → параметр объекта» по типам (selection_algorithm.md
 * §4.1). У аэропорта/медицины части проверок параметра нет (нагрузка
 * на пол, главные проезды, потолки) — по §5.2 это needs_check с
 * «параметр объекта не задан». Коды — фактические из code_map.py.
 */
    private static final Map<String, Map<Check, ParamRef>> CHECK_PARAMS = Map.of(
            "warehouse", Map.of(
                    Check.PAYLOAD, new ParamRef("pallet_unit_weight", false),
                    Check.MASS, new ParamRef("floor_load_max_kg", false),
                    Check.LENGTH, new ParamRef("rack_aisle_width", true),
                    Check.WIDTH, new ParamRef("main_aisle_width", true),
                    Check.HEIGHT, new ParamRef("storage_zone_ceiling_height", true),
                    Check.ACCURACY, new ParamRef("required_positioning_accuracy_mm", false),
                    Check.CHARGING, new ParamRef("available_power_capacity", false),
                    Check.NOISE, new ParamRef("max_allowed_noise_dba", false)),
            "airport", Map.of(
                    Check.PAYLOAD, new ParamRef("avg_baggage_weight", false),
                    Check.ACCURACY, new ParamRef("required_positioning_accuracy_mm", false),
                    Check.CHARGING, new ParamRef("charging_infrastructure_power", false),
                    Check.NOISE, new ParamRef("max_allowed_noise_dba", false)),
            "hospital", Map.of(
                    Check.PAYLOAD, new ParamRef("meal_trolley_weight_gross", false),
                    Check.LENGTH, new ParamRef("main_corridor_width", true),
                    Check.ACCURACY, new ParamRef("required_positioning_accuracy_mm", false),
                    Check.CHARGING, new ParamRef("charging_infrastructure_power", false),
                    Check.NOISE, new ParamRef("max_allowed_noise_dba", false)));
    /**
 * Веса по типам объектов (§6.1; сумма весов каждого набора = 1.00).
 */
    private static final Map<String, Map<Criterion, BigDecimal>> WEIGHTS = Map.of(
            "warehouse", Map.of(
                    Criterion.DIMENSIONS, bd("0.20"),
                    Criterion.PAYLOAD, bd("0.20"),
                    Criterion.ACCURACY, bd("0.10"),
                    Criterion.CHARGING, bd("0.10"),
                    Criterion.PRICE, bd("0.20"),
                    Criterion.TRL, bd("0.15"),
                    Criterion.CASES, bd("0.05")),
            "airport", Map.of(
                    Criterion.DIMENSIONS, bd("0.20"),
                    Criterion.PAYLOAD, bd("0.10"),
                    Criterion.ACCURACY, bd("0.10"),
                    Criterion.CHARGING, bd("0.15"),
                    Criterion.NOISE, bd("0.15"),
                    Criterion.PRICE, bd("0.15"),
                    Criterion.TRL, bd("0.10"),
                    Criterion.CASES, bd("0.05")),
            "hospital", Map.of(
                    Criterion.DIMENSIONS, bd("0.20"),
                    Criterion.PAYLOAD, bd("0.05"),
                    Criterion.ACCURACY, bd("0.15"),
                    Criterion.CHARGING, bd("0.20"),
                    Criterion.PRICE, bd("0.20"),
                    Criterion.TRL, bd("0.15"),
                    Criterion.CASES, bd("0.05")));
    private final ProjectRepository projectRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final ObjectTypeIndustryRepository objectTypeIndustryRepository;
    private final ObjectTypeParameterRepository objectTypeParameterRepository;
    private final ParameterTypeRepository parameterTypeRepository;
    private final ProjectParameterValueRepository valueRepository;
    private final SolutionRepository solutionRepository;
    private final me.yuugao.robomatch.repository.SolutionTypeRepository solutionTypeRepository;
    private final SolutionCharacteristicRepository solutionCharacteristicRepository;
    private final CharacteristicTypeRepository characteristicTypeRepository;
    private final SolutionCaseLinkRepository solutionCaseLinkRepository;
    private final VendorRepository vendorRepository;
    private final SelectionResultRepository selectionResultRepository;

    // ------------------------------------------------------------------
    // 8 обязательных проверок (selection_algorithm.md §4.1)
    // ------------------------------------------------------------------
    private final ScenarioRepository scenarioRepository;
    private final ScenarioService scenarioService;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
 * Порядок отображения: rank (fit) → статус → id.
 */
    private static Comparator<Evaluated> displayOrder() {
        return Comparator
                .comparing((Evaluated e) -> e.rank() == null
                        ? Integer.MAX_VALUE : e.rank())
                .thenComparing(Evaluated::status)
                .thenComparing(e -> e.solution().getId());
    }

    // ------------------------------------------------------------------
    // Критерии ранжирования (selection_algorithm.md §6.1)
    // ------------------------------------------------------------------

    private static boolean lt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) < 0;
    }

    private static boolean gt(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
 * Числовое значение ТТХ из specs (EAV поверх колонок).
 */
    private static BigDecimal specValue(Map<Check, Spec> specs, Check check) {
        Spec spec = specs.get(check);
        return spec == null ? null : spec.value();
    }

    /**
 * Запуск подбора: конвейер + bootstrap сценариев + UPSERT результатов.
 * Одна транзакция; гонка двух параллельных запусков (UNIQUE
 * project_id+solution_id, project_id+name сценариев) — 409
 * «повторите»: повтор идемпотентен (тот же результат).
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return итог подбора (решения, статусы, ранги)
 */
    @Transactional
    public SelectionRunDto run(Long userId, Long projectId) {
        Project project = requireOwnedProject(userId, projectId);
        List<Evaluated> evaluated = evaluate(project);
        try {
            // bootstrap ВНУТРИ ловушки: гонка двух первых запусков ломается
            // на UNIQUE(project_id, name) сценариев так же, как UPSERT
            // результатов на UNIQUE(project_id, solution_id) — оба случая
            // должны отвечать 409 «повторите», а не 500
            scenarioService.ensureDefaultScenarios(projectId);
            saveResults(projectId, evaluated);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Подбор {}: нарушение целостности при записи "
                            + "(параллельный запуск или удаление проекта): {}",
                    projectId, ex.toString());
            throw new ConflictException("Параллельный запуск подбора этого "
                    + "проекта. Повторите — результаты идемпотентны.");
        }
        return buildResponse(projectId, evaluated);
    }

    /**
 * Результаты подбора: status/reason/rank — зафиксированы запуском;
 * score/вклад/недостающие данные — пересчёт по текущим данным
 * (data_model.md §12 «вычисление на лету»). Не запускался — пусто.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return сохранённые результаты подбора с актуальными оценками
 */
    @Transactional(readOnly = true)
    public SelectionRunDto getResults(Long userId, Long projectId) {
        Project project = requireOwnedProject(userId, projectId);
        List<SelectionResult> stored =
                selectionResultRepository.findAllByProjectId(projectId);
        if (stored.isEmpty()) {
            return buildResponse(projectId, List.of());
        }
        List<Evaluated> fresh = evaluate(project);
        Map<Long, Evaluated> freshBySolution = fresh.stream().collect(
                Collectors.toMap(e -> e.solution().getId(), Function.identity()));
        List<Evaluated> merged = new ArrayList<>();
        for (SelectionResult row : stored) {
            Evaluated current = freshBySolution.get(row.getSolutionId());
            if (current == null) {
                // решение покинуло пул применимости — показываем
                // зафиксированный статус (до перезапуска подбора)
                merged.add(new Evaluated(
                        Solution.builder().id(row.getSolutionId()).build(), null,
                        row.getStatus(), row.getReason(), List.of(), false,
                        row.getRank(), null, null));
                continue;
            }
            merged.add(new Evaluated(current.solution(), current.vendorName(),
                    row.getStatus(), row.getReason(), current.missing(),
                    current.hasCases(), row.getRank(), current.score(),
                    current.contributions()));
        }
        merged.sort(displayOrder());
        return buildResponse(projectId, merged);
    }

    /**
 * Полный расчёт без записи в БД (run и GET делят одну логику).
 */
    private List<Evaluated> evaluate(Project project) {
        ObjectType objectType = objectTypeRepository.findById(project.getObjectTypeId())
                .orElseThrow(() -> new NotFoundException("Тип объекта не найден"));
        Map<Check, EffectiveParam> constraints = loadConstraints(project, objectType);
        List<SolutionData> pool = loadPool(project);
        Map<Long, String> vendorNames = resolveVendorNames(pool);

        List<Evaluated> evaluated = new ArrayList<>();
        for (SolutionData data : pool) {
            evaluated.add(classify(data, constraints,
                    vendorNames.get(data.solution().getVendorId())));
        }
        Map<Long, Ranked> ranked = rankFit(evaluated, objectType.getCode());
        List<Evaluated> result = new ArrayList<>();
        for (Evaluated e : evaluated) {
            Ranked r = ranked.get(e.solution().getId());
            result.add(r == null ? e
                    : new Evaluated(e.solution(), e.vendorName(), e.status(),
                    e.reason(), e.missing(), e.hasCases(),
                    r.rank(), r.score(), r.contributions()));
        }
        result.sort(displayOrder());
        return result;
    }

    // ------------------------------------------------------------------
    // Публичный API
    // ------------------------------------------------------------------

    /**
 * Шаг 3 (объект): эффективные значения параметров-ограничений.
 */
    private Map<Check, EffectiveParam> loadConstraints(Project project,
                                                       ObjectType objectType) {
        Map<Check, ParamRef> codeMap = CHECK_PARAMS.get(objectType.getCode());
        if (codeMap == null) {
            throw new NotFoundException("Для типа объекта «" + objectType.getName()
                    + "» подбор не настроен");
        }
        List<ObjectTypeParameter> definitions = objectTypeParameterRepository
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(objectType.getId());
        Map<Long, ParameterType> types = parameterTypeRepository
                .findAllById(definitions.stream()
                        .map(ObjectTypeParameter::getParameterTypeId).distinct()
                        .toList()).stream().collect(Collectors.toMap(
                        ParameterType::getId, Function.identity()));
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
                .findAllByProjectId(project.getId()).stream().collect(
                        Collectors.toMap(ProjectParameterValue::getObjectTypeParameterId,
                                Function.identity()));
        Map<Check, EffectiveParam> constraints = new EnumMap<>(Check.class);
        for (Map.Entry<Check, ParamRef> entry : new TreeMap<>(codeMap).entrySet()) {
            Check check = entry.getKey();
            ParamRef ref = entry.getValue();
            ObjectTypeParameter definition = definitionByCode.get(ref.code());
            if (definition == null) {
                continue; // параметр не заведён для типа — «не задан» (§5.2)
            }
            ProjectParameterValue row = values.get(definition.getId());
            BigDecimal effective = row != null && row.getValueNumeric() != null
                    ? row.getValueNumeric()
                    : definition.getDefaultValueNumeric();
            if (effective == null) {
                constraints.put(check, new EffectiveParam(nameByCode.get(ref.code()),
                        null));
                continue;
            }
            // единственная конвертация: метры объекта -> миллиметры ТТХ (§4)
            BigDecimal comparable = ref.meters()
                    ? effective.multiply(BigDecimal.valueOf(1000))
                    : effective;
            constraints.put(check, new EffectiveParam(nameByCode.get(ref.code()),
                    comparable));
        }
        return constraints;
    }

    /**
 * Шаги 1–2 + данные ТТХ: отрасли → пул решений со спеками и кейсами.
 */
    private List<SolutionData> loadPool(Project project) {
        List<ObjectTypeIndustry> links = objectTypeIndustryRepository
                .findByObjectTypeId(project.getObjectTypeId());
        List<Long> industryIds = links.stream()
                .map(ObjectTypeIndustry::getIndustryId).distinct().toList();
        List<Solution> solutions = industryIds.isEmpty()
                ? List.of()
                : solutionRepository.findAllByIndustryIds(industryIds);
        if (solutions.isEmpty()) {
            return List.of();
        }
        List<Long> solutionIds = solutions.stream().map(Solution::getId).toList();

        Map<Long, String> codeByTypeId = new HashMap<>();
        characteristicTypeRepository.findAll()
                .forEach(t -> codeByTypeId.put(t.getId(), t.getCode()));
        Map<Long, Map<String, SolutionCharacteristic>> eavBySolution = new HashMap<>();
        for (SolutionCharacteristic row : solutionCharacteristicRepository
                .findBySolutionIdIn(solutionIds)) {
            String code = codeByTypeId.get(row.getCharacteristicTypeId());
            if (code != null) {
                eavBySolution.computeIfAbsent(row.getSolutionId(),
                        k -> new HashMap<>()).put(code, row);
            }
        }
        Set<Long> withCases = solutionCaseLinkRepository.findBySolutionIdIn(solutionIds)
                .stream().map(SolutionCaseLink::getSolutionId)
                .collect(Collectors.toSet());

        List<SolutionData> pool = new ArrayList<>();
        for (Solution solution : solutions) {
            Map<Check, Spec> specs = new EnumMap<>(Check.class);
            Map<String, SolutionCharacteristic> eav =
                    eavBySolution.getOrDefault(solution.getId(), Map.of());
            for (Check check : Check.values()) {
                specs.put(check, specOf(solution, eav.get(check.specCode), check));
            }
            pool.add(new SolutionData(solution, specs,
                    withCases.contains(solution.getId())));
        }
        return pool;
    }

    // ------------------------------------------------------------------
    // Конвейер: шаги 1–5 (§2–§6 алгоритма)
    // ------------------------------------------------------------------

    /**
 * ТТХ проверки: EAV-значение с провенансом или зеркальная колонка.
 */
    private Spec specOf(Solution solution, SolutionCharacteristic eav, Check check) {
        if (eav != null) {
            return new Spec(eav.getValueNumeric(), eav.getIsConfirmed(),
                    eav.getSourceUrl(), eav.getSourceKind());
        }
        return new Spec(switch (check) {
            case PAYLOAD -> solution.getPayloadKg();
            case MASS -> solution.getMassKg();
            case LENGTH -> solution.getLengthMm();
            case WIDTH -> solution.getWidthMm();
            case HEIGHT -> solution.getHeightMm();
            case ACCURACY -> solution.getPositioningAccuracyMm();
            case CHARGING -> solution.getChargingPowerKw();
            case NOISE -> solution.getNoiseLevelDba();
        }, null, null, null);
    }

    private Map<Long, String> resolveVendorNames(List<SolutionData> pool) {
        if (pool.isEmpty()) {
            return Map.of();
        }
        return vendorRepository.findAllById(pool.stream()
                        .map(d -> d.solution().getVendorId()).distinct().toList())
                .stream().collect(Collectors.toMap(v -> v.getId(),
                        v -> v.getName() == null ? "" : v.getName()));
    }

    /**
 * Статус + причина + недостающие данные одного решения (§5).
 */
    private Evaluated classify(SolutionData data, Map<Check, EffectiveParam> constraints,
                               String vendorName) {
        List<String> violations = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Check check : Check.values()) {
            Spec spec = data.specs().get(check);
            EffectiveParam constraint = constraints.get(check);
            if (spec == null || spec.value() == null) {
                missing.add("ТТХ решения: " + check.title + " (нет данных)");
                continue;
            }
            // провенанс EAV (assumptions.md §7): не подтверждено / нет источника
            if (Boolean.FALSE.equals(spec.confirmed())) {
                missing.add("ТТХ решения: " + check.title
                        + " (данные не подтверждены независимым источником)");
                continue;
            }
            // §5.2 буквально: «ТТХ из открытого источника без source_url» —
            // провенанс неполный (source_kind не читался раньше)
            if ("open_source".equals(spec.sourceKind())
                    && (spec.sourceUrl() == null || spec.sourceUrl().isBlank())) {
                missing.add("ТТХ решения: " + check.title
                        + " (нет ссылки на источник)");
                continue;
            }
            if (constraint == null || constraint.value() == null) {
                missing.add("Параметр объекта: "
                        + (constraint == null || constraint.name() == null
                        ? check.title + " объекта" : constraint.name())
                        + " (не задан)");
                continue;
            }
            String violation = switch (check) {
                case PAYLOAD -> lt(spec.value(), constraint.value())
                        ? "грузоподъёмность " + plain(spec.value())
                          + " кг меньше максимальной массы грузовой "
                          + "единицы " + plain(constraint.value()) + " кг"
                        : null;
                case MASS -> gt(spec.value(), constraint.value())
                        ? "масса робота " + plain(spec.value())
                          + " кг превышает допустимую нагрузку на пол "
                          + plain(constraint.value()) + " кг"
                        : null;
                case LENGTH -> !lt(spec.value(), constraint.value())
                        ? "длина " + plain(spec.value())
                          + " мм не меньше ширины рабочих проходов "
                          + plain(constraint.value()) + " мм"
                        : null;
                case WIDTH -> !lt(spec.value(), constraint.value())
                        ? "ширина " + plain(spec.value())
                          + " мм не меньше ширины главных проездов "
                          + plain(constraint.value()) + " мм"
                        : null;
                case HEIGHT -> !lt(spec.value(), constraint.value())
                        ? "высота " + plain(spec.value())
                          + " мм не меньше высоты потолков "
                          + plain(constraint.value()) + " мм"
                        : null;
                case ACCURACY -> gt(spec.value(), constraint.value())
                        ? "точность позиционирования " + plain(spec.value())
                          + " мм хуже требуемой "
                          + plain(constraint.value()) + " мм"
                        : null;
                case CHARGING -> gt(spec.value(), constraint.value())
                        ? "мощность зарядки " + plain(spec.value())
                          + " кВт превышает доступную "
                          + plain(constraint.value()) + " кВт"
                        : null;
                case NOISE -> gt(spec.value(), constraint.value())
                        ? "уровень шума " + plain(spec.value())
                          + " дБА превышает допустимый "
                          + plain(constraint.value()) + " дБА"
                        : null;
            };
            if (violation != null) {
                violations.add(violation);
            }
        }
        // приоритет §5.2: сначала excluded (критические), потом needs_check
        if (!violations.isEmpty()) {
            return new Evaluated(data.solution(), vendorName,
                    SelectionStatus.EXCLUDED,
                    "Исключено: " + String.join("; ", violations), missing,
                    data.hasCases(), data.specs(), null, null, null);
        }
        if (!missing.isEmpty()) {
            return new Evaluated(data.solution(), vendorName,
                    SelectionStatus.NEEDS_CHECK,
                    "Требует проверки: " + String.join("; ", missing), missing,
                    data.hasCases(), data.specs(), null, null, null);
        }
        return new Evaluated(data.solution(), vendorName, SelectionStatus.FIT,
                "Все обязательные ТТХ пройдены (8 из 8)", List.of(),
                data.hasCases(), data.specs(), null, null, null);
    }

    /**
 * Score, вклад критериев и ранги fit-решений (§6.2).
 */
    private Map<Long, Ranked> rankFit(List<Evaluated> evaluated, String objectTypeCode) {
        Map<Criterion, BigDecimal> weights = WEIGHTS.get(objectTypeCode);
        if (weights == null) {
            return Map.of();
        }
        List<Evaluated> fit = evaluated.stream()
                .filter(e -> e.status() == SelectionStatus.FIT).toList();
        if (fit.isEmpty()) {
            return Map.of();
        }
        // значения критериев (габариты — max из трёх измерений; NULL-критерий
        // «не оценён», §6.2)
        Map<Evaluated, Map<Criterion, BigDecimal>> values = new LinkedHashMap<>();
        for (Evaluated e : fit) {
            values.put(e, criterionValues(e));
        }
        // критерии, не оценённые НИ У ОДНОГО fit-решения, исключаются из
        // формулы; веса остальных нормализуются к сумме 1.0 (§6.2)
        Map<Criterion, BigDecimal> effectiveWeights = new LinkedHashMap<>(weights);
        for (Criterion criterion : weights.keySet()) {
            boolean anyValue = values.values().stream()
                    .anyMatch(v -> v.get(criterion) != null);
            if (!anyValue) {
                effectiveWeights.remove(criterion);
            }
        }
        // перенормировка весов к сумме 1.0 (§6.2): w_new = w / Σ w_present
        BigDecimal presentWeightSum = effectiveWeights.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Criterion, BigDecimal> rawWeights = new EnumMap<>(Criterion.class);
        Map<Criterion, BigDecimal> displayWeights = new EnumMap<>(Criterion.class);
        for (Map.Entry<Criterion, BigDecimal> entry : weights.entrySet()) {
            BigDecimal effective = effectiveWeights.get(entry.getKey());
            if (effective == null) {
                rawWeights.put(entry.getKey(), BigDecimal.ZERO);
                displayWeights.put(entry.getKey(), BigDecimal.ZERO);
                continue;
            }
            BigDecimal raw = presentWeightSum.signum() == 0 ? effective
                    : effective.divide(presentWeightSum, 10, RoundingMode.HALF_UP);
            rawWeights.put(entry.getKey(), raw);
            displayWeights.put(entry.getKey(), raw.setScale(4, RoundingMode.HALF_UP));
        }
        Map<Criterion, BigDecimal> maxBy = new EnumMap<>(Criterion.class);
        Map<Criterion, BigDecimal> minBy = new EnumMap<>(Criterion.class);
        for (Criterion criterion : effectiveWeights.keySet()) {
            List<BigDecimal> present = values.values().stream()
                    .map(v -> v.get(criterion)).filter(Objects::nonNull).toList();
            maxBy.put(criterion, present.stream().max(BigDecimal::compareTo)
                    .orElse(null));
            minBy.put(criterion, present.stream().min(BigDecimal::compareTo)
                    .orElse(null));
        }
        Map<Evaluated, BigDecimal> rawScores = new LinkedHashMap<>();
        Map<Evaluated, List<CriterionContributionDto>> contributions =
                new LinkedHashMap<>();
        // порядок критериев — по enum (стабилен между запусками и средами;
        // итерация по Map.of не гарантирует порядок)
        Criterion[] criteriaOrder = Criterion.values();
        for (Evaluated e : fit) {
            Map<Criterion, BigDecimal> v = values.get(e);
            // §6.2: «промежуточные вычисления — без округления, округление
            // только на выводе»: Score суммируется по
            // неокруглённым вкладам, сортировка/ранги — по нему; отображаемые
            // значения округляются только в DTO
            BigDecimal score = BigDecimal.ZERO;
            List<CriterionContributionDto> list = new ArrayList<>();
            for (Criterion criterion : criteriaOrder) {
                if (!weights.containsKey(criterion)) {
                    continue; // критерий не входит в набор типа объекта
                }
                BigDecimal displayWeight = displayWeights.get(criterion);
                BigDecimal value = v.get(criterion);
                BigDecimal normalized = normalize(criterion, value, maxBy, minBy);
                // вклад считается по ПЕРЕНОРМИРОВАННОМУ весу (§6.2:
                // w_new = w / Σ w_present) — сумма вкладов равна Score,
                // а веса в таблице сходятся к 1.0
                BigDecimal contribution = normalized != null
                        ? rawWeights.get(criterion).multiply(normalized)
                        : BigDecimal.ZERO;
                score = score.add(contribution);
                list.add(new CriterionContributionDto(criterion.code, criterion.title,
                        displayWeight,
                        normalized == null ? null
                                : normalized.setScale(4, RoundingMode.HALF_UP),
                        contribution.setScale(3, RoundingMode.HALF_UP),
                        normalized != null));
            }
            rawScores.put(e, score);
            contributions.put(e, list);
        }
        // сортировка: Score по убыванию (без округления), тайбрейк — id
        // по возрастанию (детерминированность повторных запусков);
        // rank = позиция с 1 (§6.2)
        List<Evaluated> order = new ArrayList<>(fit);
        order.sort(Comparator.comparing((Evaluated e) -> rawScores.get(e)).reversed()
                .thenComparing(e -> e.solution().getId()));
        Map<Long, Ranked> ranked = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            Evaluated e = order.get(i);
            ranked.put(e.solution().getId(),
                    new Ranked(i + 1,
                            rawScores.get(e).setScale(2, RoundingMode.HALF_UP),
                            contributions.get(e)));
        }
        return ranked;
    }

    /**
 * Нормализация [0..1] по §6.2: value/max, min/value, бинарный кейс.
 */
    private BigDecimal normalize(Criterion criterion, BigDecimal value,
                                 Map<Criterion, BigDecimal> maxBy,
                                 Map<Criterion, BigDecimal> minBy) {
        if (value == null) {
            return null;
        }
        if (criterion == Criterion.CASES) {
            return value.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (Boolean.TRUE.equals(criterion.higherIsBetter)) {
            BigDecimal max = maxBy.get(criterion);
            return max != null && max.signum() > 0
                    ? value.divide(max, 6, RoundingMode.HALF_UP) : BigDecimal.ONE;
        }
        BigDecimal min = minBy.get(criterion);
        if (min == null) {
            return null;
        }
        if (value.signum() == 0) {
            return BigDecimal.ONE; // ноль «меньше — лучше» — лучший возможный
        }
        return value.signum() > 0
                ? min.divide(value, 6, RoundingMode.HALF_UP) : null;
    }

    /**
 * Значения критериев одного решения (§6.2): ТТХ — из specs (EAV поверх
 * зеркальных колонок — та же точка правды, что и классификация);
 * цена/УГТ/кейсы — только из колонок solution (провенанса EAV для них нет).
 */
    private Map<Criterion, BigDecimal> criterionValues(Evaluated e) {
        Solution s = e.solution();
        Map<Check, Spec> specs = e.specs();
        Map<Criterion, BigDecimal> v = new EnumMap<>(Criterion.class);
        v.put(Criterion.DIMENSIONS, maxOf(specValue(specs, Check.LENGTH),
                specValue(specs, Check.WIDTH), specValue(specs, Check.HEIGHT)));
        v.put(Criterion.PAYLOAD, specValue(specs, Check.PAYLOAD));
        v.put(Criterion.ACCURACY, specValue(specs, Check.ACCURACY));
        v.put(Criterion.CHARGING, specValue(specs, Check.CHARGING));
        v.put(Criterion.NOISE, specValue(specs, Check.NOISE));
        v.put(Criterion.PRICE, s.getPriceRub());
        v.put(Criterion.TRL, s.getTrl() == null ? null
                : BigDecimal.valueOf(s.getTrl()));
        v.put(Criterion.CASES, e.hasCases() ? BigDecimal.ONE : BigDecimal.ZERO);
        return v;
    }

    // ------------------------------------------------------------------
    // Шаг 4: классификация (§5)
    // ------------------------------------------------------------------

    /**
 * Габариты — единый скаляр max(Д, Ш, В); NULL, если хоть одно не задано.
 */
    private BigDecimal maxOf(BigDecimal... values) {
        BigDecimal max = null;
        for (BigDecimal value : values) {
            if (value == null) {
                return null;
            }
            max = max == null || value.compareTo(max) > 0 ? value : max;
        }
        return max;
    }

    /**
 * UPSERT по UNIQUE (project_id, solution_id) + удаление несостоявшихся.
 */
    private void saveResults(Long projectId, List<Evaluated> evaluated) {
        Map<Long, SelectionResult> existing = selectionResultRepository
                .findAllByProjectId(projectId).stream().collect(
                        Collectors.toMap(SelectionResult::getSolutionId,
                                Function.identity()));
        List<SelectionResult> toSave = new ArrayList<>();
        for (Evaluated e : evaluated) {
            SelectionResult row = existing.remove(e.solution().getId());
            if (row == null) {
                row = SelectionResult.builder()
                        .projectId(projectId)
                        .solutionId(e.solution().getId())
                        .build();
            }
            row.setStatus(e.status());
            row.setReason(e.reason());
            row.setRank(e.rank());
            toSave.add(row);
        }
        if (!toSave.isEmpty()) {
            selectionResultRepository.saveAll(toSave);
        }
        // пары, не вошедшие в новый запуск (сменилась применимость) — удалить
        if (evaluated.isEmpty()) {
            selectionResultRepository.deleteAllByProjectId(projectId);
        } else if (!existing.isEmpty()) {
            selectionResultRepository.deleteAllById(existing.values().stream()
                    .map(SelectionResult::getId).toList());
        }
    }

    /**
 * Ответ: сценарии проекта + результаты (одна форма для run и GET).
 */
    private SelectionRunDto buildResponse(Long projectId,
                                          List<Evaluated> evaluated) {
        List<ScenarioDto> scenarios = scenarioRepository
                .findAllByProjectIdOrderByIdAsc(projectId).stream()
                .map(s -> new ScenarioDto(s.getId(),
                        s.getType().name().toLowerCase(), s.getName()))
                .toList();
        // имена типов решений — батч
        Map<Long, String> solutionTypeNames = solutionTypeRepository
                .findAllById(evaluated.stream()
                        .map(e -> e.solution().getSolutionTypeId())
                        .filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(t -> t.getId(),
                        t -> t.getName() == null ? "" : t.getName()));
        List<SelectionResultDto> results = evaluated.stream().map(e ->
                        new SelectionResultDto(e.solution().getId(),
                                // fallback: решение могло покинуть каталог между
                                // запусками (merge-путь GET) — null-имя не рвёт UI
                                // и сортировку
                                e.solution().getName() == null
                                        ? "Решение #" + e.solution().getId()
                                        : e.solution().getName(),
                                e.vendorName(),
                                solutionTypeNames.get(e.solution().getSolutionTypeId()),
                                e.status().name().toLowerCase(), e.reason(), e.rank(),
                                // Score — только fit (схема DTO)
                                e.status() == SelectionStatus.FIT ? e.score() : null,
                                e.status() == SelectionStatus.FIT && e.contributions() != null
                                        ? e.contributions() : null,
                                // недостающие данные — для needs_check И excluded
                                //
                                e.status() != SelectionStatus.FIT && e.missing() != null
                                        && !e.missing().isEmpty() ? e.missing() : null))
                .toList();
        return new SelectionRunDto(scenarios, results);
    }

    /**
 * Проект пользователя или 404 (изоляция: чужой = несуществующий).
 */
    private Project requireOwnedProject(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new NotFoundException("Проект не найден");
        }
        return project;
    }

    // ------------------------------------------------------------------
    // Шаг 5: ранжирование fit-решений (§6)
    // ------------------------------------------------------------------

    /**
 * Проверка ТТХ: код ТТХ решения, человекочитаемое название, единица.
 */
    private enum Check {
        PAYLOAD("payload_kg", "грузоподъёмность", "кг"),
        MASS("mass_kg", "масса робота", "кг"),
        LENGTH("length_mm", "длина", "мм"),
        WIDTH("width_mm", "ширина", "мм"),
        HEIGHT("height_mm", "высота", "мм"),
        ACCURACY("positioning_accuracy_mm", "точность позиционирования", "мм"),
        CHARGING("charging_power_kw", "мощность зарядки", "кВт"),
        NOISE("noise_level_dba", "уровень шума", "дБА");

        final String specCode;
        final String title;
        final String unit;

        Check(String specCode, String title, String unit) {
            this.specCode = specCode;
            this.title = title;
            this.unit = unit;
        }
    }

    /**
 * Критерий ранжирования: направление нормализации (null — бинарный).
 */
    private enum Criterion {
        DIMENSIONS("dimensions", "Габариты", false),
        PAYLOAD("payload", "Грузоподъёмность", true),
        ACCURACY("accuracy", "Точность", false),
        CHARGING("charging", "Инфраструктура (зарядка)", false),
        NOISE("noise", "Шум", false),
        PRICE("price", "Цена", false),
        TRL("trl", "УГТ (TRL)", true),
        CASES("cases", "Кейсы", null);

        final String code;
        final String title;
        final Boolean higherIsBetter;

        Criterion(String code, String title, Boolean higherIsBetter) {
            this.code = code;
            this.title = title;
            this.higherIsBetter = higherIsBetter;
        }
    }

    /**
 * Ссылка на параметр объекта: код + признак «значение в метрах».
 */
    private record ParamRef(String code, boolean meters) {
    }

    /**
 * Параметр объекта-ограничение: имя для сообщений + значение в единице ТТХ.
 */
    private record EffectiveParam(String name, BigDecimal value) {
    }

    /**
 * ТТХ решения с провенансом (EAV-строка или зеркальная колонка).
 */
    private record Spec(BigDecimal value, Boolean confirmed, String sourceUrl,
                        String sourceKind) {
    }

    // ------------------------------------------------------------------
    // Шаг 6: сохранение (§10.7) и сборка ответа
    // ------------------------------------------------------------------

    /**
 * Полный набор данных одного решения: решение, 8 ТТХ, кейсы.
 */
    private record SolutionData(Solution solution, Map<Check, Spec> specs,
                                boolean hasCases) {
    }

    /**
 * Итог подбора одного решения (до ранжирования rank/score = null).
 * specs — 8 ТТХ с провенансом: ранжирование берёт значения из них
 * (EAV поверх зеркальных колонок, §6.2 «наравне с колонками»),
 * а не из колонок повторно.
 */
    private record Evaluated(Solution solution, String vendorName,
                             SelectionStatus status, String reason,
                             List<String> missing, boolean hasCases,
                             Map<Check, Spec> specs,
                             Integer rank, BigDecimal score,
                             List<CriterionContributionDto> contributions) {

        Evaluated(Solution solution, String vendorName, SelectionStatus status,
                  String reason, List<String> missing, boolean hasCases,
                  Integer rank, BigDecimal score,
                  List<CriterionContributionDto> contributions) {
            this(solution, vendorName, status, reason, missing, hasCases,
                    Map.of(), rank, score, contributions);
        }
    }

    /**
 * Результат ранжирования одного fit-решения.
 */
    private record Ranked(int rank, BigDecimal score,
                          List<CriterionContributionDto> contributions) {
    }
}
