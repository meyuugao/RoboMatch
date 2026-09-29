package me.yuugao.robomatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.CatalogImportSummaryDto;
import me.yuugao.robomatch.dto.ImportErrorDto;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.ImportException;
import me.yuugao.robomatch.repository.*;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Интеграционные тесты импорта таблицы организатора:
 * ПОРТ ПРАВИЛ Python-маппера (scripts/seed/mappers/
 * catalog_mapper.py) — счётчики обязаны совпадать с эталоном seed.
 * <p>
 * Эталон получен запуском map_catalog на реальном catalog_export_v4.csv:
 * 223 строки -> 187 решений (23 группы дублей), 250 применений,
 * 175 кейсов, 195 связей, 103 вендора, 9 отраслей, 24 региона,
 * 11 типов, 68 подтипов, 95 процессов.
 * <p>
 * Окружение: H2 in-memory PostgreSQL-режим (Boot 4 не даёт @DataJpaTest
 * в текущем дереве зависимостей — паттерн @SpringBootTest, как у
 * остальных интеграционных тестов репозитория).
 * <p>
 * Файл полного каталога — снапшот в src/test/resources (побайтово равен
 * docs/source/catalog_export_v4.csv, сверяется тестом): build-контекст
 * Docker-сборки backend — только backend/, файла docs/ в нём нет, а тест
 * обязан гоняться и при сборке образа (backend/Dockerfile: gradle build).
 * <p>
 * Фикстуры расширенного формата (docs/test-fixtures/README.md): XLSX-набор A
 * и CSV-набор B, 4 решения x 27 ТТХ в каждом, снапшоты в src/test/resources/
 * test-fixtures (та же сверка байт-в-байт с docs/test-fixtures). Справочник
 * characteristic_type (27 кодов) вставляется тестом: в H2 его заполняет
 * только python-seed, а импортёр валидирует заголовки по этому справочнику.
 */
