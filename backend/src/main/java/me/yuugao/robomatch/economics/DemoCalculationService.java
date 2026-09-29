package me.yuugao.robomatch.economics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import me.yuugao.robomatch.domain.ObjectType;
import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.domain.Vendor;
import me.yuugao.robomatch.domain.SolutionCharacteristic;
import me.yuugao.robomatch.domain.CharacteristicType;
import me.yuugao.robomatch.domain.ScenarioType;
import me.yuugao.robomatch.dto.ComparisonDto;
import me.yuugao.robomatch.dto.DemoCalculationDto;
import me.yuugao.robomatch.dto.DemoDescriptorDto;
import me.yuugao.robomatch.dto.InterpretationDto;
import me.yuugao.robomatch.dto.SensitivityRowDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.CharacteristicTypeRepository;
import me.yuugao.robomatch.repository.ObjectTypeRepository;
import me.yuugao.robomatch.repository.SolutionCharacteristicRepository;
import me.yuugao.robomatch.repository.SolutionRepository;
import me.yuugao.robomatch.repository.VendorRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Гостевой демо-расчёт (роль «гость», сценарий mvp_scope.md): сравнение
 * трёх сценариев на константах демо-набора «Склад» - КАК в проекте, но
 * полностью в памяти, БД читается только для каталога (состав, цены, ТТХ)
 * и НИЧЕГО не пишет (ни проектов, ни сценариев, ни расчётов).
 * <p>
 * Значения демо-набора - DemoConstants (единственный источник); допущения -
 * каталог допущений без переопределений (проекта нет); состав - решения
 * каталога по имени и вендору. Формулы - тот же EconomicModel, что и в
 * проектных расчётах (результаты идентичны по построению).
 * <p>
 * Методы только для чтения: транзакция readOnly - страховка от случайной
 * записи (JPA не будет флашить изменения).
 */
@Service
public class DemoCalculationService {

    private final ObjectTypeRepository objectTypeRepository;
    private final SolutionRepository solutionRepository;
    private final SolutionCharacteristicRepository characteristicRepository;
    private final CharacteristicTypeRepository characteristicTypeRepository;
    private final VendorRepository vendorRepository;
    private final AssumptionService assumptionService;
    private final SensitivityService sensitivityService;
    private final EconomicCalculationService calculationService;

    public DemoCalculationService(
            ObjectTypeRepository objectTypeRepository,
            SolutionRepository solutionRepository,
            SolutionCharacteristicRepository characteristicRepository,
            CharacteristicTypeRepository characteristicTypeRepository,
            VendorRepository vendorRepository,
            AssumptionService assumptionService,
            SensitivityService sensitivityService,
            EconomicCalculationService calculationService) {
        this.objectTypeRepository = objectTypeRepository;
        this.solutionRepository = solutionRepository;
        this.characteristicRepository = characteristicRepository;
        this.characteristicTypeRepository = characteristicTypeRepository;
        this.vendorRepository = vendorRepository;
        this.assumptionService = assumptionService;
        this.sensitivityService = sensitivityService;
        this.calculationService = calculationService;
    }

    /**
 * Описание демо-расчёта для страницы гостя: доступные типы объектов,
 * параметры демо-набора и состав.
 *
 * @return дескриптор демо-расчёта
 */
    @Transactional(readOnly = true)
    public DemoDescriptorDto descriptor() {
        List<DemoDescriptorDto.DemoObjectTypeDto> types = objectTypeRepository
                .findAll().stream()
                .sorted(Comparator.comparing(ObjectType::getId))
                .map(type -> new DemoDescriptorDto.DemoObjectTypeDto(
                        type.getCode(), type.getName(),
                        Boolean.TRUE.equals(type.getIsCalcEnabled())))
                .toList();
        List<DemoDescriptorDto.DemoParameterDto> parameters =
                new ArrayList<>();
        DemoConstants.DISPLAY.forEach((code, display) -> parameters.add(
                new DemoDescriptorDto.DemoParameterDto(display.title(),
                        DemoConstants.PARAMS.get(code), display.unit())));
        List<DemoDescriptorDto.DemoCompositionDto> composition =
                DemoConstants.COMPOSITION.stream().map(line ->
                        new DemoDescriptorDto.DemoCompositionDto(
                                line.solutionName(), line.vendorName(),
                                line.quantity()))
                        .toList();
        ObjectType warehouse = requireDemoObjectType(
                DemoConstants.OBJECT_TYPE_CODE);
        return new DemoDescriptorDto(warehouse.getName(), types, parameters,
                composition);
    }

