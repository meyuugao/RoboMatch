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

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест имитации (,
 * 2.1.4, 3.7.4, 4.3.3, 4.4.3): полный HTTP-путь через SecurityFilterChain.
 *
 * <p>Сценарии: 401 без токена; 404 чужого проекта; 400 не-склад; 400
 * base (инвариант); 400 пустой состав; 200 run → completed с 6 KPI;
 * GET последнего результата; история по проекту; сохранение схемы
 * - POST export → 200 + exportUrl, GET файла → SVG без
 * скриптов (санитизация).
 *
 * <p>Фикстура: склад с 5 параметрами имитации, робот 1,5 м/с в составе
 * покупки (3 ед.), P_nominal 90 - переопределением допущения.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "app.simulation.root=./build/test-simulations-it",
        "spring.datasource.url=jdbc:h2:mem:simit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationControllerIT {

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
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectAssumptionRepository assumptionRepository;
    private Long warehouseTypeId;
    private Long airportTypeId;
    private Long solutionId;

    @BeforeAll
    void seedFixtures() throws IOException {
        // Остатки прошлых прогонов: H2 in-memory перезапускает id с 1,
        // а файлы в build/test-simulations-it переживают gradle test -
        // старые {projectId}/{simulationId}.svg сталкиваются с новыми
        // id (GET до сохранения обязан быть 404). Чистим корень класса.
        Path storageRoot = Path.of("./build/test-simulations-it");
        if (Files.exists(storageRoot)) {
            try (Stream<Path> paths = Files.walk(storageRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {
                        // лучшее усилие - путь пересоздастся при сохранении
                    }
                });
            }
        }
        transactionTemplate.executeWithoutResult(tx -> {
            ObjectType warehouse = objectTypeRepository.save(ObjectType
                    .builder().code("warehouse").name("Склад")
                    .isCalcEnabled(true).dataSourceNote("Тест").build());
            warehouseTypeId = warehouse.getId();
            ObjectType airport = objectTypeRepository.save(ObjectType
                    .builder().code("airport").name("Аэропорт")
                    .isCalcEnabled(false).dataSourceNote("Тест").build());
            airportTypeId = airport.getId();
            otp(warehouse.getId(), "shifts_per_day", "2");
            otp(warehouse.getId(), "shift_duration", "11");
            otp(warehouse.getId(), "peak_load_factor", "1.5");
            otp(warehouse.getId(), "inbound_pallets_per_day", "1000");
            otp(warehouse.getId(), "outbound_pallets_per_day", "1000");
            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name("Ронави").build());
            solutionId = solutionRepository.save(Solution.builder()
                    .vendorId(vendor.getId()).name("Ronavi H1500")
                    .productClass("brs").status("operation")
                    .priceRub(BigDecimal.valueOf(2_500_000)).trl((short) 9)
                    .sourceKind("organizer_catalog")
                    .sourceUrl("catalog_export_v4.csv")
                    .speedMs(BigDecimal.valueOf(1.5)).build()).getId();
        });
    }

    @AfterAll
    void cleanup() {
        transactionTemplate.executeWithoutResult(tx -> {
            assumptionRepository.deleteAll();
            projectRepository.deleteAll();
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
                .defaultValueNumeric(new BigDecimal(defaultValue))
                .sourceNote("Тест").build());
    }

    // ------------------------------------------------------------------
    // Хелперы HTTP
    // ------------------------------------------------------------------

    private String registerAndLogin() throws Exception {
        String login = "sim_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = post("/api/auth/register", null,
                "{\"login\":\"" + login + "\",\"password\":\"password123\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    private Long createProject(String token, Long typeId,
                               String nameSuffix) throws Exception {
        HttpResponse<String> response = post("/api/projects", token,
                "{\"name\":\"Склад имитации " + nameSuffix
                        + "\",\"objectTypeId\":" + typeId + "}");
        assertThat(response.statusCode()).isEqualTo(201);
        return ((Integer) JsonPath.read(response.body(), "$.id"))
                .longValue();
    }

    private ProjectWithScenarios projectWithScenarios(String token,
                                                      int robots)
            throws Exception {
        Long projectId = createProject(token, warehouseTypeId,
                UUID.randomUUID().toString().substring(0, 6));
        assertThat(post("/api/projects/" + projectId + "/scenarios", token,
                "").statusCode()).isEqualTo(201);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        Integer baseId = scenarioIdByType(list.body(), "base");
        HttpResponse<String> updated = put(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId,
                token, "{\"solutions\":[{\"solutionId\":" + solutionId
                        + ",\"quantity\":" + robots + "}]}");
        assertThat(updated.statusCode()).isEqualTo(200);
        // P_nominal для 6-го KPI (достижимость) - допущение проекта
        // (тело - карта «код → значение» в обёртке values, см. DTO)
        HttpResponse<String> putAssumption = put(
                "/api/projects/" + projectId + "/assumptions", token,
                "{\"values\":{\"robot_nominal_productivity_per_hour\":"
                        + "\"90\"}}");
        assertThat(putAssumption.statusCode()).isEqualTo(200);
        return new ProjectWithScenarios(projectId, purchaseId, baseId);
    }

    private Integer scenarioIdByType(String body, String type) {
        List<Integer> ids = JsonPath.read(body,
                "$[?(@.type=='" + type + "')].id");
        assertThat(ids).isNotEmpty();
        return ids.get(0);
    }

    private HttpResponse<String> post(String path, String token, String json)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json == null ? "" : json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
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
        return client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noToken_returns401() throws Exception {
        assertThat(post("/api/projects/1/scenarios/1/simulation/run",
                null, "").statusCode()).isEqualTo(401);
        assertThat(get("/api/projects/1/scenarios/1/simulation", null)
                .statusCode()).isEqualTo(401);
        assertThat(get("/api/projects/1/simulations", null).statusCode())
                .isEqualTo(401);
        assertThat(get("/api/projects/1/simulations/1/export", null)
                .statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // Доступ и валидации
    // ------------------------------------------------------------------

    @Test
    void foreignProject_returns404() throws Exception {
        String owner = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(owner, 3);
        String stranger = registerAndLogin();
        assertThat(post("/api/projects/" + fixture.projectId()
                + "/scenarios/" + fixture.purchaseId()
                + "/simulation/run", stranger, "").statusCode())
                .isEqualTo(404);
        assertThat(get("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation",
                stranger).statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + fixture.projectId()
                + "/simulations", stranger).statusCode()).isEqualTo(404);
    }

    @Test
    void nonWarehouse_returns400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, airportTypeId, "аэропорт");
        assertThat(post("/api/projects/" + projectId + "/scenarios",
                token, "").statusCode()).isEqualTo(201);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        HttpResponse<String> run = post("/api/projects/" + projectId
                + "/scenarios/" + purchaseId + "/simulation/run", token, "");
        assertThat(run.statusCode()).isEqualTo(400);
        assertThat(run.body()).contains("Имитация доступна только для склада");
    }

    @Test
    void baseScenario_returns400() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 3);
        HttpResponse<String> run = post("/api/projects/"
                + fixture.projectId() + "/scenarios/" + fixture.baseId()
                + "/simulation/run", token, "");
        assertThat(run.statusCode()).isEqualTo(400);
        assertThat(run.body()).contains("Базовый сценарий не содержит");
    }

    @Test
    void emptyComposition_returns400() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 3);
        // покупка без состава: состав замещается пустым списком
        HttpResponse<String> cleared = put("/api/projects/"
                        + fixture.projectId() + "/scenarios/" + fixture.purchaseId(),
                token, "{\"solutions\":[]}");
        assertThat(cleared.statusCode()).isEqualTo(200);
        HttpResponse<String> run = post("/api/projects/"
                + fixture.projectId() + "/scenarios/" + fixture.purchaseId()
                + "/simulation/run", token, "");
        assertThat(run.statusCode()).isEqualTo(400);
        assertThat(run.body()).contains("Состав сценария пуст");
    }

    @Test
    void runReturnsCompletedWithSixKpi() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 3);
        HttpResponse<String> run = post("/api/projects/"
                + fixture.projectId() + "/scenarios/" + fixture.purchaseId()
                + "/simulation/run", token, "");
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(run.body(), "$.status"))
                .isEqualTo("completed");
        // 6 KPI присутствуют
        assertThat((Double) JsonPath.read(run.body(),
                "$.actualThroughputPerHour")).isEqualTo(90.0);
        assertThat((Double) JsonPath.read(run.body(),
                "$.utilizationPct")).isEqualTo(100.0);
        assertThat((Double) JsonPath.read(run.body(),
                "$.idlePct")).isEqualTo(0.0);
        assertThat((List<?>) JsonPath.read(run.body(), "$.zones"))
                .hasSize(4);
        assertThat((Double) JsonPath.read(run.body(),
                "$.achievabilityPct")).isEqualTo(33.3);
        assertThat((Double) JsonPath.read(run.body(),
                "$.declaredThroughputPerHour")).isEqualTo(270.0);
        // состав и входы - для 2D-схемы
        assertThat((List<?>) JsonPath.read(run.body(), "$.composition"))
                .hasSize(1);
        assertThat((Integer) JsonPath.read(run.body(), "$.robots"))
                .isEqualTo(3);
        // сверка с экономикой: расчёта нет - предупреждение
        assertThat((List<?>) JsonPath.read(run.body(), "$.warnings"))
                .isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Запуск, KPI, статусы
    // ------------------------------------------------------------------

    @Test
    void latestReturnsLastRun_historyListsProject() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 3);
        HttpResponse<String> run = post("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation/run",
                token, "");
        assertThat(run.statusCode()).isEqualTo(200);
        Integer simulationId = JsonPath.read(run.body(), "$.id");
        HttpResponse<String> latest = get("/api/projects/"
                + fixture.projectId() + "/scenarios/" + fixture.purchaseId()
                + "/simulation", token);
        assertThat(latest.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(latest.body(), "$.status"))
                .isEqualTo("completed");

        HttpResponse<String> history = get("/api/projects/"
                + fixture.projectId() + "/simulations", token);
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(history.body(), "$[*].id"))
                .isNotEmpty();
        // до первого запуска - 404 (другой проект без имитаций)
        String other = registerAndLogin();
        ProjectWithScenarios fresh = projectWithScenarios(other, 1);
        assertThat(get("/api/projects/" + fresh.projectId()
                + "/scenarios/" + fresh.purchaseId() + "/simulation", token)
                .statusCode()).isEqualTo(404);
    }

    @Test
    void exportSchema_savesSvgAndReturnsUrl() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 2);
        HttpResponse<String> run = post("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation/run",
                token, "");
        assertThat(run.statusCode()).isEqualTo(200);
        Integer simulationId = JsonPath.read(run.body(), "$.id");
        // схема со скриптом - сервер санитизирует (self-XSS исключён)
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\""
                + " height=\"10\"><script>alert(1)</script>"
                + "<rect width=\"10\" height=\"10\" onload=\"evil()\"/>"
                + "</svg>";
        HttpResponse<String> export = post("/api/projects/"
                + fixture.projectId() + "/simulations/" + simulationId + "/export", token, "{\"svg\":" + quote(svg)
                + "}");
        assertThat(export.statusCode()).isEqualTo(200);
        String exportUrl = JsonPath.read(export.body(), "$.exportUrl");
        assertThat(exportUrl).startsWith("/api/projects/");
        // файл отдаётся владельцу и НЕ содержит скриптов
        HttpResponse<String> file = get(exportUrl, token);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).contains("<svg");
        assertThat(file.body()).doesNotContain("<script");
        assertThat(file.body()).doesNotContain("onload");
        // exportUrl в kpi_json последнего результата
        HttpResponse<String> latest = get("/api/projects/"
                + fixture.projectId() + "/scenarios/" + fixture.purchaseId()
                + "/simulation", token);
        assertThat((String) JsonPath.read(latest.body(), "$.exportUrl"))
                .isEqualTo(exportUrl);
        // чужой файл - 404 (изоляция)
        String stranger = registerAndLogin();
        assertThat(get(exportUrl, stranger).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Сохранение схемы
    // ------------------------------------------------------------------

    /**
 * JSON-экранирование строки для тела запроса.
 */
    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    /**
 * Регресс-тест санитизации SVG -
 * корпус обходов, которыми обходился регексп-блэклист. Каждый
 * вектор: экспорт 200, в сохранённом файле вектор ОТСУТСТВУЕТ.
 */
    @Test
    void exportSchema_blocksXssVectors() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 2);
        HttpResponse<String> run = post("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation/run",
                token, "");
        assertThat(run.statusCode()).isEqualTo(200);
        Integer simulationId = JsonPath.read(run.body(), "$.id");
        String[] vectors = {
                // 1. foreignObject + iframe srcdoc - выполнялся БЕЗ клика
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><foreignObject>"
                        + "<iframe srcdoc=\"&lt;script&gt;alert(1)&lt;/script&gt;\">"
                        + "</iframe></foreignObject><rect width=\"5\" height=\"5\"/></svg>",
                // 2. javascript:-href (прямой и через entity)
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><a "
                        + "href=\"javascript:alert(2)\"><text>x</text></a>"
                        + "<a href=\"&#106;avascript:alert(2b)\">"
                        + "<text>y</text></a></svg>",
                // 3. SMIL: set с on*-атрибутом и animate с javascript:href
                // (xmlns:xlink объявлен - вектор ВАЛИДНЫМ XML, чтобы
                // проверить именно allowlist-удаление set/animate)
                "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                        + "xmlns:xlink=\"http://www.w3.org/1999/xlink\"><rect width=\"5\" "
                        + "height=\"5\"><set attributeName=\"onmouseover\" "
                        + "to=\"alert(3)\"/></rect><animate "
                        + "xlink:href=\"javascript:alert(3b)\"/></svg>",
                // 4. use с data:-ссылкой
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><use "
                        + "href=\"data:image/svg+xml;base64,PHN2Zz48c2NyaXB0Pjwvc2NyaXB0Pjwvc3ZnPg==\"/>"
                        + "</svg>",
                // 5. смешанный регистр тега script
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><ScRiPt>"
                        + "alert(6)</ScRiPt><rect width=\"5\" height=\"5\"/>"
                        + "</svg>",
        };
        for (int i = 0; i < vectors.length; i += 1) {
            HttpResponse<String> export = post("/api/projects/"
                            + fixture.projectId() + "/simulations/" + simulationId + "/export", token,
                    "{\"svg\":" + quote(vectors[i]) + "}");
            assertThat(export.statusCode())
                    .as("вектор %d: экспорт должен проходить (файл чистится)", i)
                    .isEqualTo(200);
            String exportUrl = JsonPath.read(export.body(), "$.exportUrl");
            HttpResponse<String> file = get(exportUrl, token);
            assertThat(file.statusCode()).isEqualTo(200);
            String body = file.body().toLowerCase();
            assertThat(body)
                    .as("вектор %d: script не должен сохраниться", i)
                    .doesNotContain("<script").doesNotContain("srcdoc")
                    .doesNotContain("onerror").doesNotContain("onmouseover")
                    .doesNotContain("javascript:").doesNotContain("foreignobject")
                    .doesNotContain("<iframe").doesNotContain("<use")
                    .doesNotContain("<set ").doesNotContain("<animate");
        }
        // легитимная схема (как генерирует SimulationView) - остаётся
        // рабочей картинкой после санитизации
        String clean = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
                + "<g transform=\"translate(5,5)\"><circle r=\"4\" fill=\"#2563eb\"/>"
                + "<text x=\"1\" y=\"1\">робот</text></g></svg>";
        HttpResponse<String> export = post("/api/projects/"
                + fixture.projectId() + "/simulations/" + simulationId + "/export", token, "{\"svg\":" + quote(clean) + "}");
        assertThat(export.statusCode()).isEqualTo(200);
        HttpResponse<String> file = get(
                (String) JsonPath.read(export.body(), "$.exportUrl"), token);
        assertThat(file.body()).contains("<circle").contains("робот");

        // битая разметка (не XML) - 400 понятной ошибкой
        HttpResponse<String> broken = post("/api/projects/"
                + fixture.projectId() + "/simulations/" + simulationId + "/export", token, "{\"svg\":\"<svg><rect>\"}");
        assertThat(broken.statusCode()).isEqualTo(400);
    }

    /**
 * Внешние URI (http/https/file) в href/src
 * и инлайн-CSS url(...) вырезаются - схема самодостаточна, внешних
 * запросов при открытии файла быть не должно.
 */
    @Test
    void exportSchema_stripsExternalUrisAndInlineStyle() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 2);
        HttpResponse<String> run = post("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation/run",
                token, "");
        assertThat(run.statusCode()).isEqualTo(200);
        Integer simulationId = JsonPath.read(run.body(), "$.id");
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                + "xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                // image с внешними http:/file: ссылками - вырезаются
                + "<image href=\"http://evil.example/x.png\" width=\"5\" height=\"5\"/>"
                + "<image xlink:href=\"file:///etc/passwd\" width=\"5\" height=\"5\"/>"
                // style с url - атрибут удаляется целиком
                + "<rect width=\"5\" height=\"5\" style=\"fill:url(https://evil.example/c)\"/>"
                // url(…) с внешней схемой в paint-атрибутах (fill/filter) -
                // атрибут вырезается (внутренние url(#id) остаются)
                + "<rect width=\"5\" height=\"5\" fill=\"url(https://evil.example/paint)\""
                + " filter=\"url(http://evil.example/filter)\"/>"
                // легитимные ссылка-фрагмент и относительный путь - остаются
                + "<lineargradient id=\"g\"/><rect fill=\"url(#g)\" width=\"5\" height=\"5\"/>"
                + "<image href=\"robot.png\" width=\"5\" height=\"5\"/>"
                + "</svg>";
        HttpResponse<String> export = post("/api/projects/"
                + fixture.projectId() + "/simulations/" + simulationId + "/export", token, "{\"svg\":" + quote(svg) + "}");
        assertThat(export.statusCode()).isEqualTo(200);
        HttpResponse<String> file = get(
                (String) JsonPath.read(export.body(), "$.exportUrl"), token);
        assertThat(file.statusCode()).isEqualTo(200);
        String body = file.body().toLowerCase();
        assertThat(body)
                .doesNotContain("http://evil.example")
                .doesNotContain("https://evil.example")
                .doesNotContain("file:///")
                .doesNotContain("style=");
        // фрагмент и относительный href сохранены
        assertThat(file.body()).contains("url(#g)").contains("robot.png");
    }

    /**
 * Редизайн схемы: элементы нового вида - сетка (pattern),
 * маркеры-стрелки, фильтр тени с примитивом feDropShadow -
 * декларативная статичная графика, санитизация сохраняет их
 * (вид на экране = вид в сохранённом/экспортированном файле).
 */
    @Test
    void exportSchema_keepsRedesignedSchemaElements() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 2);
        HttpResponse<String> run = post("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation/run",
                token, "");
        assertThat(run.statusCode()).isEqualTo(200);
        Integer simulationId = JsonPath.read(run.body(), "$.id");
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\">"
                + "<defs>"
                + "<pattern id=\"schema-grid\" width=\"20\" height=\"20\""
                + " patternUnits=\"userSpaceOnUse\">"
                + "<path d=\"M 20 0 L 0 0 0 20\" fill=\"none\""
                + " stroke=\"#e2e8f0\" stroke-width=\"0.6\"/>"
                + "</pattern>"
                + "<marker id=\"arrow\" viewBox=\"0 0 10 10\""
                + " markerWidth=\"7\" markerHeight=\"7\">"
                + "<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"#64748b\"/>"
                + "</marker>"
                + "<filter id=\"schema-shadow\" x=\"-40%\" y=\"-40%\""
                + " width=\"180%\" height=\"180%\">"
                + "<feDropShadow dx=\"0\" dy=\"1.5\" stdDeviation=\"2\""
                + " flood-color=\"#0f172a\" flood-opacity=\"0.2\"/>"
                + "</filter>"
                + "</defs>"
                + "<rect width=\"940\" height=\"460\" fill=\"#f8fafc\"/>"
                + "<rect x=\"13\" y=\"13\" width=\"934\" height=\"454\""
                + " fill=\"url(#schema-grid)\"/>"
                + "<g filter=\"url(#schema-shadow)\">"
                + "<rect x=\"-11\" y=\"-7\" width=\"23\" height=\"15\""
                + " rx=\"5\" fill=\"#f8fafc\"/>"
                + "</g>"
                + "</svg>";
        HttpResponse<String> export = post("/api/projects/"
                        + fixture.projectId() + "/simulations/" + simulationId + "/export", token,
                "{\"svg\":" + quote(svg) + "}");
        assertThat(export.statusCode()).isEqualTo(200);
        HttpResponse<String> file = get(
                (String) JsonPath.read(export.body(), "$.exportUrl"), token);
        assertThat(file.statusCode()).isEqualTo(200);
        String body = file.body().toLowerCase();
        assertThat(body)
                .contains("pattern")
                .contains("schema-grid")
                .contains("marker")
                .contains("filter")
                .contains("fedropshadow")
                .contains("url(#schema-grid)")
                .contains("url(#schema-shadow)");
    }

    /**
 * Неподдерживаемый метод (GET на пути с
 * PUT/POST) - 405, а не 500 «Внутренняя ошибка сервера».
 */
    @Test
    void methodNotAllowed_gives405not500() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 2);
        HttpResponse<String> response = get(
                "/api/projects/" + fixture.projectId() + "/scenarios/"
                        + fixture.purchaseId(), token);
        assertThat(response.statusCode()).isEqualTo(405);
    }

    /**
 * Файл схемы до первого сохранения - 404, не 500.
 */
    @Test
    void downloadSchema_beforeSave_returns404() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 1);
        HttpResponse<String> run = post("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation/run",
                token, "");
        assertThat(run.statusCode()).isEqualTo(200);
        Integer simulationId = JsonPath.read(run.body(), "$.id");
        HttpResponse<String> latest = get("/api/projects/"
                + fixture.projectId() + "/scenarios/" + fixture.purchaseId()
                + "/simulation", token);
        assertThat((Integer) JsonPath.read(latest.body(), "$.id"))
                .isEqualTo(simulationId);
        HttpResponse<String> file = get("/api/projects/"
                + fixture.projectId() + "/simulations/" + simulationId
                + "/export", token);
        assertThat(file.statusCode()).isEqualTo(404);
    }

    /**
 * Легаси-путь сохранения под /scenarios/{sid}/simulation/export
 * больше не существует - 404; схема сохраняется по симметричному
 * пути /simulations/{simId}/export (POST).
 */
    @Test
    void exportSchema_legacyScenarioPath_404() throws Exception {
        String token = registerAndLogin();
        ProjectWithScenarios fixture = projectWithScenarios(token, 2);
        HttpResponse<String> run = post("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation/run",
                token, "");
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(post("/api/projects/" + fixture.projectId()
                        + "/scenarios/" + fixture.purchaseId() + "/simulation/export",
                token, "{\"svg\":\"<svg xmlns=\\\"http://www.w3.org/2000/svg\\\"/>\"}")
                .statusCode()).isEqualTo(404);
    }

    /**
 * Проект с 3 сценариями и составом покупки (N роботов).
 */
    private record ProjectWithScenarios(Long projectId, int purchaseId,
                                        int baseId) {
    }
}