// корень хранилища импортов изолирован во временный
// каталог теста — иначе прогон тестов пишет в ./data/admin-imports,
// тот же каталог, где живой backend хранит копии для refresh
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.jwt.secret=it-test-secret-0123456789-0123456789-0123456789",
        "spring.datasource.url=jdbc:h2:mem:catalogimport;MODE=PostgreSQL;NON_KEYWORDS=USER,VALUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.admin-import.root=${java.io.tmpdir}/admin-import-it"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogImporterTest {

    private static final UUID ID_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ID_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ID_C = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ID_D = UUID.fromString("44444444-4444-4444-4444-444444444444");
    /**
 * external_id фикстур A/B (scripts/gen_test_fixtures.py — стабильные).
 */
    private static final UUID FIXTURE_A1 =
            UUID.fromString("aaaa0001-4000-8000-0000-000000000001");
    private static final UUID FIXTURE_A2 =
            UUID.fromString("aaaa0002-4000-8000-0000-000000000002");
    private static final UUID FIXTURE_A3 =
            UUID.fromString("aaaa0003-4000-8000-0000-000000000003");
    private static final UUID FIXTURE_A4 =
            UUID.fromString("aaaa0004-4000-8000-0000-000000000004");
    private static final UUID FIXTURE_B1 =
            UUID.fromString("bbbb0001-4000-8000-0000-000000000001");
    private static final UUID FIXTURE_B4 =
            UUID.fromString("bbbb0004-4000-8000-0000-000000000004");
    /**
 * Компактная фикстура со всеми правилами порта (см. тесты ниже).
 */
    private static final String SMALL_CSV = """
            id;Название;тип;статус;компания;описание;Тип;Подтип;Сценарий;Кейсы;УГТ;Рын Потенциал;Регион;Отрасль;Цена изделия
            %s;Робот А;brs;operation;ООО Ромбот;Короткое описание;Мобильные роботы;AMR;Внутрискладская логистика;Внедрено 10 роботов;8;4;Москва;Логистика;1 000 000,00
            %s;Робот А;brs;operation;ООО Ромбот;А это гораздо более длинное описание робота А из второй строки группы дублей;Мобильные роботы;AMR;Внутрискладская логистика, Погрузка-разгрузка;Внедрено 10 роботов;8;4;Москва;Логистика;900 000,00
            %s;Робот Б;brs;operation;ООО Ромбот;;Мобильные роботы;Робот уборщик;Мониторинг, патрулирование, перевозка грузов;;9;5;Московская область;Логистика;2 000 000,00
            %s;Дрон В;bas;piloting;АО Вентус;"Многострочное
            описание дрона";БПЛА;;Доставка биоматериаловм;Кейс дрона;7;;Санкт-Петербург;Медицина;3 500 000,00
            %s;Дрон Г;bas;operation;АО Вентус;Разведывательный дрон;БПЛА;;Доставка в удаленные, труднодоступные районы;Кейс дрона;;3;Санкт-Петербург;Медицина;1 500 000,00
            """.formatted(ID_A, ID_A, ID_B, ID_C, ID_D);
    @TempDir
    static Path tempDir;
    @Autowired
    private CatalogImporter importer;
    @Autowired
    private AdminImportRepository importRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SolutionRepository solutionRepository;
    @Autowired
    private SolutionApplicationRepository applicationRepository;
    @Autowired
    private SolutionCaseRepository caseRepository;
    @Autowired
    private SolutionCaseLinkRepository caseLinkRepository;
    @Autowired
    private SolutionCharacteristicRepository characteristicRepository;
    @Autowired
    private CharacteristicTypeRepository characteristicTypeRepository;
    @Autowired
    private VendorRepository vendorRepository;
    @Autowired
    private IndustryRepository industryRepository;
    @Autowired
    private ProcessRepository processRepository;
    @Autowired
    private RegionRepository regionRepository;
    @Autowired
    private SolutionTypeRepository solutionTypeRepository;
    @Autowired
    private SolutionSubtypeRepository solutionSubtypeRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    private Long userId;

    /**
 * Снапшот фикстуры в test resources равен docs/test-fixtures байт-в-байт.
 */
    private static void assertFixtureSnapshotMatches(String name, byte[] snapshot)
            throws Exception {
        Path repoFile = Path.of("..", "docs", "test-fixtures", name);
        if (Files.exists(repoFile)) {
            assertThat(Files.readAllBytes(repoFile))
                    .as("снапшот src/test/resources/test-fixtures/" + name
                            + " должен совпадать с docs/test-fixtures/" + name)
                    .isEqualTo(snapshot);
        }
    }

    /**
 * Байты ресурса из test classpath (снапшот каталога организатора).
 */
    private static byte[] readTestResource(String name) throws Exception {
        try (java.io.InputStream in =
                     CatalogImporterTest.class.getResourceAsStream(name)) {
            assertThat(in).as("ресурс test classpath " + name).isNotNull();
            return in.readAllBytes();
        }
    }

    @BeforeAll
    void createAdminUser() {
        User admin = User.builder().login("importer-admin")
                .passwordHash("x").role(UserRole.ADMIN).build();
        userId = userRepository.save(admin).getId();
        seedCharacteristicTypes();
    }

    /**
 * Справочник characteristic_type — 27 кодов из characteristics_mapper.py
 * (в H2 его заполняет только python-seed, а тесты фикстур импортируют
 * колонки ТТХ, валидируемые по этому справочнику).
 */
    private void seedCharacteristicTypes() {
        if (characteristicTypeRepository.count() > 0) {
            return;
        }
        record Spec(String code, String name, String group, String dataType) {
        }
        int order = 0;
        List<Spec> types = List.of(
                new Spec("country_of_origin", "Страна происхождения",
                        "identification", "text"),
                new Spec("navigation_type", "Тип навигации",
                        "identification", "text"),
                new Spec("payload_kg", "Грузоподъёмность, кг", "technical", "number"),
                new Spec("mass_kg", "Масса, кг", "technical", "number"),
                new Spec("length_mm", "Длина, мм", "technical", "number"),
                new Spec("width_mm", "Ширина, мм", "technical", "number"),
                new Spec("height_mm", "Высота, мм", "technical", "number"),
                new Spec("positioning_accuracy_mm", "Точность позиционирования, мм",
                        "technical", "number"),
                new Spec("speed_m_s", "Скорость, м/с", "technical", "number"),
                new Spec("charging_power_kw", "Мощность зарядки, кВт",
                        "technical", "number"),
                new Spec("noise_level_dba", "Уровень шума, дБА", "technical", "number"),
                new Spec("autonomy_h", "Автономность, ч", "technical", "number"),
                new Spec("productivity", "Производительность", "technical", "text"),
                new Spec("operating_conditions", "Допустимые условия эксплуатации",
                        "technical", "text"),
                new Spec("lifecycle_years", "Срок службы, лет", "technical", "number"),
                new Spec("floor_coverage_req", "Требования к покрытию",
                        "infrastructure", "text"),
                new Spec("communication_req", "Требования к связи",
                        "infrastructure", "text"),
                new Spec("integration_req", "Требования к интеграции",
                        "infrastructure", "text"),
                new Spec("service_req", "Требования к сервисному обслуживанию",
                        "infrastructure", "text"),
                new Spec("software_cost", "Стоимость ПО, руб.", "economic", "number"),
                new Spec("implementation_cost", "Стоимость внедрения, руб.",
                        "economic", "number"),
                new Spec("maintenance_cost", "Стоимость обслуживания, руб./год",
                        "economic", "number"),
                new Spec("acquisition_model", "Модель приобретения",
                        "economic", "text"),
                new Spec("limitations", "Ограничения", "applicability", "text"),
                new Spec("source", "Источник", "data_quality", "text"),
                new Spec("source_date", "Дата актуализации", "data_quality", "date"),
                new Spec("confirmation_status", "Признак подтверждённости",
                        "data_quality", "text"));
        for (Spec t : types) {
            characteristicTypeRepository.save(CharacteristicType.builder()
                    .code(t.code()).name(t.name()).groupCode(t.group())
                    .dataType(t.dataType())
                    .isFilterable(true).isRequired(false)
                    .sortOrder(++order)
                    .build());
        }
    }

    // ------------------------------------------------------------------
    // Порт правил маппера
    // ------------------------------------------------------------------

    @BeforeEach
    void cleanCatalog() {
        // чистая БД на каждый тест: импорт накапливает и данные, и
        // справочники (остатки «утекали» в счётчики следующих тестов).
        // characteristic_type — справочник, НЕ трогаем (живёт с @BeforeAll)
        transactionTemplate.executeWithoutResult(tx -> {
            characteristicRepository.deleteAll();
            caseLinkRepository.deleteAll();
            applicationRepository.deleteAll();
            caseRepository.deleteAll();
            solutionRepository.deleteAll();
            vendorRepository.deleteAll();
            industryRepository.deleteAll();
            processRepository.deleteAll();
            regionRepository.deleteAll();
            solutionTypeRepository.deleteAll();
            solutionSubtypeRepository.deleteAll();
            importRepository.deleteAll();
        });
    }

    private CatalogImportSummaryDto importCsv(String csv, String fileName) {
        return importer.importFile(new MockMultipartFile("file", fileName,
                "text/csv", csv.getBytes(StandardCharsets.UTF_8)), userId);
    }

    @Test
    void smallCsvMatchesPythonMapperSemantics() {
        CatalogImportSummaryDto summary = importCsv(SMALL_CSV, "small.csv");

        // зерно: 5 строк -> 4 решения (1 группа дублей)
        assertThat(summary.getCsvRows()).isEqualTo(5);
        assertThat(summary.getSolutions()).isEqualTo(4);
        assertThat(summary.getDupGroups()).isEqualTo(1);

        var sol = summary.getEntities().get("solutions");
        assertThat(sol.getAdded()).isEqualTo(4);

        // применения: 5 (дубль применения у А схлопнулся, конфликт цен -> min)
        var apps = summary.getEntities().get("applications");
        assertThat(apps.getAdded()).isEqualTo(5);

        // кейсы: 2 уникальных текста, 3 связи (кейс дрона у двух решений)
        assertThat(summary.getEntities().get("cases").getAdded()).isEqualTo(2);
        assertThat(summary.getEntities().get("case_links").getAdded()).isEqualTo(3);

        // справочники
        assertThat(summary.getEntities().get("vendors").getAdded()).isEqualTo(2);
        assertThat(summary.getEntities().get("industries").getAdded()).isEqualTo(2);
        assertThat(summary.getEntities().get("regions").getAdded()).isEqualTo(3);
        assertThat(summary.getEntities().get("solution_types").getAdded()).isEqualTo(2);
        assertThat(summary.getEntities().get("processes").getAdded()).isEqualTo(5);

        // объединение дубля: описание — самое длинное, цена — минимальная
        var solutionA = solutionRepository.findByExternalId(ID_A).orElseThrow();
        assertThat(solutionA.getDescription()).contains("гораздо более длинное");
        assertThat(solutionA.getPriceRub()).isEqualByComparingTo("900000");

        // применение с конфликтом цен: сохранена минимальная
        assertThat(applicationRepository.count()).isEqualTo(5);

        // карта опечаток подтипов (assumptions.md §10/§13)
        var solutionB = solutionRepository.findByExternalId(ID_B).orElseThrow();
        Long subtypeId = solutionB.getSolutionSubtypeId();
        assertThat(subtypeId).isNotNull();

        // разрез «Сценарий»: опечатка процесса исправлена (§10)
        assertThat(summary.getWarnings().toString())
                .contains("Доставка биоматериаловм")
                .contains("Доставка биоматериалов");

        // провенанс импорта организатора
        assertThat(solutionA.getSourceKind()).isEqualTo("organizer_catalog");
        assertThat(solutionA.getSourceUrl()).isEqualTo("catalog_export_v4.csv");
    }

    @Test
    void scenarioExceptionValueIsNotSplit() {
        importCsv(SMALL_CSV, "small.csv");
        // «Доставка в удаленные, труднодоступные районы» — один процесс
        // (§12: запятая внутри названия), а не три обрывка
        long distinctProcesses = applicationRepository.findAll().stream()
                .map(me.yuugao.robomatch.domain.SolutionApplication::getProcessId)
                .distinct().count();
        assertThat(distinctProcesses).isEqualTo(5);
    }

    @Test
    void multilineQuotedCellSurvivesParsing() {
        importCsv(SMALL_CSV, "small.csv");
        var solutionC = solutionRepository.findByExternalId(ID_C).orElseThrow();
        // многострочная ячейка не разбила строку на две: пробелы (в т.ч.
        // \n) нормализуются norm_text (как pandas в seed), текст целый
        assertThat(solutionC.getDescription())
                .isEqualTo("Многострочное описание дрона");
    }

    @Test
    void repeatedImportIsIdempotent() {
        importCsv(SMALL_CSV, "small.csv");
        CatalogImportSummaryDto second = importCsv(SMALL_CSV, "small.csv");

        // 0 добавлений и 0 обновлений: всё skipped (идемпотентность)
        second.getEntities().forEach((entity, counters) -> {
            assertThat(counters.getAdded()).as(entity + ".added").isZero();
            assertThat(counters.getUpdated()).as(entity + ".updated").isZero();
        });
        assertThat(second.getEntities().get("solutions").getSkipped()).isEqualTo(4);
        assertThat(solutionRepository.count()).isEqualTo(4);
        assertThat(applicationRepository.count()).isEqualTo(5);
        assertThat(caseRepository.count()).isEqualTo(2);
        assertThat(caseLinkRepository.count()).isEqualTo(3);
    }

    @Test
    void brokenCellsGiveRowErrorsAndNothingIsSaved() {
        String broken = """
                id;Название;тип;статус;компания;описание;Тип;Подтип;Сценарий;Кейсы;УГТ;Рын Потенциал;Регион;Отрасль;Цена изделия
                %s;Робот Сломан;brs;hype;ООО Ромбот;;Мобильные роботы;AMR;Внутрискладская логистика;;8;4;Москва;Логистика;1 000 000,00
                %s;;brs;operation;;0;Мобильные роботы;AMR;Внутрискладская логистика;;8;4;Москва;Логистика;не число
                """.formatted(ID_A, ID_B);

        assertThatThrownBy(() -> importCsv(broken, "broken.csv"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("ошибки");

        // ничего не записано, история фиксирует неудачу
        assertThat(solutionRepository.count()).isZero();
        assertThat(applicationRepository.count()).isZero();
        Optional<AdminImportLog> failed = importRepository
                .findFirstByOrderByStartedAtDesc();
        assertThat(failed).isPresent();
        assertThat(failed.get().getStatus()).isEqualTo("failed");
    }

    @Test
    void missingColumnGivesFormatError() {
        String noIndustry = """
                id;Название;тип;статус;компания;описание;Тип;Подтип;Сценарий;Кейсы;УГТ;Рын Потенциал;Регион;Цена изделия
                %s;Робот;brs;operation;ООО Ромбот;;Мобильные роботы;AMR;Внутрискладская логистика;;8;4;Москва;100
                """.formatted(ID_A);

        assertThatThrownBy(() -> importCsv(noIndustry, "no-industry.csv"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("отсутствуют колонки");
    }

    @Test
    void regeneratedUuidsUpdateExistingCardsByName() {
        importCsv(SMALL_CSV, "small.csv");
        // организатор воссоздал файл с новыми UUID: карточки совпадают по
        // (вендор, название) -> UPDATE, а не дубли (UNIQUE vendor_id+name)
        UUID regeneratedA = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
        String regen = SMALL_CSV.replace(ID_A.toString(), regeneratedA.toString());
        CatalogImportSummaryDto summary = importCsv(regen, "regenerated.csv");

        // обновлена только карточка А (перепривязка UUID), B/C/D без изменений
        assertThat(summary.getEntities().get("solutions").getAdded()).isZero();
        assertThat(summary.getEntities().get("solutions").getUpdated()).isEqualTo(1);
        assertThat(summary.getEntities().get("solutions").getSkipped()).isEqualTo(3);
        assertThat(solutionRepository.count()).isEqualTo(4);
        // external_id перепривязан к новому UUID
        assertThat(solutionRepository.findByExternalId(regeneratedA)).isPresent();
    }

    @Test
    void staleRunningImportIsFailedAndDoesNotBlock() {
        // инцидент-сценарий: backend упал посреди импорта, строка
        // 'running' осталась; новый импорт не должен блокироваться вечно
        AdminImportLog stale = importRepository.save(AdminImportLog.builder()
                .fileName("stale.csv")
                .filePath("stale.csv")
                .sizeBytes(1L)
                .startedAt(Instant.now().minus(java.time.Duration.ofMinutes(30)))
                .status("running")
                .createdByUserId(userId)
                .build());

        CatalogImportSummaryDto summary = importCsv(SMALL_CSV, "after-stale.csv");

        assertThat(summary.getSolutions()).isEqualTo(4);
        // перечитываем: сервис обновил свой экземпляр строки из БД
        AdminImportLog reloaded = importRepository.findById(stale.getId())
                .orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("failed");
        assertThat(reloaded.getFinishedAt()).isNotNull();
    }

    @Test
    void inFlightImportConflicts() {
        importRepository.save(AdminImportLog.builder()
                .fileName("in-flight.csv")
                .filePath("x.csv")
                .sizeBytes(1L)
                .startedAt(Instant.now())
                .status("running")
                .createdByUserId(userId)
                .build());

        assertThatThrownBy(() -> importCsv(SMALL_CSV, "second.csv"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже выполняется");
    }

    @Test
    void historyKeepsCompletedRowWithSummary() {
        CatalogImportSummaryDto summary = importCsv(SMALL_CSV, "history.csv");

        List<AdminImportLog> history = importRepository
                .findAllByOrderByStartedAtDesc(org.springframework.data.domain
                        .PageRequest.of(0, 10));
        assertThat(history).hasSize(1);
        AdminImportLog row = history.get(0);
        assertThat(row.getStatus()).isEqualTo("completed");
        // чтение summary_json через тот же разбор, что и в API истории
        var parsed = CatalogJson.toMap(row.getSummaryJson());
        assertThat(parsed).isNotNull();
        assertThat(((Number) parsed.get("solutions")).intValue()).isEqualTo(4);
        assertThat(parsed.get("entities")).isNotNull();
        assertThat(row.getFileName()).isEqualTo("history.csv");
        assertThat(summary.getImportId()).isEqualTo(row.getId());
    }

    @Test
    void refreshWithoutFilesRejected() {
        assertThatThrownBy(() -> importer.refreshLast(userId))
                .isInstanceOf(me.yuugao.robomatch.exception.BadRequestException.class)
                .hasMessageContaining("нет");
    }

    @Test
    void refreshReimportsLastFile() {
        importCsv(SMALL_CSV, "first.csv");
        // «потеря» данных каталога: решения с применениями удалены напрямую,
        // справочники и кейсы целы — refresh восстанавливает решения
        caseLinkRepository.deleteAll();
        applicationRepository.deleteAll();
        solutionRepository.deleteAll();

        CatalogImportSummaryDto summary = importer.refreshLast(userId);

        assertThat(summary.getEntities().get("solutions").getAdded()).isEqualTo(4);
        assertThat(summary.getEntities().get("applications").getAdded()).isEqualTo(5);
        assertThat(summary.getEntities().get("case_links").getAdded()).isEqualTo(3);
        assertThat(solutionRepository.count()).isEqualTo(4);
        // в истории теперь две записи: исходная + refresh
        assertThat(importRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Фикстуры расширенного формата (docs/test-fixtures)
    // ------------------------------------------------------------------

    @Test
    void xlsxFileIsImported() throws Exception {
        Path xlsx = writeSmallXlsx();
        CatalogImportSummaryDto summary = importer.importFile(
                new MockMultipartFile("file", "small.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        Files.readAllBytes(xlsx)),
                userId);

        assertThat(summary.getSolutions()).isEqualTo(2);
        assertThat(summary.getEntities().get("solutions").getAdded()).isEqualTo(2);
        assertThat(applicationRepository.count()).isEqualTo(2);
    }

    @Test
    void fullCatalogMatchesPythonSeedCounters() throws Exception {
        // эталон map_catalog на catalog_export_v4.csv (см. javadoc класса).
        // Файл читается из test resources, а не относительным путём ../docs:
        // при Docker-сборке образа тест запускается в контексте, где есть
        // только backend/ (docs/source недоступен), и раньше падал на
        // проверке существования файла — счётчики тут ни при чём.
        byte[] catalogCsv = readTestResource("/catalog_export_v4.csv");

        // защита от рассинхрона: где есть полный чекаут (локально, CI),
        // снапшот обязан совпадать с docs/source байт-в-байт
        Path repoCsv = Path.of("..", "docs", "source", "catalog_export_v4.csv");
        if (Files.exists(repoCsv)) {
            assertThat(Files.readAllBytes(repoCsv))
                    .as("снапшот src/test/resources/catalog_export_v4.csv "
                            + "должен совпадать с docs/source/... — «обнови "
                            + "снапшот и эталонные счётчики (data_model §7)»")
                    .isEqualTo(catalogCsv);
        }

        CatalogImportSummaryDto summary = importer.importFile(
                new MockMultipartFile("file", "catalog_export_v4.csv",
                        "text/csv", catalogCsv), userId);

        assertThat(summary.getCsvRows()).isEqualTo(223);
        assertThat(summary.getSolutions()).isEqualTo(187);
        assertThat(summary.getDupGroups()).isEqualTo(23);
        assertThat(summary.getEntities().get("solutions").getAdded()).isEqualTo(187);
        assertThat(summary.getEntities().get("applications").getAdded()).isEqualTo(250);
        assertThat(summary.getEntities().get("cases").getAdded()).isEqualTo(175);
        assertThat(summary.getEntities().get("case_links").getAdded()).isEqualTo(195);
        assertThat(summary.getEntities().get("vendors").getAdded()).isEqualTo(103);
        assertThat(summary.getEntities().get("industries").getAdded()).isEqualTo(9);
        assertThat(summary.getEntities().get("regions").getAdded()).isEqualTo(24);
        assertThat(summary.getEntities().get("solution_types").getAdded()).isEqualTo(11);
        assertThat(summary.getEntities().get("solution_subtypes").getAdded())
                .isEqualTo(68);
        assertThat(summary.getEntities().get("processes").getAdded()).isEqualTo(95);

        // повторный импорт — чистая идемпотентность на полном каталоге
        CatalogImportSummaryDto second = importer.importFile(
                new MockMultipartFile("file", "catalog_export_v4.csv",
                        "text/csv", catalogCsv), userId);
        second.getEntities().forEach((entity, counters) -> {
            assertThat(counters.getAdded()).as(entity + ".added").isZero();
            assertThat(counters.getUpdated()).as(entity + ".updated").isZero();
        });
        assertThat(second.getEntities().get("solutions").getSkipped()).isEqualTo(187);
    }

    @Test
    void importTestFixtureA_xlsx() throws Exception {
        byte[] fixture = readTestResource("/test-fixtures/full_catalog_a.xlsx");
        assertFixtureSnapshotMatches("full_catalog_a.xlsx", fixture);

        CatalogImportSummaryDto summary = importer.importFile(
                new MockMultipartFile("file", "full_catalog_a.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        fixture), userId);

        // 4 решения, все 27 ТТХ у каждого
        assertThat(summary.getCsvRows()).isEqualTo(4);
        assertThat(summary.getSolutions()).isEqualTo(4);
        assertThat(summary.getEntities().get("solutions").getAdded()).isEqualTo(4);
        assertThat(summary.getEntities().get("characteristics").getAdded())
                .isEqualTo(4 * 27);
        assertThat(summary.getEntities().get("characteristics").getUpdated()).isZero();
        assertThat(summary.getEntities().get("vendors").getAdded()).isEqualTo(1);
        // A1/A4: Внутрискладская логистика + Погрузка-разгрузка (2 тройки),
        // A2: 1, A3: Уборка помещений (новый процесс) — 6 применений
        assertThat(summary.getEntities().get("applications").getAdded()).isEqualTo(6);
        assertThat(characteristicRepository.count()).isEqualTo(4 * 27);

        // зеркальные колонки solution синхронизированы (§5.8)
        Solution a1 = solutionRepository.findByExternalId(FIXTURE_A1).orElseThrow();
        assertThat(a1.getPayloadKg()).isEqualByComparingTo("1500");
        assertThat(a1.getMassKg()).isEqualByComparingTo("1350");
        assertThat(a1.getLengthMm()).isEqualByComparingTo("2050");
        assertThat(a1.getWidthMm()).isEqualByComparingTo("1150");
        assertThat(a1.getHeightMm()).isEqualByComparingTo("2200");
        assertThat(a1.getPositioningAccuracyMm()).isEqualByComparingTo("10");
        assertThat(a1.getSpeedMs()).isEqualByComparingTo("2.0");
        assertThat(a1.getChargingPowerKw()).isEqualByComparingTo("9.5");
        assertThat(a1.getNoiseLevelDba()).isEqualByComparingTo("68");

        // EAV: провенанс organizer_catalog + источник/дата/подтверждение из файла
        SolutionCharacteristic payload = charRow(FIXTURE_A1, "payload_kg");
        assertThat(payload.getSourceKind()).isEqualTo("organizer_catalog");
        assertThat(payload.getSourceUrl())
                .isEqualTo("https://github.com/meyuugao/LCT_Hakaton");
        assertThat(payload.getSourceDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 26));
        assertThat(payload.getIsConfirmed()).isTrue();
        assertThat(payload.getValueNumeric()).isEqualByComparingTo("1500");

        // текстовые и дата-ТТХ типобезопасны
        assertThat(charRow(FIXTURE_A1, "navigation_type").getValueText())
                .isEqualTo("лидарная SLAM");
        assertThat(charRow(FIXTURE_A1, "source_date").getValueDate())
                .isEqualTo(java.time.LocalDate.of(2026, 9, 26));
        assertThat(charRow(FIXTURE_A1, "software_cost").getValueNumeric())
                .isEqualByComparingTo("350000");

        // 4 решения x 27 ТТХ — полный набор у каждого
        for (UUID ext : List.of(FIXTURE_A1, FIXTURE_A2, FIXTURE_A3, FIXTURE_A4)) {
            Solution sol = solutionRepository.findByExternalId(ext).orElseThrow();
            assertThat(characteristicRepository.findBySolutionId(sol.getId()))
                    .as("ТТХ решения " + sol.getName())
                    .hasSize(27);
        }
    }

    @Test
    void importTestFixtureB_csv() throws Exception {
        byte[] fixture = readTestResource("/test-fixtures/full_catalog_b.csv");
        assertFixtureSnapshotMatches("full_catalog_b.csv", fixture);

        CatalogImportSummaryDto summary = importer.importFile(
                new MockMultipartFile("file", "full_catalog_b.csv",
                        "text/csv", fixture), userId);

        assertThat(summary.getCsvRows()).isEqualTo(4);
        assertThat(summary.getSolutions()).isEqualTo(4);
        assertThat(summary.getEntities().get("characteristics").getAdded())
                .isEqualTo(4 * 27);
        assertThat(characteristicRepository.count()).isEqualTo(4 * 27);

        // зеркала CSV-набора: штабелер B1 и тяжёлый FMR B4
        Solution b1 = solutionRepository.findByExternalId(FIXTURE_B1).orElseThrow();
        assertThat(b1.getPayloadKg()).isEqualByComparingTo("1000");
        assertThat(b1.getNoiseLevelDba()).isEqualByComparingTo("66");
        Solution b4 = solutionRepository.findByExternalId(FIXTURE_B4).orElseThrow();
        assertThat(b4.getPayloadKg()).isEqualByComparingTo("2500");
        assertThat(b4.getSpeedMs()).isEqualByComparingTo("1.7");

        // наборы A и B независимы: B не видит решений A
        assertThat(solutionRepository.findByExternalId(FIXTURE_A1)).isEmpty();

        // провенанс фикстуры B идентичен A (репозиторий, дата, подтверждено)
        SolutionCharacteristic mass = charRow(FIXTURE_B4, "mass_kg");
        assertThat(mass.getSourceKind()).isEqualTo("organizer_catalog");
        assertThat(mass.getIsConfirmed()).isTrue();
        assertThat(mass.getSourceDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 26));
    }

    @Test
    void fixtureReimportsAreIdempotent() throws Exception {
        importer.importFile(new MockMultipartFile("file", "a.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                readTestResource("/test-fixtures/full_catalog_a.xlsx")), userId);
        importer.importFile(new MockMultipartFile("file", "b.csv", "text/csv",
                readTestResource("/test-fixtures/full_catalog_b.csv")), userId);

        // повтор обоих файлов — 0 добавлений и 0 обновлений (всё skipped)
        CatalogImportSummaryDto againA = importer.importFile(
                new MockMultipartFile("file", "a.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        readTestResource("/test-fixtures/full_catalog_a.xlsx")), userId);
        CatalogImportSummaryDto againB = importer.importFile(
                new MockMultipartFile("file", "b.csv", "text/csv",
                        readTestResource("/test-fixtures/full_catalog_b.csv")), userId);
        for (CatalogImportSummaryDto again : List.of(againA, againB)) {
            again.getEntities().forEach((entity, counters) -> {
                assertThat(counters.getAdded()).as(entity + ".added").isZero();
                assertThat(counters.getUpdated()).as(entity + ".updated").isZero();
            });
        }
        assertThat(againA.getEntities().get("characteristics").getSkipped())
                .isEqualTo(4 * 27);
        assertThat(solutionRepository.count()).isEqualTo(8);
        assertThat(characteristicRepository.count()).isEqualTo(8 * 27);
    }

    @Test
    void unknownColumnIsRejected() {
        // опечатка в колонке — не должна молча терять данные: заголовок,
        // не совпадающий ни с 15 колонками организатора, ни с кодом ТТХ,
        // отклоняется явной ошибкой формата
        String bad = SMALL_CSV.replaceFirst("УГТ", "УГТ2");
        assertThatThrownBy(() -> importCsv(bad, "typo.csv"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("Неизвестные колонки")
                .hasMessageContaining("УГТ2");
    }

    @Test
    void malformedCharacteristicCellGivesRowError() {
        // числовое ТТХ с мусором — ошибка строки, ничего не записано
        String csv = """
                id;Название;тип;статус;компания;описание;Тип;Подтип;Сценарий;Кейсы;УГТ;Рын Потенциал;Регион;Отрасль;Цена изделия;payload_kg
                %s;Робот Т;brs;operation;ООО Ромбот;;Мобильные роботы;AMR;Внутрискладская логистика;;8;4;Москва;Логистика;1 000 000,00;не число
                """.formatted(ID_A);
        assertThatThrownBy(() -> importCsv(csv, "bad-char.csv"))
                .isInstanceOf(ImportException.class)
                .hasMessageContaining("ошибки")
                .extracting("errors").asInstanceOf(list(ImportErrorDto.class))
                .singleElement()
                .satisfies(err -> {
                    assertThat(err.parameterCode()).isEqualTo("payload_kg");
                    assertThat(err.message()).contains("не является числом");
                });
        assertThat(solutionRepository.count()).isZero();
        assertThat(characteristicRepository.count()).isZero();
    }

    @Test
    void extendedColumnsDoNotClobberAbsentCharacteristics() throws Exception {
        // файл БЕЗ колонок ТТХ не трогает существующие характеристики
        // и зеркала (поведение полного каталога организатора сохранено)
        importer.importFile(new MockMultipartFile("file", "b.csv", "text/csv",
                readTestResource("/test-fixtures/full_catalog_b.csv")), userId);
        Solution b1 = solutionRepository.findByExternalId(FIXTURE_B1).orElseThrow();
        assertThat(b1.getPayloadKg()).isEqualByComparingTo("1000");

        importCsv(SMALL_CSV, "small.csv"); // 15 колонок, без ТТХ
        assertThat(characteristicRepository.count()).isEqualTo(4 * 27);
        assertThat(b1.getPayloadKg()).isEqualByComparingTo("1000");
    }

    /**
 * EAV-строка ТТХ решения по external_id + коду.
 */
    private SolutionCharacteristic charRow(UUID externalId, String code) {
        Solution solution = solutionRepository.findByExternalId(externalId).orElseThrow();
        CharacteristicType type = characteristicTypeRepository.findAll().stream()
                .filter(t -> t.getCode().equals(code)).findFirst().orElseThrow();
        return characteristicRepository
                .findBySolutionIdAndCharacteristicTypeId(solution.getId(), type.getId())
                .orElseThrow();
    }

    /**
 * Двухстрочный XLSX с теми же 15 колонками (POI).
 */
    private Path writeSmallXlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Каталог");
            Row header = sheet.createRow(0);
            String[] columns = {"id", "Название", "тип", "статус", "компания",
                    "описание", "Тип", "Подтип", "Сценарий", "Кейсы", "УГТ",
                    "Рын Потенциал", "Регион", "Отрасль", "Цена изделия"};
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            Row row1 = sheet.createRow(1);
            String[] values1 = {ID_A.toString(), "Робот Икс", "brs", "operation",
                    "ООО Ромбот", "Из Excel", "Мобильные роботы", "AMR",
                    "Внутрискладская логистика", "", "8", "4", "Москва",
                    "Логистика", "1000"};
            for (int i = 0; i < values1.length; i++) {
                row1.createCell(i).setCellValue(values1[i]);
            }
            Row row2 = sheet.createRow(2);
            String[] values2 = {ID_B.toString(), "Робот Игрек", "brs", "operation",
                    "ООО Ромбот", "Из Excel 2", "Мобильные роботы", "AMR",
                    "Погрузка-разгрузка", "", "7", "3", "Москва",
                    "Логистика", "2000"};
            for (int i = 0; i < values2.length; i++) {
                row2.createCell(i).setCellValue(values2[i]);
            }
            Path file = tempDir.resolve("small.xlsx");
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            return file;
        }
    }
}