    /**
 * Демо-расчёт в памяти: три сценария (base/purchase/raas) на константах
 * демо-набора, состав - из каталога (только чтение). Ничего не
 * сохраняется: ни проекта, ни сценария, ни расчёта.
 *
 * @param objectTypeCode код типа объекта (по умолчанию «Склад»)
 * @return сравнение сценариев демо-расчёта с признаком демо
 */
    @Transactional(readOnly = true)
    public DemoCalculationDto calculate(String objectTypeCode) {
        String code = objectTypeCode == null || objectTypeCode.isBlank()
                ? DemoConstants.OBJECT_TYPE_CODE : objectTypeCode.trim();
        ObjectType objectType = requireDemoObjectType(code);

        // --- допущения: каталог без переопределений + демо-константа ----
        Map<String, String> assumptionValues = new LinkedHashMap<>(
                assumptionService.catalogDefaultsSnapshot().values());
        assumptionValues.put("robot_nominal_productivity_per_hour",
                DemoConstants.ROBOT_NOMINAL_PRODUCTIVITY);
        AssumptionService.EffectiveAssumptions eff =
                new AssumptionService.EffectiveAssumptions(assumptionValues);

        // --- состав из каталога (цена и ТТХ - только чтение) ------------
        List<EconomicModel.SolutionLine> lines = demoLines();
        BigDecimal powerConsumption = powerConsumption(lines, eff,
                DemoConstants.COMPOSITION);

        // --- производные входы формул (как в проектном расчёте) ---------
        Map<String, String> params = DemoConstants.PARAMS;
        BigDecimal shifts = num(params.get("shifts_per_day"));
        BigDecimal shiftDuration = num(params.get("shift_duration"));
        BigDecimal workingDays = num(params.get("working_days_per_year"));
        BigDecimal peakFactor = num(params.get("peak_load_factor"));
        BigDecimal inbound = num(params.get("inbound_pallets_per_day"));
        BigDecimal outbound = num(params.get("outbound_pallets_per_day"));
        BigDecimal pickers = num(params.get("pickers_count"));
        BigDecimal forklifts = num(params.get("forklift_operators_count"));
        BigDecimal pickerSalary = num(params.get("picker_salary_gross"));
        BigDecimal forkliftSalary = num(params
                .get("forklift_operator_salary_gross"));
        BigDecimal payrollRate = num(params
                .get("payroll_insurance_contributions_rate"));

        BigDecimal dailyOps = inbound.add(outbound);
        BigDecimal hoursPerDay = shifts.multiply(shiftDuration);
        BigDecimal peakDemand = dailyOps.divide(hoursPerDay, 6,
                RoundingMode.HALF_UP).multiply(peakFactor);
        BigDecimal hoursYear = hoursPerDay.multiply(workingDays);
        BigDecimal operationsYear = dailyOps.multiply(workingDays);
        BigDecimal contourSalarySum = pickerSalary.multiply(pickers)
                .add(forkliftSalary.multiply(forklifts));
        BigDecimal fotBase = contourSalarySum
                .multiply(BigDecimal.valueOf(12)).multiply(payrollRate);
        BigDecimal staffCount = pickers.add(forklifts);
        BigDecimal salaryYear = staffCount.signum() > 0
                ? contourSalarySum.divide(staffCount, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(12))
                : BigDecimal.ZERO;

        EconomicModel.RaasTerms raas = new EconomicModel.RaasTerms(
                eff.raw("raas_payment_model"),
                eff.require("raas_rate_month_pct"),
                eff.require("raas_contract_years").intValue(),
                Boolean.parseBoolean(eff.raw("raas_buyout")),
                eff.numeric("raas_usage_rate_rub"));
        EconomicModel.LoanTerms loan = new EconomicModel.LoanTerms(
                eff.numeric("loan_amount_rub"),
                eff.numeric("loan_rate_pct"),
                eff.numeric("loan_term_years") == null ? null
                        : eff.numeric("loan_term_years").intValue());

        // --- расчёт трёх сценариев одной моделью ------------------------
        Map<EconomicModel.ScenarioKind, EconomicModel.EconomicsResult>
                results = new LinkedHashMap<>();
        Map<EconomicModel.ScenarioKind, EconomicModel.EconomicsInput> inputs
                = new LinkedHashMap<>();
        for (EconomicModel.ScenarioKind kind : List.of(
                EconomicModel.ScenarioKind.BASE,
                EconomicModel.ScenarioKind.PURCHASE,
                EconomicModel.ScenarioKind.RAAS)) {
            List<EconomicModel.SolutionLine> kindLines =
                    kind == EconomicModel.ScenarioKind.BASE
                            ? List.of() : lines;
            EconomicModel.EconomicsInput input =
                    new EconomicModel.EconomicsInput(kind, peakDemand,
                            hoursYear, operationsYear, fotBase, salaryYear,
                            payrollRate, DemoConstants.HORIZON_YEARS,
                            eff.require("k_load"), eff.require("k_reserve"),
                            eff.numeric("k_availability"),
                            eff.numeric("robot_nominal_productivity_per_hour"),
                            powerConsumption,
                            eff.require("tariff_rub_kwh"),
                            eff.require("reserve_rate"),
                            eff.require("ratio_infra"),
                            eff.require("c_infra_pct"),
                            eff.require("c_software_pct"),
                            eff.require("c_integration_pct"),
                            eff.require("c_commissioning_pct"),
                            eff.require("c_training_pct"),
                            eff.require("c_service_pct"),
                            eff.require("c_licenses_pct"),
                            eff.require("c_communication_rub_year"),
                            eff.require("c_consumables_pct"),
                            eff.require("c_repair_pct"),
                            eff.numeric("amortization_years"),
                            eff.require("equipment_cost_factor_pct"),
                            eff.numeric("other_annual_effect_rub"),
                            eff.numeric("exploitation_staff_count") == null
                                    ? 0
                                    : eff.numeric(
                                    "exploitation_staff_count").intValue(),
                            raas, loan, kindLines);
            inputs.put(kind, input);
            results.put(kind, EconomicModel.calculate(input));
        }

        // --- колонки сравнения (как в проектной таблице) ----------------
        String versionData = demoVersionData(assumptionValues, lines);
        List<ComparisonDto.ScenarioColumnDto> columns = new ArrayList<>();
        ComparisonDto.ScenarioColumnDto baseColumn = null;
        for (EconomicModel.ScenarioKind kind : List.of(
                EconomicModel.ScenarioKind.BASE,
                EconomicModel.ScenarioKind.PURCHASE,
                EconomicModel.ScenarioKind.RAAS)) {
            EconomicModel.EconomicsResult result = results.get(kind);
            ScenarioType type = scenarioTypeOf(kind);
            Integer requiredRobots = result.requiredRobots();
            boolean underpowered = result.underpowered();
            InterpretationDto paybackView =
                    calculationService.interpret(result.selectedRobots(),
                            result.paybackYears(), result.effectYear(),
                            result.capexTotal(), type);
            List<SensitivityRowDto> sensitivity =
                    kind == EconomicModel.ScenarioKind.BASE
                            ? List.of()
                            : sensitivityService.sensitivity(
                                    inputs.get(kind), result);
            ComparisonDto.ScenarioColumnDto column =
                    new ComparisonDto.ScenarioColumnDto(
                            null, null, type.name().toLowerCase(),
                            nameOf(kind), true, Instant.now(),
                            result.selectedRobots(), requiredRobots,
                            underpowered, result.overpowered(),
                            result.nInfra(), result.capexTotal(),
                            result.opexTotal(), result.opexDelta(),
                            result.deltaFot(), result.effectYear(),
                            paybackView,
                            underpowered ? null : result.roiPct(),
                            result.tcoRub(), null, null, null,
                            sensitivity, result.warnings(), versionData);
            if (kind == EconomicModel.ScenarioKind.BASE) {
                baseColumn = column;
            }
            columns.add(column);
        }
        // Δ к базовому (после сборки колонок - как в проектной таблице)
        List<ComparisonDto.ScenarioColumnDto> withDeltas =
                new ArrayList<>();
        for (ComparisonDto.ScenarioColumnDto column : columns) {
            if (baseColumn != null && !column.equals(baseColumn)) {
                withDeltas.add(new ComparisonDto.ScenarioColumnDto(
                        column.scenarioId(), column.calculationId(),
                        column.type(), column.name(), column.calculated(),
                        column.calculatedAt(), column.selectedRobots(),
                        column.requiredRobots(), column.underpowered(),
                        column.overpowered(), column.nInfra(),
                        column.capex(), column.opexYear(),
                        column.opexDelta(), column.deltaFot(),
                        column.effectYear(), column.payback(),
                        column.roiPct(), column.tco(),
                        delta(column.capex(), baseColumn.capex()),
                        delta(column.tco(), baseColumn.tco()),
                        delta(column.effectYear(),
                                baseColumn.effectYear()),
                        column.sensitivity(), column.warnings(),
                        column.versionData()));
            } else {
                withDeltas.add(column);
            }
        }
        ComparisonDto comparison = new ComparisonDto(withDeltas,
                DemoConstants.HORIZON_YEARS, EconomicModel.MODEL_VERSION);
        return new DemoCalculationDto(true, objectType.getName(),
                comparison);
    }

