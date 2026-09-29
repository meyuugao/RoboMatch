package me.yuugao.robomatch.controller;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.repository.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест подбора:
 * полный HTTP-путь через SecurityFilterChain (/api/projects/** —
 * authenticated) до контроллера, сервисов и H2-БД.
 * <p>
 * Ключевые сценарии: 401 без токена на всех эндпоинтах, изоляция
 * (чужой проект — 404 на run/get/manual add), запуск подбора
 * (bootstrap 3 сценариев, статусы fit/excluded/needs_check, ранг,
 * Score и вклад критериев, недостающие данные), чтение результатов,
 * ручное добавление в сценарий (201/409/404/400 —).
 * <p>
 * Окружение как в ProjectControllerIT: H2 in-memory (PostgreSQL-режим),
 * Flyway выключен, create-drop от сущностей, HTTP — java.net.http.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:selectionit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelectionControllerIT {

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    int port;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ObjectTypeRepository objectTypeRepository;
    @Autowired
    private ObjectTypeIndustryRepository objectTypeIndustryRepository;
    @Autowired
    private ObjectTypeParameterRepository objectTypeParameterRepository;
    @Autowired
    private ParameterTypeRepository parameterTypeRepository;
    @Autowired
    private IndustryRepository industryRepository;
    @Autowired
    private VendorRepository vendorRepository;
    @Autowired
    private me.yuugao.robomatch.repository.SolutionTypeRepository solutionTypeRepository;
    @Autowired
    private SolutionRepository solutionRepository;
    @Autowired
    private SolutionApplicationRepository solutionApplicationRepository;
    @Autowired
    private SolutionCaseLinkRepository solutionCaseLinkRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private ScenarioSolutionRepository scenarioSolutionRepository;
    @Autowired
    private SelectionResultRepository selectionResultRepository;
    private Long warehouseTypeId;
    private Long fitSolutionId;
    private Long excludedSolutionId;
    private Long needsCheckSolutionId;
    private Long vendorId;

    @BeforeAll
    void seedFixtures() {
        transactionTemplate.executeWithoutResult(tx -> {
            ObjectType warehouse = objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse").name("Склад").isCalcEnabled(true)
                    .dataSourceNote("Тест").build());
            objectTypeRepository.save(ObjectType.builder()
                    .code("airport").name("Аэропорт").isCalcEnabled(false)
                    .dataSourceNote("Тест").build());
            warehouseTypeId = warehouse.getId();

            Industry industry = industryRepository.save(Industry.builder()
                    .code("trade_and_services").name("Торговля и услуги").build());
            objectTypeIndustryRepository.save(ObjectTypeIndustry.builder()
                    .objectTypeId(warehouse.getId()).industryId(industry.getId())
                    .sourceNote("assumptions.md §1").build());

            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name("ООО «ТестРобот»").build());
            me.yuugao.robomatch.domain.SolutionType robotType =
                    solutionTypeRepository.save(me.yuugao.robomatch.domain.SolutionType
                            .builder().code("mobile_robots").name("Мобильные роботы")
                            .build());

            // 8 параметров склада (worst-case,: метры — проходы
            otp("pallet_unit_weight", "Максимальная масса грузовой единицы (паллет), кг",
                    "1500");
            otp("floor_load_max_kg", "Максимальная нагрузка на пол (на точку опоры), кг",
                    "5000");
            otp("rack_aisle_width", "Минимальная ширина рабочих проходов между"
                    + " стеллажами, м", "1.5");
            otp("main_aisle_width", "Минимальная ширина главных проездов, м", "2.5");
            otp("storage_zone_ceiling_height", "Минимальная высота потолков в зоне"
                    + " хранения, м", "5");
            otp("required_positioning_accuracy_mm", "Требование к точности"
                    + " позиционирования (в самой требовательной операции), мм", "5");
            otp("available_power_capacity", "Минимальная доступная мощность для"
                    + " зарядки роботов, кВт", "100");
            otp("max_allowed_noise_dba", "Максимально допустимый уровень шума"
                    + " (в самой тихой зоне), дБА", "70");

            // решения: полный (fit), слабая грузоподъёмность (excluded),
            // без шума (needs_check)
            Solution fit = solutionRepository.save(robot("Робот Полный",
                    "1500", "1500", "900", "700", "300", "1", "1", "60", "2700000"));
            Solution weak = solutionRepository.save(robot("Робот Слабый",
                    "800", "1500", "900", "700", "300", "1", "1", "60", "1500000"));
            Solution noNoise = solutionRepository.save(robot("Робот БезШума",
                    "1500", "1500", "900", "700", "300", "1", "1", null, "900000"));
            fitSolutionId = fit.getId();
            excludedSolutionId = weak.getId();
            needsCheckSolutionId = noNoise.getId();
            for (Solution solution : List.of(fit, weak, noNoise)) {
                solutionApplicationRepository.save(SolutionApplication.builder()
                        .solutionId(solution.getId()).industryId(industry.getId())
                        .processId(1L).build());
            }
            solutionCaseLinkRepository.save(SolutionCaseLink.builder()
                    .solutionId(fit.getId()).caseId(1L).build());
        });
    }

    @AfterAll
    void cleanScenarios() {
        // сценарии/результаты разных тестов изолируются проектами —
        // глобальной очистки не требуется; закрываем клиент
        client.close();
    }

    private Solution robot(String name, String payload, String mass, String length,
                           String width, String height, String accuracy,
                           String charging, String noise, String price) {
        return Solution.builder()
                .name(name).vendorId(vendorId()).productClass("brs")
                .solutionTypeId(solutionTypeId())
                .status("operation").priceRub(new BigDecimal(price)).trl((short) 8)
                .sourceKind("organizer_catalog").sourceUrl("catalog_export_v4.csv")
                .payloadKg(new BigDecimal(payload)).massKg(new BigDecimal(mass))
                .lengthMm(new BigDecimal(length)).widthMm(new BigDecimal(width))
                .heightMm(new BigDecimal(height))
                .positioningAccuracyMm(new BigDecimal(accuracy))
                .chargingPowerKw(new BigDecimal(charging))
                .noiseLevelDba(noise == null ? null : new BigDecimal(noise))
                .build();
    }

    private Long vendorId() {
        return vendorRepository.findAll().get(0).getId();
    }

    private Long solutionTypeId() {
        return solutionTypeRepository.findAll().get(0).getId();
    }

    private void otp(String code, String name, String defaultValue) {
        ParameterType type = parameterTypeRepository.save(ParameterType.builder()
                .code(code).name(name).unit("ед")
                .valueType(ParameterValueType.NUMBER).build());
        objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                .objectTypeId(warehouseTypeId).parameterTypeId(type.getId())
                .groupName("Тест").isRequired(false)
                .defaultValueNumeric(new BigDecimal(defaultValue))
                .sourceNote("Тест").build());
    }

    // ------------------------------------------------------------------
    // Хелперы HTTP
    // ------------------------------------------------------------------

    private String registerAndLogin() throws Exception {
        String login = "sel_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = post("/api/auth/register", null,
                "{\"login\":\"" + login + "\",\"password\":\"password123\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    private Long createProject(String token) throws Exception {
        HttpResponse<String> response = post("/api/projects", token,
                "{\"name\":\"Склад " + UUID.randomUUID().toString().substring(0, 6)
                        + "\",\"objectTypeId\":" + warehouseTypeId + "}");
        assertThat(response.statusCode()).isEqualTo(201);
        return ((Integer) JsonPath.read(response.body(), "$.id")).longValue();
    }

    private HttpResponse<String> post(String path, String token, String json)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------
    // 401 / изоляция
    // ------------------------------------------------------------------

    @Test
    void run_401_withoutToken() throws Exception {
        assertThat(post("/api/projects/1/selection/run", null, "").statusCode())
                .isEqualTo(401);
    }

    @Test
    void get_401_withoutToken() throws Exception {
        assertThat(get("/api/projects/1/selection", null).statusCode()).isEqualTo(401);
    }

    @Test
    void manualAdd_401_withoutToken() throws Exception {
        assertThat(post("/api/projects/1/scenarios/1/solutions", null,
                "{\"solutionId\":1,\"manualReason\":\"причина\"}").statusCode())
                .isEqualTo(401);
    }

    @Test
    void run_404_foreignProject() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);
        // чужой пользователь не видит проект Alice
        String other = registerAndLogin();
        assertThat(post("/api/projects/" + projectId + "/selection/run", other, "")
                .statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + projectId + "/selection", other).statusCode())
                .isEqualTo(404);
        assertThat(post("/api/projects/" + projectId
                        + "/scenarios/1/solutions", other,
                "{\"solutionId\":1,\"manualReason\":\"причина\"}").statusCode())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Запуск и чтение
    // ------------------------------------------------------------------

    @Test
    void run_200_scenariosStatusesRankScoreExplanations() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);

        HttpResponse<String> response =
                post("/api/projects/" + projectId + "/selection/run", token, "");
        assertThat(response.statusCode()).isEqualTo(200);

        // bootstrap: 3 сценария с типами base/purchase/raas
        List<String> types = JsonPath.read(response.body(),
                "$.scenarios[*].type");
        assertThat(types).containsExactly("base", "purchase", "raas");

        // статусы: fit (полный), excluded (груз 800 < 1500), needs_check (шум NULL)
        assertThat(statusOf(response, fitSolutionId)).isEqualTo("fit");
        assertThat(statusOf(response, excludedSolutionId)).isEqualTo("excluded");
        assertThat(statusOf(response, needsCheckSolutionId)).isEqualTo("needs_check");

        // fit: ранг 1, Score, вклад критериев (вес + нормированное + вклад)
        assertThat((Integer) fieldOf(response, fitSolutionId, "rank")).isEqualTo(1);
        assertThat((Double) fieldOf(response, fitSolutionId, "score"))
                .isEqualTo(1.0);
        List<String> criterionCodes = JsonPath.read(response.body(),
                "$.results[?(@.solutionId==" + fitSolutionId
                        + ")].criteriaContribution[*].code");
        assertThat(criterionCodes).contains("price", "payload", "dimensions", "trl",
                "cases", "accuracy", "charging");
        // excluded: причина с нарушением, без ранга/score/вклада
        String reason = (String) fieldOf(response, excludedSolutionId, "reason");
        assertThat(reason).contains("грузоподъёмность");
        // needs_check: недостающие данные
        List<String> missing = JsonPath.read(response.body(),
                "$.results[?(@.solutionId==" + needsCheckSolutionId
                        + ")].missingData[*]");
        assertThat(missing).anyMatch(m -> m.contains("уровень шума"));

        // persisted: selection_result + scenario_solution нет (только сценарии)
        assertThat(selectionResultRepository.findAllByProjectId(projectId))
                .hasSize(3);
        List<Scenario> scenarios =
                scenarioRepository.findAllByProjectIdOrderByIdAsc(projectId);
        assertThat(scenarios).hasSize(3);
        assertThat(scenarios.get(0).getType()).isEqualTo(ScenarioType.BASE);
        assertThat(scenarios.get(0).getName()).isEqualTo("Текущий процесс без роботизации");

        // повторный запуск идемпотентен: те же 3 сценария, те же 3 результата
        HttpResponse<String> again =
                post("/api/projects/" + projectId + "/selection/run", token, "");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(selectionResultRepository.findAllByProjectId(projectId))
                .hasSize(3);
        assertThat(scenarioRepository.findAllByProjectIdOrderByIdAsc(projectId))
                .hasSize(3);
    }

    @Test
    void get_200_afterRun_and_neverRunEmpty() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);

        // до запуска — пустой список
        HttpResponse<String> before = get("/api/projects/" + projectId + "/selection",
                token);
        assertThat(before.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(before.body(), "$.results.length()"))
                .isEqualTo(0);

        post("/api/projects/" + projectId + "/selection/run", token, "");

        HttpResponse<String> after = get("/api/projects/" + projectId + "/selection",
                token);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(after.body(), "$.results.length()"))
                .isEqualTo(3);
        assertThat(statusOf(after, fitSolutionId)).isEqualTo("fit");
    }

    @Test
    void get_404_unknownProject() throws Exception {
        String token = registerAndLogin();
        assertThat(get("/api/projects/999999/selection", token).statusCode())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Ручное добавление
    // ------------------------------------------------------------------

    @Test
    void manualAdd_201_and_409_onDuplicate() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);
        post("/api/projects/" + projectId + "/selection/run", token, "");
        Long purchaseScenarioId = scenarioRepository
                .findAllByProjectIdOrderByIdAsc(projectId).stream()
                .filter(s -> s.getType() == ScenarioType.PURCHASE)
                .findFirst().orElseThrow().getId();

        // fit-решение — «Добавить в сценарий»
        HttpResponse<String> added = post("/api/projects/" + projectId
                        + "/scenarios/" + purchaseScenarioId + "/solutions",
                token, "{\"solutionId\":" + fitSolutionId
                        + ",\"manualReason\":\"Основной кандидат для пилота\"}");
        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(purchaseScenarioId, fitSolutionId))
                .hasValueSatisfying(row -> {
                    assertThat(row.getIsManual()).isTrue();
                    assertThat(row.getManualReason())
                            .isEqualTo("Основной кандидат для пилота");
                    assertThat(row.getQuantity()).isEqualTo(1);
                });

        // дубликат — 409
        HttpResponse<String> duplicate = post("/api/projects/" + projectId
                        + "/scenarios/" + purchaseScenarioId + "/solutions",
                token, "{\"solutionId\":" + fitSolutionId
                        + ",\"manualReason\":\"ещё раз\"}");
        assertThat(duplicate.statusCode()).isEqualTo(409);
    }

    @Test
    void manualAdd_201_excludedSolution_withWarningSemantics() throws Exception {
        //
        String token = registerAndLogin();
        Long projectId = createProject(token);
        post("/api/projects/" + projectId + "/selection/run", token, "");
        Long raasScenarioId = scenarioRepository
                .findAllByProjectIdOrderByIdAsc(projectId).stream()
                .filter(s -> s.getType() == ScenarioType.RAAS)
                .findFirst().orElseThrow().getId();

        HttpResponse<String> added = post("/api/projects/" + projectId
                        + "/scenarios/" + raasScenarioId + "/solutions",
                token, "{\"solutionId\":" + excludedSolutionId
                        + ",\"manualReason\":\"Планируем заменить паллеты на лёгкие\"}");
        assertThat(added.statusCode()).isEqualTo(201);
    }

    @Test
    void manualAdd_404_unknownScenarioOrSolution() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);
        post("/api/projects/" + projectId + "/selection/run", token, "");

        // несуществующий сценарий
        assertThat(post("/api/projects/" + projectId
                        + "/scenarios/999999/solutions", token,
                "{\"solutionId\":" + fitSolutionId + ",\"manualReason\":\"причина\"}")
                .statusCode()).isEqualTo(404);
        // несуществующее решение
        Long scenarioId = scenarioRepository
                .findAllByProjectIdOrderByIdAsc(projectId).stream()
                .filter(s -> s.getType() == ScenarioType.PURCHASE).findFirst()
                .orElseThrow().getId();
        assertThat(post("/api/projects/" + projectId
                        + "/scenarios/" + scenarioId + "/solutions", token,
                "{\"solutionId\":999999,\"manualReason\":\"причина\"}")
                .statusCode()).isEqualTo(404);
    }

    @Test
    void manualAdd_400_blankReason() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);
        post("/api/projects/" + projectId + "/selection/run", token, "");
        Long scenarioId = scenarioRepository
                .findAllByProjectIdOrderByIdAsc(projectId).stream()
                .filter(s -> s.getType() == ScenarioType.PURCHASE).findFirst()
                .orElseThrow().getId();

        HttpResponse<String> response = post("/api/projects/" + projectId
                        + "/scenarios/" + scenarioId + "/solutions", token,
                "{\"solutionId\":" + fitSolutionId + ",\"manualReason\":\"   \"}");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Утилиты ответа
    // ------------------------------------------------------------------

    private String statusOf(HttpResponse<String> response, long solutionId) {
        List<String> list = JsonPath.read(response.body(),
                "$.results[?(@.solutionId==" + solutionId + ")].status");
        return list.get(0);
    }

    private Object fieldOf(HttpResponse<String> response, long solutionId,
                           String field) {
        List<Object> list = JsonPath.read(response.body(),
                "$.results[?(@.solutionId==" + solutionId + ")]." + field);
        return list.get(0);
    }

    @Test
    void manualAdd_404_scenarioOfAnotherProjectOfSameUser() throws Exception {
        // сценарий проекта B подставлен в URL проекта A —
        // даже тот же пользователь получает 404 (сценарий «не найден
        // в этом проекте»), изоляция по проекту
        String token = registerAndLogin();
        Long projectA = createProject(token);
        Long projectB = createProject(token);
        post("/api/projects/" + projectB + "/selection/run", token, "");
        Long scenarioB = scenarioRepository.findAllByProjectIdOrderByIdAsc(projectB)
                .get(0).getId();

        HttpResponse<String> response = post("/api/projects/" + projectA
                        + "/scenarios/" + scenarioB + "/solutions", token,
                "{\"solutionId\":" + fitSolutionId
                        + ",\"manualReason\":\"причина\"}");
        assertThat(response.statusCode()).isEqualTo(404);
        // и ничего не записано в сценарий проекта B
        assertThat(scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(scenarioB, fitSolutionId)).isEmpty();
    }

    @Test
    void run_responseContainsSolutionTypeName() throws Exception {
        //
        String token = registerAndLogin();
        Long projectId = createProject(token);
        HttpResponse<String> response =
                post("/api/projects/" + projectId + "/selection/run", token, "");
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> types = com.jayway.jsonpath.JsonPath.read(response.body(),
                "$.results[?(@.solutionId==" + fitSolutionId + ")].solutionTypeName");
        assertThat(types).first().isEqualTo("Мобильные роботы");
    }
}
