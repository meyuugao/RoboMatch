package me.yuugao.robomatch.economics;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.CalculationFullDto;
import me.yuugao.robomatch.dto.ComparisonDto;
import me.yuugao.robomatch.dto.ManualAdjustRequest;
import me.yuugao.robomatch.repository.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

/**
 * Сервисные тесты ручной корректировки: пересчёт
 * зависимых метрик при корректировке total_capex / effect_year
 * (payback_years = CAPEX/Effect, roi_pct = Effect×Горизонт/CAPEX×100,
 * горизонт - из metrics_json.horizonYears, округление §4 до десятых);
 * корректировка payback_years или roi_pct зависимых НЕ пересчитывает -
 * в adjustedFrom попадает пометка «зависимые метрики не пересчитаны».
 *
 * <p>Фикстура - «Максимум - 3 решения» (тот же контур, что
 * CalculationControllerIT): 3 робота по 2.5М, зарядка 1.35 кВт, срок
 * службы 6 лет; CAPEX = 12 416 250, Effect = 199 169 474,
 * Payback = 0.1, ROI(5 лет) = 8020.5%.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:adjit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomicCalculationServiceTest {

    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ObjectTypeRepository objectTypeRepository;
    @Autowired
    private ObjectTypeParameterRepository objectTypeParameterRepository;
    @Autowired
    private ParameterTypeRepository parameterTypeRepository;
    @Autowired
    private VendorRepository vendorRepository;
    @Autowired
    private SolutionRepository solutionRepository;
    @Autowired
    private CharacteristicTypeRepository characteristicTypeRepository;
    @Autowired
    private SolutionCharacteristicRepository solutionCharacteristicRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectAssumptionRepository projectAssumptionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ScenarioSolutionRepository scenarioSolutionRepository;
    @Autowired
    private EconomicCalculationService service;

    private Long userId;
    private Long projectId;
    private Long purchaseId;
    private Long warehouseTypeId;
    private Long robotId;

    @BeforeAll
    void seedFixture() {
        transactionTemplate.executeWithoutResult(tx -> {
            User user = userRepository.save(User.builder()
                    .login("adjust_user").passwordHash("x")
                    .role(UserRole.USER).build());
            userId = user.getId();
            ObjectType warehouse = objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse").name("Склад").isCalcEnabled(true)
                    .dataSourceNote("Тест").build());
            warehouseTypeId = warehouse.getId();
            projectId = projectRepository.save(Project.builder()
                            .userId(userId).objectTypeId(warehouse.getId())
                            .name("Adjust").status(ProjectStatus.ACTIVE).build())
                    .getId();

            String[][] params = {
                    {"total_warehouse_area", "20000"},
                    {"shifts_per_day", "2"},
                    {"working_days_per_year", "365"},
                    {"shift_duration", "11"},
                    {"peak_load_factor", "1.5"},
                    {"inbound_pallets_per_day", "1000"},
                    {"outbound_pallets_per_day", "1000"},
                    {"picking_lines_per_day", "100000"},
                    {"pallet_positions", "20000"},
                    {"active_sku_count", "2000"},
                    {"pallet_unit_weight", "800"},
                    {"total_warehouse_staff", "180"},
                    {"pickers_count", "100"},
                    {"forklift_operators_count", "25"},
                    {"picker_throughput_lines_per_hour", "150"},
                    {"picker_salary_gross", "100000"},
                    {"forklift_operator_salary_gross", "120000"},
                    {"payroll_insurance_contributions_rate", "1.302"},
                    {"floor_flatness_deviation", "3"},
                    {"main_aisle_width", "3.5"},
                    {"rack_aisle_width", "2.8"},
                    {"storage_zone_ceiling_height", "10"},
                    {"payback_horizon", "5"},
            };
            for (String[] p : params) {
                otp(warehouse.getId(), p[0], p[1]);
            }
            otpText(warehouse.getId(), "racking_type", "Фронтальные");
            otpText(warehouse.getId(), "pallet_dimensions", "1200x800x1600");

            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name("ООО «ТестРобот»").build());
            Solution robot = solutionRepository.save(Solution.builder()
                    .name("Робот Тестовый").vendorId(vendor.getId())
                    .productClass("brs").status("operation")
                    .priceRub(new BigDecimal("2500000")).trl((short) 9)
                    .sourceKind("organizer_catalog")
                    .sourceUrl("catalog_export_v4.csv")
                    .chargingPowerKw(new BigDecimal("1.35")).build());
            robotId = robot.getId();
            CharacteristicType lifetime = characteristicTypeRepository.save(
                    CharacteristicType.builder().code("lifecycle_years")
                            .name("Срок службы, лет").groupCode("technical")
                            .dataType("number").unit("лет")
                            .isFilterable(false).isRequired(false)
                            .sortOrder(13).build());
            characteristicTypeRepository.save(CharacteristicType.builder()
                    .code("charging_power_kw").name("Мощность зарядки, кВт")
                    .groupCode("technical").dataType("number").unit("кВт")
                    .isFilterable(true).isRequired(true).sortOrder(8).build());
            solutionCharacteristicRepository.save(SolutionCharacteristic
                    .builder().solutionId(robot.getId())
                    .characteristicTypeId(lifetime.getId())
                    .valueNumeric(new BigDecimal("6"))
                    .sourceKind("organizer_catalog").isConfirmed(true).build());

            purchaseId = scenarioRepository.save(Scenario.builder()
                    .projectId(projectId).type(ScenarioType.PURCHASE)
                    .name("Покупка оборудования").build()).getId();
            scenarioSolutionRepository.save(ScenarioSolution.builder()
                    .scenarioId(purchaseId).solutionId(robot.getId())
                    .quantity(3).isManual(false).build());
        });
    }

    private void otp(Long typeId, String code, String defaultValue) {
        ParameterType type = parameterTypeRepository.findAll().stream()
                .filter(t -> code.equals(t.getCode())).findFirst()
                .orElseGet(() -> parameterTypeRepository.save(ParameterType
                        .builder().code(code).name("Параметр " + code)
                        .unit("ед").valueType(ParameterValueType.NUMBER).build()));
        objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                .objectTypeId(typeId).parameterTypeId(type.getId())
                .groupName("Тест").isRequired(true)
                .defaultValueNumeric(defaultValue == null ? null
                        : new BigDecimal(defaultValue))
                .sourceNote("Тест").build());
    }

    private void otpText(Long typeId, String code, String defaultValue) {
        ParameterType type = parameterTypeRepository.findAll().stream()
                .filter(t -> code.equals(t.getCode())).findFirst()
                .orElseGet(() -> parameterTypeRepository.save(ParameterType
                        .builder().code(code).name("Параметр " + code)
                        .unit("-").valueType(ParameterValueType.TEXT).build()));
        objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                .objectTypeId(typeId).parameterTypeId(type.getId())
                .groupName("Тест").isRequired(true)
                .defaultValueText(defaultValue).sourceNote("Тест").build());
    }

    private CalculationFullDto calculate() {
        return service.calculate(userId, projectId, purchaseId);
    }

    /**
 * Проект для тестов интерпретации:
 * тот же тип объекта (склад с параметрами фикстуры), опционально -
 * допущение P_nominal (для required по формуле §2.1) и состав
 * покупки с заданным количеством роботов (0 - пустой сценарий).
 * Возвращает [projectId, purchaseScenarioId].
 */
    private Long[] interpretationProject(String pNominal, int robots) {
        return transactionTemplate.execute(tx -> {
            Long pid = projectRepository.save(Project.builder()
                    .userId(userId).objectTypeId(warehouseTypeId)
                    .name("Интерпретация " + System.nanoTime())
                    .status(ProjectStatus.ACTIVE).build()).getId();
            if (pNominal != null) {
                projectAssumptionRepository.save(ProjectAssumption.builder()
                        .projectId(pid)
                        .name("robot_nominal_productivity_per_hour")
                        .value(pNominal).build());
            }
            // base создаётся первым (пустой по определению, инвариант
            // data_model.md §10.5) - [0]=проект, [1]=purchase, [2]=base
            Long baseId = scenarioRepository.save(Scenario.builder()
                    .projectId(pid).type(ScenarioType.BASE)
                    .name("Текущий процесс без роботизации").build()).getId();
            Long sid = scenarioRepository.save(Scenario.builder()
                    .projectId(pid).type(ScenarioType.PURCHASE)
                    .name("Покупка оборудования").build()).getId();
            if (robots > 0) {
                scenarioSolutionRepository.save(ScenarioSolution.builder()
                        .scenarioId(sid).solutionId(robotId)
                        .quantity(robots).isManual(false).build());
            }
            return new Long[]{pid, sid, baseId};
        });
    }

    private ComparisonDto.ScenarioColumnDto purchaseColumn(Long pid) {
        return service.compare(userId, pid).scenarios().stream()
                .filter(c -> "purchase".equals(c.type()))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // Ревью C/FORM-1 (2026-09-24): base - «не применяется»
    // ------------------------------------------------------------------

    @Test
    void baseScenario_notApplicable_interpretation() {
        // Базовый сценарий пуст ПО ОПРЕДЕЛЕНИЮ (инвариант base,
        // data_model.md §10.5) - ветка empty_comparison не
        // должна его перехватывать: окупаемость к base неприменима
        // в принципе, сравнение - по OPEX и TCO 
        Long[] ids = interpretationProject(null, 3);
        service.calculate(userId, ids[0], ids[2]);
        ComparisonDto.ScenarioColumnDto base = service.compare(userId, ids[0])
                .scenarios().stream()
                .filter(c -> "base".equals(c.type()))
                .findFirst().orElseThrow();
        assertThat(base.selectedRobots()).isZero();
        assertThat(base.payback().category()).isEqualTo("not_applicable");
        assertThat(base.payback().text())
                .isEqualTo("Не применяется - базовый сценарий сравнивается "
                        + "по годовому OPEX и TCO");
    }

    // ------------------------------------------------------------------
    // Единая формулировка underpowered
    // ------------------------------------------------------------------

    @Test
    void underpowered_consistentMessages() {
        // Проект 16 «Максимум - 1 решение»: 1 робот при требуемых 3
        // (peak = 136.36, P_nominal = 90, k_load = 0.75, k_av = 1.0 →
        // need = 136.36 × 1.15 / 67.5 = 2.32 → 3). Payback и ROI обязаны
        // показывать ОДНУ И ТУ ЖЕ причину: «Н/Д - парк меньше требуемого
        // по пиковой нагрузке» (раньше payback - «Не рассчитано: …»,
        // ROI - голое «Н/Д»)
        Long[] ids = interpretationProject("90", 1);
        service.calculate(userId, ids[0], ids[1]);
        ComparisonDto.ScenarioColumnDto purchase = purchaseColumn(ids[0]);
        assertThat(purchase.selectedRobots()).isEqualTo(1);
        assertThat(purchase.requiredRobots()).isEqualTo(3);
        assertThat(purchase.underpowered()).isTrue();
        assertThat(purchase.payback().category()).isEqualTo("underpowered");
        assertThat(purchase.payback().text())
                .isEqualTo("Н/Д - парк меньше требуемого по пиковой нагрузке");
        // ROI при недоборе скрыт числом - в UI та же формулировка
        assertThat(purchase.roiPct()).isNull();
    }

    // ------------------------------------------------------------------
    // Интерпретация пустого состава
    // ------------------------------------------------------------------

    @Test
    void emptyComposition_interpretation() {
        // Проект 15 «Минимальный склад»: пустой состав - первая ветка
        // интерпретации (приоритет ВЫШЕ проверки Effect_year ≤ 0):
        // «Состав пуст - добавьте решения в сценарий», а не «Не
        // окупается - годовой эффект не положителен»
        Long[] ids = interpretationProject(null, 0);
        CalculationFullDto calc = service.calculate(userId, ids[0], ids[1]);
        // пустой состав: ΔFOT = 0, эффект не положителен -
        // но настоящая причина - отсутствие решений, а не экономика
        assertThat(calc.details().get("selectedRobots")).isEqualTo(0);
        assertThat((BigDecimal) calc.details().get("deltaFot"))
                .isEqualByComparingTo("0");
        ComparisonDto.ScenarioColumnDto purchase = purchaseColumn(ids[0]);
        assertThat(purchase.selectedRobots()).isZero();
        assertThat(purchase.payback().category())
                .isEqualTo("empty_composition");
        assertThat(purchase.payback().text())
                .isEqualTo("Состав пуст - добавьте решения в сценарий");
        // приоритет ветки: категория не «none» (эффект не положителен)
        assertThat(purchase.payback().category()).isNotEqualTo("none");
    }

    // ------------------------------------------------------------------
    // Окупаемость < 0,5 года - в месяцах
    // ------------------------------------------------------------------

    @Test
    void paybackMonths_whenUnderHalfYear() {
        // Свой проект (не фикстура Adjust: её последний расчёт мог быть
        // скорректирован другими тестами): 3 робота, Payback = 0.1 г. -
        // как проект 17 «Максимум - 5 решений»; рядом с подписью «до 3
        // лет» значение «0,1 г.» нечитаемо: paybackMonths = round(0,1 ×
        // 12, 1) = 1.2 (§4, только отображение)
        Long[] ids = interpretationProject(null, 3);
        service.calculate(userId, ids[0], ids[1]);
        ComparisonDto.ScenarioColumnDto purchase = purchaseColumn(ids[0]);
        assertThat(purchase.payback().paybackYears())
                .isEqualByComparingTo("0.1");
        assertThat(purchase.payback().paybackMonths())
                .isEqualByComparingTo("1.2");
        assertThat(purchase.payback().category()).isEqualTo("fast");
    }

    // ------------------------------------------------------------------
    // Пересчёт зависимых метрик
    // ------------------------------------------------------------------

    @Test
    void adjust_recalculates_dependents() {
        CalculationFullDto source = calculate();
        // База: CAPEX 12 416 250, Effect 199 169 474, Payback 0.1,
        // ROI(5) = 8020.5 (те же числа, что CalculationControllerIT)
        assertThat(source.totalCapex())
                .isEqualByComparingTo("12416250");
        assertThat(source.paybackYears()).isEqualByComparingTo("0.1");
        assertThat(source.roiPct()).isEqualByComparingTo("8020.5");

        // --- 1. Корректировка effect_year → payback и roi пересчитаны -----
        CalculationFullDto adjusted = service.adjust(userId, projectId,
                source.id(), new ManualAdjustRequest("effect_year",
                        new BigDecimal("190000000"), "Пересогласован эффект"));
        assertThat(adjusted.effectYear())
                .isEqualByComparingTo("190000000");
        // Payback = 12 416 250 / 190 000 000 = 0.0653 → 0.1 (§4)
        assertThat(adjusted.paybackYears()).isEqualByComparingTo("0.1");
        // ROI = 190 000 000 × 5 / 12 416 250 × 100 = 7651.263 → 7651.3
        assertThat(adjusted.roiPct()).isEqualByComparingTo("7651.3");
        // adjustedFrom фиксирует, что пересчитано автоматически
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> adjustedFrom =
                (java.util.Map<String, Object>) adjusted.details()
                        .get("adjustedFrom");
        assertThat((List<String>) adjustedFrom.get("recalculatedMetrics"))
                .containsExactly("payback_years", "roi_pct");
        // Зеркала details синхронизированы
        assertThat((BigDecimal) adjusted.details().get("paybackYears"))
                .isEqualByComparingTo("0.1");
        assertThat((BigDecimal) adjusted.details().get("roiPct"))
                .isEqualByComparingTo("7651.3");

        // --- 2. Корректировка total_capex → payback и roi от нового CAPEX -
        CalculationFullDto capexAdjusted = service.adjust(userId, projectId,
                source.id(), new ManualAdjustRequest("total_capex",
                        new BigDecimal("15000000"), "Скидка вендора 17%"));
        // Payback = 15 000 000 / 199 169 474 = 0.0753 → 0.1
        assertThat(capexAdjusted.paybackYears()).isEqualByComparingTo("0.1");
        // ROI = 199 169 474 × 5 / 15 000 000 × 100 = 6638.98 → 6639.0
        assertThat(capexAdjusted.roiPct()).isEqualByComparingTo("6639.0");

        // --- 3. Корректировка payback_years → зависимые НЕ пересчитаны ----
        CalculationFullDto paybackAdjusted = service.adjust(userId, projectId,
                source.id(), new ManualAdjustRequest("payback_years",
                        new BigDecimal("2.5"), "Консервативная оценка"));
        assertThat(paybackAdjusted.paybackYears())
                .isEqualByComparingTo("2.5");
        // ROI остался от исходного расчёта - не пересчитан
        assertThat(paybackAdjusted.roiPct()).isEqualByComparingTo("8020.5");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> paybackFrom =
                (java.util.Map<String, Object>) paybackAdjusted.details()
                        .get("adjustedFrom");
        assertThat((String) paybackFrom.get("dependentsNote"))
                .contains("не пересчитаны");
        assertThat(paybackFrom.get("recalculatedMetrics")).isNull();

        // --- 4. Корректировка roi_pct → payback не трогаем ----------------
        CalculationFullDto roiAdjusted = service.adjust(userId, projectId,
                source.id(), new ManualAdjustRequest("roi_pct",
                        new BigDecimal("9000"), "Пересчёт накопленного"));
        assertThat(roiAdjusted.roiPct()).isEqualByComparingTo("9000");
        // Payback остался от исходного расчёта (0.1), ROI заменён
        assertThat(roiAdjusted.paybackYears()).isEqualByComparingTo("0.1");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> roiFrom =
                (java.util.Map<String, Object>) roiAdjusted.details()
                        .get("adjustedFrom");
        assertThat((String) roiFrom.get("dependentsNote"))
                .contains("не пересчитаны");
    }

    // ------------------------------------------------------------------
    // A9: кредит в детальном расчёте - оба значения в metrics_json
    // ------------------------------------------------------------------

    @Test
    void calculate_withoutLoan_noLoanBlock() {
        CalculationFullDto calc = calculate();
        // Кредит не задан - блока loan в details нет, Effect = до долга
        assertThat(calc.details().get("loan")).isNull();
        assertThat(calc.effectYear()).isEqualByComparingTo("199169474");
    }

    @Test
    void adjust_appendOnly_newRowAndVersionInherited() {
        CalculationFullDto source = calculate();
        CalculationFullDto adjusted = service.adjust(userId, projectId,
                source.id(), new ManualAdjustRequest("total_opex",
                        new BigDecimal("2000000"), "Уточнён сервисный"));
        // append-only: новый расчёт, version_data наследуется (§10.10)
        assertThat(adjusted.id()).isNotEqualTo(source.id());
        assertThat(adjusted.versionData())
                .isEqualTo(source.versionData());
        // total_opex не имеет зависимых - ничего не пересчитано
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> from =
                (java.util.Map<String, Object>) adjusted.details()
                        .get("adjustedFrom");
        assertThat(from.get("recalculatedMetrics")).isNull();
        assertThat(from.get("dependentsNote")).isNull();
    }
}
