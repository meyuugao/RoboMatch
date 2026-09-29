package me.yuugao.robomatch.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.CalculationFullDto;
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
 * Интеграционный тест-дубль эталона docs/economics_golden.md
 * (2026-09-23): та же конфигурация, что E2E-проект «E2E: Максимум - 1
 * решение» (склад с базовыми значениями параметров, Ronavi H1500 × 1 в
 * purchase и raas, base пустой), - сверка КАЖДОЙ формулы §2.1–2.13
 * (статьи CAPEX/OPEX, эффект, окупаемость, ROI, TCO, RaaS) с эталонными
 * значениями из доки (inline-копия машинного блока golden).
 *
 * <p>Регрессия горизонта: параметр payback_horizon из БД
 * приходит строкой numeric(16,4) «10.0000» - Integer.parseInt падал и
 * горизонт молча дефолтился в 5; тест ставит
 * значение именно через project_parameter_value (numeric) и требует
 * horizonYears = 10 и ROI/TCO от горизонта 10.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:goldit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EconomicsGoldenTest {

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
    private ProjectParameterValueRepository valueRepository;
    @Autowired
    private ProjectAssumptionRepository assumptionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ScenarioSolutionRepository scenarioSolutionRepository;
    @Autowired
    private EconomicCalculationService service;

    private Long userId;
    private Long projectId;
    private Long purchaseId;
    private Long raasId;
    private Long baseId;
    private Long horizonParamId;

    // ------------------------------------------------------------------
    // Фикстура = E2E «Максимум - 1 решение» (economics_golden.md)
    // ------------------------------------------------------------------

    @BeforeAll
    void seedGoldenFixture() {
        transactionTemplate.executeWithoutResult(tx -> {
            User user = userRepository.save(User.builder()
                    .login("golden_user").passwordHash("x")
                    .role(UserRole.USER).build());
            userId = user.getId();
            ObjectType warehouse = objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse").name("Склад").isCalcEnabled(true)
                    .dataSourceNote("Тест").build());
            projectId = projectRepository.save(Project.builder()
                            .userId(userId).objectTypeId(warehouse.getId())
                            .name("Golden").status(ProjectStatus.ACTIVE).build())
                    .getId();

            // 24 обязательных источника экономики + горизонт; дефолты -
            // базовые значения листа «Склад» (economics_golden.md §1)
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
                    {"pallet_unit_weight", "1500"},
                    {"total_warehouse_staff", "180"},
                    {"pickers_count", "100"},
                    {"forklift_operators_count", "25"},
                    {"picker_throughput_lines_per_hour", "150"},
                    {"picker_salary_gross", "100000"},
                    {"forklift_operator_salary_gross", "120000"},
                    {"payroll_insurance_contributions_rate", "1.302"},
                    {"floor_flatness_deviation", "3"},
                    {"main_aisle_width", "2.5"},
                    {"rack_aisle_width", "1.5"},
                    {"storage_zone_ceiling_height", "5"},
                    {"payback_horizon", "5"},
            };
            for (String[] p : params) {
                Long otpId = otp(warehouse.getId(), p[0], p[1]);
                if ("payback_horizon".equals(p[0])) {
                    horizonParamId = otpId;
                }
            }
            otpText(warehouse.getId(), "racking_type", "Фронтальные");
            otpText(warehouse.getId(), "pallet_dimensions", "1200×800×1600");

            // Ronavi H1500: цена 2.7М, зарядка 1.35 кВт (EAV - как в каталоге)
            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name("Ронави").build());
            Solution robot = solutionRepository.save(Solution.builder()
                    .name("Ronavi H1500").vendorId(vendor.getId())
                    .productClass("brs").status("operation")
                    .priceRub(new BigDecimal("2700000")).trl((short) 9)
                    .sourceKind("organizer_catalog")
                    .sourceUrl("catalog_export_v4.csv").build());
            CharacteristicType charging = characteristicTypeRepository.save(
                    CharacteristicType.builder().code("charging_power_kw")
                            .name("Мощность зарядки, кВт")
                            .groupCode("technical").dataType("number")
                            .unit("кВт").isFilterable(true).isRequired(true)
                            .sortOrder(8).build());
            characteristicTypeRepository.save(CharacteristicType.builder()
                    .code("lifecycle_years").name("Срок службы, лет")
                    .groupCode("technical").dataType("number").unit("лет")
                    .isFilterable(false).isRequired(false).sortOrder(13)
                    .build());
            solutionCharacteristicRepository.save(SolutionCharacteristic
                    .builder().solutionId(robot.getId())
                    .characteristicTypeId(charging.getId())
                    .valueNumeric(new BigDecimal("1.35"))
                    .sourceKind("open_source").isConfirmed(true).build());

            // три сценария (base пустой - инвариант), состав purchase/raas
            baseId = scenarioRepository.save(Scenario.builder()
                    .projectId(projectId).type(ScenarioType.BASE)
                    .name("Текущий процесс без роботизации").build()).getId();
            purchaseId = scenarioRepository.save(Scenario.builder()
                    .projectId(projectId).type(ScenarioType.PURCHASE)
                    .name("Покупка оборудования").build()).getId();
            raasId = scenarioRepository.save(Scenario.builder()
                    .projectId(projectId).type(ScenarioType.RAAS)
                    .name("Роботы как услуга").build()).getId();
            for (Long scenarioId : List.of(purchaseId, raasId)) {
                scenarioSolutionRepository.save(ScenarioSolution.builder()
                        .scenarioId(scenarioId).solutionId(robot.getId())
                        .quantity(1).isManual(false).build());
            }

            // P_nominal = 90 оп/ч - допущение проекта
            assumptionRepository.save(ProjectAssumption.builder()
                    .projectId(projectId)
                    .name("robot_nominal_productivity_per_hour")
                    .value("90").build());
        });
    }

    private Long otp(Long typeId, String code, String defaultValue) {
        ParameterType type = parameterTypeRepository.findAll().stream()
                .filter(t -> code.equals(t.getCode())).findFirst()
                .orElseGet(() -> parameterTypeRepository.save(ParameterType
                        .builder().code(code).name("Параметр " + code)
                        .unit("ед").valueType(ParameterValueType.NUMBER).build()));
        return objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                .objectTypeId(typeId).parameterTypeId(type.getId())
                .groupName("Тест").isRequired(true)
                .defaultValueNumeric(defaultValue == null ? null
                        : new BigDecimal(defaultValue))
                .sourceNote("Тест").build()).getId();
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

    // ------------------------------------------------------------------
    // Эталон: покупка (economics_golden.md §2, все §2.1–2.9)
    // ------------------------------------------------------------------

    @Test
    void purchase_matchesGoldenAllFormulas() {
        CalculationFullDto calc = service.calculate(userId, projectId,
                purchaseId);
        assertThat(calc.versionModel()).isEqualTo("economic-model-1.0");
        // §2.1–2.2: выбранный состав 1, требуемое по формуле 3,
        // недобор - флаг; вся экономика - по выбранному составу
        assertThat(((Number) calc.details().get("selectedRobots")).longValue()).isEqualTo(1L);
        assertThat(((Number) calc.details().get("requiredRobots")).longValue()).isEqualTo(3L);
        assertThat((Boolean) calc.details().get("underpowered")).isTrue();
        assertThat(((Number) calc.details().get("nInfra")).longValue()).isEqualTo(1L);
        // §2.3 CAPEX (все 7 статей, до рубля)
        @SuppressWarnings("unchecked")
        var capex = (java.util.Map<String, Object>) calc.details().get("capex");
        assertThat(((Number) capex.get("equipment")).longValue()).isEqualTo(2_700_000L);
        assertThat(((Number) capex.get("infra")).longValue()).isEqualTo(270_000L);
        assertThat(((Number) capex.get("software")).longValue()).isEqualTo(405_000L);
        assertThat(((Number) capex.get("integration")).longValue()).isEqualTo(405_000L);
        assertThat(((Number) capex.get("commissioning")).longValue()).isEqualTo(202_500L);
        assertThat(((Number) capex.get("training")).longValue()).isEqualTo(81_000L);
        assertThat(((Number) capex.get("reserve")).longValue()).isEqualTo(406_350L);
        assertThat(((Number) capex.get("total")).longValue()).isEqualTo(4_469_850L);
        assertThat(calc.totalCapex()).isEqualByComparingTo("4469850");
        // §2.4 OPEX (все 7 статей)
        @SuppressWarnings("unchecked")
        var opex = (java.util.Map<String, Object>) calc.details().get("opex");
        assertThat(((Number) opex.get("service")).longValue()).isEqualTo(270_000L);
        assertThat(((Number) opex.get("licenses")).longValue()).isEqualTo(135_000L);
        assertThat(((Number) opex.get("electricity")).longValue()).isEqualTo(75_884L);
        assertThat(((Number) opex.get("communication")).longValue()).isEqualTo(36_000L);
        assertThat(((Number) opex.get("consumables")).longValue()).isEqualTo(54_000L);
        assertThat(((Number) opex.get("repair")).longValue()).isEqualTo(94_500L);
        assertThat(((Number) opex.get("staff")).longValue()).isEqualTo(0L);
        assertThat(((Number) opex.get("total")).longValue()).isEqualTo(665_384L);
        assertThat(calc.totalOpex()).isEqualByComparingTo("665384");
        // §2.5–2.6: ΔOPEX/ΔFOT/эффект; K_начислений = 1.302 с единицей
        assertThat(((Number) calc.details().get("opexBase")).longValue()).isEqualTo(203_112_000L);
        assertThat(((Number) calc.details().get("fotBase")).longValue()).isEqualTo(203_112_000L);
        assertThat(((Number) calc.details().get("deltaFot")).longValue()).isEqualTo(203_112_000L);
        assertThat(calc.opexDeltaRub())
                .isEqualByComparingTo("-202446616");
        assertThat(((Number) calc.details().get("effectGross")).longValue())
                .isEqualTo(202_446_616L);
        assertThat(((Number) calc.details().get("amortYear")).longValue()).isEqualTo(744_975L);
        assertThat(calc.effectYear()).isEqualByComparingTo("201701641");
        // §2.7–2.9: окупаемость/ROI/TCO (округления §4)
        assertThat(calc.paybackYears()).isEqualByComparingTo("0.0");
        assertThat(calc.roiPct()).isEqualByComparingTo("22562.5");
        assertThat(calc.tcoRub()).isEqualByComparingTo("7796770");
        assertThat(((Number) calc.details().get("replacements")).longValue()).isEqualTo(0L);
        assertThat(((Number) calc.details().get("horizonYears")).longValue()).isEqualTo(5L);
        // §2.12: чувствительность - 3 параметра × 5 шагов
        @SuppressWarnings("unchecked")
        var sens = (java.util.List<?>) calc.details().get("sensitivity");
        assertThat(sens).hasSize(15);
    }

    // ------------------------------------------------------------------
    // Эталон: RaaS fixed без выкупа (§2.11)
    // ------------------------------------------------------------------

    @Test
    void raas_matchesGolden() {
        CalculationFullDto calc = service.calculate(userId, projectId,
                raasId);
        assertThat(calc.totalCapex()).isEqualByComparingTo("0");
        assertThat(calc.totalOpex()).isEqualByComparingTo("759884");
        assertThat(calc.effectYear()).isEqualByComparingTo("202352116");
        assertThat(calc.paybackYears()).isNull();  // CAPEX = 0 - не определён
        assertThat(calc.roiPct()).isNull();
        assertThat(calc.tcoRub()).isEqualByComparingTo("3799420");
        @SuppressWarnings("unchecked")
        var raas = (java.util.Map<String, Object>) calc.details().get("raas");
        assertThat(raas.get("paymentModel")).isEqualTo("fixed");
        assertThat(((Number) raas.get("rateMonthRub")).longValue()).isEqualTo(54_000L);
        assertThat(((Number) raas.get("paymentYear")).longValue()).isEqualTo(648_000L);
        assertThat(raas.get("paymentYears")).isEqualTo(5);
        assertThat(((Number) raas.get("buyoutValue")).longValue()).isEqualTo(0L);
        assertThat(((Number) raas.get("capexRaas")).longValue()).isEqualTo(0L);
    }

    // ------------------------------------------------------------------
    // Эталон: базовый сценарий (§2.13 - колонка сравнения)
    // ------------------------------------------------------------------

    @Test
    void base_matchesGolden() {
        CalculationFullDto calc = service.calculate(userId, projectId,
                baseId);
        assertThat(calc.totalOpex()).isEqualByComparingTo("203112000");
        assertThat(calc.effectYear()).isEqualByComparingTo("0");
        assertThat(calc.tcoRub()).isEqualByComparingTo("1015560000");
        assertThat(calc.paybackYears()).isNull();
    }

    // ------------------------------------------------------------------
    // Некорректные входы блокируют расчёт (§6)
    // ------------------------------------------------------------------

    @Test
    void invalidHorizon_blocksCalculation_notSilentDefault() {
        // горизонт 0 (и не-число) раньше молча подменялся дефолтом 5
        transactionTemplate.executeWithoutResult(tx -> valueRepository.save(
                ProjectParameterValue.builder().projectId(projectId)
                        .objectTypeParameterId(horizonParamId)
                        .valueNumeric(BigDecimal.ZERO)
                        .source(ParameterValueSource.MANUAL)
                        .updatedAt(java.time.Instant.now()).build()));
        try {
            assertThatThrownBy(() -> service.calculate(userId, projectId,
                    purchaseId))
                    .isInstanceOf(me.yuugao.robomatch.exception
                            .EconomicValidationException.class)
                    .hasMessageContaining("Горизонт расчёта окупаемости");
        } finally {
            transactionTemplate.executeWithoutResult(tx ->
                    valueRepository.findAllByProjectId(projectId).stream()
                            .filter(v -> v.getObjectTypeParameterId()
                                    .equals(horizonParamId))
                            .forEach(valueRepository::delete));
        }
    }

    @Test
    void loanWithZeroRate_blocksCalculation() {
        // нулевая ставка - нулевой знаменатель аннуитета (§2.10):
        // блокировка 400 вместо 500
        me.yuugao.robomatch.domain.ProjectAssumption loan =
                ProjectAssumption.builder().projectId(projectId)
                        .name("loan_amount_rub").value("2000000").build();
        me.yuugao.robomatch.domain.ProjectAssumption rate =
                ProjectAssumption.builder().projectId(projectId)
                        .name("loan_rate_pct").value("0").build();
        me.yuugao.robomatch.domain.ProjectAssumption term =
                ProjectAssumption.builder().projectId(projectId)
                        .name("loan_term_years").value("3").build();
        transactionTemplate.executeWithoutResult(tx -> {
            assumptionRepository.save(loan);
            assumptionRepository.save(rate);
            assumptionRepository.save(term);
        });
        try {
            assertThatThrownBy(() -> service.calculate(userId, projectId,
                    purchaseId))
                    .isInstanceOf(me.yuugao.robomatch.exception
                            .EconomicValidationException.class)
                    .hasMessageContaining("ставка должна быть");
        } finally {
            transactionTemplate.executeWithoutResult(tx -> {
                assumptionRepository.delete(loan);
                assumptionRepository.delete(rate);
                assumptionRepository.delete(term);
            });
        }
    }

    // ------------------------------------------------------------------
    // Регрессия горизонта: «10.0000» парсится как 10
    // ------------------------------------------------------------------

    @Test
    void horizonFromNumericParameter_appliedNotDefaulted() {
        // значение пишется в БД как numeric(16,4) - строка «10.0000»;
        // до фикса Integer.parseInt падал и горизонт молча брался 5
        transactionTemplate.executeWithoutResult(tx -> valueRepository.save(
                ProjectParameterValue.builder().projectId(projectId)
                        .objectTypeParameterId(horizonParamId)
                        .valueNumeric(new BigDecimal("10.0000"))
                        .source(ParameterValueSource.MANUAL)
                        .updatedAt(java.time.Instant.now()).build()));
        try {
            CalculationFullDto calc = service.calculate(userId, projectId,
                    purchaseId);
            assertThat(((Number) calc.details().get("horizonYears")).longValue()).isEqualTo(10L);
            // ROI = 201 701 641 × 10 / 4 469 850 × 100 = 45 124.9%
            assertThat(calc.roiPct()).isEqualByComparingTo("45124.9");
            // TCO = 4 469 850 + 665 384 × 10 + 1 × 2 700 000 = 13 823 690
            assertThat(calc.tcoRub()).isEqualByComparingTo("13823690");
            assertThat(((Number) calc.details().get("replacements")).longValue())
                    .isEqualTo(2_700_000L);
        } finally {
            transactionTemplate.executeWithoutResult(tx ->
                    valueRepository.findAllByProjectId(projectId).stream()
                            .filter(v -> v.getObjectTypeParameterId()
                                    .equals(horizonParamId))
                            .forEach(valueRepository::delete));
            // возврат к дефолту для остальных тестов (горизонт 5)
            service.calculate(userId, projectId, purchaseId);
        }
    }
}
