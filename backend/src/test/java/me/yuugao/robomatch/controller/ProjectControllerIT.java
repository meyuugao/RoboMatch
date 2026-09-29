package me.yuugao.robomatch.controller;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.domain.ObjectType;
import me.yuugao.robomatch.repository.ObjectTypeRepository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест проектов: полный
 * HTTP-путь через SecurityFilterChain (/api/projects/** - authenticated)
 * до контроллера, сервиса, репозитория и H2-БД.
 * <p>
 * Ключевые сценарии - изоляция (чужой проект 404, не 403), дубликат
 * имени 409, копирование с именем «(копия)»/«(копия 2)», каскадное
 * поведение не проверяем - на H2 (create-drop от сущностей) внешних
 * ключей-каскадов нет, их гарантирует схема V1 на PostgreSQL.
 * <p>
 * Окружение как в AuthControllerIT: H2 in-memory в режиме PostgreSQL,
 * Flyway выключен, HTTP-клиент - java.net.http.HttpClient (чёрный ящик).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:projectsit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectControllerIT {

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectTypeRepository objectTypeRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    private Long warehouseTypeId;

    @BeforeAll
    void seed() {
        transactionTemplate.executeWithoutResult(tx -> {
            ObjectType warehouse = objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse").name("Склад").isCalcEnabled(true).build());
            objectTypeRepository.save(ObjectType.builder()
                    .code("airport").name("Аэропорт").isCalcEnabled(false).build());
            warehouseTypeId = warehouse.getId();
        });
    }

    // ------------------------------------------------------------------
    // Хелперы
    // ------------------------------------------------------------------

    /**
 * Регистрирует пользователя и возвращает его JWT.
 */
    private String registerAndLogin() throws Exception {
        String login = "prj_" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"login\":\"" + login + "\",\"password\":\"password123\"}";
        HttpResponse<String> response = post("/api/auth/register", null, body);
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    /**
 * Тело создания проекта с уникальным именем.
 */
    private String createBody(String name) {
        return "{\"name\":\"" + name + "\",\"description\":\"Описание " + name
                + "\",\"objectTypeId\":" + warehouseTypeId + "}";
    }

    private HttpResponse<String> post(String path, String token, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher body = json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        return client.send(builder.POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String token, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
 * Создаёт проект и возвращает его id.
 */
    private Long createProject(String token, String name) throws Exception {
        HttpResponse<String> response = post("/api/projects", token, createBody(name));
        assertThat(response.statusCode()).isEqualTo(201);
        return ((Integer) JsonPath.read(response.body(), "$.id")).longValue();
    }

    // ------------------------------------------------------------------
    // Доступ
    // ------------------------------------------------------------------

    @Test
    void listWithoutToken_returns401() throws Exception {
        HttpResponse<String> response = get("/api/projects", null);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void createWithoutToken_returns401() throws Exception {
        HttpResponse<String> response = post("/api/projects", null, createBody("Без токена"));
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void allMutationsWithoutToken_return401() throws Exception {
        // 401 проверяется на всех мутациях, а не только list/create -
        // изоляция не должна зависеть от одного правила путей
        assertThat(get("/api/projects/1", null).statusCode()).isEqualTo(401);
        assertThat(put("/api/projects/1", null, "{\"name\":\"x\"}")
                .statusCode()).isEqualTo(401);
        assertThat(post("/api/projects/1/copy", null, null).statusCode()).isEqualTo(401);
        assertThat(delete("/api/projects/1", null).statusCode()).isEqualTo(401);
    }

    @Test
    void allOperationsOnMissingProject_return404() throws Exception {
        // 404 на несуществующем id - не только на GET: все операции
        // должны одинаково отвечать 404
        String token = registerAndLogin();
        assertThat(put("/api/projects/999999", token, "{\"name\":\"x\"}")
                .statusCode()).isEqualTo(404);
        assertThat(post("/api/projects/999999/copy", token, null).statusCode()).isEqualTo(404);
        assertThat(delete("/api/projects/999999", token).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Список и изоляция
    // ------------------------------------------------------------------

    @Test
    void list_returnsOnlyOwnProjects() throws Exception {
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        createProject(alice, "Проект Алисы " + UUID.randomUUID().toString().substring(0, 4));
        createProject(alice, "Ещё проект Алисы " + UUID.randomUUID().toString().substring(0, 4));
        createProject(bob, "Проект Боба " + UUID.randomUUID().toString().substring(0, 4));

        HttpResponse<String> aliceList = get("/api/projects", alice);
        HttpResponse<String> bobList = get("/api/projects", bob);

        assertThat(aliceList.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(aliceList.body(), "$.totalElements")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(bobList.body(), "$.totalElements")).isEqualTo(1);
        // стабильная обёртка PageResponse - единый контракт с каталогом
        assertThat((String) JsonPath.read(aliceList.body(), "$.content[0].objectTypeName"))
                .isEqualTo("Склад");
        assertThat((String) JsonPath.read(aliceList.body(), "$.content[0].status"))
                .isEqualTo("draft");
    }

    @Test
    void list_invalidPagination_returns400() throws Exception {
        String token = registerAndLogin();
        assertThat(get("/api/projects?page=-1", token).statusCode()).isEqualTo(400);
        assertThat(get("/api/projects?size=0", token).statusCode()).isEqualTo(400);
        assertThat(get("/api/projects?size=101", token).statusCode()).isEqualTo(400);
        assertThat(get("/api/projects?size=100", token).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // Создание
    // ------------------------------------------------------------------

    @Test
    void create_returns201WithCard() throws Exception {
        String token = registerAndLogin();
        String name = "Новый склад " + UUID.randomUUID().toString().substring(0, 4);

        HttpResponse<String> response = post("/api/projects", token, createBody(name));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(response.body(), "$.name")).isEqualTo(name);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("draft");
        assertThat((String) JsonPath.read(response.body(), "$.objectTypeCode"))
                .isEqualTo("warehouse");
        assertThat((Boolean) JsonPath.read(response.body(), "$.objectTypeIsCalcEnabled"))
                .isTrue();
    }

    @Test
    void createDuplicateName_returns409() throws Exception {
        String token = registerAndLogin();
        String name = "Дубль " + UUID.randomUUID().toString().substring(0, 4);
        createProject(token, name);

        HttpResponse<String> duplicate = post("/api/projects", token, createBody(name));

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(duplicate.body(), "$.message"))
                .contains("уже существует");
    }

    @Test
    void createInvalidBodies_return400() throws Exception {
        String token = registerAndLogin();
        // пустое имя
        assertThat(post("/api/projects", token,
                "{\"name\":\"\",\"objectTypeId\":" + warehouseTypeId + "}").statusCode())
                .isEqualTo(400);
        // имя длиннее 128
        String longName = "x".repeat(129);
        assertThat(post("/api/projects", token,
                "{\"name\":\"" + longName + "\",\"objectTypeId\":" + warehouseTypeId + "}")
                .statusCode()).isEqualTo(400);
        // описание длиннее 1024
        String longDescription = "d".repeat(1025);
        assertThat(post("/api/projects", token,
                "{\"name\":\"С длинным описанием\",\"description\":\"" + longDescription
                        + "\",\"objectTypeId\":" + warehouseTypeId + "}").statusCode())
                .isEqualTo(400);
        // без типа объекта
        assertThat(post("/api/projects", token, "{\"name\":\"Без типа\"}").statusCode())
                .isEqualTo(400);
        // неизвестный тип объекта
        assertThat(post("/api/projects", token,
                "{\"name\":\"Неизвестный тип\",\"objectTypeId\":999999}").statusCode())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Карточка и изоляция
    // ------------------------------------------------------------------

    @Test
    void getOwnProject_returns200() throws Exception {
        String token = registerAndLogin();
        Long id = createProject(token, "Мой склад " + UUID.randomUUID().toString().substring(0, 4));

        HttpResponse<String> response = get("/api/projects/" + id, token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.id")).isEqualTo(id.intValue());
    }

    @Test
    void getForeignProject_returns404not403() throws Exception {
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        Long aliceProject = createProject(alice, "Чужой склад");

        HttpResponse<String> response = get("/api/projects/" + aliceProject, bob);

        // 404, не 403: не раскрываем существование чужих проектов
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void getUnknownProject_returns404() throws Exception {
        String token = registerAndLogin();
        assertThat(get("/api/projects/999999", token).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Редактирование
    // ------------------------------------------------------------------

    @Test
    void updateOwnProject_returns200() throws Exception {
        String token = registerAndLogin();
        Long id = createProject(token, "До правки");

        HttpResponse<String> response = put("/api/projects/" + id, token,
                "{\"name\":\"После правки\",\"description\":\"Новое описание\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.name")).isEqualTo("После правки");
        assertThat((String) JsonPath.read(response.body(), "$.description"))
                .isEqualTo("Новое описание");
    }

    @Test
    void updateForeignProject_returns404() throws Exception {
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        Long aliceProject = createProject(alice, "Чужое не правится");

        HttpResponse<String> response =
                put("/api/projects/" + aliceProject, bob, "{\"name\":\"Взлом\"}");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void updateToDuplicateName_returns409() throws Exception {
        String token = registerAndLogin();
        createProject(token, "Первый");
        Long second = createProject(token, "Второй");

        HttpResponse<String> response =
                put("/api/projects/" + second, token, "{\"name\":\"Первый\"}");

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    void updateInvalidBody_returns400() throws Exception {
        String token = registerAndLogin();
        Long id = createProject(token, "Валидация правки");

        assertThat(put("/api/projects/" + id, token, "{\"name\":\"\"}").statusCode())
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // Копирование
    // ------------------------------------------------------------------

    @Test
    void copyProject_returns201WithCopyName() throws Exception {
        String token = registerAndLogin();
        Long id = createProject(token, "Оригинал для копии");

        HttpResponse<String> copy1 = post("/api/projects/" + id + "/copy", token, null);
        assertThat(copy1.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(copy1.body(), "$.name")).isEqualTo("Оригинал для копии (копия)");
        assertThat((String) JsonPath.read(copy1.body(), "$.status")).isEqualTo("draft");

        Long copyId = ((Integer) JsonPath.read(copy1.body(), "$.id")).longValue();
        HttpResponse<String> copy2 = post("/api/projects/" + id + "/copy", token, null);
        assertThat(copy2.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(copy2.body(), "$.name"))
                .isEqualTo("Оригинал для копии (копия 2)");
        assertThat((Integer) JsonPath.read(copy2.body(), "$.id")).isNotEqualTo(copyId.intValue());
    }

    @Test
    void copyLongName_fits128_andCopyIsEditable() throws Exception {
        // имя 124 символа + " (копия)" = 132 - раньше превышало
        // лимит; теперь усекается до 128, и копию можно сохранить PUT-ом
        String token = registerAndLogin();
        String longName = "Д".repeat(124);
        Long id = createProject(token, longName);

        HttpResponse<String> copy = post("/api/projects/" + id + "/copy", token, null);

        assertThat(copy.statusCode()).isEqualTo(201);
        String copyName = JsonPath.read(copy.body(), "$.name");
        assertThat(copyName.length()).isEqualTo(128);
        assertThat(copyName).endsWith("(копия)");
        Long copyId = ((Integer) JsonPath.read(copy.body(), "$.id")).longValue();

        // копию с усечённым именем можно отредактировать: PUT с корректным
        // телом (в т.ч. новым именем в пределах 128) проходит валидацию
        HttpResponse<String> rename = put("/api/projects/" + copyId, token,
                "{\"name\":\"Короткое имя копии\",\"description\":\"после правки\"}");
        assertThat(rename.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(rename.body(), "$.name"))
                .isEqualTo("Короткое имя копии");
        assertThat((String) JsonPath.read(rename.body(), "$.description"))
                .isEqualTo("после правки");
    }

    @Test
    void copyForeignProject_returns404() throws Exception {
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        Long aliceProject = createProject(alice, "Не копируется чужое");

        HttpResponse<String> response = post("/api/projects/" + aliceProject + "/copy", bob, null);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Удаление
    // ------------------------------------------------------------------

    @Test
    void deleteOwnProject_returns204_then404() throws Exception {
        String token = registerAndLogin();
        Long id = createProject(token, "На удаление");

        assertThat(delete("/api/projects/" + id, token).statusCode()).isEqualTo(204);
        assertThat(get("/api/projects/" + id, token).statusCode()).isEqualTo(404);
    }

    @Test
    void deleteForeignProject_returns404() throws Exception {
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        Long aliceProject = createProject(alice, "Не удаляется чужое");

        assertThat(delete("/api/projects/" + aliceProject, bob).statusCode()).isEqualTo(404);
        // проект Алисы жив
        assertThat(get("/api/projects/" + aliceProject, alice).statusCode()).isEqualTo(200);
    }
}
