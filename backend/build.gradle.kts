// build.gradle.kts — «рецепт» сборки backend на Gradle 9.7 (Kotlin DSL).
//
// Как это читается:
// - plugins: java (компиляция, тесты, jar) + плагин Spring Boot 4.1.1
//   (задачи bootRun / bootJar) + io.spring.dependency-management
//   (подключает BOM — версии Spring-библиотек указывать не нужно,
//   ровно как в Maven это делал spring-boot-starter-parent).
// - toolchain 21: проект собирается под JDK 21 (LTS); если локально
//   стоит другая Java — Gradle сам найдёт/скачает нужную.
// - repositories: откуда качать библиотеки. Maven Central — это просто
//   репозиторий артефактов, он остаётся и при сборщике Gradle.
// - Каждый dependency — один «кирпичик»: webmvc (REST), data-jpa
//   (Hibernate + Spring Data), security (Spring Security), flyway
//   (миграции БД), springdoc (OpenAPI/Swagger UI), lombok
//   (генерация getter/setter/билдера), postgresql (JDBC-драйвер).

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "me.yuugao.robomatch"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // REST: встроенный Tomcat, Jackson (JSON), контроллеры.
    // ВАЖНО (Spring Boot 4): старый spring-boot-starter-web переименован
    // в spring-boot-starter-webmvc (именно его генерирует start.spring.io).
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Spring Data JPA + Hibernate: сущности, репозитории, транзакции
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Spring Security: SecurityFilterChain, BCrypt, JwtAuthFilter
    implementation("org.springframework.boot:spring-boot-starter-security")

    // JJWT: генерация и проверка JWT (HMAC-SHA256).
    // api — интерфейс (компиляция), impl + jackson — рантайм-реализации.
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // Валидация DTO: @Valid/@NotBlank/@Size (ТЗ 4.5.4 — понятные ошибки)
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Flyway: версионные миграции БД (V1__..., V2__...).
    // ВАЖНО (Spring Boot 4): автоконфигурации разнесены по модулям,
    // поэтому нужен spring-boot-starter-flyway — без него Flyway
    // просто не запустится при старте приложения.
    implementation("org.springframework.boot:spring-boot-starter-flyway")

    // Модуль поддержки PostgreSQL (для Flyway 10+ обязателен)
    implementation("org.flywaydb:flyway-database-postgresql")

    // springdoc-openapi: генерация OpenAPI-спецификации + Swagger UI
    // (ТЗ 4.2.5, 3.8.5: спецификация и документация API).
    // Версия указана явно: springdoc не входит в BOM Spring Boot.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // Jackson 2 (com.fasterxml): сериализация metrics_json расчётов
    // экономики. Boot 4 работает на Jackson 3 (tools.jackson), бина
    // ObjectMapper Jackson 2 в контейнере нет — модуль экономики
    // использует собственный экземпляр. Зависимость существовала только
    // транзитивно (springdoc -> swagger-core -> jackson-databind) —
    // объявляем явно, чтобы апгрейд springdoc не сломал компиляцию
    // Версия указана явно: Jackson 2 нет в BOM Boot 4.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.5")

    // Apache POI: чтение и генерация Excel (импорт параметров ТЗ 3.2.3,
    // шаблон xlsx; экспорт отчёта ТЗ 3.7.3 — листы XSSF). poi-ooxml тянет
    // poi (xls/HSSF) и ooxml-lite; версии POI нет в BOM Spring Boot —
    // указана явно.
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    // Apache PDFBox 3.x: генерация PDF-отчёта (ТЗ 3.7.3).
    // Apache 2.0 — свободное ПО (ТЗ 4.2.2). Кириллица — встроенные TTF
    // Noto Sans Regular+Bold (OFL) в resources/fonts через PDType0Font.
    // Версии PDFBox и Batik нет в BOM Spring Boot — указаны явно.
    implementation("org.apache.pdfbox:pdfbox:3.0.5")

    // Batik: растеризация сохранённой 2D-схемы (SVG -> PNG) для вставки
    // в PDF-отчёт (ТЗ 3.7.4). Apache 2.0. Схема приходит из хранилища
    // ПОСЛЕ серверной санитизации (SimulationStorage, allowlist) —
    // скрипты/SMIL/внешние ссылки исключены конструктивно.
    // batik-codec обязателен: PNGTranscoder пишет PNG через WriteAdapter
    // из этого модуля (без него — «no WriteAdapter is available»).
    implementation("org.apache.xmlgraphics:batik-transcoder:1.17")
    implementation("org.apache.xmlgraphics:batik-codec:1.17")

    // Lombok: @Getter/@Setter/@Builder вместо boilerplate.
    // compileOnly + annotationProcessor: библиотека нужна только при
    // компиляции — в исполняемый jar НЕ упаковывается (в Maven ради
    // этого приходился настраивать exclude в spring-boot-maven-plugin).
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // JDBC-драйвер PostgreSQL
    runtimeOnly("org.postgresql:postgresql")

    // Тесты: JUnit 5, AssertJ, Mockito
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // H2: in-memory БД для интеграционных тестов (режим PostgreSQL)
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<Test> {
    // JUnit 5: без этого Gradle ищет JUnit 4
    useJUnitPlatform()
    // По умолчанию Gradle подхватывает только *Test/*Tests — подключаем
    // интеграционные *IT (AuthControllerIT), аналогично surefire-include.
    include("**/*Test.class", "**/*Tests.class", "**/*IT.class")
}

// bootJar собирает «толстый» исполняемый jar (java -jar ...).
// Обычная задача jar дублировала бы его (*-plain.jar) — отключаем,
// чтобы в build/libs лежал ровно один артефакт.
tasks.jar {
    enabled = false
}
