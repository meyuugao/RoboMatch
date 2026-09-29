package me.yuugao.robomatch.controller;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.domain.Process;
import me.yuugao.robomatch.repository.*;

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
import java.time.LocalDate;
import java.util.List;

import com.jayway.jsonpath.JsonPath;

/**
 * Интеграционный тест каталога: полный HTTP-путь
 * через SecurityFilterChain (permitAll на /api/solutions/** и /api/filters)
 * до контроллера, сервиса, репозитория и H2-БД.
 * <p>
 * Датасет фиксированный (7 решений, часть с NULL ТТХ - для сортировки
 * и «дырявых» данных организатора), заполняется один раз на класс.
 * <p>
 * Окружение как в AuthControllerIT: H2 in-memory в режиме PostgreSQL,
 * Flyway выключен, схема создаётся Hibernate (create-drop). HTTP-клиент -
 * встроенный java.net.http.HttpClient (чёрный ящик, RANDOM_PORT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:catalogit;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SolutionControllerIT {

    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort
    private int port;
    @Autowired
    private VendorRepository vendorRepository;
    @Autowired
    private SolutionTypeRepository solutionTypeRepository;
    @Autowired
    private SolutionSubtypeRepository solutionSubtypeRepository;
    @Autowired
    private RegionRepository regionRepository;
    @Autowired
    private IndustryRepository industryRepository;
    @Autowired
    private ProcessRepository processRepository;
    @Autowired
    private SolutionApplicationRepository solutionApplicationRepository;
    @Autowired
    private SolutionCharacteristicRepository solutionCharacteristicRepository;
    @Autowired
    private CharacteristicTypeRepository characteristicTypeRepository;
    @Autowired
    private SolutionCaseRepository solutionCaseRepository;
    @Autowired
    private SolutionCaseLinkRepository solutionCaseLinkRepository;
    @Autowired
    private SolutionTypeSubtypeMappingRepository mappingRepository;
    @Autowired
    private SolutionRepository solutionRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    // id фиксируются после сидинга и используются в тестах
    private Long ronaviH1500;
    private Long ronaviSr;
    private Long dikomDmr;
    private Long typeId;
    private Long industryTradeId;
    private Long industryManufacturingId;
    private Long processWarehouseId;
    private Long processProductionId;

    @BeforeAll
    void seed() {
        transactionTemplate.executeWithoutResult(tx -> {
            Vendor ronavi = vendorRepository.save(Vendor.builder().name("Ронави Роботикс").build());
            Vendor moros = vendorRepository.save(Vendor.builder().name("Морос").build());
            Vendor dikom = vendorRepository.save(Vendor.builder().name("Завод ДиКом").build());
            SolutionType amr = solutionTypeRepository.save(SolutionType.builder()
                    .code("amr").name("AMR").build());
            SolutionSubtype forklift = solutionSubtypeRepository.save(SolutionSubtype.builder()
                    .code("forklift").name("Погрузчик").build());
            Region moscow = regionRepository.save(Region.builder()
                    .code("moscow").name("Москва").build());
            Industry trade = industryRepository.save(Industry.builder()
                    .code("trade_and_services").name("Торговля и услуги").build());
            Industry manufacturing = industryRepository.save(Industry.builder()
                    .code("manufacturing").name("Производство").build());
            Process warehouse = processRepository.save(Process.builder()
                    .code("inwarehouse_logistics").name("Внутрискладская логистика")
                    .isActive(true).build());
            Process production = processRepository.save(Process.builder()
                    .code("inproduction_logistics").name("Внутрипроизводственная логистика")
                    .isActive(true).build());
            mappingRepository.save(SolutionTypeSubtypeMapping.builder()
                    .solutionTypeId(amr.getId()).solutionSubtypeId(forklift.getId())
                    .sourceNote("тест").build());

            CharacteristicType payloadType = characteristicTypeRepository.save(
                    CharacteristicType.builder()
                            .code("payload_kg").name("Грузоподъёмность").groupCode("technical")
                            .dataType("number").unit("кг").isFilterable(true)
                            .isRequired(true).sortOrder(1).build());
            CharacteristicType autonomyType = characteristicTypeRepository.save(
                    CharacteristicType.builder()
                            .code("autonomy_h").name("Автономность").groupCode("technical")
                            .dataType("number").unit("ч").isFilterable(false)
                            .isRequired(false).sortOrder(2).build());
            characteristicTypeRepository.save(CharacteristicType.builder()
                    .code("navigation_type").name("Тип навигации").groupCode("technical")
                    .dataType("text").unit(null).isFilterable(false)
                    .isRequired(false).sortOrder(3).build());

            Solution a = saveSolution("Ронави H1500", ronavi.getId(), "brs", amr.getId(),
                    forklift.getId(), moscow.getId(), "operation",
                    "2700000", (short) 8, "1500");
            Solution b = saveSolution("Ронави SR", ronavi.getId(), "brs", amr.getId(),
                    forklift.getId(), moscow.getId(), "operation",
                    "3200000", (short) 9, "2000");
            saveSolution("Морос AMR 100", moros.getId(), "brs", null, null, null, "piloting",
                    "700000", (short) 7, "150");
            Solution d = saveSolution("ДиКом DMR 1200", dikom.getId(), "brs", amr.getId(),
                    null, null, "rnd", "1200000", null, "1200");
            saveSolution("Система управления складом", ronavi.getId(), "software", null,
                    null, null, "operation", "500000", (short) 5, null);
            saveSolution("Ронави H2000", ronavi.getId(), "brs", amr.getId(),
                    forklift.getId(), moscow.getId(), "operation",
                    "2900000", (short) 6, "1800");
            saveSolution("Морос AMR 800", moros.getId(), "brs", null, null, null, "piloting",
                    "1100000", (short) 7, "800");

            // применения: отрасль+процесс в ОДНОЙ строке solution_application
            solutionApplicationRepository.save(SolutionApplication.builder()
                    .solutionId(a.getId()).industryId(trade.getId())
                    .processId(warehouse.getId()).build());
            solutionApplicationRepository.save(SolutionApplication.builder()
                    .solutionId(b.getId()).industryId(trade.getId())
                    .processId(warehouse.getId()).build());
            solutionApplicationRepository.save(SolutionApplication.builder()
                    .solutionId(b.getId()).industryId(manufacturing.getId())
                    .processId(production.getId()).build());
            // Морос AMR 100: производство + внутрипроизводственная (другая пара)
            Solution moros100 = solutionRepository.findAll().stream()
                    .filter(s -> s.getName().equals("Морос AMR 100")).findFirst().orElseThrow();
            solutionApplicationRepository.save(SolutionApplication.builder()
                    .solutionId(moros100.getId()).industryId(manufacturing.getId())
                    .processId(production.getId()).build());

            // ТТХ для карточки Ронави H1500: числа, текст, провенанс, is_confirmed
            solutionCharacteristicRepository.save(SolutionCharacteristic.builder()
                    .solutionId(a.getId()).characteristicTypeId(payloadType.getId())
                    .valueNumeric(new BigDecimal("1500")).sourceKind("open_source")
                    .sourceUrl("https://ronavi-robotics.ru/catalogue/h1500")
                    .sourceDate(LocalDate.of(2026, 9, 19)).isConfirmed(true).build());
            solutionCharacteristicRepository.save(SolutionCharacteristic.builder()
                    .solutionId(a.getId()).characteristicTypeId(autonomyType.getId())
                    .valueNumeric(new BigDecimal("8")).sourceKind("open_source")
                    .isConfirmed(false).build());

            // кейс
            SolutionCase warehouseCase = solutionCaseRepository.save(SolutionCase.builder()
                    .name("Кейс: склад сети «Пятёрочка»")
                    .description("40 роботов, срок окупаемости 2 года")
                    .sourceUrl("https://example.com/case").sourceDate(LocalDate.of(2026, 9, 1))
                    .build());
            solutionCaseLinkRepository.save(new SolutionCaseLink(a.getId(), warehouseCase.getId()));

            ronaviH1500 = a.getId();
            ronaviSr = b.getId();
            dikomDmr = d.getId();
            typeId = amr.getId();
            industryTradeId = trade.getId();
            industryManufacturingId = manufacturing.getId();
            processWarehouseId = warehouse.getId();
            processProductionId = production.getId();
        });
    }

    private Solution saveSolution(String name, Long vendorId, String productClass, Long typeId,
                                  Long subtypeId, Long regionId, String status,
                                  String price, Short trl, String payloadKg) {
        return solutionRepository.save(Solution.builder()
                .name(name).vendorId(vendorId).productClass(productClass)
                .solutionTypeId(typeId).solutionSubtypeId(subtypeId).regionId(regionId)
                .status(status).priceRub(new BigDecimal(price)).trl(trl)
                .payloadKg(payloadKg == null ? null : new BigDecimal(payloadKg))
                .sourceKind("organizer_catalog")
                .sourceUrl("catalog_export_v4.csv")
                .sourceDate(LocalDate.of(2026, 9, 17))
                .build());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // --- список: форма ответа и поиск -------------------------------------

    @Test
    void list_returnsPageResponseShape() throws Exception {
        HttpResponse<String> response = get("/api/solutions");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(7);
        assertThat((Integer) JsonPath.read(response.body(), "$.size")).isEqualTo(20);
        assertThat((Integer) JsonPath.read(response.body(), "$.page")).isEqualTo(0);
        assertThat((List<?>) JsonPath.read(response.body(), "$.content")).hasSize(7);
        // JOIN-имена: у каждого элемента vendorName, а не vendorId
        assertThat((String) JsonPath.read(response.body(), "$.content[0].vendorName"))
                .isNotBlank();
        // «голых» id в списке больше нет: поля отсутствуют в контракте
        assertThat(response.body()).doesNotContain("\"vendorId\"");
        assertThat(response.body()).doesNotContain("\"solutionTypeId\"");
    }

    @Test
    void search_q_isCaseInsensitive() throws Exception {
        HttpResponse<String> response = get("/api/solutions?q=РОНАВИ");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(3);
        assertThat((List<String>) JsonPath.read(response.body(),
                "$.content[*].name")).allMatch(name -> name.contains("Ронави"));
    }

    @Test
    void search_qWithLikeWildcard_treatedLiterally() throws Exception {
        // % и _ - вилдкарды LIKE; сервис экранирует их
        // раньше q="%" возвращал весь каталог). Решений с такими символами
        // в названии нет - ожидаем пустой результат, а не все 7.
        // В URL литеральный % кодируется как %25.
        assertThat(get("/api/solutions?q=%25").statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(get("/api/solutions?q=%25").body(),
                "$.totalElements")).isZero();
        assertThat((Integer) JsonPath.read(get("/api/solutions?q=_").body(),
                "$.totalElements")).isZero();
    }

    // --- фильтры ------------------------------------------------------------

    @Test
    void filter_byType() throws Exception {
        HttpResponse<String> response = get("/api/solutions?typeId=" + typeId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(4);
    }

    @Test
    void filter_bySubtype() throws Exception {
        // подтип «Погрузчик» указан у трёх Ронави (H1500, SR, H2000)
        Long subtypeId = solutionSubtypeRepository.findAll().get(0).getId();
        HttpResponse<String> response = get("/api/solutions?subtypeId=" + subtypeId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(3);
        List<String> names = JsonPath.read(response.body(), "$.content[*].name");
        assertThat(names).allMatch(name -> name.contains("Ронави"));
    }

    @Test
    void filter_byStatus() throws Exception {
        HttpResponse<String> response = get("/api/solutions?status=rnd");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(1);
        assertThat((String) JsonPath.read(response.body(), "$.content[0].name"))
                .isEqualTo("ДиКом DMR 1200");
    }

    @Test
    void filter_byTrlRange() throws Exception {
        HttpResponse<String> response = get("/api/solutions?trlMin=7&trlMax=8");

        assertThat(response.statusCode()).isEqualTo(200);
        // Ронави H1500 (8), Морос AMR 100 (7), Морос AMR 800 (7); SR (9) и DMR (NULL) вне
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(3);
    }

    @Test
    void filter_byPriceRange() throws Exception {
        HttpResponse<String> response = get("/api/solutions?priceMin=1000000&priceMax=2700000");

        assertThat(response.statusCode()).isEqualTo(200);
        // DMR 1.2M, AMR 800 1.1M, H1500 2.7M
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(3);
    }

    @Test
    void filter_byPayloadMin() throws Exception {
        HttpResponse<String> response = get("/api/solutions?payloadMin=1000");

        assertThat(response.statusCode()).isEqualTo(200);
        // H1500 1500, SR 2000, H2000 1800, DMR 1200 (система и AMR 100/800 - меньше)
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(4);
    }

    @Test
    void filter_industryAndProcess_matchSameRow() throws Exception {
        // пара (торговля, внутрискладская) есть только у H1500 и SR
        HttpResponse<String> response = get("/api/solutions?industryId=" + industryTradeId
                + "&processId=" + processWarehouseId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(2);
    }

    @Test
    void filter_industryProcess_crossPair_returnsZero() throws Exception {
        // пары (торговля, внутрипроизводственная) нет ни у кого: SR применён
        // в торговле ДЛЯ склада и в производстве ДЛЯ производства. Независимые
        // условия дали бы SR и Морос AMR 100 - ноль доказывает семантику
        // «одной строки» solution_application
        // (отрицательный кейс)
        HttpResponse<String> response = get("/api/solutions?industryId=" + industryTradeId
                + "&processId=" + processProductionId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isZero();
    }

    @Test
    void filter_industryOnly() throws Exception {
        HttpResponse<String> response = get("/api/solutions?industryId="
                + industryManufacturingId);

        assertThat(response.statusCode()).isEqualTo(200);
        // SR и Морос AMR 100 применяются в производстве
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(2);
    }

    @Test
    void filter_processOnly() throws Exception {
        HttpResponse<String> response = get("/api/solutions?processId=" + processProductionId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(2);
    }

    @Test
    void combined_searchAndPriceFilter() throws Exception {
        HttpResponse<String> response = get("/api/solutions?q=ронави&priceMax=2800000");

        assertThat(response.statusCode()).isEqualTo(200);
        // из трёх «Ронави» дешевле 2.8M только H1500
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(1);
        assertThat((String) JsonPath.read(response.body(), "$.content[0].name"))
                .isEqualTo("Ронави H1500");
    }

    // --- сортировка и пагинация ---------------------------------------------

    @Test
    void sort_byPriceAsc() throws Exception {
        HttpResponse<String> response = get("/api/solutions?sortBy=price&sortDir=asc");

        assertThat(response.statusCode()).isEqualTo(200);
        List<Double> prices = JsonPath.read(response.body(), "$.content[*].priceRub");
        assertThat(prices).isSorted();
        assertThat(prices).hasSize(7);
    }

    @Test
    void sort_byPayloadDesc_nullsLast() throws Exception {
        HttpResponse<String> response = get("/api/solutions?sortBy=payload_kg&sortDir=desc");

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> names = JsonPath.read(response.body(), "$.content[*].name");
        assertThat(names).hasSize(7);
        // максимальная грузоподъёмность - первой, NULL («Система управления») - последним
        assertThat(names.get(0)).isEqualTo("Ронави SR");
        assertThat(names.get(names.size() - 1)).isEqualTo("Система управления складом");
    }

    @Test
    void sort_byTrlDesc_nullsLast() throws Exception {
        HttpResponse<String> response = get("/api/solutions?sortBy=trl&sortDir=desc");

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> names = JsonPath.read(response.body(), "$.content[*].name");
        // УГТ 9 (SR) - первый, DMR 1200 без УГТ - последний
        assertThat(names.get(0)).isEqualTo("Ронави SR");
        assertThat(names.get(names.size() - 1)).isEqualTo("ДиКом DMR 1200");
    }

    @Test
    void sort_byTrlAsc_nullsLast() throws Exception {
        // и в возрастание NULL уходит в конец (COALESCE-сентинел 10)
        HttpResponse<String> response = get("/api/solutions?sortBy=trl&sortDir=asc");

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> names = JsonPath.read(response.body(), "$.content[*].name");
        assertThat(names.get(0)).isEqualTo("Система управления складом"); // УГТ 5
        assertThat(names.get(names.size() - 1)).isEqualTo("ДиКом DMR 1200"); // NULL
    }

    @Test
    void sort_byCreatedAt_asc_fullyOrdered() throws Exception {
        // created_at NOT NULL; при равных метках времени вторичный ключ s.id
        // делает порядок детерминированным (первым - раньше созданный)
        HttpResponse<String> response = get("/api/solutions?sortBy=created_at&sortDir=asc");

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> names = JsonPath.read(response.body(), "$.content[*].name");
        assertThat(names).hasSize(7);
        assertThat(names.get(0)).isEqualTo("Ронави H1500");
        assertThat(names.get(names.size() - 1)).isEqualTo("Морос AMR 800");
    }

    @Test
    void pagination_sliceAndTotals() throws Exception {
        HttpResponse<String> response = get("/api/solutions?page=1&size=2&sortBy=price&sortDir=asc");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(response.body(), "$.content")).hasSize(2);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalPages")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalElements")).isEqualTo(7);
        assertThat((Integer) JsonPath.read(response.body(), "$.page")).isEqualTo(1);
    }

    // --- невалидные параметры -> 400 -----------------------------------------

    @Test
    void invalidSortBy_returns400() throws Exception {
        HttpResponse<String> response = get("/api/solutions?sortBy=hack");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message")).contains("sortBy");
    }

    @Test
    void negativePage_returns400() throws Exception {
        assertThat(get("/api/solutions?page=-1").statusCode()).isEqualTo(400);
    }

    @Test
    void tooLargeSize_returns400() throws Exception {
        assertThat(get("/api/solutions?size=101").statusCode()).isEqualTo(400);
    }

    @Test
    void invertedPriceRange_returns400() throws Exception {
        assertThat(get("/api/solutions?priceMin=2000000&priceMax=1000000")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void negativePriceMin_returns400() throws Exception {
        assertThat(get("/api/solutions?priceMin=-100").statusCode()).isEqualTo(400);
    }

    @Test
    void typeMismatch_returns400not500() throws Exception {
        HttpResponse<String> response = get("/api/solutions?typeId=abc");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message")).contains("typeId");
    }

    // --- карточка --------------------------------------------------------------

    @Test
    void card_returnsFullDtoWithRelations() throws Exception {
        HttpResponse<String> response = get("/api/solutions/" + ronaviH1500);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.name")).isEqualTo("Ронави H1500");
        assertThat((String) JsonPath.read(response.body(), "$.vendorName"))
                .isEqualTo("Ронави Роботикс");
        assertThat((String) JsonPath.read(response.body(), "$.solutionTypeName")).isEqualTo("AMR");
        assertThat((String) JsonPath.read(response.body(), "$.regionName")).isEqualTo("Москва");
        // ТТХ из EAV с провенансом
        List<?> characteristics = JsonPath.read(response.body(), "$.characteristics");
        assertThat(characteristics).hasSize(2);
        assertThat((String) JsonPath.read(response.body(),
                "$.characteristics[0].sourceKind")).isEqualTo("open_source");
        // кейс и применение
        assertThat((List<?>) JsonPath.read(response.body(), "$.cases")).hasSize(1);
        assertThat((List<?>) JsonPath.read(response.body(), "$.applications")).hasSize(1);
        assertThat((String) JsonPath.read(response.body(),
                "$.applications[0].processName")).isEqualTo("Внутрискладская логистика");
    }

    @Test
    void card_unknownId_returns404() throws Exception {
        HttpResponse<String> response = get("/api/solutions/999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(response.body(), "$.message")).contains("999999");
    }

    // --- сравнение -------------------------------------------------------------

    @Test
    void compare_twoSolutions_returnsFullDtosInRequestOrder() throws Exception {
        HttpResponse<String> response =
                get("/api/solutions/compare?ids=" + ronaviH1500 + "," + dikomDmr);

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> names = JsonPath.read(response.body(), "$[*].name");
        assertThat(names).containsExactly("Ронави H1500", "ДиКом DMR 1200");
        // полные карточки: у H1500 есть ТТХ и кейс
        assertThat((List<?>) JsonPath.read(response.body(), "$[0].characteristics")).hasSize(2);
        assertThat((List<?>) JsonPath.read(response.body(), "$[0].cases")).hasSize(1);
    }

    @Test
    void compare_duplicateIdsCollapse() throws Exception {
        HttpResponse<String> response = get("/api/solutions/compare?ids=" + ronaviH1500
                + "," + ronaviH1500 + "," + ronaviSr);

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> names = JsonPath.read(response.body(), "$[*].name");
        assertThat(names).containsExactly("Ронави H1500", "Ронави SR");
    }

    @Test
    void compare_singleId_returns400() throws Exception {
        HttpResponse<String> response = get("/api/solutions/compare?ids=" + ronaviH1500);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.message")).contains("минимум");
    }

    @Test
    void compare_elevenIds_returns400() throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long i = 1; i <= 11; i++) {
            ids.append(i).append(i < 11 ? "," : "");
        }

        assertThat(get("/api/solutions/compare?ids=" + ids).statusCode()).isEqualTo(400);
    }

    @Test
    void compare_unknownId_returns404() throws Exception {
        HttpResponse<String> response =
                get("/api/solutions/compare?ids=" + ronaviH1500 + ",999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat((String) JsonPath.read(response.body(), "$.message")).contains("999999");
    }

    // --- словари фильтров --------------------------------------------------------

    @Test
    void filters_returnDictionariesAndRanges() throws Exception {
        HttpResponse<String> response = get("/api/filters");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(response.body(), "$.types")).hasSize(1);
        assertThat((String) JsonPath.read(response.body(), "$.types[0].name")).isEqualTo("AMR");
        assertThat((List<String>) JsonPath.read(response.body(), "$.statuses"))
                .containsExactly("operation", "piloting", "rnd");
        // BigDecimal в JSON -> число с дробной частью; JsonPath читает Double
        assertThat((Double) JsonPath.read(response.body(), "$.priceMin")).isEqualTo(500000.0);
        assertThat((Double) JsonPath.read(response.body(), "$.priceMax")).isEqualTo(3200000.0);
        assertThat((Integer) JsonPath.read(response.body(), "$.trlMin")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(response.body(), "$.trlMax")).isEqualTo(9);
        assertThat((List<?>) JsonPath.read(response.body(), "$.typeSubtypeMapping")).hasSize(1);
    }

    @Test
    void filters_accessibleWithoutToken() throws Exception {
        // каталог гостю открыт - словари тоже, иначе страница не соберётся
        assertThat(get("/api/filters").statusCode()).isEqualTo(200);
    }
}
