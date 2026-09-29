# RoboMatch Backend — Spring Boot 4.1.1 + Java 21

REST-сервис RoboMatch: аутентификация (JWT) и проекты с параметрами
объекта, каталог решений с подбором, сценарии и расчёт экономики,
имитация 2D + KPI, экспорт отчётов (PDF/Excel/CSV) и админка
(справочники, решения, импорт каталога организатора).
Стек: PostgreSQL + Flyway + Spring Data JPA + Spring Security (JWT) +
springdoc-openapi.
Сборщик — **Gradle 9.7.1** (Kotlin DSL); сам Gradle не нужен: обёртка `gradlew`
скачает его при первом запуске.

## Структура пакетов (me.yuugao.robomatch)

```
me.yuugao.robomatch/
├── RoboMatchApplication.java   # точка входа
├── config/                     # SecurityFilterChain, OpenAPI, JPA-аудит
├── controller/                 # REST-контроллеры (только приём/ответ)
├── service/                    # бизнес-логика (каталог, проекты, параметры,
│                               # файловые хранилища вложений/схем/отчётов)
├── repository/                 # Spring Data JPA репозитории
├── domain/                     # JPA-сущности (соответствуют схеме БД)
├── dto/                        # объекты передачи данных (контракт API)
├── mapper/                     # domain -> dto
├── exception/                  # ошибки + @RestControllerAdvice
├── security/                   # JWT-аутентификация: генерация/проверка токена,
│                               # фильтр, доступ к текущему пользователю
├── selection/                  # подбор решений и сценарии сравнения
├── simulation/                 # имитация 2D + KPI
├── economics/                  # расчёт экономики: формулы, чувствительность,
│                               # допущения, интерпретация окупаемости
├── export/                     # отчёты PDF/Excel/CSV + растеризация SVG-схем
└── admin/                      # админка: справочники, решения, ТТХ,
                                # импорт таблицы каталога организатора
```

Схема запроса: `Controller -> Service -> Repository -> PostgreSQL`,
ответ идёт обратно через `Mapper` (entity -> dto).

## Запуск

Требуется: JDK 21, PostgreSQL 16 (п. «База данных» ниже). Gradle не требуется —
обёртка `gradlew` сама скачает Gradle 9.7.1 при первом запуске.

```bash
# База данных (если ещё не поднята; из КОРНЯ проекта)
docker compose up -d db

# Backend (Gradle 9.7.1 скачается сам через gradlew)
cd backend
./gradlew bootRun
```

Альтернатива — собрать jar и запускать его:

```bash
./gradlew build                      # тесты + исполняемый jar
java -jar build/libs/robomatch-backend-0.1.0-SNAPSHOT.jar
```

Артефакты сборки — в `build/` (не `target/`, как у Maven): `build/libs/` — jar,
`build/reports/tests/test/` — HTML-отчёт по тестам.

### База данных

- Адрес, логин, пароль — в `src/main/resources/application.yml`
  (значения по умолчанию: `localhost:5432/robomatch`, `postgres/postgres`).
- Переопределяются переменными окружения: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
- **Схему создаёт Flyway** при старте: `V1__baseline_schema.sql` —
  базовая схема (29 таблиц), `V2__demo_data.sql` — 8 реальных строк каталога;
  V3–V7 достраивают схему до полной (31 таблица; консолидированный DDL —
  `scripts/seed/schema.psql`).
- Свои изменения схемы — НОВЫМИ файлами `V8__...`, применённые не править.
- `ddl-auto: validate` — Hibernate только сверяет сущности со схемой.

### Полный каталог (187 решений)

```bash
# из корня проекта; идемпотентно, совпадает с демо-строками V2 по external_id
DATABASE_URL='postgresql+psycopg2://postgres:postgres@localhost:5432/robomatch' \
  python scripts/seed/run.py
```

## Проверка

```bash
curl http://localhost:8080/api/solutions        # список решений (200)
curl http://localhost:8080/api/solutions/1      # одно решение (200)
curl http://localhost:8080/api/solutions/999    # 404 + тело ошибки
curl http://localhost:8080/v3/api-docs          # OpenAPI-спецификация (JSON)
```

- Swagger UI: **http://localhost:8080/swagger-ui.html**
  (документация всех реализованных API).
- Без аутентификации открыты: регистрация/вход (`/api/auth/register`,
  `/api/auth/login`), каталог `/api/solutions/**` и словари `/api/filters`, `/v3/api-docs/**`,
  `/swagger-ui/**` (гость листает каталог — mvp_scope.md).
  Остальные `/api/**` без токена — 401: `curl -i http://localhost:8080/api/projects`;
  `/api/admin/**` дополнительно требует роль ADMIN.

## Тесты

```bash
./gradlew test    # unit-тесты (моки) + интеграционные *IT
                  # на in-memory H2 (режим PostgreSQL), без внешней БД
```

## Docker

Один контейнер (multi-stage Dockerfile рядом):

```bash
docker compose up -d --build                   # весь стек
```

## Известные нюансы

- **Spring Boot 4**: автоконфигурации разнесены по модулям — для Flyway нужен
  `spring-boot-starter-flyway` (см. build.gradle.kts), не просто `flyway-core`.
- **Spring Boot 4**: старый `spring-boot-starter-web` переименован в
  `spring-boot-starter-webmvc` — в `build.gradle.kts` используется новое имя.
- `trl` и `completeness_pct` в БД — `smallint`, поэтому в сущности это `Short`
  (Hibernate валидирует типы строго; `Integer` не пройдёт `ddl-auto: validate`).
- ТТХ (payload_kg и пр.) в данных организатора не заполнены — приходят `null`;
  это нормально: ТТХ — проекция EAV (`solution_characteristic`), заполняется
  позже вручную/админкой.
