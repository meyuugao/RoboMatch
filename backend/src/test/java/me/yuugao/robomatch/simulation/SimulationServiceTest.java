package me.yuugao.robomatch.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.SimulationRunDto;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.repository.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Сервисные тесты имитации:
 * идемпотентность повторного запуска (детерминированная модель - те же
 * KPI), гонка повторного запуска (живой running → 409),
 * зависший running (TTL 15 минут) помечается failed, ошибка расчёта →
 * статус failed с причиной, изоляция чужих проектов (404).
 *
 * <p>Фикстура: склад с 5 параметрами имитации (смены/продолжительность/
 * пик/приёмка/отгрузка - дефолты датасета), покупка из 3 роботов
 * (скорость 1,5 м/с), P_nominal 90 - переопределением допущения.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "app.simulation.root=./build/test-simulations",
        "spring.datasource.url=jdbc:h2:mem:simsrv;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationServiceTest {

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
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectAssumptionRepository assumptionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ScenarioSolutionRepository scenarioSolutionRepository;
    @Autowired
    private SimulationResultRepository simulationRepository;
    @Autowired
    private CalculationRepository calculationRepository;
    @Autowired
    private SimulationService simulationService;

    private Long userId;
    private Long otherUserId;
    private Long projectId;
    private Long purchaseId;
    private Long baseId;
    private Long emptyPurchaseId;
    private Long crossScenarioId;

    @BeforeAll
    void seedFixtures() {
        transactionTemplate.executeWithoutResult(tx -> {
            me.yuugao.robomatch.domain.User user =
                    me.yuugao.robomatch.domain.User.builder()
                            .login("sim-user").passwordHash("x")
                            .role(me.yuugao.robomatch.domain.UserRole.USER)
                            .build();
            userId = userRepository.save(user).getId();
            me.yuugao.robomatch.domain.User other =
                    me.yuugao.robomatch.domain.User.builder()
                            .login("sim-other").passwordHash("x")
                            .role(me.yuugao.robomatch.domain.UserRole.USER)
                            .build();
            otherUserId = userRepository.save(other).getId();

            ObjectType warehouse = objectTypeRepository.save(ObjectType
                    .builder().code("warehouse").name("Склад")
                    .isCalcEnabled(true).dataSourceNote("Тест").build());
            otp(warehouse.getId(), "shifts_per_day", "2");
            otp(warehouse.getId(), "shift_duration", "11");
            otp(warehouse.getId(), "peak_load_factor", "1.5");
            otp(warehouse.getId(), "inbound_pallets_per_day", "1000");
            otp(warehouse.getId(), "outbound_pallets_per_day", "1000");

            Project project = projectRepository.save(Project.builder()
                    .userId(userId).objectTypeId(warehouse.getId())
                    .name("Склад имитации").status(
                            me.yuugao.robomatch.domain.ProjectStatus.DRAFT)
                    .build());
            projectId = project.getId();
            // P_nominal для достижимости (каталог допущений без дефолта)
            assumptionRepository.save(ProjectAssumption.builder()
                    .projectId(projectId)
                    .name("robot_nominal_productivity_per_hour")
                    .value("90").build());

            baseId = scenarioRepository.save(Scenario.builder()
                    .projectId(projectId).type(ScenarioType.BASE)
                    .name("Базовый процесс").build()).getId();
            purchaseId = scenarioRepository.save(Scenario.builder()
                    .projectId(projectId).type(ScenarioType.PURCHASE)
                    .name("Покупка оборудования").build()).getId();
            emptyPurchaseId = scenarioRepository.save(Scenario.builder()
                    .projectId(projectId).type(ScenarioType.PURCHASE)
                    .name("Пустая покупка").build()).getId();

            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name("Ронави").build());
            Solution robot = solutionRepository.save(Solution.builder()
                    .vendorId(vendor.getId()).name("Ronavi H1500")
                    .productClass("brs").status("operation")
                    .priceRub(BigDecimal.valueOf(2_500_000)).trl((short) 9)
                    .sourceKind("organizer_catalog")
                    .sourceUrl("catalog_export_v4.csv")
                    .speedMs(BigDecimal.valueOf(1.5)).build());
            scenarioSolutionRepository.save(ScenarioSolution.builder()
                    .scenarioId(purchaseId).solutionId(robot.getId())
                    .quantity(3).isManual(false).build());
        });
    }

    // ------------------------------------------------------------------
    // Запуск: 200 completed, KPI 6 штук
    // ------------------------------------------------------------------

    private void otp(Long typeId, String code, String defaultValue) {
        ParameterType type = parameterTypeRepository.save(ParameterType
                .builder().code(code).name("Параметр " + code)
                .unit("ед").valueType(ParameterValueType.NUMBER).build());
        objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                .objectTypeId(typeId).parameterTypeId(type.getId())
                .groupName("Тест").isRequired(true)
                .defaultValueNumeric(new BigDecimal(defaultValue))
                .sourceNote("Тест").build());
    }

    // ------------------------------------------------------------------
    // Идемпотентность: повторный запуск - та же модель, те же KPI
    // ------------------------------------------------------------------

    @Test
    void runCompletesWithSixKpi() {
        SimulationRunDto view = simulationService.run(userId, projectId,
                purchaseId);
        assertThat(view.status()).isEqualTo("completed");
        assertThat(view.id()).isNotNull();
        // 3 робота × 30 оп/ч = 90 < пик 136,4: фактическая = мощность
        // парка (дефицит), загрузка 100%, простоев нет
        assertThat(view.actualThroughputPerHour())
                .isEqualByComparingTo("90.0");
        assertThat(view.utilizationPct()).isEqualByComparingTo("100.0");
        assertThat(view.idlePct()).isEqualByComparingTo("0.0");
        assertThat(view.declaredThroughputPerHour())
                .isEqualByComparingTo("270.0");
        assertThat(view.zones()).hasSize(4);
        assertThat(view.achievabilityPct()).isEqualByComparingTo("33.3");
        assertThat(view.robots()).isEqualTo(3);
        assertThat(view.chargingStations()).isEqualTo(2);
        assertThat(view.durationMs()).isNotNull();
        // дефицит мощности - предупреждение
        assertThat(view.warnings()).anyMatch(w -> w.contains("превышает"));
        // экономика не рассчитана - предупреждение сверки
        assertThat(view.warnings())
                .anyMatch(w -> w.contains("Экономика сценария ещё не"));
    }

    // ------------------------------------------------------------------
    // Гонка повторного запуска: живой running → 409
    // ------------------------------------------------------------------

    @Test
    void repeatedRunIsIdempotent_newRowSameKpi() {
        SimulationRunDto first = simulationService.run(userId, projectId,
                purchaseId);
        SimulationRunDto second = simulationService.run(userId, projectId,
                purchaseId);
        assertThat(second.id()).isNotEqualTo(first.id()); // история: новая строка
        assertThat(second.actualThroughputPerHour())
                .isEqualByComparingTo(first.actualThroughputPerHour());
        assertThat(second.utilizationPct())
                .isEqualByComparingTo(first.utilizationPct());
        assertThat(second.zones()).isEqualTo(first.zones());
        assertThat(second.version())
                .isEqualTo(SimulationModel.MODEL_VERSION);
        List<SimulationResult> rows = simulationRepository
                .findAllByScenarioIdOrderByIdDesc(purchaseId);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.getStatus()).isEqualTo("completed"));
    }

    // ------------------------------------------------------------------
    // Зависший running (TTL 15 минут): помечается failed, запуск идёт
    // ------------------------------------------------------------------

    @Test
    void concurrentRun_conflict409() {
        SimulationResult running = transactionTemplate.execute(tx ->
                simulationRepository.save(SimulationResult.builder()
                        .scenarioId(purchaseId).startedAt(Instant.now())
                        .status("running").build()));
        try {
            assertThatThrownBy(() -> simulationService.run(userId,
                    projectId, purchaseId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("уже выполняется");
        } finally {
            transactionTemplate.executeWithoutResult(tx ->
                    simulationRepository.deleteById(running.getId()));
        }
    }

    // ------------------------------------------------------------------
    // Ошибка расчёта → статус failed с причиной
    // ------------------------------------------------------------------

    @Test
    void staleRunningIsFailed_newRunSucceeds() {
        SimulationResult stale = transactionTemplate.execute(tx ->
                simulationRepository.save(SimulationResult.builder()
                        .scenarioId(purchaseId)
                        .startedAt(Instant.now()
                                .minus(SimulationService.RUNNING_TTL
                                        .plusSeconds(60)))
                        .status("running").build()));
        SimulationRunDto view = simulationService.run(userId, projectId,
                purchaseId);
        assertThat(view.status()).isEqualTo("completed");
        SimulationResult reloaded = simulationRepository
                .findById(stale.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("failed");
    }

    // ------------------------------------------------------------------
    // Валидации: base - 400 (инвариант), чужой проект - 404
    // ------------------------------------------------------------------

    @Test
    void brokenInputs_markFailedWithReason() {
        // пустая покупка - 400 ДО записи running (валидация состава);
        // для провала ПОСЛЕ running: проект без параметров склада
        assertThatThrownBy(() -> simulationService.run(userId, projectId,
                emptyPurchaseId))
                .isInstanceOf(me.yuugao.robomatch.exception
                        .BadRequestException.class)
                .hasMessageContaining("Состав сценария пуст");

        // отдельный проект без параметров: running создан, расчёт упал
        Long[] brokenIds = transactionTemplate.execute(tx -> {
            ObjectType bare = objectTypeRepository.save(ObjectType
                    .builder().code("bare").name("Голый склад")
                    .isCalcEnabled(true).dataSourceNote("Т").build());
            Project broken = projectRepository.save(Project.builder()
                    .userId(userId).objectTypeId(bare.getId())
                    .name("Без параметров").status(
                            me.yuugao.robomatch.domain.ProjectStatus.DRAFT)
                    .build());
            Scenario purchase = scenarioRepository.save(Scenario.builder()
                    .projectId(broken.getId()).type(ScenarioType.PURCHASE)
                    .name("Покупка").build());
            scenarioSolutionRepository.save(ScenarioSolution.builder()
                    .scenarioId(purchase.getId())
                    .solutionId(solutionRepository.findAll().get(0).getId())
                    .quantity(1).isManual(false).build());
            return new Long[]{broken.getId(), purchase.getId()};
        });
        SimulationRunDto failed = simulationService.run(userId,
                brokenIds[0], brokenIds[1]);
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.error()).contains("параметры склада");
    }

    @Test
    void baseScenarioRejected_withHumanMessage() {
        assertThatThrownBy(() -> simulationService.run(userId, projectId,
                baseId))
                .isInstanceOf(me.yuugao.robomatch.exception
                        .BadRequestException.class)
                .hasMessageContaining("Базовый сценарий не содержит");
    }

    // ------------------------------------------------------------------
    // История и последний результат
    // ------------------------------------------------------------------

    @Test
    void foreignProject_notFound404() {
        assertThatThrownBy(() -> simulationService.run(otherUserId,
                projectId, purchaseId))
                .isInstanceOf(me.yuugao.robomatch.exception
                        .NotFoundException.class);
        assertThatThrownBy(() -> simulationService.latest(otherUserId,
                projectId, purchaseId))
                .isInstanceOf(me.yuugao.robomatch.exception
                        .NotFoundException.class);
        assertThatThrownBy(() -> simulationService.history(otherUserId,
                projectId))
                .isInstanceOf(me.yuugao.robomatch.exception
                        .NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // Сверка с расчётом экономики
    // ------------------------------------------------------------------

    @Test
    void historyAndLatest() {
        // порядок методов JUnit недетерминирован - сами создаём историю
        simulationService.run(userId, projectId, purchaseId);
        simulationService.run(userId, projectId, purchaseId);
        List<SimulationRunDto> history = simulationService.history(userId,
                projectId);
        assertThat(history.size()).isGreaterThanOrEqualTo(2);
        // свежие сверху
        assertThat(history.get(0).id())
                .isGreaterThan(history.get(history.size() - 1).id());
        SimulationRunDto latest = simulationService.latest(userId,
                projectId, purchaseId);
        assertThat(latest.id()).isEqualTo(history.get(0).id());
        assertThat(latest.status()).isEqualTo("completed");
    }

    /**
 * Сценарий сверки (отдельный - не ломает предупреждение «экономика
 * не рассчитана» в тестах purchaseId) с тем же составом 3× робота.
 */
    private synchronized Long crossScenario() {
        if (crossScenarioId != null) {
            return crossScenarioId;
        }
        crossScenarioId = transactionTemplate.execute(tx -> {
            Long sid = scenarioRepository.save(Scenario.builder()
                    .projectId(projectId).type(ScenarioType.PURCHASE)
                    .name("Сверка с экономикой").build()).getId();
            Solution robot = solutionRepository.findAll().get(0);
            scenarioSolutionRepository.save(ScenarioSolution.builder()
                    .scenarioId(sid).solutionId(robot.getId())
                    .quantity(3).isManual(false).build());
            return sid;
        });
        return crossScenarioId;
    }

    /**
 * Строка расчёта с metrics_json (последняя по calculated_at).
 */
    private void seedCalculation(Long scenarioId, String metricsJson) {
        transactionTemplate.executeWithoutResult(tx ->
                calculationRepository.save(Calculation.builder()
                        .scenarioId(scenarioId)
                        .versionData("cafe")
                        .versionModel("economic-model-1.0")
                        .calculatedAt(Instant.now())
                        .metricsJson(metricsJson)
                        .build()));
    }

    @Test
    void crossCheckWarnsWhenCompositionChangedSinceCalculation() {
        long sid = crossScenario();
        seedCalculation(sid, "{\"selectedRobots\": 5}");
        SimulationRunDto view = simulationService.run(userId, projectId, sid);
        // состав сценария - 3 робота, расчёт помнил 5
        assertThat(view.warnings())
                .anyMatch(w -> w.contains("Состав изменён")
                        && w.contains("5 ед.")
                        && w.contains("3 ед."));
    }

    @Test
    void crossCheckWarnsWhenFleetBelowRequiredRobots() {
        long sid = crossScenario();
        seedCalculation(sid,
                "{\"selectedRobots\": 3, \"requiredRobots\": 7}");
        SimulationRunDto view = simulationService.run(userId, projectId, sid);
        assertThat(view.warnings())
                .anyMatch(w -> w.contains("меньше требуемого")
                        && w.contains("7 ед."));
    }

    @Test
    void crossCheckUsesCalculationSnapshotOfAssumptions() {
        // предупреждение о P_effective обязано цитировать СНИМОК
        // допущений последнего расчёта, а не текущие допущения проекта
        // (у проекта P_nominal=90; снимок расчёта - 30)
        long sid = crossScenario();
        seedCalculation(sid, "{\"selectedRobots\": 3, "
                + "\"effectiveAssumptions\": {"
                + "\"robot_nominal_productivity_per_hour\": \"30\", "
                + "\"k_load\": \"0.75\", "
                + "\"k_availability\": \"1.0\"}}");
        SimulationRunDto view = simulationService.run(userId, projectId, sid);
        // модель: 30 оп/ч на робота; P_effective снимка = 30×0.75×1.0 = 22.5
        assertThat(view.warnings())
                .anyMatch(w -> w.contains("расходится с заложенной")
                        && w.contains("22.5"));
        // ложная атрибуция текущих допущений (90×0.75=67.5) исключена
        assertThat(view.warnings().stream()
                .filter(w -> w.contains("расходится с заложенной"))
                .toList())
                .noneMatch(w -> w.contains("67.5"));
        // достижимость KPI - по-прежнему к ТЕКУЩЕМУ P_nominal (90): 30/90
        assertThat(view.achievabilityPct()).isEqualByComparingTo("33.3");
    }

    @Test
    void crossCheckSilentWhenModelMatchesCalculation() {
        // согласованный расчёт: тот же состав, P_nominal из снимка подобран
        // к фактической мощности модели (30 оп/ч на робота = 30/1.0/1.0);
        // ВАЖНО: этот тест сеет САМУЮ ПОСЛЕДНЮЮ строку расчёта (Instant.now
        // при выполнении), поэтому предупреждение сверки гаснет
        long sid = crossScenario();
        seedCalculation(sid, "{\"selectedRobots\": 3, "
                + "\"effectiveAssumptions\": {"
                + "\"robot_nominal_productivity_per_hour\": \"30\", "
                + "\"k_load\": \"1.0\", "
                + "\"k_availability\": \"1.0\"}}");
        SimulationRunDto view = simulationService.run(userId, projectId, sid);
        assertThat(view.warnings())
                .noneMatch(w -> w.contains("расходится с заложенной")
                        || w.contains("меньше требуемого"));
        // состав согласован (3 == 3)
        assertThat(view.warnings())
                .noneMatch(w -> w.contains("Состав изменён"));
    }
}
