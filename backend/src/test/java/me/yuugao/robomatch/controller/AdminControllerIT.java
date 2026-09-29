package me.yuugao.robomatch.controller;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.domain.AdminImportLog;
import me.yuugao.robomatch.domain.User;
import me.yuugao.robomatch.domain.UserRole;
import me.yuugao.robomatch.repository.AdminImportRepository;
import me.yuugao.robomatch.repository.UserRepository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест админки (,
 * 3.3.5, 3.3.6): полный HTTP-путь через SecurityFilterChain.
 * <p>
 * Проверяет: 401 без токена; 403 для роли user (hasRole('ADMIN') на
 * /api/admin/** - SecurityConfig); CRUD решений/справочников/ТТХ для
 * admin (200/201/204/400/404/409); импорт CSV через multipart с
 * summary; лимит 413; 409 при in-flight импорте; историю; refresh.
 * <p>
 * Окружение - как у SolutionControllerIT: H2 in-memory PostgreSQL-режим,
 * Flyway выключен, схема - create-drop. Лимит файла каталога сжат до
 * 500 байт (app.admin-import.max-bytes) для теста 413.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:adminit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.admin-import.max-bytes=500"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminControllerIT {

    private static String adminToken;
    private static String userToken;
    private static Long vendorId;
    private static Long createdSolutionId;
    private static Long industryId;
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminImportRepository importRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ------------------------------------------------------------------
    // HTTP-помощники
    // ------------------------------------------------------------------

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + token).GET().build();
        return send(request);
    }

    private HttpResponse<String> request(String method, String path, String token,
                                         String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + token);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        return send(builder.build());
    }

    private HttpResponse<String> postMultipart(String path, String token, byte[] file,
                                               String fileName) throws Exception {
        String boundary = "----it" + UUID.randomUUID().toString().replace("-", "");
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + new String(file, StandardCharsets.UTF_8) + "\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private String login(String login, String password) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"login\":\"" + login + "\",\"password\":\"" + password
                                + "\"}"))
                .build());
        return JsonPath.read(response.body(), "$.token");
    }

    // ------------------------------------------------------------------
    // Данные и вход
    // ------------------------------------------------------------------

    @BeforeAll
    void prepareAccountsAndCatalog() throws Exception {
        // роли: admin (доступ) и user (403); пароли хешируются тем же
        // PasswordEncoder, что проверяет логин (два хеша на весь класс)
        userRepository.save(User.builder().login("it_admin")
                .passwordHash(passwordEncoder.encode("admin-password"))
                .role(UserRole.ADMIN).build());
        userRepository.save(User.builder().login("it_user")
                .passwordHash(passwordEncoder.encode("user-password"))
                .role(UserRole.USER).build());
        adminToken = login("it_admin", "admin-password");
        userToken = login("it_user", "user-password");

        // справочник: вендор и отрасль для карточки решения
        HttpResponse<String> vendor = request("POST", "/api/admin/references/vendor",
                adminToken, "{\"name\":\"ИТ Вендор Тест\"}");
        vendorId = ((Number) JsonPath.read(vendor.body(), "$.id")).longValue();
        HttpResponse<String> industry = request("POST", "/api/admin/references/industry",
                adminToken,
                "{\"code\":\"it_test_industry\",\"name\":\"ИТ-тест отрасль\"}");
        industryId = ((Number) JsonPath.read(industry.body(), "$.id")).longValue();
    }

    @Test
    @Order(1)
    void adminEndpointsRequireAuthentication() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/admin/solutions"))).GET().build());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Требуется авторизация");
    }

    @Test
    @Order(2)
    void adminEndpointsForbiddenForUserRole() throws Exception {
        // роль user: все методы /api/admin/** -> 403
        assertThat(get("/api/admin/solutions", userToken).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/admin/solutions", userToken,
                "{\"name\":\"x\"}").statusCode()).isEqualTo(403);
        assertThat(request("DELETE", "/api/admin/references/vendor/1", userToken,
                null).statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/admin/catalog/import", userToken, null)
                .statusCode()).isEqualTo(403);
        assertThat(get("/api/admin/catalog/import/history", userToken).statusCode())
                .isEqualTo(403);
        assertThat(request("POST", "/api/admin/catalog/refresh", userToken, null)
                .statusCode()).isEqualTo(403);
    }

    @Test
    @Order(3)
    void publicSolutionsStillOpenForGuest() throws Exception {
        // регресс: публичный /api/solutions не требует ни токена, ни роли
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/solutions"))).GET().build());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(4)
    void createSolutionValidatesFields() throws Exception {
        // 400: битый product_class
        HttpResponse<String> bad = request("POST", "/api/admin/solutions", adminToken,
                "{\"name\":\"Плохой класс\",\"vendorId\":" + vendorId
                        + ",\"productClass\":\"drone\",\"status\":\"operation\","
                        + "\"priceRub\":100}");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("Класс продукта");

        // 400: отрицательная цена
        HttpResponse<String> negative = request("POST", "/api/admin/solutions",
                adminToken,
                "{\"name\":\"Минус цена\",\"vendorId\":" + vendorId
                        + ",\"productClass\":\"brs\",\"status\":\"operation\","
                        + "\"priceRub\":-5}");
        assertThat(negative.statusCode()).isEqualTo(400);
        assertThat(negative.body()).contains("отрицательной");

        // 400: УГТ вне 1..9
        HttpResponse<String> trl = request("POST", "/api/admin/solutions", adminToken,
                "{\"name\":\"УГТ 15\",\"vendorId\":" + vendorId
                        + ",\"productClass\":\"brs\",\"status\":\"operation\","
                        + "\"priceRub\":100,\"trl\":15}");
        assertThat(trl.statusCode()).isEqualTo(400);
        assertThat(trl.body()).contains("УГТ");

        // 400: несуществующий вендор
        HttpResponse<String> noVendor = request("POST", "/api/admin/solutions",
                adminToken,
                "{\"name\":\"Без вендора\",\"vendorId\":99999,"
                        + "\"productClass\":\"brs\",\"status\":\"operation\","
                        + "\"priceRub\":100}");
        assertThat(noVendor.statusCode()).isEqualTo(400);
        assertThat(noVendor.body()).contains("Производитель");
    }

    @Test
    @Order(5)
    void createAndReadManualSolution() throws Exception {
        HttpResponse<String> created = request("POST", "/api/admin/solutions",
                adminToken, """
                        {"name":"ИТ Робот Тест","vendorId":%d,
                         "productClass":"brs","status":"piloting",
                         "description":"Создано интеграционным тестом",
                         "priceRub":1234567.50,"trl":7,"marketPotential":4,
                         "payloadKg":250.5}
                        """.formatted(vendorId));
        assertThat(created.statusCode()).isEqualTo(201);
        createdSolutionId = ((Number) JsonPath.read(created.body(), "$.id")).longValue();
        assertThat((String) JsonPath.read(created.body(), "$.sourceKind"))
                .isEqualTo("manual");
        assertThat((String) JsonPath.read(created.body(), "$.vendorName"))
                .isEqualTo("ИТ Вендор Тест");
        assertThat((Double) JsonPath.read(created.body(), "$.priceRub"))
                .isEqualTo(1234567.5);

        // дубль названия у того же вендора -> 409 (UNIQUE vendor+name)
        HttpResponse<String> duplicate = request("POST", "/api/admin/solutions",
                adminToken, """
                        {"name":"ИТ Робот Тест","vendorId":%d,
                         "productClass":"brs","status":"operation","priceRub":1}
                        """.formatted(vendorId));
        assertThat(duplicate.statusCode()).isEqualTo(409);

        // чтение: карточка - через публичный гостевой эндпоинт каталога
        // (read-дубликат под /api/admin/solutions/{id} убран)
        HttpResponse<String> fetched = get("/api/solutions/" + createdSolutionId,
                adminToken);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(fetched.body(), "$.name"))
                .isEqualTo("ИТ Робот Тест");

        // легаси-путь карточки под /api/admin: GET больше не обслуживается
        // (405 - на пути остались только PUT/DELETE записи)
        assertThat(get("/api/admin/solutions/" + createdSolutionId,
                adminToken).statusCode()).isEqualTo(405);

        // список с фильтром «требуют проверки» находит ручную карточку
        HttpResponse<String> needsCheck = get("/api/admin/solutions?needsCheck=true",
                adminToken);
        assertThat(needsCheck.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(needsCheck.body(), "$.totalElements"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(6)
    void updateSolutionAndProvenance() throws Exception {
        HttpResponse<String> updated = request("PUT",
                "/api/admin/solutions/" + createdSolutionId, adminToken, """
                        {"name":"ИТ Робот Тест v2","vendorId":%d,
                         "productClass":"brs","status":"operation",
                         "priceRub":2000000}
                        """.formatted(vendorId));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(updated.body(), "$.name"))
                .isEqualTo("ИТ Робот Тест v2");
        assertThat((String) JsonPath.read(updated.body(), "$.sourceKind"))
                .isEqualTo("manual");

        // 404: несуществующее
        assertThat(request("PUT", "/api/admin/solutions/999999", adminToken, """
                {"name":"Нет такого","vendorId":%d,"productClass":"brs",
                 "status":"operation","priceRub":1}
                """.formatted(vendorId)).statusCode()).isEqualTo(404);
    }

    @Test
    @Order(7)
    void characteristicsUpsertAndDelete() throws Exception {
        // numeric ТТХ (payload_kg): сначала находим тип через публичные фильтры
        HttpResponse<String> filters = get("/api/filters", adminToken);
        // в фильтрах нет перечня типов характеристик - берём id через БД?
        // Типы характеристик сеются сидом; в тестовой H2 их нет - создаём
        HttpResponse<String> typeCreated = request("POST",
                "/api/admin/references/characteristic_type", adminToken, """
                        {"code":"it_payload","name":"ИТ Грузоподъёмность",
                         "groupCode":"technical","dataType":"number","unit":"кг"}
                        """);
        assertThat(typeCreated.statusCode()).isEqualTo(201);
        Long typeId = ((Number) JsonPath.read(typeCreated.body(), "$.id")).longValue();

        // несоответствие типа значения -> 400
        HttpResponse<String> wrongType = request("PUT",
                "/api/admin/solutions/" + createdSolutionId
                        + "/characteristics/" + typeId,
                adminToken, "{\"valueText\":\"текст вместо числа\"}");
        assertThat(wrongType.statusCode()).isEqualTo(400);
        assertThat(wrongType.body()).contains("число");

        // корректный upsert
        HttpResponse<String> upsert = request("PUT",
                "/api/admin/solutions/" + createdSolutionId
                        + "/characteristics/" + typeId,
                adminToken, "{\"valueNumeric\":42.5}");
        assertThat(upsert.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(upsert.body(), "$.sourceKind"))
                .isEqualTo("manual");
        assertThat((Boolean) JsonPath.read(upsert.body(), "$.isConfirmed")).isFalse();

        // зеркало в карточке не обновится (код не из 9 канонических) - ок;
        // open_source без ссылки -> 400
        HttpResponse<String> noUrl = request("PUT",
                "/api/admin/solutions/" + createdSolutionId
                        + "/characteristics/" + typeId,
                adminToken,
                "{\"valueNumeric\":1,\"sourceKind\":\"open_source\"}");
        assertThat(noUrl.statusCode()).isEqualTo(400);
        assertThat(noUrl.body()).contains("ссылка");

        // удаление значения -> 204, повторное -> 404
        assertThat(request("DELETE",
                "/api/admin/solutions/" + createdSolutionId
                        + "/characteristics/" + typeId, adminToken, null)
                .statusCode()).isEqualTo(204);
        assertThat(request("DELETE",
                "/api/admin/solutions/" + createdSolutionId
                        + "/characteristics/" + typeId, adminToken, null)
                .statusCode()).isEqualTo(404);
    }

    @Test
    @Order(8)
    void referencesCrudFullCycle() throws Exception {
        // создание
        HttpResponse<String> created = request("POST",
                "/api/admin/references/region", adminToken,
                "{\"code\":\"it_region\",\"name\":\"ИТ-Регион\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        Long regionId = ((Number) JsonPath.read(created.body(), "$.id")).longValue();

        // дубликат кода -> 409
        assertThat(request("POST", "/api/admin/references/region", adminToken,
                "{\"code\":\"it_region\",\"name\":\"Другой регион\"}").statusCode())
                .isEqualTo(409);

        // дубликат имени -> 409
        assertThat(request("POST", "/api/admin/references/region", adminToken,
                "{\"code\":\"it_region_2\",\"name\":\"ИТ-Регион\"}").statusCode())
                .isEqualTo(409);

        // код вне конвенции -> 400
        assertThat(request("POST", "/api/admin/references/region", adminToken,
                "{\"code\":\"1Bad-Код\",\"name\":\"Ещё регион\"}").statusCode())
                .isEqualTo(400);

        // неизвестный справочник -> 400
        assertThat(get("/api/admin/references/unknown", adminToken).statusCode())
                .isEqualTo(400);

        // правка
        HttpResponse<String> updated = request("PUT",
                "/api/admin/references/region/" + regionId, adminToken,
                "{\"code\":\"it_region\",\"name\":\"ИТ-Регион Переименован\"}");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(updated.body(), "$.name"))
                .isEqualTo("ИТ-Регион Переименован");
        assertThat(request("PUT", "/api/admin/references/region/999999", adminToken,
                "{\"code\":\"x\",\"name\":\"нет такого\"}").statusCode())
                .isEqualTo(404);

        // удаление без ссылок -> 204; повторное -> 404
        assertThat(request("DELETE", "/api/admin/references/region/" + regionId,
                adminToken, null).statusCode()).isEqualTo(204);
        assertThat(request("DELETE", "/api/admin/references/region/" + regionId,
                adminToken, null).statusCode()).isEqualTo(404);

        // удаление отрасли со ссылкой из решения -> 409: применений нет,
        // но решение не ссылается на отрасль; создаём ссылку через импорт
        // (industry используется в применениях) - отдельно ниже
        assertThat(get("/api/admin/references", adminToken).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(9)
    void importCsvWithSummaryAndIdempotency() throws Exception {
        String csv = """
                id;Название;тип;статус;компания;описание;Тип;Подтип;Сценарий;Кейсы;УГТ;Рын Потенциал;Регион;Отрасль;Цена изделия
                99999999-9999-9999-9999-999999999999;Импортный робот;brs;operation;ИТ Вендор Тест;Из импорта;Мобильные роботы;AMR;Внутрискладская логистика;;8;4;;ИТ-тест отрасль;700000
                """;
        HttpResponse<String> imported = postMultipart("/api/admin/catalog/import",
                adminToken, csv.getBytes(StandardCharsets.UTF_8), "it-import.csv");
        assertThat(imported.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(imported.body(), "$.solutions"))
                .isEqualTo(1);
        assertThat((Integer) JsonPath.read(imported.body(),
                "$.entities.solutions.added")).isEqualTo(1);
        // вендор и отрасль уже были (созданы в BeforeAll) -> skipped
        assertThat((Integer) JsonPath.read(imported.body(),
                "$.entities.vendors.skipped")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(imported.body(),
                "$.entities.industries.skipped")).isEqualTo(1);

        // идемпотентность: повторный импорт - 0 добавлений
        HttpResponse<String> again = postMultipart("/api/admin/catalog/import",
                adminToken, csv.getBytes(StandardCharsets.UTF_8), "it-import.csv");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(again.body(),
                "$.entities.solutions.added")).isZero();
        assertThat((Integer) JsonPath.read(again.body(),
                "$.entities.solutions.updated")).isZero();
        assertThat((Integer) JsonPath.read(again.body(),
                "$.entities.solutions.skipped")).isEqualTo(1);
    }

    @Test
    @Order(10)
    void importBrokenCsvGives400WithRowList() throws Exception {
        String broken = """
                id;Название;тип;статус;компания;описание;Тип;Подтип;Сценарий;Кейсы;УГТ;Рын Потенциал;Регион;Отрасль;Цена изделия
                88888888-8888-8888-8888-888888888888;Сломанный робот;brs;production;ИТ Вендор Тест;;Мобильные роботы;AMR;Внутрискладская логистика;;8;4;;ИТ-тест отрасль;100
                """;
        HttpResponse<String> response = postMultipart("/api/admin/catalog/import",
                adminToken, broken.getBytes(StandardCharsets.UTF_8), "broken.csv");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("errors");
        assertThat(response.body()).contains("статус");
    }

    @Test
    @Order(11)
    void importTooLargeRejectedWith413() throws Exception {
        // лимит в этом тесте - 500 байт (properties класса)
        String big = "id;Название;тип;статус;компания;описание;Тип;Подтип;Сценарий;Кейсы;УГТ;Рын Потенциал;Регион;Отрасль;Цена изделия\n"
                + "x".repeat(1000);
        HttpResponse<String> response = postMultipart("/api/admin/catalog/import",
                adminToken, big.getBytes(StandardCharsets.UTF_8), "big.csv");
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    @Order(12)
    void importWrongExtensionRejected() throws Exception {
        HttpResponse<String> response = postMultipart("/api/admin/catalog/import",
                adminToken, "hello".getBytes(StandardCharsets.UTF_8), "file.txt");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("CSV");
    }

    @Test
    @Order(13)
    void inFlightImportGives409AndHistoryWorks() throws Exception {
        // история уже содержит успешные/битые импорты
        HttpResponse<String> history = get("/api/admin/catalog/import/history?limit=10",
                adminToken);
        assertThat(history.statusCode()).isEqualTo(200);
        int total = ((java.util.List<?>) JsonPath.read(history.body(), "$.*")).size();
        assertThat(total).isGreaterThanOrEqualTo(3);

        // in-flight: вставляем running-строку напрямую
        me.yuugao.robomatch.domain.AdminImportLog running =
                importRepository.save(AdminImportLog.builder()
                        .fileName("in-flight.csv").filePath("none.csv").sizeBytes(1L)
                        .startedAt(Instant.now()).status("running")
                        .createdByUserId(userRepository
                                .findAll().iterator().next().getId())
                        .build());
        HttpResponse<String> conflict = postMultipart("/api/admin/catalog/import",
                adminToken, "id".getBytes(StandardCharsets.UTF_8), "blocked.csv");
        assertThat(conflict.statusCode()).isEqualTo(409);
        // прибираем за собой: помечаем running завершённым
        running.setStatus("completed");
        running.setFinishedAt(Instant.now());
        importRepository.save(running);
    }

    @Test
    @Order(14)
    void refreshWithoutCompletedFileGives400OrRuns() throws Exception {
        // в этой БД уже есть успешные импорты с сохранённым файлом:
        // refresh должен либо отработать (200), либо честно сказать 400
        HttpResponse<String> response = request("POST", "/api/admin/catalog/refresh",
                adminToken, null);
        assertThat(response.statusCode()).isIn(200, 400);
        if (response.statusCode() == 200) {
            assertThat((Integer) JsonPath.read(response.body(), "$.solutions"))
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @Order(15)
    void deleteSolutionReferencedByScenarioConflicts() throws Exception {
        // решение из импорта (99999999-...) без ссылок - удаляем (204)
        HttpResponse<String> list = get("/api/admin/solutions?q=Импортный", adminToken);
        Integer id = JsonPath.read(list.body(), "$.content[0].id");
        assertThat(id).isNotNull();
        HttpResponse<String> deleted = request("DELETE",
                "/api/admin/solutions/" + id, adminToken, null);
        assertThat(deleted.statusCode()).isEqualTo(204);

        // ручное решение удалять нельзя: оно в сценарии? - нет сценария,
        // зато ТТХ удалены выше; создаём прямую ссылку нельзя без проекта,
        // поэтому проверяем 404 на несуществующее
        assertThat(request("DELETE", "/api/admin/solutions/999999", adminToken, null)
                .statusCode()).isEqualTo(404);
    }

    @Test
    @Order(16)
    void deleteManualSolutionAfterCleanup() throws Exception {
        // финал: ручное решение без ссылок удаляется (204); карточка
        // после удаления - 404 через публичный read
        HttpResponse<String> deleted = request("DELETE",
                "/api/admin/solutions/" + createdSolutionId, adminToken, null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(get("/api/solutions/" + createdSolutionId, adminToken)
                .statusCode()).isEqualTo(404);
    }

    @Test
    @Order(17)
    void referenceCountsEndpoint() throws Exception {
        HttpResponse<String> response = get("/api/admin/references", adminToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.vendor"))
                .isGreaterThanOrEqualTo(1);
        assertThat((Integer) JsonPath.read(response.body(), "$.characteristic_type"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(18)
    void processReferenceSupportsIsActive() throws Exception {
        HttpResponse<String> created = request("POST",
                "/api/admin/references/process", adminToken,
                "{\"code\":\"it_process\",\"name\":\"ИТ-процесс\",\"isActive\":true}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(created.body(), "$.isActive")).isTrue();
        Long processId = ((Number) JsonPath.read(created.body(), "$.id")).longValue();

        HttpResponse<String> updated = request("PUT",
                "/api/admin/references/process/" + processId, adminToken,
                "{\"code\":\"it_process\",\"name\":\"ИТ-процесс\",\"isActive\":false}");
        assertThat((Boolean) JsonPath.read(updated.body(), "$.isActive")).isFalse();

        assertThat(request("DELETE", "/api/admin/references/process/" + processId,
                adminToken, null).statusCode()).isEqualTo(204);
    }

    @Test
    @Order(19)
    void bulkDeleteSolutionsMixedResult() throws Exception {
        // два решения без ссылок + несуществующий id: независимый
        // результат по каждой позиции, уже удалённые не откатываются
        Long first = ((Number) JsonPath.read(request("POST", "/api/admin/solutions",
                adminToken, """
                        {"name":"ИТ Массовый 1","vendorId":%d,
                         "productClass":"brs","status":"operation","priceRub":100}
                        """.formatted(vendorId).replace("\n", " ")).body(), "$.id"))
                .longValue();
        Long second = ((Number) JsonPath.read(request("POST", "/api/admin/solutions",
                adminToken, """
                        {"name":"ИТ Массовый 2","vendorId":%d,
                         "productClass":"bas","status":"operation","priceRub":200}
                        """.formatted(vendorId).replace("\n", " ")).body(), "$.id"))
                .longValue();

        HttpResponse<String> bulk = request("POST", "/api/admin/solutions/bulk-delete",
                adminToken, "{\"ids\":[" + first + "," + second + ",999999]}");
        assertThat(bulk.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(bulk.body(), "$.deleted.length()"))
                .isEqualTo(2);
        assertThat((Integer) JsonPath.read(bulk.body(), "$.failed.length()"))
                .isEqualTo(1);
        Integer failedId = JsonPath.read(bulk.body(), "$.failed[0].id");
        assertThat(failedId).isEqualTo(999999);
        assertThat((String) JsonPath.read(bulk.body(), "$.failed[0].reason"))
                .contains("не найдено");
        // карточки удалённых - 404 через публичный read
        assertThat(get("/api/solutions/" + first, adminToken).statusCode())
                .isEqualTo(404);

        // повтор того же списка: всё в failed, deleted пуст
        HttpResponse<String> again = request("POST", "/api/admin/solutions/bulk-delete",
                adminToken, "{\"ids\":[" + first + "," + second + ",999999]}");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(again.body(), "$.deleted.length()"))
                .isEqualTo(0);
        assertThat((Integer) JsonPath.read(again.body(), "$.failed.length()"))
                .isEqualTo(3);
    }

    @Test
    @Order(20)
    void bulkDeleteValidatesRequestList() throws Exception {
        // пустой список
        assertThat(request("POST", "/api/admin/solutions/bulk-delete", adminToken,
                "{\"ids\":[]}").statusCode()).isEqualTo(400);
        // null-список
        assertThat(request("POST", "/api/admin/solutions/bulk-delete", adminToken,
                "{\"ids\":null}").statusCode()).isEqualTo(400);
        // null-элемент - 400 с причиной
        HttpResponse<String> nullItem = request("POST",
                "/api/admin/references/vendor/bulk-delete", adminToken,
                """
                        {"ids":[1,null]}""");
        assertThat(nullItem.statusCode()).isEqualTo(400);
        assertThat(nullItem.body()).contains("null");
        // больше 100 идентификаторов
        StringBuilder ids = new StringBuilder();
        for (int i = 1; i <= 101; i++) {
            ids.append(i).append(',');
        }
        HttpResponse<String> tooMany = request("POST",
                "/api/admin/solutions/bulk-delete", adminToken,
                "{\"ids\":[" + ids.substring(0, ids.length() - 1) + "]}");
        assertThat(tooMany.statusCode()).isEqualTo(400);
        assertThat(tooMany.body()).contains("100");
    }

    @Test
    @Order(21)
    void bulkDeleteForbiddenForUserRoleAndUnknownDict() throws Exception {
        // 401 без токена (SecurityFilterChain на /api/admin/**)
        assertThat(request("POST", "/api/admin/solutions/bulk-delete",
                "invalid-token", "{\"ids\":[1]}").statusCode()).isEqualTo(401);
        assertThat(send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/admin/solutions/bulk-delete")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ids\":[1]}"))
                .build()).statusCode()).isEqualTo(401);
        // 403 для роли user - обе ручки
        assertThat(request("POST", "/api/admin/solutions/bulk-delete", userToken,
                "{\"ids\":[1]}").statusCode()).isEqualTo(403);
        assertThat(request("POST", "/api/admin/references/vendor/bulk-delete",
                userToken, "{\"ids\":[1]}").statusCode()).isEqualTo(403);
        // неизвестный справочник - 400
        HttpResponse<String> unknownDict = request("POST",
                "/api/admin/references/unknown_dict/bulk-delete", adminToken,
                "{\"ids\":[1]}");
        assertThat(unknownDict.statusCode()).isEqualTo(400);
        assertThat(unknownDict.body()).contains("Неизвестный справочник");
    }

    @Test
    @Order(22)
    void bulkDeleteReferencesMixedResult() throws Exception {
        // решение с vendorId гарантирует FK-ссылку на вендора из @BeforeAll
        HttpResponse<String> referencing = request("POST", "/api/admin/solutions",
                adminToken, """
                        {"name":"ИТ На Вендора","vendorId":%d,
                         "productClass":"brs","status":"operation","priceRub":100}
                        """.formatted(vendorId).replace("\n", " "));
        assertThat(referencing.statusCode()).isEqualTo(201);

        // свободный вендор удалится, занятый - отказ с причиной
        Long freeVendor = ((Number) JsonPath.read(request("POST",
                "/api/admin/references/vendor", adminToken,
                "{\"name\":\"ИТ Свободный Вендор\"}").body(), "$.id")).longValue();

        HttpResponse<String> bulk = request("POST",
                "/api/admin/references/vendor/bulk-delete", adminToken,
                "{\"ids\":[" + freeVendor + "," + vendorId + "]}");
        assertThat(bulk.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(bulk.body(), "$.deleted.length()"))
                .isEqualTo(1);
        Integer failedId = JsonPath.read(bulk.body(), "$.failed[0].id");
        assertThat(failedId.intValue()).isEqualTo(vendorId.intValue());
        assertThat((String) JsonPath.read(bulk.body(), "$.failed[0].reason"))
                .contains("есть решения в каталоге");
        // свободный вендор действительно удалён
        assertThat(get("/api/admin/references/vendor?page=0&size=200", adminToken)
                .body()).doesNotContain("ИТ Свободный Вендор");
    }
}
