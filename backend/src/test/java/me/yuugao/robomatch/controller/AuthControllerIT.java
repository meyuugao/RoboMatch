package me.yuugao.robomatch.controller;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест аутентификации: полный HTTP-путь через
 * SecurityFilterChain (JwtAuthFilter -> правила путей -> контроллер).
 * <p>
 * Окружение: H2 in-memory в режиме PostgreSQL, Flyway выключен, схема
 * создаётся Hibernate (create-drop) - тесту нужны только таблицы
 * сущностей user и solution. Секрет JWT задан явно (fail-fast проверка
 * JwtService не должна ронять тестовый контекст).
 * <p>
 * HTTP-клиент - встроенный java.net.http.HttpClient (чёрный ящик,
 * RANDOM_PORT): не зависит от переименований тестовых утилит Boot 4
 * (TestRestTemplate переехал в отдельный модуль), тело ответа читается
 * через JsonPath из spring-boot-starter-test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:authit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AuthControllerIT {

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    private int port;
    @Autowired
    private org.springframework.context.ApplicationContext context;

    private static String registerBody() {
        // уникальный логин на вызов - тесты не зависят друг от друга
        String login = "it_user_" + UUID.randomUUID().toString().substring(0, 8);
        return "{\"login\":\"" + login + "\",\"password\":\"password123\"}";
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void contextStarts_withBeans() {
        // контекст поднялся: SecurityConfig, JwtService, UserService, контроллеры
        assertThat(context.getBean(me.yuugao.robomatch.config.SecurityConfig.class)).isNotNull();
        assertThat(context.getBean(me.yuugao.robomatch.security.JwtService.class)).isNotNull();
    }

    @Test
    void register_returns201AndTokenWithUserRole() throws Exception {
        HttpResponse<String> response = post("/api/auth/register", registerBody());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(response.body(), "$.token")).isNotBlank();
        assertThat((String) JsonPath.read(response.body(), "$.user.role")).isEqualTo("user");
        assertThat((Object) JsonPath.read(response.body(), "$.user.createdAt")).isNotNull();
    }

    @Test
    void registerSameLoginTwice_returns409() throws Exception {
        String body = registerBody();
        post("/api/auth/register", body);

        HttpResponse<String> second = post("/api/auth/register", body);

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(second.body(), "$.message")).isEqualTo("Логин уже занят");
    }

    @Test
    void registerShortPassword_returns400() throws Exception {
        String body = "{\"login\":\"it_short_pw\", \"password\":\"short\"}";

        HttpResponse<String> response = post("/api/auth/register", body);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message")).contains("Пароль");
    }

    @Test
    void loginAfterRegister_returns200AndToken() throws Exception {
        String body = registerBody();
        String login = JsonPath.read(body, "$.login");
        post("/api/auth/register", body);

        HttpResponse<String> response = post("/api/auth/login",
                "{\"login\":\"" + login + "\",\"password\":\"password123\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.token")).isNotBlank();
        assertThat((String) JsonPath.read(response.body(), "$.user.login")).isEqualTo(login);
    }

    @Test
    void loginWrongPassword_returns401() throws Exception {
        String body = registerBody();
        String login = JsonPath.read(body, "$.login");
        post("/api/auth/register", body);

        HttpResponse<String> response = post("/api/auth/login",
                "{\"login\":\"" + login + "\",\"password\":\"wrong-password\"}");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .isEqualTo("Неверный логин или пароль");
    }

    @Test
    void loginUnknownLogin_returns401SameMessage() throws Exception {
        // несуществующий логин: то же единое сообщение и тот же объём
        // работы bcrypt (фиктивный хеш) - защита от timing-перечисления
        HttpResponse<String> response = post("/api/auth/login",
                "{\"login\":\"no_such_user_at_all\",\"password\":\"whatever-123\"}");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .isEqualTo("Неверный логин или пароль");
    }

    @Test
    void meWithoutToken_returns401() throws Exception {
        HttpResponse<String> response = get("/api/auth/me", null);

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .isEqualTo("Требуется авторизация");
    }

    @Test
    void meWithGarbageToken_returns401() throws Exception {
        // битый/подделанный токен: JwtAuthFilter отбрасывает аутентификацию,
        // запрос доходит до security-цепочки неаутентифицированным -> 401
        // (а не 500 и не 403)
        HttpResponse<String> response = get("/api/auth/me", "garbage.token.here");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .isEqualTo("Требуется авторизация");
    }

    @Test
    void meWithToken_returns200AndLogin() throws Exception {
        String body = registerBody();
        String login = JsonPath.read(body, "$.login");
        HttpResponse<String> registered = post("/api/auth/register", body);
        String token = JsonPath.read(registered.body(), "$.token");

        HttpResponse<String> response = get("/api/auth/me", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.login")).isEqualTo(login);
        assertThat((String) JsonPath.read(response.body(), "$.role")).isEqualTo("user");
    }

    @Test
    void userTokenOnAdminPath_returns403() throws Exception {
        String body = registerBody();
        HttpResponse<String> registered = post("/api/auth/register", body);
        String token = JsonPath.read(registered.body(), "$.token");

        HttpResponse<String> response = get("/api/admin/anything", token);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .isEqualTo("Недостаточно прав");
    }

    @Test
    void unknownPathWithToken_returns404() throws Exception {
        String body = registerBody();
        HttpResponse<String> registered = post("/api/auth/register", body);
        String token = JsonPath.read(registered.body(), "$.token");

        HttpResponse<String> response = get("/api/unknown-path", token);

        // NoResourceFoundException -> 404 (не 500 от catch-all)
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .isEqualTo("Эндпоинт не найден");
    }
}
