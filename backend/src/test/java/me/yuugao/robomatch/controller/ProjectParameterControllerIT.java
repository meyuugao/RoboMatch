package me.yuugao.robomatch.controller;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.domain.ObjectType;
import me.yuugao.robomatch.domain.ObjectTypeParameter;
import me.yuugao.robomatch.domain.ParameterType;
import me.yuugao.robomatch.domain.ParameterValueType;
import me.yuugao.robomatch.repository.ObjectTypeParameterRepository;
import me.yuugao.robomatch.repository.ObjectTypeRepository;
import me.yuugao.robomatch.repository.ParameterTypeRepository;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест параметров объекта (
 * 3.2.2–3.2.5, 4.3.4, 4.4.6): полный HTTP-путь через SecurityFilterChain
 * (/api/projects/** — authenticated) до контроллера, сервиса, репозиториев
 * и H2-БД; хранилище вложений — реальное, во временном каталоге
 * (проверяется физическое наличие/удаление файлов).
 * <p>
 * Ключевые сценарии: изоляция (чужой проект 404 на всех эндпоинтах),
 * валидация типов/диапазонов/обязательности, атомарность
 * импорта (ошибка в файле — прежние значения не тронуты),
 * шаблон, вложения, удаление проекта вместе с файлами.
 * <p>
 * Окружение как в ProjectControllerIT: H2 in-memory (PostgreSQL-режим),
 * Flyway выключен, create-drop от сущностей, HTTP — java.net.http.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:parametersit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // лимит multipart занижен относительно прод-дефолта (10 МБ):
        // проверяем механизм servlet-413 компактным телом — большой файл
        // заставляет Tomcat рвать соединение на середине записи клиента
        // (java.net.http не может это прочитать), маленькое тело в
        // сокет-буфере дописывается и 413 спокойно читается
        "spring.servlet.multipart.max-file-size=16KB"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectParameterControllerIT {

    /**
 * Реальный корень хранилища на время теста (файлы проверяются на диске).
 */
    static final Path UPLOAD_ROOT;

    static {
        try {
            UPLOAD_ROOT = Files.createTempDirectory("robomatch-it-uploads");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectTypeRepository objectTypeRepository;
    @Autowired
    private ObjectTypeParameterRepository objectTypeParameterRepository;
    @Autowired
    private ParameterTypeRepository parameterTypeRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    private Long warehouseTypeId;
    private Long airportTypeId;
    private Long areaParamId;
    private Long payrollParamId;
    private Long pickingLinesParamId;
    private Long pickingUnitsParamId;
    private Long shiftsParamId;
    private Long wmsParamId;
    private Long floorParamId;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("app.upload.root", () -> UPLOAD_ROOT.toAbsolutePath().toString());
    }

    @AfterAll
    static void cleanUploadRoot() throws IOException {
        try (Stream<Path> paths = Files.walk(UPLOAD_ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // временный каталог — не важно
                }
            });
        }
    }

    @BeforeAll
    void seed() {
        transactionTemplate.executeWithoutResult(tx -> {
            ObjectType warehouse = objectTypeRepository.save(ObjectType.builder()
                    .code("warehouse").name("Склад").isCalcEnabled(true).build());
            ObjectType airport = objectTypeRepository.save(ObjectType.builder()
                    .code("airport").name("Аэропорт").isCalcEnabled(false).build());
            warehouseTypeId = warehouse.getId();
            airportTypeId = airport.getId();

            ParameterType area = parameterTypeRepository.save(ParameterType.builder()
                    .code("total_warehouse_area").name("Общая площадь склада")
                    .unit("кв. м").valueType(ParameterValueType.NUMBER).build());
            ParameterType shifts = parameterTypeRepository.save(ParameterType.builder()
                    .code("shifts_per_day").name("Смен в сутки")
                    .unit("шт").valueType(ParameterValueType.NUMBER).build());
            ParameterType wms = parameterTypeRepository.save(ParameterType.builder()
                    .code("has_wms").name("Наличие WMS")
                    .unit(null).valueType(ParameterValueType.BOOLEAN).build());
            ParameterType floor = parameterTypeRepository.save(ParameterType.builder()
                    .code("floor_type").name("Тип покрытия")
                    .unit(null).valueType(ParameterValueType.TEXT).build());
            ParameterType passengerFlow = parameterTypeRepository.save(ParameterType.builder()
                    .code("passenger_flow").name("Пассажиропоток")
                    .unit("чел/год").valueType(ParameterValueType.NUMBER).build());
            // worst-case параметры: фиксированная константа и производный
            ParameterType payroll = parameterTypeRepository.save(ParameterType.builder()
                    .code("payroll_tax_rate").name("Коэффициент начислений на ФОТ")
                    .unit(null).valueType(ParameterValueType.NUMBER).build());
            ParameterType pickingLines = parameterTypeRepository.save(ParameterType.builder()
                    .code("picking_lines_per_day").name("Объём отбора (строк/сутки, всего)")
                    .unit("строк/сут").valueType(ParameterValueType.NUMBER).build());
            ParameterType pickingUnits = parameterTypeRepository.save(ParameterType.builder()
                    .code("picking_units_per_day").name("Объём отбора (штук/сутки, всего)")
                    .unit("шт./сут").valueType(ParameterValueType.NUMBER).build());

            areaParamId = objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                    .objectTypeId(warehouse.getId()).parameterTypeId(area.getId())
                    .groupName("Общие").isRequired(true)
                    .defaultValueNumeric(new java.math.BigDecimal("5000"))
                    .minValue(new java.math.BigDecimal("1"))
                    .maxValue(new java.math.BigDecimal("1000000"))
                    .sourceNote("Датасеты_хакатон.xlsx, лист «Склад»").build()).getId();
            shiftsParamId = objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                    .objectTypeId(warehouse.getId()).parameterTypeId(shifts.getId())
                    .groupName("Общие").isRequired(true)
                    .defaultValueNumeric(new java.math.BigDecimal("2"))
                    .minValue(new java.math.BigDecimal("1"))
                    .maxValue(new java.math.BigDecimal("4")).build()).getId();
            wmsParamId = objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                    .objectTypeId(warehouse.getId()).parameterTypeId(wms.getId())
                    .groupName("Общие").isRequired(false)
                    .defaultValueBool(true).build()).getId();
            floorParamId = objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                    .objectTypeId(warehouse.getId()).parameterTypeId(floor.getId())
                    .groupName("Инфраструктура").isRequired(false)
                    .defaultValueText("Бетон").build()).getId();
            objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                    .objectTypeId(airport.getId()).parameterTypeId(passengerFlow.getId())
                    .groupName("Общие").isRequired(true)
                    .defaultValueNumeric(new java.math.BigDecimal("1000000")).build());
            payrollParamId = objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                    .objectTypeId(warehouse.getId()).parameterTypeId(payroll.getId())
                    .groupName("Персонал").isRequired(true).isFixed(true)
                    .defaultValueNumeric(new java.math.BigDecimal("1.302"))
                    .minValue(new java.math.BigDecimal("1.302"))
                    .maxValue(new java.math.BigDecimal("1.302"))
                    .sourceNote("Легенда XLSX").build()).getId();
            pickingLinesParamId = objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                    .objectTypeId(warehouse.getId()).parameterTypeId(pickingLines.getId())
                    .groupName("Операции").isRequired(true)
                    .defaultValueNumeric(new java.math.BigDecimal("100000"))
                    .minValue(new java.math.BigDecimal("50000"))
                    .maxValue(new java.math.BigDecimal("500000")).build()).getId();
            pickingUnitsParamId = objectTypeParameterRepository.save(ObjectTypeParameter.builder()
                    .objectTypeId(warehouse.getId()).parameterTypeId(pickingUnits.getId())
                    .groupName("Операции").isRequired(true).isDerived(true)
                    .defaultValueNumeric(new java.math.BigDecimal("150000"))
                    .minValue(new java.math.BigDecimal("75000"))
                    .maxValue(new java.math.BigDecimal("750000")).build()).getId();
        });
    }

    // ------------------------------------------------------------------
    // Хелперы
    // ------------------------------------------------------------------

    private String registerAndLogin() throws Exception {
        String login = "prm_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = post("/api/auth/register", null,
                "{\"login\":\"" + login + "\",\"password\":\"password123\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return JsonPath.read(response.body(), "$.token");
    }

    private Long createProject(String token, String name) throws Exception {
        HttpResponse<String> response = post("/api/projects", token,
                "{\"name\":\"" + name + "\",\"description\":\"IT\",\"objectTypeId\":"
                        + warehouseTypeId + "}");
        assertThat(response.statusCode()).isEqualTo(201);
        return ((Integer) JsonPath.read(response.body(), "$.id")).longValue();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

    private HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
 * POST multipart/form-data с одним файлом (java.net.http не умеет сам).
 */
    private HttpResponse<String> postMultipart(String path, String token, String fileName,
                                               String mime, byte[] content) throws Exception {
        String boundary = "----robomatch" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write("--" + boundary + "\r\n");
        writer.write("Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\n");
        writer.write("Content-Type: " + mime + "\r\n\r\n");
        writer.flush();
        out.write(content);
        writer.write("\r\n--" + boundary + "--\r\n");
        writer.flush();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
 * Минимальный XLSX «шаблонного» вида: строка 1 — коды, строка 2 — значения.
 */
    private byte[] xlsx(String[] header, String[] values) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Значения");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                headerRow.createCell(i).setCellValue(header[i]);
            }
            Row valueRow = sheet.createRow(1);
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    continue;
                }
                try {
                    valueRow.createCell(i).setCellValue(Double.parseDouble(values[i]));
                } catch (NumberFormatException ex) {
                    valueRow.createCell(i).setCellValue(values[i]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // JsonPath-хелперы, устойчивые к порядку параметров в ответе
    // (порядок зависит от коллации БД: «Инфраструктура» < «Общие» в
    // кириллице) — все проверки по коду параметра
    // ------------------------------------------------------------------

    /**
 * Поле параметра по коду (JsonPath-фильтр); null — параметр/поле нет.
 */
    private Object fieldOf(String body, String code, String field) {
        List<Object> matches = JsonPath.read(body,
                "$[?(@.code=='" + code + "')]." + field);
        if (matches.isEmpty() || matches.get(0) == null) {
            return null;
        }
        return matches.get(0);
    }

    /**
 * currentValue.value параметра по коду; null — значения нет.
 */
    private Object currentValueOf(String body, String code) {
        List<Object> matches = JsonPath.read(body,
                "$[?(@.code=='" + code + "')].currentValue");
        if (matches.isEmpty() || matches.get(0) == null) {
            return null;
        }
        return ((java.util.Map<String, Object>) matches.get(0)).get("value");
    }

    // ------------------------------------------------------------------
    // Доступ: 401 без токена на всех эндпоинтах
    // ------------------------------------------------------------------

    @Test
    void allEndpoints_withoutToken_401() throws Exception {
        Long projectId = createProject(registerAndLogin(), "Без токена 401");
        String base = "/api/projects/" + projectId + "/parameters";
        assertThat(get(base, null).statusCode()).isEqualTo(401);
        assertThat(put(base + "/" + areaParamId, null, "{\"value\":1}").statusCode())
                .isEqualTo(401);
        assertThat(delete(base + "/" + areaParamId, null).statusCode()).isEqualTo(401);
        assertThat(get(base + "/attachments", null).statusCode()).isEqualTo(401);
        assertThat(get(base + "/template", null).statusCode()).isEqualTo(401);
        // import без токена — тоже 401 (до multipart-разбора)
        assertThat(postMultipart(base + "/import", null, "import.csv", "text/csv",
                utf8("code\n1\n")).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // Список параметров
    // ------------------------------------------------------------------

    @Test
    void list_returnsMetadataAndCurrentValues() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Список параметров");

        HttpResponse<String> response =
                get("/api/projects/" + projectId + "/parameters", token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        // 7 параметров склада (4 редактируемых + 3 worst-case A1:
        // фиксированный ФОТ, строки отбора, производные штук/сутки);
        // аэропортовый не должен попасть
        assertThat((Integer) JsonPath.read(response.body(), "$.length()")).isEqualTo(7);
        assertThat((String) fieldOf(response.body(), "total_warehouse_area", "name"))
                .isEqualTo("Общая площадь склада");
        assertThat((String) fieldOf(response.body(), "total_warehouse_area", "unit"))
                .isEqualTo("кв. м");
        assertThat((Boolean) fieldOf(response.body(), "total_warehouse_area",
                "isRequired")).isTrue();
        assertThat((Double) fieldOf(response.body(), "total_warehouse_area",
                "minValue")).isEqualTo(1.0);
        // дефолт и источник норматива видны пользователю
        assertThat((Double) fieldOf(response.body(), "total_warehouse_area",
                "defaultValue.value")).isEqualTo(5000.0);
        assertThat((String) fieldOf(response.body(), "total_warehouse_area",
                "sourceNote")).contains("Датасеты");
        // значения ещё нет
        assertThat(currentValueOf(response.body(), "total_warehouse_area")).isNull();
    }

    // ------------------------------------------------------------------
    // Изоляция: чужой проект — 404 на всех эндпоинтах
    // ------------------------------------------------------------------

    @Test
    void foreignProject_allEndpoints_404() throws Exception {
        String owner = registerAndLogin();
        String stranger = registerAndLogin();
        Long projectId = createProject(owner, "Чужой проект 404");
        String base = "/api/projects/" + projectId + "/parameters";

        assertThat(get(base, stranger).statusCode()).isEqualTo(404);
        assertThat(put(base + "/" + areaParamId, stranger, "{\"value\":100}")
                .statusCode()).isEqualTo(404);
        assertThat(delete(base + "/" + areaParamId, stranger).statusCode()).isEqualTo(404);
        assertThat(get(base + "/attachments", stranger).statusCode()).isEqualTo(404);
        assertThat(get(base + "/template", stranger).statusCode()).isEqualTo(404);
        assertThat(postMultipart(base + "/import", stranger, "import.csv", "text/csv",
                utf8("total_warehouse_area\n100\n")).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Ручной ввод
    // ------------------------------------------------------------------

    @Test
    void putNumber_savesAndShowsValue() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Ручной ввод");
        String base = "/api/projects/" + projectId + "/parameters";

        HttpResponse<String> put = put(base + "/" + areaParamId, token, "{\"value\":4200}");
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(((Number) JsonPath.read(put.body(), "$.currentValue.value")).doubleValue())
                .isEqualTo(4200.0);
        assertThat((Object) JsonPath.read(put.body(), "$.updatedAt")).isNotNull();

        HttpResponse<String> list = get(base, token);
        assertThat(((Number) currentValueOf(list.body(), "total_warehouse_area")).doubleValue())
                .isEqualTo(4200.0);
    }

    @Test
    void putBooleanAndText_ok() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Булевы и текстовые");
        String base = "/api/projects/" + projectId + "/parameters";

        HttpResponse<String> bool = put(base + "/" + wmsParamId, token, "{\"value\":false}");
        assertThat(bool.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(bool.body(), "$.currentValue.value")).isFalse();

        HttpResponse<String> text = put(base + "/" + floorParamId, token,
                "{\"value\":\"Эпоксидное покрытие\"}");
        assertThat(text.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(text.body(), "$.currentValue.value"))
                .isEqualTo("Эпоксидное покрытие");
    }

    @Test
    void putOutOfRange_400WithRangeText() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Вне диапазона");
        HttpResponse<String> response = put(
                "/api/projects/" + projectId + "/parameters/" + areaParamId,
                token, "{\"value\":2000000}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .contains("от 1 до 1000000")
                .contains("кв. м");
    }

    @Test
    void putRequiredNull_400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Обязательный null");
        HttpResponse<String> response = put(
                "/api/projects/" + projectId + "/parameters/" + areaParamId,
                token, "{\"value\":null}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .contains("обязательный");
    }

    @Test
    void putWrongType_400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Неверный тип");
        // строка вместо числа
        assertThat(put("/api/projects/" + projectId + "/parameters/" + areaParamId,
                token, "{\"value\":\"много\"}").statusCode()).isEqualTo(400);
        // число вместо логического
        assertThat(put("/api/projects/" + projectId + "/parameters/" + wmsParamId,
                token, "{\"value\":1}").statusCode()).isEqualTo(400);
    }

    @Test
    void putAirportParameterOnWarehouseProject_404() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Чужой тип параметра");
        Long airportParamId = objectTypeParameterRepository
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(airportTypeId)
                .get(0).getId();
        HttpResponse<String> response = put(
                "/api/projects/" + projectId + "/parameters/" + airportParamId,
                token, "{\"value\":100}");
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void deleteValue_204AndIdempotent() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Сброс значения");
        String base = "/api/projects/" + projectId + "/parameters";

        assertThat(put(base + "/" + areaParamId, token, "{\"value\":4200}").statusCode())
                .isEqualTo(200);
        assertThat(delete(base + "/" + areaParamId, token).statusCode()).isEqualTo(204);
        // повторный сброс отсутствующего значения — тоже 204
        assertThat(delete(base + "/" + areaParamId, token).statusCode()).isEqualTo(204);

        HttpResponse<String> list = get(base, token);
        assertThat(currentValueOf(list.body(), "total_warehouse_area")).isNull();
    }

    // ------------------------------------------------------------------
    // Импорт
    // ------------------------------------------------------------------

    @Test
    void importValidCsv_writesValuesAndAttachment() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Импорт CSV");
        String base = "/api/projects/" + projectId + "/parameters";

        String csv = "total_warehouse_area;shifts_per_day;has_wms;floor_type\n"
                + "4200,5;2;да;Бетон\n";
        HttpResponse<String> response = postMultipart(base + "/import", token,
                "import.csv", "text/csv", utf8(csv));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.importedCount")).isEqualTo(4);
        Integer attachmentId = JsonPath.read(response.body(), "$.attachmentId");

        // значения записаны (source=import)
        HttpResponse<String> list = get(base, token);
        assertThat(((Number) currentValueOf(list.body(), "total_warehouse_area")).doubleValue())
                .isEqualTo(4200.5);
        assertThat((Boolean) currentValueOf(list.body(), "has_wms")).isTrue();
        assertThat((String) currentValueOf(list.body(), "floor_type")).isEqualTo("Бетон");

        // вложение в истории, файл физически на диске
        HttpResponse<String> attachments = get(base + "/attachments", token);
        assertThat(attachments.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(attachments.body(), "$.length()")).isEqualTo(1);
        Path stored = UPLOAD_ROOT.resolve(projectId.toString())
                .resolve(attachmentId + "_import.csv");
        assertThat(stored).exists();

        // хранилище: tmp-зона пуста (файл перенесён, не скопирован)
        assertThat(Files.exists(UPLOAD_ROOT.resolve("tmp").resolve(stored.getFileName())))
                .isFalse();
    }

    @Test
    void importXlsx_200() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Импорт XLSX");
        String base = "/api/projects/" + projectId + "/parameters";

        byte[] excel = xlsx(
                new String[]{"total_warehouse_area", "shifts_per_day", "has_wms"},
                new String[]{"3000", "3", "нет"});
        HttpResponse<String> response = postMultipart(base + "/import", token,
                "import.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excel);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.importedCount")).isEqualTo(3);

        HttpResponse<String> list = get(base, token);
        assertThat(((Number) currentValueOf(list.body(), "total_warehouse_area")).doubleValue())
                .isEqualTo(3000.0);
        assertThat((Boolean) currentValueOf(list.body(), "has_wms")).isFalse();
    }

    @Test
    void importBadCell_400_nothingChanged() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Атомарность");
        String base = "/api/projects/" + projectId + "/parameters";

        // до импорта: ручное значение, которое обязано уцелеть
        assertThat(put(base + "/" + areaParamId, token, "{\"value\":4200}").statusCode())
                .isEqualTo(200);

        // в файле корректная площадь, но смена 99 (диапазон 1..4) — весь
        // файл отклоняется, БД не меняется
        String csv = "total_warehouse_area;shifts_per_day\n4200;99\n";
        HttpResponse<String> response = postMultipart(base + "/import", token,
                "import.csv", "text/csv", utf8(csv));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message"))
                .contains("Ничего не сохранено");
        assertThat((Integer) JsonPath.read(response.body(), "$.errors.length()")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(response.body(), "$.errors[0].row")).isEqualTo(2);
        assertThat((String) JsonPath.read(response.body(), "$.errors[0].parameterCode"))
                .isEqualTo("shifts_per_day");

        // прежнее значение не тронуто
        HttpResponse<String> list = get(base, token);
        assertThat(((Number) currentValueOf(list.body(), "total_warehouse_area")).doubleValue())
                .isEqualTo(4200.0);
        assertThat(currentValueOf(list.body(), "shifts_per_day"))
                .as("shifts_per_day не должен быть записан из битого файла").isNull();
        // вложение не создано
        assertThat((Integer) JsonPath.read(
                get(base + "/attachments", token).body(), "$.length()")).isEqualTo(0);
    }

    @Test
    void importWrongMime_400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Неверный MIME");
        HttpResponse<String> response = postMultipart(
                "/api/projects/" + projectId + "/parameters/import", token,
                "report.pdf", "application/pdf", utf8("%PDF-1.4 garbage"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message")).contains("Excel");
    }

    @Test
    void importTooLarge_servletLevel413() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Слишком большой файл");
        // тело заметно больше тестового лимита multipart (16 КБ) — servlet
        // отклоняет загрузку ДО контроллера: MaxUploadSizeExceededException
        // -> 413 (прод-лимит 10 МБ проверяет сервисный слой + handler)
        byte[] bigBody = new byte[128 * 1024];
        HttpResponse<String> response = postMultipart(
                "/api/projects/" + projectId + "/parameters/import", token,
                "import.csv", "text/csv", bigBody);
        assertThat(response.statusCode()).isEqualTo(413);
    }

    // ------------------------------------------------------------------
    // Шаблон
    // ------------------------------------------------------------------

    @Test
    void templateXlsx_200WithContentType() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Шаблон xlsx");
        HttpResponse<String> response =
                get("/api/projects/" + projectId + "/parameters/template", token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.headers().firstValue("Content-Disposition").orElse(""))
                .contains("parameters_warehouse_template.xlsx");
    }

    @Test
    void templateCsv_200AndUnknownFormat_400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Шаблон csv");
        HttpResponse<String> csv = get(
                "/api/projects/" + projectId + "/parameters/template?format=csv", token);
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse(""))
                .contains("text/csv");
        // коды параметров склада в заголовке
        assertThat(csv.body()).contains("total_warehouse_area");

        HttpResponse<String> bad = get(
                "/api/projects/" + projectId + "/parameters/template?format=pdf", token);
        assertThat(bad.statusCode()).isEqualTo(400);
    }

    @Test
    void template_roundTrip_csvImportedByTemplate() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Шаблон туда-обратно");
        String base = "/api/projects/" + projectId + "/parameters";

        String template = get(base + "/template?format=csv", token).body();
        // строка значений строится ПО КОЛОНКАМ ШАБЛОНА (порядок кодов в
        // шаблоне = порядок метаданных, но тест не зависит от него)
        String headerLine = template.split("\r?\n")[0].replace("\uFEFF", "");
        java.util.Map<String, String> values = java.util.Map.of(
                "total_warehouse_area", "1500",
                "shifts_per_day", "1",
                "has_wms", "нет",
                "floor_type", "Бетон",
                // worst-case A1: строки отбора редактируемы и есть в шаблоне;
                // фиксированный ФОТ и производные штук/сутки в шаблон НЕ входят
                "picking_lines_per_day", "100000");
        StringBuilder row = new StringBuilder();
        for (String code : headerLine.split(";")) {
            if (row.length() > 0) {
                row.append(';');
            }
            row.append(values.get(code));
        }
        String filled = template + row + "\n";
        HttpResponse<String> response = postMultipart(base + "/import", token,
                "import.csv", "text/csv", utf8(filled));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.importedCount")).isEqualTo(5);
    }

    // ------------------------------------------------------------------
    // Вложения (удаление файла с диска)
    // ------------------------------------------------------------------

    @Test
    void deleteAttachment_204_fileRemovedFromDisk() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Удаление вложения");
        String base = "/api/projects/" + projectId + "/parameters";

        String csv = "total_warehouse_area\n4200\n";
        HttpResponse<String> imported = postMultipart(base + "/import", token,
                "import.csv", "text/csv", utf8(csv));
        assertThat(imported.statusCode()).isEqualTo(200);
        Integer attachmentId = JsonPath.read(imported.body(), "$.attachmentId");
        Path stored = UPLOAD_ROOT.resolve(projectId.toString())
                .resolve(attachmentId + "_import.csv");
        assertThat(stored).exists();

        HttpResponse<String> deleted =
                delete(base + "/attachments/" + attachmentId, token);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(stored).doesNotExist();
        // значение параметра при этом осталось (удаляется только файл)
        assertThat(((Number) currentValueOf(get(base, token).body(),
                "total_warehouse_area")).doubleValue()).isEqualTo(4200.0);
    }

    @Test
    void deleteAttachment_foreignProject_404() throws Exception {
        String owner = registerAndLogin();
        String stranger = registerAndLogin();
        Long projectId = createProject(owner, "Чужое вложение 404");
        String base = "/api/projects/" + projectId + "/parameters";
        HttpResponse<String> imported = postMultipart(base + "/import", owner,
                "import.csv", "text/csv", utf8("total_warehouse_area\n100\n"));
        Integer attachmentId = JsonPath.read(imported.body(), "$.attachmentId");

        assertThat(delete(base + "/attachments/" + attachmentId, stranger).statusCode())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Удаление проекта — вместе с файлами
    // ------------------------------------------------------------------

    @Test
    void deleteProject_removesAttachmentFiles() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Проект с файлами");
        String base = "/api/projects/" + projectId + "/parameters";

        HttpResponse<String> imported = postMultipart(base + "/import", token,
                "import.csv", "text/csv", utf8("total_warehouse_area\n100\n"));
        Integer attachmentId = JsonPath.read(imported.body(), "$.attachmentId");
        Path stored = UPLOAD_ROOT.resolve(projectId.toString())
                .resolve(attachmentId + "_import.csv");
        assertThat(stored).exists();

        HttpResponse<String> deleted = delete("/api/projects/" + projectId, token);
        assertThat(deleted.statusCode()).isEqualTo(204);
        // каталог проекта в хранилище удалён физически
        assertThat(UPLOAD_ROOT.resolve(projectId.toString())).doesNotExist();
        // и сам проект больше не доступен
        assertThat(get(base, token).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // Worst-case параметры: фиксированные и производные
    // ------------------------------------------------------------------

    @Test
    void worstCase_putFixedParam_400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Склад А");

        HttpResponse<String> response = put("/api/projects/" + projectId
                + "/parameters/" + payrollParamId, token, "{\"value\":1.5}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("фиксированный");
    }

    @Test
    void worstCase_putDerivedParam_400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Склад А");

        HttpResponse<String> response = put("/api/projects/" + projectId
                + "/parameters/" + pickingUnitsParamId, token, "{\"value\":999999}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("рассчитывается автоматически");
    }

    @Test
    void worstCase_deleteFixedParam_400() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Склад А");

        HttpResponse<String> response = delete("/api/projects/" + projectId
                + "/parameters/" + payrollParamId, token);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void worstCase_derivedComputedFromSource() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Склад А");

        // 1) до ввода источника: производный = дефолт источника x 1.5
        HttpResponse<String> before = get("/api/projects/" + projectId + "/parameters", token);
        assertThat(before.statusCode()).isEqualTo(200);
        List<Number> unitsBefore = JsonPath.read(before.body(),
                "$[?(@.code=='picking_units_per_day')].currentValue.value");
        assertThat(unitsBefore).first().isEqualTo(150000.0);
        List<Boolean> derivedBefore = JsonPath.read(before.body(),
                "$[?(@.code=='picking_units_per_day')].isDerived");
        assertThat(derivedBefore).first().isEqualTo(true);
        List<Boolean> fixedBefore = JsonPath.read(before.body(),
                "$[?(@.code=='picking_units_per_day')].isFixed");
        assertThat(fixedBefore).first().isEqualTo(false);
        List<String> sourceBefore = JsonPath.read(before.body(),
                "$[?(@.code=='picking_units_per_day')].derivedFromName");
        assertThat(sourceBefore).first().isEqualTo("Объём отбора (строк/сутки, всего)");

        // 2) пользователь меняет источник: 200000 x 1.5 = 300000
        HttpResponse<String> setSource = put("/api/projects/" + projectId
                + "/parameters/" + pickingLinesParamId, token, "{\"value\":200000}");
        assertThat(setSource.statusCode()).isEqualTo(200);

        HttpResponse<String> after = get("/api/projects/" + projectId + "/parameters", token);
        List<Number> unitsAfter = JsonPath.read(after.body(),
                "$[?(@.code=='picking_units_per_day')].currentValue.value");
        assertThat(unitsAfter).first().isEqualTo(300000.0);
    }

    @Test
    void worstCase_fixedShowsConstant() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Склад А");

        HttpResponse<String> list = get("/api/projects/" + projectId + "/parameters", token);
        assertThat(list.statusCode()).isEqualTo(200);
        List<Boolean> fixed = JsonPath.read(list.body(),
                "$[?(@.code=='payroll_tax_rate')].isFixed");
        assertThat(fixed).first().isEqualTo(true);
        // константа отдаётся как currentValue (1.302), даже если строки нет
        List<Number> constant = JsonPath.read(list.body(),
                "$[?(@.code=='payroll_tax_rate')].currentValue.value");
        assertThat(constant).first().isEqualTo(1.302);
    }

    @Test
    void worstCase_templateExcludesFixedAndDerived() throws Exception {
        String token = registerAndLogin();
        Long projectId = createProject(token, "Склад А");

        HttpResponse<String> template = get("/api/projects/" + projectId
                + "/parameters/template?format=csv", token);
        assertThat(template.statusCode()).isEqualTo(200);
        assertThat(template.body()).contains("picking_lines_per_day");
        assertThat(template.body()).doesNotContain("payroll_tax_rate");
        assertThat(template.body()).doesNotContain("picking_units_per_day");
    }
}
