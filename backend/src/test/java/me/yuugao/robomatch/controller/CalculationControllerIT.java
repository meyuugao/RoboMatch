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
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест расчёта экономики (,
 * 3.1.5, 3.5.4, 4.4.3): полный HTTP-путь через SecurityFilterChain до
 * контроллера, сервисов и H2-БД.
 * <p>
 * Ключевые сценарии: POST /calculate → 200 (базовый и покупка), append-only
 * (два вызова - две строки, версия данных стабильна при тех же входах),
 * снимок допущений расчёта, PUT /assumptions → новый расчёт с новым
 * снимком, GET /compare - 3 сценария с дельтами и чувствительностью,
 * POST /adjust - новая строка расчёта + manual_adjustment, 400 при
 * отсутствии обязательных параметров (assumptions.md §2) и для типа
 * объекта без расчёта (is_calc_enabled).
 * <p>
 * Фикстуры: склад с 26 обязательными параметрами экономики (дефолты
 * датасета), робот с ТТХ (срок службы 6 лет, зарядка 1.35 кВт) в составе
 * сценария покупки (3 ед. по 2 500 000 руб.).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:calcit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CalculationControllerIT {

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    int port;
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
    private ProjectRepository projectRepository;
    private Long warehouseTypeId;
    private Long warehouseNoDefaultsTypeId;
    private Long airportTypeId;
    private Long solutionId;

    @BeforeAll
    void seedFixtures() {
        transactionTemplate.executeWithoutResult(tx -> {
            ObjectType warehouse = objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse").name("Склад").isCalcEnabled(true)
                    .dataSourceNote("Тест").build());
            warehouseTypeId = warehouse.getId();
            // Тот же набор, но один обязательный параметр без дефолта и без
            // значения - кейс 400 «не заполнены обязательные»
            ObjectType broken = objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse_broken").name("Склад без дефолта")
                    .isCalcEnabled(true).dataSourceNote("Тест").build());
            warehouseNoDefaultsTypeId = broken.getId();
            ObjectType airport = objectTypeRepository.save(ObjectType.builder()
                    .code("airport").name("Аэропорт").isCalcEnabled(false)
                    .dataSourceNote("Тест").build());
            airportTypeId = airport.getId();

            // 26 обязательных параметров экономики склада (assumptions §2)
            // + горизонт (опциональный); дефолты - датасет организатора
            String[][] params = {
                    {"total_warehouse_area", "20000"},
                    {"active_zone_area", "10000"},
                    {"shifts_per_day", "2"},
                    {"working_days_per_year", "365"},
                    {"shift_duration", "11"},
                    {"peak_load_factor", "1.5"},
                    {"inbound_pallets_per_day", "1000"},
                    {"outbound_pallets_per_day", "1000"},
                    {"picking_lines_per_day", "100000"},
                    {"picking_units_per_day", "150000"},
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
                    {"payback_horizon", "5"},
            };
            for (String[] p : params) {
                otp(warehouse.getId(), p[0], p[1]);
                // у «битого» типа паллетоместа - БЕЗ дефолта (отдельно ниже)
                if (!"pallet_positions".equals(p[0])) {
                    otp(broken.getId(), p[0], p[1]);
                }
            }
            // текстовые обязательные (тип стеллажной системы, габариты паллеты)
            for (Long typeId : List.of(warehouse.getId(), broken.getId())) {
                otpText(typeId, "racking_type", "Фронтальные");
                otpText(typeId, "pallet_dimensions", "1200x800x1600");
                otp(typeId, "main_aisle_width", "3.5");
                otp(typeId, "rack_aisle_width", "2.8");
                otp(typeId, "storage_zone_ceiling_height", "10");
            }
            // у «битого» типа паллетоместа без дефолта → расчёт должен
            // ответить 400 со списком недостающего
            otp(broken.getId(), "pallet_positions", null);

            // робот: цена 2.5М, ТТХ - срок службы 6 лет, зарядка 1.35 кВт
            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name("ООО «ТестРобот»").build());
            Solution robot = solutionRepository.save(Solution.builder()
                    .name("Робот Тестовый").vendorId(vendor.getId())
                    .productClass("brs").status("operation")
                    .priceRub(new BigDecimal("2500000")).trl((short) 9)
                    .sourceKind("organizer_catalog")
                    .sourceUrl("catalog_export_v4.csv")
                    .chargingPowerKw(new BigDecimal("1.35")).build());
            solutionId = robot.getId();
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
        });
    }

    @AfterAll
    void close() {
        client.close();
    }

    // ------------------------------------------------------------------
    // Фикстуры
    // ------------------------------------------------------------------

    private void otp(Long typeId, String code, String defaultValue) {
        // ParameterType один на код (UNIQUE); определение объекта - своё
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

    // ------------------------------------------------------------------
    // Хелперы HTTP
    // ------------------------------------------------------------------

    private String registerAndLogin() throws Exception {
        String login = "eco_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = post("/api/auth/register", null,
                "{\"login\":\"" + login + "\",\"password\":\"password123\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    /**
 * Id сценария по типу (фильтр читается списком - надёжно в Jayway).
 */
    private Integer scenarioIdByType(String body, String type) {
        List<Map<String, Object>> rows = JsonPath.read(body,
                "$[?(@.type == '" + type + "')]");
        return (Integer) rows.get(0).get("id");
    }

    private Long createProject(String token, Long typeId) throws Exception {
        HttpResponse<String> response = post("/api/projects", token,
                "{\"name\":\"Склад " + UUID.randomUUID().toString().substring(0, 6)
                        + "\",\"objectTypeId\":" + typeId + "}");
        assertThat(response.statusCode()).isEqualTo(201);
        return ((Integer) JsonPath.read(response.body(), "$.id")).longValue();
    }

    /**
 * Проект с 3 сценариями и составом покупки (3 робота).
 */
    private Long projectWithScenarios(String token) throws Exception {
        Long projectId = createProject(token, warehouseTypeId);
        assertThat(post("/api/projects/" + projectId + "/scenarios", token, "")
                .statusCode()).isEqualTo(201);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        HttpResponse<String> updated = put(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId, token,
                "{\"solutions\":[{\"solutionId\":" + solutionId
                        + ",\"quantity\":3}]}");
        assertThat(updated.statusCode()).isEqualTo(200);
        return projectId;
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

    private HttpResponse<String> put(String path, String token, String json)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json));
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
    // Доступ и изоляция
    // ------------------------------------------------------------------

    @Test
    void noToken_returns401() throws Exception {
        assertThat(post("/api/projects/1/scenarios/1/calculate", null, "")
                .statusCode()).isEqualTo(401);
        assertThat(get("/api/projects/1/compare", null).statusCode())
                .isEqualTo(401);
        assertThat(get("/api/projects/1/assumptions", null).statusCode())
                .isEqualTo(401);
        assertThat(get("/api/projects/1/scenarios/1/calculations", null)
                .statusCode()).isEqualTo(401);
    }

    @Test
    void foreignProject_returns404() throws Exception {
        String owner = registerAndLogin();
        Long projectId = projectWithScenarios(owner);
        // состав читаем владельцем; чужой - только для проверок 404
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", owner);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        String stranger = registerAndLogin();
        assertThat(post("/api/projects/" + projectId + "/scenarios/" + purchaseId
                + "/calculate", stranger, "").statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + projectId + "/compare", stranger)
                .statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + projectId + "/assumptions", stranger)
                .statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + projectId + "/scenarios/" + purchaseId
                + "/calculations", stranger).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Расчёт: базовый и покупка
    // ------------------------------------------------------------------

    @Test
    void calculateBase_200_zeroCapex() throws Exception {
        String token = registerAndLogin();
        Long projectId = projectWithScenarios(token);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer baseId = scenarioIdByType(list.body(), "base");
        HttpResponse<String> response = post(
                "/api/projects/" + projectId + "/scenarios/" + baseId
                        + "/calculate", token, "");
        assertThat(response.statusCode()).isEqualTo(200);
        // Базовый сценарий: CAPEX 0, OPEX = ФОТ контура (§1.1):
        // (100×100000 + 25×120000) × 12 × 1.302 = 203 112 000
        assertThat(((Number) JsonPath.read(response.body(), "$.totalCapex"))
                .doubleValue()).isEqualTo(0.0);
        assertThat(((Number) JsonPath.read(response.body(), "$.totalOpex"))
                .doubleValue()).isEqualTo(203112000.0);
        assertThat(((Number) JsonPath.read(response.body(), "$.effectYear"))
                .doubleValue()).isEqualTo(0.0);
        // Версии заполнены
        assertThat(JsonPath.read(response.body(), "$.versionModel").toString())
                .isEqualTo("economic-model-1.0");
        assertThat(((String) JsonPath.read(response.body(), "$.versionData"))
                .length()).isEqualTo(12);
        // Снимок допущений непуст (§10.9)
        assertThat((Integer) JsonPath.read(response.body(),
                "$.assumptions.length()")).isPositive();
    }

    @Test
    void calculatePurchase_200_metricsAndSensitivity() throws Exception {
        String token = registerAndLogin();
        Long projectId = projectWithScenarios(token);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        HttpResponse<String> response = post(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId
                        + "/calculate", token, "");
        assertThat(response.statusCode()).isEqualTo(200);
        // CAPEX = 12 416 250 (3 × 2.5М + статьи + резерв 10%)
        assertThat(((Number) JsonPath.read(response.body(), "$.totalCapex"))
                .doubleValue()).isEqualTo(12416250.0);
        // Эффект = 203 112 000 − 1 873 151 − 2 069 375 = 199 169 474
        assertThat(((Number) JsonPath.read(response.body(), "$.effectYear"))
                .doubleValue()).isEqualTo(199169474.0);
        // Окупаемость положительная (0.1), ROI за 5 лет:
        // 199 169 474 × 5 / 12 416 250 × 100 = 8020.5%
        assertThat(((Number) JsonPath.read(response.body(), "$.paybackYears"))
                .doubleValue()).isEqualTo(0.1);
        assertThat(((Number) JsonPath.read(response.body(), "$.roiPct"))
                .doubleValue()).isEqualTo(8020.5);
        // Чувствительность: 3 параметра × 5 шагов
        assertThat((Integer) JsonPath.read(response.body(),
                "$.details.sensitivity.length()")).isEqualTo(15);
        // Выбранный состав - из scenario_solution;
        // P_nominal в проекте не задан - requiredRobots отсутствует
        assertThat((Integer) JsonPath.read(response.body(),
                "$.details.selectedRobots")).isEqualTo(3);
        // Все параметры объекта сохранены в details
        // воспроизведение расчёта по входам)
        assertThat((Integer) JsonPath.read(response.body(),
                "$.details.inputs.parameters.size()")).isPositive();
        assertThat((String) JsonPath.read(response.body(),
                "$.details.inputs.parameters.pickers_count")).isNotNull();
    }

    @Test
    void calculate_appendOnly_twoCallsTwoRows() throws Exception {
        String token = registerAndLogin();
        Long projectId = projectWithScenarios(token);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        String path = "/api/projects/" + projectId + "/scenarios/" + purchaseId
                + "/calculate";
        HttpResponse<String> first = post(path, token, "");
        assertThat(first.statusCode()).isEqualTo(200);
        HttpResponse<String> second = post(path, token, "");
        assertThat(second.statusCode()).isEqualTo(200);
        Integer id1 = JsonPath.read(first.body(), "$.id");
        Integer id2 = JsonPath.read(second.body(), "$.id");
        // append-only: два вызова - две РАЗНЫЕ строки
        assertThat(id1).isNotEqualTo(id2);
        // Входы не менялись - версия данных совпадает (воспроизведение)
        assertThat((String) JsonPath.read(first.body(), "$.versionData"))
                .isEqualTo(JsonPath.read(second.body(), "$.versionData"));
        // История: свежие сверху, обе строки
        HttpResponse<String> history = get(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId
                        + "/calculations", token);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(history.body(), "$.length()"))
                .isEqualTo(2);
        assertThat((Integer) JsonPath.read(history.body(), "$[0].id"))
                .isEqualTo(id2);
    }

    // ------------------------------------------------------------------
    // Допущения: PUT меняет снимок следующего расчёта
    // ------------------------------------------------------------------

    @Test
    void assumptions_updateAndSnapshot() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, warehouseTypeId);
        // Дефолты каталога видны (assumptions §22-23)
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/assumptions", token);
        assertThat(list.statusCode()).isEqualTo(200);
        List<Map<String, Object>> kload = JsonPath.read(list.body(),
                "$[?(@.name == 'k_load')]");
        assertThat((String) kload.get(0).get("value")).isEqualTo("0.75");
        // Изменение допущения
        HttpResponse<String> updated = put(
                "/api/projects/" + projectId + "/assumptions", token,
                "{\"values\":{\"k_load\":\"0.8\"}}");
        assertThat(updated.statusCode()).isEqualTo(200);
        kload = JsonPath.read(updated.body(), "$[?(@.name == 'k_load')]");
        assertThat((String) kload.get(0).get("value")).isEqualTo("0.8");
        // Неизвестный код - 400; зафиксированное организатором - 400
        assertThat(put("/api/projects/" + projectId + "/assumptions", token,
                "{\"values\":{\"magic\":\"1\"}}").statusCode()).isEqualTo(400);
        assertThat(put("/api/projects/" + projectId + "/assumptions", token,
                "{\"values\":{\"reserve_rate\":\"0.5\"}}").statusCode())
                .isEqualTo(400);
        // Вне диапазона - 400
        assertThat(put("/api/projects/" + projectId + "/assumptions", token,
                "{\"values\":{\"k_load\":\"0.95\"}}").statusCode()).isEqualTo(400);
        // Сброс в дефолт: null
        HttpResponse<String> reset = put(
                "/api/projects/" + projectId + "/assumptions", token,
                "{\"values\":{\"k_load\":null}}");
        kload = JsonPath.read(reset.body(), "$[?(@.name == 'k_load')]");
        assertThat((String) kload.get(0).get("value")).isEqualTo("0.75");
    }

    // ------------------------------------------------------------------
    // Ручная корректировка
    // ------------------------------------------------------------------

    @Test
    void adjust_createsNewCalculationAndAdjustmentRecord() throws Exception {
        String token = registerAndLogin();
        Long projectId = projectWithScenarios(token);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        HttpResponse<String> calculated = post(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId
                        + "/calculate", token, "");
        Integer sourceId = JsonPath.read(calculated.body(), "$.id");

        HttpResponse<String> adjusted = post(
                "/api/projects/" + projectId + "/calculations/" + sourceId
                        + "/adjust", token,
                "{\"metricName\":\"effect_year\",\"newValue\":190000000,"
                        + "\"reason\":\"Согласована скидка на сервис\"}");
        assertThat(adjusted.statusCode()).isEqualTo(200);
        Integer newId = JsonPath.read(adjusted.body(), "$.id");
        // Новый расчёт - отдельная строка (append-only, §10.10)
        assertThat(newId).isNotEqualTo(sourceId);
        assertThat(((Number) JsonPath.read(adjusted.body(), "$.effectYear"))
                .doubleValue()).isEqualTo(190000000.0);
        // Остальные метрики не тронуты
        assertThat(((Number) JsonPath.read(adjusted.body(), "$.totalCapex"))
                .doubleValue()).isEqualTo(12416250.0);
        // В деталях нового расчёта - происхождение корректировки
        assertThat((String) JsonPath.read(adjusted.body(),
                "$.details.adjustedFrom.metricName")).isEqualTo("effect_year");
        // Зеркальное поле details синхронизировано с метрикой
        // (разбивка не противоречит заголовку)
        assertThat(((Number) JsonPath.read(adjusted.body(),
                "$.details.effectYear")).doubleValue())
                .isEqualTo(190000000.0);
        // У ИСХОДНОГО расчёта - запись о вмешательстве (что/было/стало/
        // почему/кто/когда)
        HttpResponse<String> source = get(
                "/api/projects/" + projectId + "/calculations/" + sourceId,
                token);
        assertThat(source.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(source.body(),
                "$.adjustments[0].metricName")).isEqualTo("effect_year");
        assertThat(((Number) JsonPath.read(source.body(),
                "$.adjustments[0].newValue")).doubleValue())
                .isEqualTo(190000000.0);
        assertThat((String) JsonPath.read(source.body(),
                "$.adjustments[0].reason")).contains("скидка");
        // Совпадающее значение - 400; неизвестная метрика - 400
        assertThat(post("/api/projects/" + projectId + "/calculations/"
                        + sourceId + "/adjust", token,
                "{\"metricName\":\"effect_year\",\"newValue\":"
                        + ((Number) JsonPath.read(source.body(),
                        "$.effectYear")).longValue()
                        + ",\"reason\":\"Нет изменения\"}").statusCode())
                .isEqualTo(400);
        assertThat(post("/api/projects/" + projectId + "/calculations/"
                        + sourceId + "/adjust", token,
                "{\"metricName\":\"magic\",\"newValue\":1,"
                        + "\"reason\":\"?\"}").statusCode()).isEqualTo(400);
        // Экстремальное значение - 400 (@Digits: не больше 16 разрядов,
        // numeric(18,4) не переполняется через adjust)
        assertThat(post("/api/projects/" + projectId + "/calculations/"
                        + sourceId + "/adjust", token,
                "{\"metricName\":\"effect_year\",\"newValue\":1e20,"
                        + "\"reason\":\"Слишком много\"}").statusCode())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Сравнение
    // ------------------------------------------------------------------

    @Test
    void compare_threeScenariosWithDeltas() throws Exception {
        String token = registerAndLogin();
        Long projectId = projectWithScenarios(token);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        // Интерпретация ПУСТОГО состава имеет
        // приоритет над веткой RaaS без выкупа - чтобы этот тест проверял
        // именно «undefined» (CAPEX = 0, эффект положителен), даём RaaS
        // тот же состав; пустой состав покрыт отдельным сервисным тестом
        // EconomicCalculationServiceTest.emptyComposition_interpretation
        Integer raasScenarioId = scenarioIdByType(list.body(), "raas");
        assertThat(put("/api/projects/" + projectId + "/scenarios/"
                        + raasScenarioId, token,
                "{\"solutions\":[{\"solutionId\":" + solutionId
                        + ",\"quantity\":3}]}").statusCode())
                .isEqualTo(200);
        for (String type : List.of("base", "purchase", "raas")) {
            Integer scenarioId = scenarioIdByType(list.body(), type);
            assertThat(post("/api/projects/" + projectId + "/scenarios/"
                    + scenarioId + "/calculate", token, "").statusCode())
                    .isEqualTo(200);
        }
        HttpResponse<String> compare = get(
                "/api/projects/" + projectId + "/compare", token);
        assertThat(compare.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(compare.body(),
                "$.scenarios.length()")).isEqualTo(3);
        // Все три рассчитаны
        for (int i = 0; i < 3; i++) {
            assertThat((Boolean) JsonPath.read(compare.body(),
                    "$.scenarios[" + i + "].calculated")).isTrue();
        }
        // Покупка: Δ CAPEX к базовому = 12 416 250 (у базы 0)
        Integer purchaseIdx = null;
        Integer raasIdx = null;
        for (int i = 0; i < 3; i++) {
            String type = JsonPath.read(compare.body(),
                    "$.scenarios[" + i + "].type");
            if ("purchase".equals(type)) {
                purchaseIdx = i;
            } else if ("raas".equals(type)) {
                raasIdx = i;
            }
        }
        assertThat(purchaseIdx).isNotNull();
        assertThat(raasIdx).isNotNull();
        assertThat(((Number) JsonPath.read(compare.body(),
                "$.scenarios[" + purchaseIdx + "].capexDeltaToBase"))
                .doubleValue()).isEqualTo(12416250.0);
        // Интерпретация окупаемости покупки: быстрая (0.1 < 3 лет)
        assertThat((String) JsonPath.read(compare.body(),
                "$.scenarios[" + purchaseIdx + "].payback.category"))
                .isEqualTo("fast");
        // RaaS без выкупа: CAPEX 0, окупаемость не определена,
        // сравнение по эффекту и TCO (§2.11)
        assertThat(((Number) JsonPath.read(compare.body(),
                "$.scenarios[" + raasIdx + "].capex")).doubleValue())
                .isEqualTo(0.0);
        assertThat((String) JsonPath.read(compare.body(),
                "$.scenarios[" + raasIdx + "].payback.category"))
                .isEqualTo("undefined");
        // Чувствительность в сравнении: покупка - 15 строк
        assertThat((Integer) JsonPath.read(compare.body(),
                "$.scenarios[" + purchaseIdx + "].sensitivity.length()"))
                .isEqualTo(15);
    }

    @Test
    void compare_beforeCalculation_marksNotCalculated() throws Exception {
        String token = registerAndLogin();
        Long projectId = projectWithScenarios(token);
        HttpResponse<String> compare = get(
                "/api/projects/" + projectId + "/compare", token);
        assertThat(compare.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(compare.body(),
                "$.scenarios.length()")).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat((Boolean) JsonPath.read(compare.body(),
                    "$.scenarios[" + i + "].calculated")).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Валидация входов (§6: список недостающего; гейт типа объекта)
    // ------------------------------------------------------------------

    @Test
    void calculate_missingRequiredParam_400withList() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, warehouseNoDefaultsTypeId);
        assertThat(post("/api/projects/" + projectId + "/scenarios", token, "")
                .statusCode()).isEqualTo(201);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        HttpResponse<String> response = post(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId
                        + "/calculate", token, "");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .contains("pallet_positions");
    }

    @Test
    void calculate_nonWarehouseType_400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, airportTypeId);
        HttpResponse<String> response = post(
                "/api/projects/" + projectId + "/scenarios", token, "");
        // Сценарии создать можно (сущность общая), расчёт - гейт
        assertThat(response.statusCode()).isEqualTo(201);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer baseId = scenarioIdByType(list.body(), "base");
        HttpResponse<String> calculate = post(
                "/api/projects/" + projectId + "/scenarios/" + baseId
                        + "/calculate", token, "");
        assertThat(calculate.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(calculate.body(), "$.message"))
                .contains("недоступен");
    }

    @Test
    void calculate_unknownScenario_404() throws Exception {
        String token = registerAndLogin();
        Long projectId = projectWithScenarios(token);
        assertThat(post("/api/projects/" + projectId + "/scenarios/999999"
                + "/calculate", token, "").statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + projectId + "/calculations/999999",
                token).statusCode()).isEqualTo(404);
    }
}