    // ==================================================================
    // Вспомогательные
    // ==================================================================

    private ObjectType requireDemoObjectType(String code) {
        ObjectType type = objectTypeRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException(
                        "Тип объекта не найден"));
        if (!Boolean.TRUE.equals(type.getIsCalcEnabled())) {
            throw new BadRequestException("Демо-расчёт для типа объекта «"
                    + type.getName() + "» пока не поддерживается.");
        }
        return type;
    }

    /**
 * Демо-состав в строках модели: решения каталога по имени и вендору,
 * цена и ТТХ - из каталога (только чтение).
 */
    private List<EconomicModel.SolutionLine> demoLines() {
        Map<Long, Solution> byId = solutionRepository.findAll().stream()
                .collect(Collectors.toMap(Solution::getId,
                        Function.identity(), (a, b) -> a));
        Map<Long, String> vendorNameById = vendorRepository.findAll().stream()
                .collect(Collectors.toMap(Vendor::getId, Vendor::getName,
                        (a, b) -> a));
        Map<String, Long> charTypeIds = characteristicTypeRepository
                .findAll().stream()
                .filter(t -> "lifecycle_years".equals(t.getCode())
                        || "charging_power_kw".equals(t.getCode()))
                .collect(Collectors.toMap(CharacteristicType::getCode,
                        CharacteristicType::getId));
        Long lifetimeTypeId = charTypeIds.get("lifecycle_years");
        Long chargingTypeId = charTypeIds.get("charging_power_kw");
        Map<Long, List<SolutionCharacteristic>> charsBySolution =
                characteristicRepository.findBySolutionIdIn(
                                List.copyOf(byId.keySet())).stream()
                        .collect(Collectors.groupingBy(
                                SolutionCharacteristic::getSolutionId));
        Map<Long, BigDecimal> lifetimeBySolution = new HashMap<>();
        Map<Long, BigDecimal> chargingBySolution = new HashMap<>();
        for (Map.Entry<Long, List<SolutionCharacteristic>> entry
                : charsBySolution.entrySet()) {
            for (SolutionCharacteristic characteristic : entry.getValue()) {
                if (characteristic.getValueNumeric() == null) {
                    continue;
                }
                if (lifetimeTypeId != null && lifetimeTypeId.equals(
                        characteristic.getCharacteristicTypeId())) {
                    lifetimeBySolution.put(entry.getKey(),
                            characteristic.getValueNumeric());
                } else if (chargingTypeId != null && chargingTypeId.equals(
                        characteristic.getCharacteristicTypeId())) {
                    chargingBySolution.put(entry.getKey(),
                            characteristic.getValueNumeric());
                }
            }
        }
        List<EconomicModel.SolutionLine> lines = new ArrayList<>();
        for (DemoConstants.DemoLine demoLine : DemoConstants.COMPOSITION) {
            Solution solution = byId.values().stream()
                    .filter(s -> demoLine.solutionName().equals(s.getName())
                            && demoLine.vendorName().equals(
                            vendorNameById.get(s.getVendorId())))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException(
                            "Демо-состав недоступен: решение «"
                                    + demoLine.solutionName()
                                    + "» не найдено в каталоге"));
            lines.add(new EconomicModel.SolutionLine(solution.getId(),
                    solution.getName(), demoLine.quantity(),
                    solution.getPriceRub() == null ? BigDecimal.ZERO
                            : solution.getPriceRub(),
                    lifetimeBySolution.get(solution.getId()),
                    chargingBySolution.get(solution.getId())));
        }
        return List.copyOf(lines);
    }

    /**
 * Потребляемая мощность роботов демо-состава: та же цепочка фолбэков,
 * что и в проектном расчёте (допущение → ТТХ зарядки из EAV).
 */
    private BigDecimal powerConsumption(
            List<EconomicModel.SolutionLine> lines,
            AssumptionService.EffectiveAssumptions eff,
            List<DemoConstants.DemoLine> composition) {
        BigDecimal explicit = eff.numeric("robot_power_consumption_kw");
        if (explicit != null) {
            return explicit;
        }
        int total = composition.stream()
                .mapToInt(DemoConstants.DemoLine::quantity).sum();
        if (total == 0) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        int covered = 0;
        Map<String, Integer> quantityByName = composition.stream()
                .collect(Collectors.toMap(
                        DemoConstants.DemoLine::solutionName,
                        DemoConstants.DemoLine::quantity, Integer::sum));
        for (EconomicModel.SolutionLine line : lines) {
            Integer quantity = quantityByName.get(line.name());
            if (quantity == null || line.chargingPowerKw() == null) {
                continue;
            }
            weighted = weighted.add(line.chargingPowerKw()
                    .multiply(BigDecimal.valueOf(quantity)));
            covered += quantity;
        }
        return covered > 0 ? weighted.divide(BigDecimal.valueOf(covered),
                6, RoundingMode.HALF_UP) : null;
    }

    /** Версия данных демо: SHA-256 канона входов (параметры + допущения +
 * состав с ценами и ТТХ) - как у проектных расчётов. */
    private String demoVersionData(Map<String, String> assumptionValues,
                                   List<EconomicModel.SolutionLine> lines) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(DemoConstants.PARAMS).forEach((code, value) ->
                canonical.append("p:").append(code).append('=')
                        .append(value).append(';'));
        new TreeMap<>(assumptionValues).forEach((code, value) ->
                canonical.append("a:").append(code).append('=')
                        .append(value).append(';'));
        lines.stream()
                .sorted(Comparator.comparing(
                        EconomicModel.SolutionLine::solutionId))
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

    private static ScenarioType scenarioTypeOf(
            EconomicModel.ScenarioKind kind) {
        return switch (kind) {
            case BASE -> ScenarioType.BASE;
            case PURCHASE -> ScenarioType.PURCHASE;
            case RAAS -> ScenarioType.RAAS;
        };
    }

    private static String nameOf(EconomicModel.ScenarioKind kind) {
        return switch (kind) {
            case BASE -> "Текущий процесс без роботизации";
            case PURCHASE -> "Покупка оборудования";
            case RAAS -> "Роботы как услуга";
        };
    }

    private static BigDecimal delta(BigDecimal value, BigDecimal base) {
        if (value == null || base == null) {
            return null;
        }
        return value.subtract(base);
    }

    private static BigDecimal num(String raw) {
        return raw == null ? null : new BigDecimal(raw.replace(',', '.'));
    }
}
