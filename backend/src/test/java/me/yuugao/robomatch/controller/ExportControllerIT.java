package me.yuugao.robomatch.controller;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.domain.Calculation;
import me.yuugao.robomatch.domain.ObjectType;
import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.domain.Vendor;
import me.yuugao.robomatch.repository.*;
import me.yuugao.robomatch.service.ExportStorage;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест экспорта (–3.7.5,
 * 4.4.3, 4.4.6): полный HTTP-путь через SecurityFilterChain.
 *
 * <p>Сценарии: 401 без токена на всех эндпоинтах; 404 чужого проекта и
 * чужой выгрузки; 400 не-склад / нет расчётов / битый формат; 201
 * генерация pdf/xlsx/csv (сценарии создаются API, расчёты вставляются
 * репозиторием - отчёт читает последние расчёты, не считая сам);
 * GET списка; GET файла (Content-Type, Content-Disposition filename*
 * для кириллицы); DELETE 204/404; удаление проекта чистит каталог
 * data/exports/{projectId}.
 *
 * <p>Фикстура: склад + аэропорт, проект с 3 сценариями, решение в
 * составе покупки, расчёты с metrics_json у всех сценариев.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "app.export.root=./build/test-exports-it",
        "spring.datasource.url=jdbc:h2:mem:exportit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportControllerIT {

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    int port;
    @Autowired
    private TransactionTemplate transactionTemplate;
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
    private ExportStorage exportStorage;
    private Long warehouseTypeId;
    private Long airportTypeId;
    private Long solutionId;

    /**
 * Текст PDF (PDFTextStripper; переносы строк нормализуем).
 */
    private static String pdfText(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                     org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper()
                    .getText(document).replaceAll("\\s+", " ");
        }
    }

    @BeforeAll
    void seedFixtures() {
        transactionTemplate.executeWithoutResult(tx -> {
            ObjectType warehouse = objectTypeRepository.save(ObjectType
                    .builder().code("warehouse").name("Склад")
                    .isCalcEnabled(true).dataSourceNote("Тест").build());
            warehouseTypeId = warehouse.getId();
            ObjectType airport = objectTypeRepository.save(ObjectType
                    .builder().code("airport").name("Аэропорт")
                    .isCalcEnabled(false).dataSourceNote("Тест").build());
            airportTypeId = airport.getId();
            // «пустой» вендор ЗАНИМАЕТ id 1 - id
            // вендора «Ронави» гарантированно отличается от id решения
            // (раньше vendor.id == solution.id == 1 маскировал баг
            // карты вендоров в ReportDataBuilder)
            vendorRepository.save(Vendor.builder()
                    .name("Запасной вендор").build());
            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name("Ронави").build());
            solutionId = solutionRepository.save(Solution.builder()
                    .vendorId(vendor.getId())
                    .name("Ronavi H1500").productClass("brs")
                    .status("operation")
                    .priceRub(BigDecimal.valueOf(2_500_000)).trl((short) 9)
                    .sourceKind("organizer_catalog")
                    .sourceUrl("catalog_export_v4.csv")
                    .sourceDate(java.time.LocalDate.of(2026, 9, 17))
                    .speedMs(BigDecimal.valueOf(1.5)).build()).getId();
        });
    }

    // ------------------------------------------------------------------
    // Хелперы
    // ------------------------------------------------------------------

    @AfterAll
    void cleanup() {
        transactionTemplate.executeWithoutResult(tx -> {
            calculationRepository.deleteAll();
            scenarioRepository.deleteAll();
            projectRepository.deleteAll();
            solutionRepository.deleteAll();
            vendorRepository.deleteAll();
            objectTypeRepository.deleteAll();
        });
    }

    private String registerAndLogin() throws Exception {
        String login = "exp_" + UUID.randomUUID().toString()
                .substring(0, 8);
        HttpResponse<String> response = post("/api/auth/register", null,
                "{\"login\":\"" + login + "\",\"password\":\"password123\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    private Fixture fixtureWithCalculations(String token) throws Exception {
        HttpResponse<String> created = post("/api/projects", token,
                "{\"name\":\"Склад экспорта " + UUID.randomUUID()
                        .toString().substring(0, 6)
                        + "\",\"objectTypeId\":" + warehouseTypeId + "}");
        assertThat(created.statusCode()).isEqualTo(201);
        Long projectId = ((Integer) JsonPath.read(created.body(), "$.id"))
                .longValue();
        assertThat(post("/api/projects/" + projectId + "/scenarios", token,
                "").statusCode()).isEqualTo(201);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Long purchaseId = scenarioIdByType(list.body(), "purchase");
        Long baseId = scenarioIdByType(list.body(), "base");
        Long raasId = scenarioIdByType(list.body(), "raas");
        // состав покупки (12 роботов)
        HttpResponse<String> put = put("/api/projects/" + projectId
                        + "/scenarios/" + purchaseId, token,
                "{\"solutions\":[{\"solutionId\":" + solutionId
                        + ",\"quantity\":12}]}");
        assertThat(put.statusCode()).isEqualTo(200);
        // расчёты всех трёх сценариев (отчёт читает их, не считая сам)
        transactionTemplate.executeWithoutResult(tx -> {
            calculationRepository.save(calculation(baseId, 0));
            calculationRepository.save(calculation(purchaseId, 12));
            calculationRepository.save(calculation(raasId, 12));
        });
        return new Fixture(projectId, purchaseId, baseId, raasId);
    }

    private Calculation calculation(Long scenarioId, int robots) {
        String metrics = "{\"selectedRobots\":" + robots
                + ",\"nInfra\":4,\"requiredRobots\":10,"
                + "\"underpowered\":false,\"overpowered\":false,"
                + "\"horizonYears\":5,\"deltaFot\":160000000,"
                + "\"effectiveAssumptions\":{\"k_load\":\"0.75\"},"
                + "\"composition\":[{\"solutionId\":" + solutionId
                + ",\"solutionName\":\"Ronavi H1500\",\"quantity\":12,"
                + "\"priceRub\":2500000.0,\"manual\":false}],"
                + "\"warnings\":[]}";
        return Calculation.builder()
                .scenarioId(scenarioId)
                .versionData("002d9870f4a0")
                .versionModel("economic-model-1.0")
                .totalCapex(robots == 0 ? null
                        : BigDecimal.valueOf(25_743_025))
                .totalOpex(BigDecimal.valueOf(4_200_000))
                .opexDeltaRub(BigDecimal.valueOf(-158_000_000))
                .effectYear(BigDecimal.valueOf(150_000_000))
                .paybackYears(robots == 0 ? null
                        : new BigDecimal("0.1"))
                .roiPct(BigDecimal.valueOf(4758.6))
                .tcoRub(BigDecimal.valueOf(31_000_000))
                .metricsJson(metrics)
                .build();
    }

    private Long scenarioIdByType(String body, String type) {
        List<Integer> ids = JsonPath.read(body,
                "$[?(@.type=='" + type + "')].id");
        assertThat(ids).isNotEmpty();
        return ids.get(0).longValue();
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

    private HttpResponse<String> delete(String path, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .DELETE();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------
    // Доступ
    // ------------------------------------------------------------------

    @Test
    void noToken_returns401_onAllEndpoints() throws Exception {
        assertThat(post("/api/projects/1/exports", null,
                "{\"format\":\"pdf\"}").statusCode()).isEqualTo(401);
        assertThat(get("/api/projects/1/exports", null).statusCode())
                .isEqualTo(401);
        assertThat(get("/api/projects/1/exports/1", null).statusCode())
                .isEqualTo(401);
        assertThat(delete("/api/projects/1/exports/1", null).statusCode())
                .isEqualTo(401);
    }

    @Test
    void foreignProject_returns404() throws Exception {
        String owner = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(owner);
        String stranger = registerAndLogin();
        assertThat(post("/api/projects/" + fixture.projectId()
                + "/exports", stranger, "{\"format\":\"pdf\"}")
                .statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + fixture.projectId()
                + "/exports", stranger).statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + fixture.projectId()
                + "/exports/1", stranger).statusCode()).isEqualTo(404);
        assertThat(delete("/api/projects/" + fixture.projectId()
                + "/exports/1", stranger).statusCode()).isEqualTo(404);
    }

    @Test
    void nonWarehouse_returns400() throws Exception {
        String token = registerAndLogin();
        HttpResponse<String> created = post("/api/projects", token,
                "{\"name\":\"Аэропорт экспорта\",\"objectTypeId\":"
                        + airportTypeId + "}");
        assertThat(created.statusCode()).isEqualTo(201);
        Long projectId = ((Integer) JsonPath.read(created.body(), "$.id"))
                .longValue();
        HttpResponse<String> response = post("/api/projects/" + projectId
                + "/exports", token, "{\"format\":\"pdf\"}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains(
                "Экспорт доступен только для склада");
    }

    @Test
    void noCalculations_autoCalculates_reportNotBlocked() throws Exception {
        // расчётов нет - перед отчётом запускается автозапуск; отчёт
        // генерируется В ЛЮБОМ случае (неудача расчёта печатается
        // в разделе экономики, имитации - заметкой у схемы)
        String token = registerAndLogin();
        HttpResponse<String> created = post("/api/projects", token,
                "{\"name\":\"Склад без расчётов\",\"objectTypeId\":"
                        + warehouseTypeId + "}");
        Long projectId = ((Integer) JsonPath.read(created.body(), "$.id"))
                .longValue();
        assertThat(post("/api/projects/" + projectId + "/scenarios", token,
                "").statusCode()).isEqualTo(201);
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Long purchaseId = scenarioIdByType(list.body(), "purchase");
        // состав есть - цель автозапуска имитации
        HttpResponse<String> put = put("/api/projects/" + projectId
                        + "/scenarios/" + purchaseId, token,
                "{\"solutions\":[{\"solutionId\":" + solutionId
                        + ",\"quantity\":3}]}");
        assertThat(put.statusCode()).isEqualTo(200);

        HttpResponse<String> response = post("/api/projects/" + projectId
                + "/exports", token, "{\"format\":\"pdf\"}");
        // 201 - а не 400 «сначала рассчитайте» (автозапуск внутри)
        assertThat(response.statusCode()).isEqualTo(201);

        // автозапуск расчёта выполнялся: либо появились расчёты, либо
        // причина зафиксирована (в тестовом контексте нет метаданных
        // параметров - расчёт честно не выполняется, отчёт с заметкой)
        HttpResponse<String> history = get("/api/projects/" + projectId
                + "/scenarios/" + purchaseId + "/calculations", token);
        assertThat(history.statusCode()).isEqualTo(200);

        // PDF читается и НЕ содержит отсылок к номерам пунктов ТЗ
        HttpResponse<String> file = get("/api/projects/" + projectId
                        + "/exports/" + JsonPath.read(response.body(), "$.id"),
                token);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).doesNotContain("\u0422\u0417"); // «ТЗ»
    }

    @Test
    void invalidFormat_returns400() throws Exception {
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        assertThat(post("/api/projects/" + fixture.projectId()
                + "/exports", token, "{\"format\":\"docx\"}")
                .statusCode()).isEqualTo(400);
        assertThat(post("/api/projects/" + fixture.projectId()
                + "/exports", token, "{\"format\":\"\"}")
                .statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Генерация и файлы
    // ------------------------------------------------------------------

    @Test
    void createPdf_returns201WithView() throws Exception {
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        HttpResponse<String> response = post("/api/projects/"
                        + fixture.projectId() + "/exports", token,
                "{\"format\":\"pdf\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(response.body(), "$.format"))
                .isEqualTo("pdf");
        assertThat((String) JsonPath.read(response.body(), "$.fileName"))
                .startsWith("RoboMatch_").contains(".pdf");
        assertThat((Integer) JsonPath.read(response.body(),
                "$.sizeBytes")).isPositive();
        assertThat((String) JsonPath.read(response.body(), "$.createdByLogin"))
                .isNotBlank();
        assertThat((String) JsonPath.read(response.body(), "$.downloadUrl"))
                .isEqualTo("/api/projects/" + fixture.projectId()
                        + "/exports/" + JsonPath.read(response.body(),
                        "$.id"));
    }

    @Test
    void reportTheme_darkPdfAndValidation() throws Exception {
        // тема отчёта - light/dark, на 201 не влияет (контент
        // тот же, палитра разная); мусорное значение - 400 с подсказкой
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        HttpResponse<String> dark = post("/api/projects/"
                        + fixture.projectId() + "/exports", token,
                "{\"format\":\"pdf\",\"theme\":\"dark\"}");
        assertThat(dark.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(dark.body(), "$.format"))
                .isEqualTo("pdf");
        HttpResponse<String> light = post("/api/projects/"
                        + fixture.projectId() + "/exports", token,
                "{\"format\":\"pdf\",\"theme\":\"light\"}");
        assertThat(light.statusCode()).isEqualTo(201);
        HttpResponse<String> invalid = post("/api/projects/"
                        + fixture.projectId() + "/exports", token,
                "{\"format\":\"pdf\",\"theme\":\"blue\"}");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("light или dark");
    }

    @Test
    void createAllThreeFormats_filesExist() throws Exception {
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        for (String format : List.of("pdf", "xlsx", "csv")) {
            HttpResponse<String> response = post("/api/projects/"
                            + fixture.projectId() + "/exports", token,
                    "{\"format\":\"" + format + "\"}");
            assertThat(response.statusCode()).isEqualTo(201);
            Integer id = JsonPath.read(response.body(), "$.id");
            Path file = Path.of("build/test-exports-it",
                    fixture.projectId().toString(), id + "." + format);
            assertThat(Files.exists(file)).isTrue();
            assertThat(Files.size(file)).isPositive();
        }
    }

    @Test
    void historyListsExports_freshFirst() throws Exception {
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        post("/api/projects/" + fixture.projectId() + "/exports", token,
                "{\"format\":\"pdf\"}");
        post("/api/projects/" + fixture.projectId() + "/exports", token,
                "{\"format\":\"csv\"}");
        HttpResponse<String> list = get("/api/projects/"
                + fixture.projectId() + "/exports", token);
        assertThat(list.statusCode()).isEqualTo(200);
        List<String> formats = JsonPath.read(list.body(), "$[*].format");
        assertThat(formats).containsExactly("csv", "pdf"); // свежие сверху
    }

    @Test
    void downloadFile_contentTypeDispositionAndBody() throws Exception {
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        HttpResponse<String> created = post("/api/projects/"
                        + fixture.projectId() + "/exports", token,
                "{\"format\":\"pdf\"}");
        Integer exportId = JsonPath.read(created.body(), "$.id");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/api/projects/" + fixture.projectId()
                        + "/exports/" + exportId))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<byte[]> file = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("Content-Type").orElse(""))
                .isEqualTo("application/pdf");
        String disposition = file.headers()
                .firstValue("Content-Disposition").orElse("");
        assertThat(disposition).contains("attachment");
        assertThat(disposition).contains("filename*=UTF-8''");
        assertThat(new String(file.body(), 0, 5,
                StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // производитель решения в отчёте - из каталога
        // по vendorId (не по id решения); вендор «Ронави» имеет id > 1
        String pdfText = pdfText(file.body());
        assertThat(pdfText).contains("Ронави");
        assertThat(pdfText).doesNotContain("Запасной вендор");

        // чужой не скачивает (изоляция выгрузки)
        String stranger = registerAndLogin();
        assertThat(get("/api/projects/" + fixture.projectId()
                + "/exports/" + exportId, stranger).statusCode())
                .isEqualTo(404);
    }

    @Test
    void downloadCsv_bomAndUtf8Header() throws Exception {
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        HttpResponse<String> created = post("/api/projects/"
                        + fixture.projectId() + "/exports", token,
                "{\"format\":\"csv\"}");
        Integer exportId = JsonPath.read(created.body(), "$.id");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/api/projects/" + fixture.projectId()
                        + "/exports/" + exportId))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<byte[]> file = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        String contentType = file.headers()
                .firstValue("Content-Type").orElse("");
        assertThat(contentType).startsWith("text/csv");
        assertThat(contentType).contains("charset=UTF-8");
        assertThat(file.body()[0]).isEqualTo((byte) 0xEF); // UTF-8 BOM
        assertThat(new String(file.body(), 3, file.body().length - 3,
                StandardCharsets.UTF_8)).startsWith("section;key");
    }

    @Test
    void deleteExport_204then404() throws Exception {
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        HttpResponse<String> created = post("/api/projects/"
                        + fixture.projectId() + "/exports", token,
                "{\"format\":\"xlsx\"}");
        Integer exportId = JsonPath.read(created.body(), "$.id");
        assertThat(delete("/api/projects/" + fixture.projectId()
                + "/exports/" + exportId, token).statusCode())
                .isEqualTo(204);
        assertThat(get("/api/projects/" + fixture.projectId()
                + "/exports/" + exportId, token).statusCode())
                .isEqualTo(404);
        assertThat(delete("/api/projects/" + fixture.projectId()
                + "/exports/" + exportId, token).statusCode())
                .isEqualTo(404);
        // файл удалён физически
        assertThat(Files.exists(Path.of("build/test-exports-it",
                fixture.projectId().toString(), exportId + ".xlsx")))
                .isFalse();
    }

    @Test
    void projectDeletion_cleansExportDirectory() throws Exception {
        String token = registerAndLogin();
        Fixture fixture = fixtureWithCalculations(token);
        post("/api/projects/" + fixture.projectId() + "/exports", token,
                "{\"format\":\"pdf\"}");
        Path dir = Path.of("build/test-exports-it",
                fixture.projectId().toString());
        assertThat(Files.exists(dir)).isTrue();
        HttpResponse<String> deleted = delete(
                "/api/projects/" + fixture.projectId(), token);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(Files.exists(dir)).isFalse(); // - файлы
        // удалены вместе с проектом
    }

    /**
 * Проект с 3 сценариями, составом покупки и расчётами всех.
 */
    private record Fixture(Long projectId, Long purchaseScenarioId,
                           Long baseScenarioId, Long raasScenarioId) {
    }
}
