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
 * Интеграционный тест сценариев:
 * полный HTTP-путь через SecurityFilterChain (/api/projects/** —
 * authenticated) до контроллера, сервисов и H2-БД.
 * <p>
 * Ключевые сценарии: 401 без токена, изоляция (чужой проект — 404 на
 * всех эндпоинтах), CRUD сценариев (создание с type и восстановление
 * недостающих, правка имени/состава, удаление), дубликат имени — 409
 * (UNIQUE project_id+name, V1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:scenit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioControllerIT {

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    int port;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ObjectTypeRepository objectTypeRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private VendorRepository vendorRepository;
    @Autowired
    private SolutionRepository solutionRepository;
    @Autowired
    private ScenarioRepository scenarioRepository;
    private Long warehouseTypeId;
    private Long solutionId;

    @BeforeAll
    void seedFixtures() {
        transactionTemplate.executeWithoutResult(tx -> {
            ObjectType warehouse = objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse").name("Склад").isCalcEnabled(true)
                    .dataSourceNote("Тест").build());
            warehouseTypeId = warehouse.getId();
            Vendor vendor = vendorRepository.save(Vendor.builder()
                    .name("ООО «ТестРобот»").build());
            Solution robot = solutionRepository.save(Solution.builder()
                    .name("Робот Тестовый").vendorId(vendor.getId())
                    .productClass("brs").status("operation")
                    .priceRub(new BigDecimal("2500000")).trl((short) 9)
                    .sourceKind("organizer_catalog")
                    .sourceUrl("catalog_export_v4.csv").build());
            solutionId = robot.getId();
        });
    }

    @AfterAll
    void close() {
        client.close();
    }

    // ------------------------------------------------------------------
    // Хелперы
    // ------------------------------------------------------------------

    private String registerAndLogin() throws Exception {
        String login = "scn_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = post("/api/auth/register", null,
                "{\"login\":\"" + login + "\",\"password\":\"password123\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    /**
 * Id сценария по типу (фильтр читается списком — надёжно в Jayway).
 */
    private Integer scenarioIdByType(String body, String type) {
        List<Map<String, Object>> rows = JsonPath.read(body,
                "$[?(@.type == '" + type + "')]");
        return (Integer) rows.get(0).get("id");
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

    private HttpResponse<String> delete(String path, String token)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).DELETE();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------
    // 401 без токена (SecurityConfig: /api/projects/** — authenticated)
    // ------------------------------------------------------------------

    @Test
    void noToken_returns401() throws Exception {
        assertThat(get("/api/projects/1/scenarios", null).statusCode()).isEqualTo(401);
        assertThat(post("/api/projects/1/scenarios", null, "{}").statusCode())
                .isEqualTo(401);
        assertThat(put("/api/projects/1/scenarios/1", null, "{}").statusCode())
                .isEqualTo(401);
        assertThat(delete("/api/projects/1/scenarios/1", null).statusCode())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // Изоляция: чужой проект — 404 на всех эндпоинтах
    // ------------------------------------------------------------------

    @Test
    void foreignProject_returns404() throws Exception {
        String ownerToken = registerAndLogin();
        Long projectId = createProject(ownerToken);
        String strangerToken = registerAndLogin();
        assertThat(get("/api/projects/" + projectId + "/scenarios", strangerToken)
                .statusCode()).isEqualTo(404);
        assertThat(post("/api/projects/" + projectId + "/scenarios", strangerToken,
                "{\"type\":\"purchase\"}").statusCode()).isEqualTo(404);
        assertThat(put("/api/projects/" + projectId + "/scenarios/1", strangerToken,
                "{\"name\":\"Чужой\"}").statusCode()).isEqualTo(404);
        assertThat(delete("/api/projects/" + projectId + "/scenarios/1",
                strangerToken).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // CRUD
    // ------------------------------------------------------------------

    @Test
    void createMissing_bootstrapThreeScenarios() throws Exception {
        // Проект без сценариев (например, создан до запуска подбора): POST без
        // тела создаёт все недостающие сценарии по умолчанию
        String token = registerAndLogin();
        Long projectId = createProject(token);
        HttpResponse<String> response = post(
                "/api/projects/" + projectId + "/scenarios", token, "");
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(response.body(), "$.length()"))
                .isEqualTo(3);
        assertThat(JsonPath.read(response.body(), "$[0].type").toString())
                .isEqualTo("base");
        assertThat(JsonPath.read(response.body(), "$[1].type").toString())
                .isEqualTo("purchase");
        assertThat(JsonPath.read(response.body(), "$[2].type").toString())
                .isEqualTo("raas");
        // Повтор — все три уже есть: 400 с подсказкой
        assertThat(post("/api/projects/" + projectId + "/scenarios", token, "")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void crudScenario_renameAndComposition() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);
        post("/api/projects/" + projectId + "/scenarios", token, "");

        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        assertThat(list.statusCode()).isEqualTo(200);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");

        // Создание конкретного сценария (второй purchase с составом)
        HttpResponse<String> created = post(
                "/api/projects/" + projectId + "/scenarios", token,
                "{\"type\":\"purchase\",\"name\":\"Покупка 2027\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        List<Map<String, Object>> createdRows = JsonPath.read(created.body(),
                "$[?(@.name == 'Покупка 2027')]");
        assertThat(createdRows.get(0).get("type")).isEqualTo("purchase");

        // Дубликат имени — 409 (UNIQUE project_id+name)
        assertThat(post("/api/projects/" + projectId + "/scenarios", token,
                "{\"type\":\"purchase\",\"name\":\"Покупка 2027\"}").statusCode())
                .isEqualTo(409);

        // Правка состава: замещение целиком, ручная строка требует причину
        HttpResponse<String> updated = put(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId, token,
                "{\"solutions\":[{\"solutionId\":" + solutionId
                        + ",\"quantity\":3,\"manual\":true,"
                        + "\"manualReason\":\"Пилот на 3 робота\"}]}");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(updated.body(), "$.solutions.length()"))
                .isEqualTo(1);
        assertThat((Integer) JsonPath.read(updated.body(),
                "$.solutions[0].quantity")).isEqualTo(3);
        assertThat(JsonPath.read(updated.body(), "$.solutions[0].manual")
                .toString()).isEqualTo("true");

        // Ручная строка без причины — 400
        HttpResponse<String> noReason = put(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId, token,
                "{\"solutions\":[{\"solutionId\":" + solutionId
                        + ",\"manual\":true}]}");
        assertThat(noReason.statusCode()).isEqualTo(400);

        // Переименование + дубликат имени — 409
        assertThat(put("/api/projects/" + projectId + "/scenarios/" + purchaseId,
                token, "{\"name\":\"Покупка 2027\"}").statusCode()).isEqualTo(409);
        assertThat(put("/api/projects/" + projectId + "/scenarios/" + purchaseId,
                token, "{\"name\":\"Покупка (основной)\"}").statusCode())
                .isEqualTo(200);

        // Несуществующее решение в составе — 404
        assertThat(put("/api/projects/" + projectId + "/scenarios/" + purchaseId,
                token, "{\"solutions\":[{\"solutionId\":999999}]}").statusCode())
                .isEqualTo(404);

        // Состав больше 200 позиций — 400 (@Size:
        // без лимита один запрос с 10^5 строк исчерпал бы пул)
        StringBuilder huge = new StringBuilder("[");
        for (int i = 0; i <= 200; i++) {
            if (i > 0) {
                huge.append(',');
            }
            huge.append("{\"solutionId\":").append(solutionId).append('}');
        }
        huge.append(']');
        assertThat(put("/api/projects/" + projectId + "/scenarios/" + purchaseId,
                token, "{\"solutions\":" + huge + "}").statusCode())
                .isEqualTo(400);

        // Удаление: 204, сценария больше нет; каскад состава — схемой V1
        Long extraId = ((Integer) createdRows.get(0).get("id")).longValue();
        assertThat(delete("/api/projects/" + projectId + "/scenarios/" + extraId,
                token).statusCode()).isEqualTo(204);
        assertThat(scenarioRepository.findById(extraId)).isEmpty();
        // Сценарий чужого проекта — 404
        String stranger = registerAndLogin();
        assertThat(delete("/api/projects/" + projectId + "/scenarios/" + purchaseId,
                stranger).statusCode()).isEqualTo(404);
    }

    @Test
    void scenarioOfForeignProject_notFound() throws Exception {
        // Прямая проверка изоляции сценария: сценарий проекта A недоступен
        // через проект B (404, не 403 — не раскрываем существование)
        String ownerA = registerAndLogin();
        Long projectA = createProject(ownerA);
        post("/api/projects/" + projectA + "/scenarios", ownerA, "");
        Scenario any = scenarioRepository.findAll().stream()
                .filter(s -> s.getProjectId().equals(projectA))
                .filter(s -> s.getType() == ScenarioType.PURCHASE).findFirst()
                .orElseThrow();
        String ownerB = registerAndLogin();
        Long projectB = createProject(ownerB);
        assertThat(get("/api/projects/" + projectB + "/scenarios", ownerB)
                .statusCode()).isEqualTo(200);
        assertThat(put("/api/projects/" + projectB + "/scenarios/" + any.getId(),
                ownerB, "{\"name\":\"Захват\"}").statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Инвариант: base не содержит решений
    // ------------------------------------------------------------------

    @Test
    void manualAddToBase_rejected400_purchase201() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);
        post("/api/projects/" + projectId + "/scenarios", token, "");
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer baseId = scenarioIdByType(list.body(), "base");
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");

        // ручное добавление в base — 400 с понятным сообщением
        HttpResponse<String> toBase = post("/api/projects/" + projectId
                        + "/scenarios/" + baseId + "/solutions", token,
                "{\"solutionId\":" + solutionId
                        + ",\"manualReason\":\"Пилот\"}");
        assertThat(toBase.statusCode()).isEqualTo(400);
        assertThat(toBase.body()).contains("Базовый сценарий не содержит решений");

        // PUT с непустым составом в base — тоже 400
        assertThat(put("/api/projects/" + projectId + "/scenarios/" + baseId,
                token, "{\"solutions\":[{\"solutionId\":" + solutionId
                        + ",\"quantity\":1}]}").statusCode()).isEqualTo(400);

        // в purchase — 201 (регресс: разрешённый путь не сломан)
        assertThat(post("/api/projects/" + projectId + "/scenarios/"
                        + purchaseId + "/solutions", token,
                "{\"solutionId\":" + solutionId
                        + ",\"manualReason\":\"Пилот на складе\"}")
                .statusCode()).isEqualTo(201);
    }

    // ------------------------------------------------------------------
    // Точечное изменение состава: DELETE/PUT одной строки
    // ------------------------------------------------------------------

    @Test
    void compositionRow_deleteAndUpdate() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);
        post("/api/projects/" + projectId + "/scenarios", token, "");
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        // подготовка: одна строка состава (ручное добавление)
        assertThat(post("/api/projects/" + projectId + "/scenarios/"
                        + purchaseId + "/solutions", token,
                "{\"solutionId\":" + solutionId
                        + ",\"manualReason\":\"Пилот\",\"quantity\":2}")
                .statusCode()).isEqualTo(201);

        // PUT количества: 200, новая сумма пересчитана
        HttpResponse<String> updated = put(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId
                        + "/solutions/" + solutionId, token,
                "{\"quantity\":3}");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(updated.body(), "$.quantity"))
                .isEqualTo(3);
        assertThat((Double) JsonPath.read(updated.body(), "$.sumRub"))
                .isEqualTo(7_500_000.0);

        // quantity = 0 — 400 (валидация приложения + CHECK V1)
        assertThat(put("/api/projects/" + projectId + "/scenarios/"
                        + purchaseId + "/solutions/" + solutionId, token,
                "{\"quantity\":0}").statusCode()).isEqualTo(400);

        // несуществующая позиция — 404
        assertThat(put("/api/projects/" + projectId + "/scenarios/"
                        + purchaseId + "/solutions/999999", token,
                "{\"quantity\":2}").statusCode()).isEqualTo(404);

        // DELETE: 204, в списке состава позиции больше нет
        assertThat(delete("/api/projects/" + projectId + "/scenarios/"
                + purchaseId + "/solutions/" + solutionId, token)
                .statusCode()).isEqualTo(204);
        HttpResponse<String> after = get(
                "/api/projects/" + projectId + "/scenarios", token);
        List<Map<String, Object>> rows = JsonPath.read(after.body(),
                "$[?(@.id == " + purchaseId + ")].solutions");
        assertThat(((List<?>) rows.get(0))).isEmpty();

        // повторное DELETE — 404 (позиции уже нет)
        assertThat(delete("/api/projects/" + projectId + "/scenarios/"
                + purchaseId + "/solutions/" + solutionId, token)
                .statusCode()).isEqualTo(404);
    }

    @Test
    void compositionRow_isolationAnd401() throws Exception {
        // 401 без токена на новых эндпоинтах (SecurityConfig: /api/**)
        assertThat(put("/api/projects/1/scenarios/1/solutions/1", null,
                "{\"quantity\":2}").statusCode()).isEqualTo(401);
        assertThat(delete("/api/projects/1/scenarios/1/solutions/1", null)
                .statusCode()).isEqualTo(401);

        // чужой проект/сценарий — 404 на новых эндпоинтах
        String owner = registerAndLogin();
        Long projectId = createProject(owner);
        post("/api/projects/" + projectId + "/scenarios", owner, "");
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", owner);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");
        assertThat(post("/api/projects/" + projectId + "/scenarios/"
                        + purchaseId + "/solutions", owner,
                "{\"solutionId\":" + solutionId
                        + ",\"manualReason\":\"Пилот\"}")
                .statusCode()).isEqualTo(201);
        String stranger = registerAndLogin();
        assertThat(put("/api/projects/" + projectId + "/scenarios/"
                        + purchaseId + "/solutions/" + solutionId, stranger,
                "{\"quantity\":5}").statusCode()).isEqualTo(404);
        assertThat(delete("/api/projects/" + projectId + "/scenarios/"
                + purchaseId + "/solutions/" + solutionId, stranger)
                .statusCode()).isEqualTo(404);
        // владелец по-прежнему может (состав не тронут чужим 404-м)
        assertThat(put("/api/projects/" + projectId + "/scenarios/"
                        + purchaseId + "/solutions/" + solutionId, owner,
                "{\"quantity\":4}").statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // Ручное добавление решения: состав — домен сценариев
    // ------------------------------------------------------------------

    @Test
    void manualAdd_201_newPath_and_legacyPath404() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token);
        post("/api/projects/" + projectId + "/scenarios", token, "");
        HttpResponse<String> list = get(
                "/api/projects/" + projectId + "/scenarios", token);
        Integer purchaseId = scenarioIdByType(list.body(), "purchase");

        // новый путь — домен сценариев (полный CRUD состава в одном месте)
        HttpResponse<String> added = post(
                "/api/projects/" + projectId + "/scenarios/" + purchaseId
                        + "/solutions",
                token, "{\"solutionId\":" + solutionId
                        + ",\"quantity\":2,\"manualReason\":\"Пилот\"}");
        assertThat(added.statusCode()).isEqualTo(201);

        // строка появилась в составе сценария
        HttpResponse<String> after = get(
                "/api/projects/" + projectId + "/scenarios", token);
        List<Map<String, Object>> rows = JsonPath.read(after.body(),
                "$[?(@.type == 'purchase')].solutions[?(@.solutionId == "
                        + solutionId + ")]");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("quantity")).isEqualTo(2);

        // легаси-путь под /selection больше не существует — 404
        assertThat(post("/api/projects/" + projectId
                        + "/selection/scenarios/" + purchaseId + "/solutions", token,
                "{\"solutionId\":" + solutionId
                        + ",\"manualReason\":\"ещё\"}").statusCode())
                .isEqualTo(404);

        // base — 400: базовый сценарий не содержит решений
        Integer baseId = scenarioIdByType(list.body(), "base");
        assertThat(post(
                "/api/projects/" + projectId + "/scenarios/" + baseId
                        + "/solutions",
                token, "{\"solutionId\":" + solutionId
                        + ",\"manualReason\":\"причина\"}").statusCode())
                .isEqualTo(400);
    }
}
