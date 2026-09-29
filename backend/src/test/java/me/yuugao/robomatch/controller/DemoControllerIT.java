package me.yuugao.robomatch.controller;

import static org.assertj.core.api.Assertions.assertThat;

import me.yuugao.robomatch.domain.Calculation;
import me.yuugao.robomatch.domain.ObjectType;
import me.yuugao.robomatch.domain.Project;
import me.yuugao.robomatch.domain.Scenario;
import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.domain.Vendor;
import me.yuugao.robomatch.repository.CalculationRepository;
import me.yuugao.robomatch.repository.ObjectTypeRepository;
import me.yuugao.robomatch.repository.ProjectRepository;
import me.yuugao.robomatch.repository.ScenarioRepository;
import me.yuugao.robomatch.repository.SolutionRepository;
import me.yuugao.robomatch.repository.VendorRepository;

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
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест гостевого демо-расчёта: полный HTTP-путь без токена
 * (permitAll /api/demo/**) до сервиса и каталога H2. Главный инвариант:
 * демо-расчёт НИЧЕГО не сохраняет - количество проектов, сценариев и
 * расчётов не меняется до и после вызова.
 * <p>
 * Окружение как в SolutionControllerIT: H2 in-memory (MODE=PostgreSQL),
 * Flyway выключен, схема create-drop, HTTP-клиент - java.net.http.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:demoit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoControllerIT {

    private static final String DEMO_SOLUTION =
            "Ronavi H1500 (грузоподъемность до 1 500 кг)";
    private static final String DEMO_VENDOR = "ООО \"Ронави Роботикс\"";

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectTypeRepository objectTypeRepository;
    @Autowired
    private VendorRepository vendorRepository;
    @Autowired
    private SolutionRepository solutionRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    @Autowired
    private CalculationRepository calculationRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    void seed() {
        transactionTemplate.executeWithoutResult(tx -> {
            objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse").name("Склад").isCalcEnabled(true)
                    .dataSourceNote("Тест").build());
            objectTypeRepository.save(ObjectType.builder()
                    .code("airport").name("Аэропорт").isCalcEnabled(false)
                    .dataSourceNote("Тест").build());
            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name(DEMO_VENDOR).build());
            solutionRepository.save(Solution.builder()
                    .name(DEMO_SOLUTION).vendorId(vendor.getId())
                    .productClass("brs").status("operation")
                    .priceRub(new BigDecimal("2700000"))
                    .chargingPowerKw(new BigDecimal("1.35"))
                    .sourceKind("organizer_catalog")
                    .sourceUrl("catalog_export_v4.csv").build());
        });
    }

    private long[] persistedCounts() {
        return new long[]{
                projectRepository.count(),
                scenarioRepository.count(),
                calculationRepository.count()};
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        HttpRequest request = json == null
                ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                : builder.POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void descriptor_withoutToken_200() throws Exception {
        HttpResponse<String> response = get("/api/demo");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(),
                "$.objectTypeName")).isEqualTo("Склад");
        assertThat((Boolean) JsonPath.read(response.body(),
                "$.availableTypes[0].available")).isTrue();
        assertThat((String) JsonPath.read(response.body(),
                "$.availableTypes[1].name")).isEqualTo("Аэропорт");
        assertThat((String) JsonPath.read(response.body(),
                "$.parameters[0].title")).isEqualTo("Общая площадь склада");
        assertThat((String) JsonPath.read(response.body(),
                "$.composition[0].solutionName")).isEqualTo(DEMO_SOLUTION);
        assertThat((Integer) JsonPath.read(response.body(),
                "$.composition[0].quantity")).isEqualTo(3);
    }

    @Test
    void calculate_withoutToken_200_nothingPersisted() throws Exception {
        long[] before = persistedCounts();

        HttpResponse<String> response =
                post("/api/demo/calculate", "{\"objectTypeCode\":\"warehouse\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(response.body(), "$.demo"))
                .isTrue();
        assertThat((Integer) JsonPath.read(response.body(),
                "$.comparison.scenarios.length()")).isEqualTo(3);
        assertThat((String) JsonPath.read(response.body(),
                "$.comparison.scenarios[0].type")).isEqualTo("base");
        assertThat((String) JsonPath.read(response.body(),
                "$.comparison.scenarios[1].type")).isEqualTo("purchase");
        assertThat((String) JsonPath.read(response.body(),
                "$.comparison.scenarios[2].type")).isEqualTo("raas");
        // Состав демо: 3 робота - ровно требуемому парку
        assertThat((Integer) JsonPath.read(response.body(),
                "$.comparison.scenarios[1].selectedRobots")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(response.body(),
                "$.comparison.scenarios[1].requiredRobots")).isEqualTo(3);
        // Экономика покупки: CAPEX за парк, окупаемость рассчитана
        assertThat(((Number) JsonPath.read(response.body(),
                "$.comparison.scenarios[1].capex")).doubleValue())
                .isPositive();
        assertThat((Object) JsonPath.read(response.body(),
                "$.comparison.scenarios[1].payback.paybackYears")).isNotNull();
        // База: без CAPEX, годовой OPEX = ФОТ контура
        assertThat(((Number) JsonPath.read(response.body(),
                "$.comparison.scenarios[0].capex")).doubleValue())
                .isEqualTo(0.0);
        assertThat(((Number) JsonPath.read(response.body(),
                "$.comparison.scenarios[0].opexYear")).doubleValue())
                .isPositive();
        // ГЛАВНОЕ: ничего не сохранено
        assertThat(persistedCounts()).containsExactly(before);
    }

    @Test
    void calculate_withoutBody_defaultsToWarehouse() throws Exception {
        HttpResponse<String> response = post("/api/demo/calculate", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(),
                "$.objectTypeName")).isEqualTo("Склад");
    }

    @Test
    void calculate_unknownType_404() throws Exception {
        HttpResponse<String> response =
                post("/api/demo/calculate", "{\"objectTypeCode\":\"nope\"}");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void calculate_disabledType_400() throws Exception {
        HttpResponse<String> response =
                post("/api/demo/calculate", "{\"objectTypeCode\":\"airport\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .contains("Аэропорт");
    }

    @Test
    void calculate_withUserToken_200_nothingPersisted() throws Exception {
        String login = "it_demo_" + UUID.randomUUID().toString()
                .substring(0, 8);
        HttpResponse<String> register = post("/api/auth/register",
                "{\"login\":\"" + login + "\",\"password\":\"password123\"}");
        assertThat(register.statusCode()).isEqualTo(201);
        String token = JsonPath.read(register.body(), "$.token");
        long[] before = persistedCounts();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/api/demo/calculate"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"objectTypeCode\":\"warehouse\"}"))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(response.body(), "$.demo"))
                .isTrue();
        assertThat(persistedCounts()).containsExactly(before);
    }

    @Test
    void calculate_twice_sameResult() throws Exception {
        HttpResponse<String> first =
                post("/api/demo/calculate", "{\"objectTypeCode\":\"warehouse\"}");
        HttpResponse<String> second =
                post("/api/demo/calculate", "{\"objectTypeCode\":\"warehouse\"}");

        Object firstCapex = JsonPath.read(first.body(),
                "$.comparison.scenarios[1].capex");
        Object secondCapex = JsonPath.read(second.body(),
                "$.comparison.scenarios[1].capex");
        assertThat(secondCapex).isEqualTo(firstCapex);
        // и по-прежнему пусто в таблице расчётов
        assertThat(calculationRepository.count()).isZero();
        assertThat(projectRepository.count()).isZero();
        assertThat(scenarioRepository.count()).isZero();
    }
}
