# Архитектура

## Назначение документа

Документ фиксирует **стек**, **общую структуру** и **факты текущего
состояния системы**.

Связанные документы:
- `docs/requirements/nonfunctional.md` — требования к стеку, Docker, OpenAPI,
  производительности, безопасности.
- `docs/data_model.md` — модель данных (31 таблица, миграции, seed).
- `docs/economic_model.md` — расчётный модуль.
- `docs/selection_algorithm.md` — модуль подбора.
- `docs/assumptions.md`, `docs/glossary.md`, `docs/mvp_scope.md`.

## 1. Стек

### Backend
- Java 21
- Spring Boot 4.1.1
- Spring Web (REST)
- Spring Data JPA (Hibernate)
- Spring Security
- Flyway (миграции)
- springdoc-openapi 3.1.1 (OpenAPI, Swagger UI). Версия — актуальный
  релиз линии 3.x, которая и предназначена для Spring Boot 4 / Spring
  Framework 7 (линия 2.x осталась на Boot 3): на Maven Central новее
  нет. Совместимость с Boot 4.1.1 подтверждена живым прогоном —
  спецификация `/v3/api-docs` (OpenAPI 3.1, 63 операции), Swagger UI и
  схема bearer-jwt работают
- Lombok
- PostgreSQL (JDBC-драйвер)
- Сборка: Gradle 9.7.1
- Базовый пакет: `me.yuugao.robomatch`

### Frontend
- Next.js 16 (App Router)
- React 19
- TypeScript 5
- Tailwind CSS 4 (класс-стратегия тёмной темы: `@custom-variant dark`
  в globals.css + класс `.dark` на `<html>`)
- HTTP-клиент: fetch (встроенный)
- Тёмная тема: переключатель в шапке
  (светлая/тёмная/системная), выбор — localStorage «theme»,
  применяет инлайн-скрипт в `<head>` ДО первой отрисовки (без
  FOUC/мигания); все страницы — `dark:`-варианты Tailwind; SVG-схема
  имитации — две палитры (SimulationView), переключение на лету без
  рестарта имитации, сохранённая схема — в текущей теме; PDF-отчёт —
  параметр theme (light/dark, выбор на странице экспорта, по
  умолчанию как в UI), Excel/CSV — без темы; e2e —
  scripts/verify_dark_theme.py (43 проверки), скриншоты —
  docs/examples/theme/
- Вид 2D-схемы имитации — чистый SVG (тяжёлых библиотек нет):
  диспетчерский стиль — холст с сеткой (pattern), зоны с полупрозрачной
  семантикой нагрузки (красная/янтарная/нейтральная + зарядная), плашки имён и
  бейдж «узкое место» с иконкой-предупреждением и двойной пунктирной
  рамкой, цельные иконки роботов (корпус-капсула с ореолом, тенью
  feDropShadow, указателем направления и знаками статуса), маршрут с
  шевронами направления, читаемые иконки точек операций (коробки/
  стеллажи/корзины/молнии), подписи потоков на плашках, отдельный
  блок легенды (статусы + типы точек + маршрут + узкое место);
  адаптивность 1366×768/1920×1080/мобильный (контейнер
  overflow-x-auto); экспорт сохраняет тот же вид (санитизация
  пропускает pattern/marker/filter/fedropshadow — декларативная
  статика, расширение allowlist осознанное; Batik растеризует
  feDropShadow в PDF); e2e — scripts/verify_simulation_schema_look.py
  (18 проверок), скриншоты до/после — docs/examples/schema-redesign/

### Инфраструктура
- Docker + docker-compose
- PostgreSQL 16
- Adminer (dev-инструмент)

### Seed
- Python 3.12+, pandas, SQLAlchemy, psycopg2, pytest

**Обоснование:** весь стек — свободное ПО с открытым исходным кодом.
Подробные версии — в `build.gradle.kts`, `package.json`,
`docker-compose.yml`.

## 2. Компоненты и порты

| Компонент  | Технология  | Порт |
|------------|-------------|------|
| Frontend   | Next.js     | 3000 |
| Backend    | Spring Boot | 8080 |
| PostgreSQL | СУБД        | 5432 |
| Adminer    | админка БД  | 8081 |

Вспомогательные компоненты:
- **Flyway** — применяет миграции при старте backend.
- **Seed-скрипт** — идемпотентная загрузка каталога и параметров;
  схему не создаёт.

Принципиальные решения:
- Frontend обращается к backend **только через BFF**.
- Backend — единственный источник истины по данным.
- Схема БД принадлежит Flyway; seed и `schema.psql` — вспомогательные.

## 3. Backend: общая структура

Базовый пакет `me.yuugao.robomatch`. Data flow:
**controller → service → repository → domain**.

| Пакет         | Назначение                | Статус |
|---------------|---------------------------|--------|
| `controller/` | REST-эндпоинты            | каталог (список/карточка/сравнение) + словари фильтров + аутентификация + проекты (CRUD, копирование) + параметры объекта + подбор (запуск, результаты) + сценарии (CRUD, ручное добавление решений — состав, домен сценариев) + экономика (допущения, расчёт, история, корректировка, сравнение) + имитация (запуск, последний результат, история, сохранение/скачивание схемы) + экспорт (генерация pdf/xlsx/csv, история, скачивание, удаление) + AdminController: 16 эндпоинтов /api/admin/** — write-CRUD решений/ТТХ/7 справочников (read карточки — публичный) + импорт каталога + история + refresh |
| `service/`    | Бизнес-логика, транзакции | каталог + аккаунты + проекты + параметры (ProjectParameterService, ImportParser, AttachmentStorage, ParameterTemplateService) + SimulationStorage (хранилище схем имитации) + ExportStorage (хранилище отчётов data/exports/{projectId}) |
| `repository/` | Spring Data JPA           | 31: Solution + 12 справочников каталога + User + Project + ObjectType + ParameterType + ObjectTypeParameter + ProjectParameterValue + ProjectAttachment + ObjectTypeIndustry + Scenario + ScenarioSolution + SelectionResult + Calculation + CalculationAssumption + ManualAdjustment + ProjectAssumption + SimulationResult (таблица из V1) + Export (таблица из V1) + AdminImportLog (V7; + расширены Solution/ScenarioSolution/SelectionResult/SolutionApplication/ObjectTypeIndustry/SolutionTypeSubtypeMapping/SolutionCharacteristic методами existsBy*/поиска дублей для FK-запретов удаления 409 и adminSearch) |
| `domain/`     | JPA-сущности              | 47 файлов = 31 @Entity + 6 enum + 6 конвертеров + 4 IdClass: `Solution` (добавлены `SimulationResult`, `Export`; обе таблицы из V1, миграций не потребовалось): `Solution`, `SolutionCharacteristic`, `User` + `UserRole`, 11 справочников каталога + 2 IdClass, `Project` + `ProjectStatus`, `ObjectType`, `ParameterType` + `ParameterValueType`, `ObjectTypeParameter` (is_fixed/is_derived — V4), `ProjectParameterValue` + `ParameterValueSource`, `ProjectAttachment`, `ObjectTypeIndustry` (+IdClass), `Scenario` + `ScenarioType`, `ScenarioSolution` (+IdClass), `SelectionResult` + `SelectionStatus`, `Calculation`, `CalculationAssumption`, `ManualAdjustment`, `ProjectAssumption` (V5) |
| `dto/`        | DTO для API               | каталог (11 DTO), Auth/User, Project (Summary/Full/Create/Update), параметры (ParameterDto с isFixed/isDerived/derivedFromName, TypedValue, ParameterSetRequest, ImportResult/ImportError, Attachment), подбор (SelectionRunDto, SelectionResultDto, ScenarioDto, CriterionContributionDto, ManualAddRequest), экономика (13: AssumptionDto/UpdateRequest, CalculationDto/FullDto, ComparisonDto + вложенные, InterpretationDto с paybackHuman, ManualAdjustRequest, ScenarioCreate/UpdateRequest, ScenarioSolutionDto, ScenarioDetailsDto, SensitivityRowDto), имитация (SimulationRunDto с зонами/составом/входами, SimulationExportRequest), экспорт (ExportDto, ExportCreateRequest), админка (8: AdminSolutionCreateRequest/AdminSolutionUpdateRequest, AdminCharacteristicUpsertRequest, AdminReferenceCreateRequest/AdminReferenceUpdateRequest, AdminReferenceDto, CatalogImportSummaryDto с EntityCountersDto, AdminImportDto, ImportErrorDto (переиспользован)). Конвенция именования — подраздел «Конвенция DTO» ниже |
| `mapper/`     | Domain ↔ DTO (ручной)     | SolutionMapper, UserMapper, ProjectMapper |
| `exception/`  | Централизованные ошибки   | `GlobalExceptionHandler` (400/401/403/404/409/413/405/500; BadRequest/TypeMismatch; ImportException со списком ошибок и PayloadTooLarge; EconomicValidationException со списком недостающих) |
| `security/`   | Аутентификация, роли      | JwtService, JwtAuthFilter, CurrentUser (getUserId/requireUserId — fail-fast 401 для изоляции) |
| `config/`     | Spring-конфигурация       | Security, OpenAPI, JPA Auditing |
| `economics/`  | Расчётный модуль          | EconomicModel (чистый движок формул §2.1–2.13, без Spring/БД), EconomicCalculationService (сквозной путь: входы → валидация → расчёт → append-only расчёт со снимком допущений; синхронно ≤ 10 с), AssumptionService (каталог допущений assumptions.md §22–23 + переопределения project_assumption), SensitivityService (±20/±10/0 × 3 параметра), InterpretationFormatter (человекочитаемая окупаемость) |
| `selection/`  | Модуль подбора            | SelectionService (конвейер selection_algorithm.md §2–§9: отрасли → пул → 8 ТТХ → статусы → ранжирование → UPSERT) + ScenarioService (bootstrap base/purchase/raas при первом запуске, ручное добавление решений); Score и вклад критериев — пересчёт на лету (data_model.md §12) |
| `simulation/` | Модуль имитации           | SimulationModel (чистый движок KPI — 6 KPI: заявленная/фактическая производительность, загрузка, простои, узкие места по 4 зонам, достижимость; без Spring/БД, unit-тестируется), SimulationService (оркестратор: входы → валидация → расчёт → simulation_result running → completed/failed; синхронно, миллисекунды — укладывается в порог 60 с без очереди; сверка с последним расчётом экономики) |
| `export/`     | Модуль экспорта           | ReportDataBuilder (сборка модели отчёта из ПОСЛЕДНИХ расчётов сценариев — отчёт читает готовые метрики и не пересчитывает), PdfReportGenerator (Apache PDFBox 3.x, кириллица — встроенные Noto Sans Regular+Bold OFL в resources/fonts, 10 разделов, пометка о предварительной оценке на титуле и в конце; кликабельное оглавление (страница «Содержание» + ссылки-аннотации + закладки-Outline), нумерация страниц «N из M», акцентные заголовки разделов и зебра-полосы таблиц; SvgRasterizer — Batik SVG→PNG для вставки сохранённой 2D-схемы), ExcelReportGenerator (POI XSSF, 7 листов, числовые ячейки с форматами — деньги # ##0, проценты 0,0 %, годы 0,0, штуки 0; ширины колонок по содержимому, закрепление шапки, автофильтры, условное форматирование Δ-метрик — отрицательные красным, положительные зелёным), CsvReportGenerator (UTF-8 BOM, «;», десятичная запятая, RFC 4180 — значения 1:1 с XLSX), ExportService (оркестратор: гейт склада, наличие расчётов, in-flight-замок 409 на (проект, формат), двухшаговая запись файла+строки с компенсацией) |
| `admin/`      | Модуль админки            | AdminSolutionService (CRUD решений + валидации + провенанс manual + запрет удаления 409 при ссылках из сценариев/подбора — data_model §11), AdminReferenceService (универсальный CRUD 7 справочников: vendor без кода, process с is_active, characteristic_type с group/data_type; FK-запреты удаления 409), AdminCharacteristicService (EAV-ТТХ: типобезопасность по data_type, провенанс manual/open_source с is_confirmed, синхронизация 9 зеркальных колонок solution — §5.8), CatalogImporter (импорт таблицы организатора CSV/XLSX — ПОРТ ПРАВИЛ Python-маппера catalog_mapper.py: дедуп по external_id, карты опечаток §10/13, обработка колонки «Сценарий» (§12), upsert по external_id/(vendor,name)/имени справочников; ИДЕМПОТЕНТНОСТЬ — повтор 0 изменений; отличие от seed: не обнуляет зеркальные ТТХ; файлы в data/admin-imports/{id}.{ext}, 50 МБ -> 413, in-flight -> 409, зависший running >15 мин -> failed), CatalogFileParser (CSV-автомат по всему файлу — многострочные кавычки; XLSX через POI ImportParser), AdminTextUtil (порт normalizers/strings.py: normText/canonicalKey/slugify), CatalogJson (summary_json <-> Map; @JdbcTypeCode(SqlTypes.JSON) в сущности — PostgreSQL не принимает varchar в jsonb напрямую) |

Правила слоёв (зафиксированы, не меняются):
- Контроллер вызывает только сервис.
- Сервис — только репозиторий и маппер.
- Транзакции — на сервисе.
- Entity наружу не отдаются — только DTO.
- Ручной маппинг (MapStruct не подключён).

### Конвенция DTO (зафиксирована)

Суффикс имени класса однозначно говорит о роли типа в API:

| Суффикс           | Роль                           | Примеры                                    |
|-------------------|--------------------------------|--------------------------------------------|
| `*Request`        | тело запроса (вход)            | `ProjectCreateRequest`, `ManualAddRequest` |
| `*Dto`            | ответ/выход (единственный)     | `ProjectSummaryDto`, `SimulationRunDto`    |
| `PageResponse<T>` | универсальная обёртка страницы | `PageResponse<SolutionSummaryDto>`         |

- Выходные типы используют ТОЛЬКО суффикс `*Dto`; суффиксы `*View`,
  `*Response`, `*Summary` и беспрефиксные имена для выходных типов не
  применяются (enum'ы, доменные сущности и обёртка `PageResponse` —
  исключения, не DTO-ответы).
- Вложенные записи выходных DTO также несут суффикс `*Dto`
  (`SimulationRunDto.ZoneLoadDto`, `ComparisonDto.ScenarioColumnDto`);
  слишком общие имена раскрыты доменом: `ScenarioCompositionItemDto`,
  `ScenarioCompositionLineDto`, `SimulationInputsDto`,
  `EntityCountersDto`.
- Проверяется автоматически: `scripts/verify_openapi.py` (раздел
  «конвенция DTO») — новые имена есть в `/v3/api-docs`, старых нет,
  суффиксы `*View`/`*Response` в схемах не встречаются.

## 4. Frontend: общая структура

Next.js App Router. Все компоненты — Server Components по умолчанию.

```
frontend/
├── app/
│   ├── layout.tsx          — корневой layout
│   ├── page.tsx            — главная «/»
│   ├── (auth)/login|register — вход/регистрация
│   ├── catalog/…           — каталог, карточка, сравнение
│   ├── projects/…          — список, new, [id], [id]/edit,
│   │                          [id]/parameters — параметры,
│   │                          [id]/selection — подбор,
│   │                          [id]/scenarios, [id]/economics,
│   │                          [id]/simulation — имитация 2D + KPI
│   │                          (client-компонент
│   │                          SimulationView: SVG-схема, анимация, управление),
│   │                          [id]/export — экспорт отчёта PDF/Excel/CSV
│   │                          (client-компонент
│   │                          ExportPanel: 3 кнопки + история)
│   ├── admin/…             — админка (только роль admin,
│   │                          requireAdmin: user -> /projects?forbidden):
│   │                          дашборд /admin (плитки + последние загрузки),
│   │                          /admin/solutions (+/new, /[id] — форма +
│   │                          редактор ТТХ с провенансом), /admin/references
│   │                          (+/[dictCode] — CRUD-таблица), /admin/catalog/
│   │                          import (загрузка + refresh + журнал);
│   │                          client-компоненты components/admin/*:
│   │                          AdminSolutionForm, AdminCharacteristicsEditor,
│   │                          AdminReferencesTable, AdminSolutionsTable
│   │                          (+ AdminBulkDeleteBar, ConfirmDialog),
│   │                          CatalogImportForm
│   ├── api/                — BFF-роуты (auth, solutions, filters, projects,
│   │                          projects/[id]/parameters/*,
│   │                          projects/[id]/selection/* — 3 роута,
│   │                          projects/[id]/{scenarios,assumptions,calculations,
│   │                          compare} — 9 роутов экономики,
│   │                          projects/[id]/scenarios/[scenarioId]/simulation
│   │                          {,/run,/export}, projects/[id]/simulations
│   │                          {,/[simulationId]/export} — 5 роутов
│   │                          имитации; run — таймаут 70 с;
│   │                          projects/[id]/exports {,/[exportId]} —
│   │                          2 роута экспорта (POST-генерация —
│   │                          таймаут 60 с, скачивание — бинарный поток
│   │                          с Content-Disposition из backend)
│   └── globals.css
├── components/             — переиспользуемые компоненты
├── lib/                    — клиент API, утилиты
├── types/                  — TypeScript-типы
└── конфиги
```

**Название раздела в UI — «Управление»** (зафиксировано):
URL-пути `/admin/**` — внутренняя сущность (не переименовываются:
BFF-роуты, ссылки и тест-селекторы остаются стабильными, миграция
UI-роутов не нужна). В видимом интерфейсе раздел называется
«Управление» (шапка, заголовок дашборда, title страниц, сообщения о
правах: «Раздел «Управление» доступен только роли «Администратор»»);
слова «Админка»/«Панель администратора» в UI не используются
(E2E scripts/verify_admin_ui.py проверяет оба инварианта).

Правила (зафиксированы):
- Server Components по умолчанию; `'use client'` — где действительно нужно.
- Запросы к API — через BFF-роуты `app/api/*`.
- Прямых запросов к Spring Boot нет.
- Типы — ручные (OpenAPI-клиент не генерируется).

Все 8 шагов user-flow закрыты: `/projects/[id]/parameters`,
`/projects/[id]/selection`, `/projects/[id]/scenarios`,
`/projects/[id]/economics`, `/projects/[id]/simulation`,
`/projects/[id]/export` (`docs/requirements/user-flow.md`).

## 5. BFF-паттерн

Слой между браузером и Spring Boot. Next.js управляет сессией пользователя,
Spring Boot валидирует JWT.

Цепочка запроса:
```
Браузер -> страница Next.js -> BFF-роут (app/api/*)
        -> Spring Boot (REST) -> БД -> обратно
```

Механизм сессии (JWT через BFF):
1. Вход/регистрация: браузер -> `POST /api/auth/{login,register}`
   (BFF) -> Spring Boot проверяет креды и выдаёт JWT.
2. BFF кладёт токен в httpOnly-cookie `robomatch_session`
   (`sameSite=lax`, `path=/`, `secure` — по `COOKIE_SECURE`) и наружу
   отдаёт ТОЛЬКО профиль. Браузер и JS токен не видят.
3. Каждый следующий запрос к защищённому BFF-роуту (`/api/auth/me`,
   `/api/projects/**`) читает токен из cookie и идёт
   на backend с заголовком `Authorization: Bearer <token>` — только
   серверная сторона. Серверные вызовы из страниц (lib/api.ts)
   пробрасывают cookie текущего посетителя в подзапрос вручную —
   серверный fetch cookie сам не прикрепляет.
4. Выход: `POST /api/auth/logout` стирает cookie (токен доступа-only,
   серверных сессий нет).
5. Страницы проверяют сессию через `lib/auth.ts`: `getCurrentUser()`
   (cookie -> BFF `/me` -> backend; любая ошибка = гость),
   `requireUser()` — server-side redirect на `/login` (используется
   на всех страницах `/projects/**`).

Заголовки и параметры пробрасываются BFF-роутами точечно;
ошибки backend (401/409/400) пробрасываются наружу с русским
сообщением из `ErrorResponse`; недоступность backend — 502 с понятной
причиной (как у `/api/solutions`).

## 6. API

Backend предоставляет REST API. Документация — springdoc-openapi.

- OpenAPI-спецификация: `/v3/api-docs` (валидный JSON, OpenAPI 3.1).
- Swagger UI: `/swagger-ui.html`.
- Полнота аннотаций: 63 эндпоинта в 12 группах @Tag
  (каталог/справочники/аутентификация/проекты/параметры/подбор/
  сценарии+экономика/имитация/экспорт/админка/демо-расчёт); каждый —
  @Operation (summary + description) и @ApiResponse на все
  фактические коды (200/201/204/400/401/403/404/409/413); bearer-jwt
  — на 55 защищённых эндпоинтах (публичные 8: регистрация/вход,
  каталог, фильтры, демо-расчёт — по SecurityConfig); все 71 DTO-схем
  — @Schema (description+example) на классах и 473 полях; у JSON-тел
  POST/PUT — явные @ExampleObject запросов (2 multipart-загрузки —
  по полям формы). JavaDoc: все публичные классы/методы/record-компоненты;
  `gradlew javadoc` — 0 ошибок.
- Полный список эндпоинтов:
    - Каталог решений — **read расширен**:
      `GET /api/solutions` — список с поиском `q` (подстрока, без
      регистра), фильтрами `typeId`, `subtypeId`, `industryId`,
      `processId` (пара отрасль+процесс матчится по одной строке
      `solution_application`), `status`, `trlMin/trlMax`,
      `priceMin/priceMax`, `payloadMin`, сортировкой `sortBy`
      (name|price|trl|payload_kg|created_at) + `sortDir`, пагинацией
      `page`/`size` (1–100, дефолт 20); ответ — стабильная обёртка
      `PageResponse` (content/page/size/totalElements/totalPages);
      в элементе — имена справочников (vendorName и др.), а не id;
      невалидные параметры — 400 с пояснением. `GET /api/solutions/{id}`
      — полная карточка (ТТХ из EAV с провенансом и признаком
      подтверждённости, кейсы, применения).
      `GET /api/solutions/compare?ids=1,2,3` — 2–10 полных карточек
      для сопоставления. `GET /api/filters` — словари (типы, подтипы,
      отрасли, процессы, статусы, допустимые сочетания тип-подтип,
      типы объектов пользователя objectTypes — для
      формы создания проекта) и диапазоны цены/УГТ; гостевой
      (permitAll). Write-CRUD каталога:
      публичные read-эндпоинты каталога не изменены (см. блок
      «Админка» ниже).
    - Гостевой демо-расчёт — сценарий гостя (объём MVP;
      оба эндпоинта permitAll — гостевой минимум рядом с каталогом):
      `GET /api/demo` — описание демо-расчёта: типы объектов с
      признаком доступности демо-набора, параметры демо-набора
      «Склад» с единицами измерения, демонстрационный состав из
      каталога; `POST /api/demo/calculate` — сравнение трёх сценариев
      (base/purchase/raas) на константах демо-набора: та же таблица
      ComparisonDto, что и в проекте, но расчёт в памяти и НЕ
      сохраняется (тело необязательно — по умолчанию «Склад»;
      400 на тип без демо-набора, 404 на неизвестный код). Константы —
      DemoConstants (базовые значения демо-датасета «Склад»);
      допущения — каталог без переопределений (+ фиксированная
      номинальная производительность робота демо-состава);
      состав — решения каталога (цена/ТТХ читаются, БД только для
      чтения). Сервис — DemoCalculationService (пакет economics):
      формулы — тот же EconomicModel, интерпретация окупаемости и
      чувствительность — те же; `@Transactional(readOnly=true)` —
      страховка от записи. IT DemoControllerIT: 200 без токена,
      ничего не сохраняется (счётчики project/scenario/calculation
      неизменны), дефолт «Склад», 404/400, токен пользователя.
    - Админка (весь `/api/admin/**` — `hasRole('ADMIN')`,
      без токена 401, роль user — 403):
      `GET /api/admin/solutions` (поиск q + фильтр needsCheck —
      карточки, внесённые вручную, source_kind=manual),
      `POST /api/admin/solutions` (201; 400 валидации: класс
      brs/bas/software, статус, цена >= 0, УГТ 1..9, неотрицательные
      ТТХ; 409 дубликат (vendor, name)), `PUT|DELETE
      /api/admin/solutions/{id}` (PUT — провенанс карточки становится
      manual; DELETE 204, 409 пока решение в сценариях/результатах
      подбора — RESTRICT в схеме, data_model §11). Read-дубликат
      карточки `GET /api/admin/solutions/{id}` убран: карточка для
      формы редактирования читается публичным `GET /api/solutions/{id}`
      (одна и та же сборка SolutionFullDto), под `/api/admin`
      остаются только write-операции. Разница списков: публичный
      `GET /api/solutions` — полная панель фильтров каталога
      (тип/подтип/отрасль/процесс/статус/УГТ/цена/груз + сортировки)
      для витрины; `GET /api/admin/solutions` — управление:
      поиск по названию + фильтр needsCheck (source_kind=manual)
      + сортировка по свежести, ответ тот же
      PageResponse<SolutionSummaryDto>;
      `PUT|DELETE /api/admin/solutions/{id}/characteristics/{typeId}`
      (upsert/удаление значения EAV-ТТХ: тип значения обязан
      соответствовать data_type, провенанс manual/is_confirmed=false
      или open_source+url+date/is_confirmed=true;
      9 технических ТТХ синхронизируют зеркальные колонки solution);
      `GET /api/admin/references` (счётчики), `GET|POST
      /api/admin/references/{dictCode}`, `PUT|DELETE
      /api/admin/references/{dictCode}/{id}` — dictCode из
      industry|process|vendor|region|solution_type|solution_subtype|
      characteristic_type; код `^[a-z][a-z0-9_]*$` (assumptions §20),
      у vendor кода нет, у process — is_active, у
      characteristic_type — group_code/data_type при создании;
      DELETE 409 при FK-ссылках (решения, применения, типы объектов,
      пары «тип-подтип», значения ТТХ);
      `POST /api/admin/solutions/bulk-delete` и
      `POST /api/admin/references/{dictCode}/bulk-delete` — массовое
      удаление (тело `{ids}` — 1..100 идентификаторов, дубликаты
      игнорируются): каждая позиция обрабатывается независимо от
      остальных, ответ 200 `{deleted: [...], failed: [{id, reason}]}`
      — отказ одной записи (404 не найдено; 409-причины одиночного
      DELETE: решение в сценариях/результатах подбора, FK-ссылки
      справочника) не откатывает уже удалённые; 400 — пустой список,
      null-элемент, больше 100 id или неизвестный dictCode.
      UI-обёртка — чекбоксы строк и панель «Выбрано: N» в таблицах
      «Управления» (решения и записи справочников), диалог
      подтверждения с числом позиций и сводка с причинами отказов.
      `POST /api/admin/catalog/import` (multipart CSV/XLSX таблицы
      организатора: 200 summary добавлено/обновлено/пропущено по
      сущностям; 400 битые ячейки со списком до 20; 413 > 50 МБ;
      409 in-flight), `GET /api/admin/catalog/import/history`
      (журнал V7 со статусами и счётчиками),
      `POST /api/admin/catalog/refresh` (повторный импорт последнего
      файла; 400 если файла нет).
    - Аутентификация: `POST /api/auth/register`
      (201/409/400), `POST /api/auth/login` (200/401),
      `GET /api/auth/me` (200/401, Bearer).
    - Проекты:
      `GET /api/projects` — список своих проектов (пагинация
      page/size 1-100, сортировка updated_at DESC, обёртка
      PageResponse); `POST /api/projects` — 201/409 (дубликат
      имени в рамках пользователя)/400 (неизвестный objectTypeId,
      невалидное тело); `GET /api/projects/{id}` — карточка
      (200/404); `PUT /api/projects/{id}` — имя/описание
      (200/404/409/400; тип объекта не меняется);
      `POST /api/projects/{id}/copy` — копия с именем
      «(копия)»/«(копия N)», статус draft (201/404/409 — гонка
      подбора имени);
      `DELETE /api/projects/{id}` — 204/404, каскад дочерних
      строк схемой V1. Все — authenticated; изоляция:
      userId только из JWT (CurrentUser.requireUserId()), чужой
      проект — 404, не 403.
    - Параметры объекта: все — authenticated, изоляция как у проектов
      (чужой — 404, userId из JWT):
      `GET /api/projects/{id}/parameters` — список параметров типа
      объекта проекта с метаданными (code/name/unit/groupName/
      valueType/isRequired/defaultValue/min/max/sourceNote)
      и текущим значением currentValue {kind, value} (200/404;
      3 запроса, без N+1); `PUT /api/projects/{id}/parameters/{parameterId}`
      — установка значения `{"value": ...}` (200/400/
      404; валидация типа/диапазона/обязательности по метаданным,
      идемпотентный UPSERT, source=manual; per-field схема); `DELETE .../parameters/{parameterId}` — сброс значения
      (204/404); `POST .../parameters/import` — импорт Excel/CSV по
      шаблону, multipart (200/400/404/413; АТОМАРНО:
      весь файл валидируется до записи, ошибка в одной ячейке — 400
      со списком строк/колонок, ничего не сохраняется; успех — одна
      транзакция: вложение + все значения source=import);
      `GET .../parameters/attachments` — история загрузок;
      `DELETE .../parameters/attachments/{attachmentId}` — удалить
      вложение (файл + строка, 204/404);
      `GET .../parameters/template?format=xlsx|csv` — шаблон импорта
      (колонки-коды; лист «Параметры» с единицами, диапазонами,
      дефолтами и источниками нормативов); фиксированные и производные
      параметры в шаблон и импорт не входят (PUT/DELETE
      значения — 400 «фиксированный»/«рассчитывается автоматически»,
      непустая ячейка такой колонки в файле — 400 per-cell; в списке
      параметров фиксированные отдают константу, производные —
      вычисленное значение с derivedFromName).
    - Подбор (все authenticated, изоляция как у проектов —
      чужой 404, userId из JWT):
      `POST /api/projects/{id}/selection/run` — запуск алгоритма
      selection_algorithm.md §2–§9 (отрасли объекта → пул применимых →
      8 обязательных ТТХ → статусы fit/needs_check/excluded → ранжирование
      fit с весами и вкладом критериев → UPSERT selection_result,
      несостоявшиеся пары удаляются; повтор идемпотентен, гонка — 409);
      первый запуск создаёт 3 сценария base/purchase/raas;
      `GET /api/projects/{id}/selection` — сценарии + результаты с
      объяснениями: status/reason/rank — зафиксированы запуском,
      score/criteriaContribution/missingData — пересчёт по текущим данным
      (data_model.md §12); пусто, если не запускался. Ручное
      добавление решения в сценарий — под `/scenarios`
      (мутация состава — домен сценариев, см. блок «Экономика»).
    - Экономика (все authenticated, изоляция как у проектов — чужой
      404, userId из JWT; формулы — docs/economic_model.md, версия
      «economic-model-1.0»):
      `GET /api/projects/{id}/scenarios` — сценарии с составом
      оборудования (подбор + ручные добавления) и признаками
      `lastCalculatedAt`/`compositionChanged` (состав изменён с
      момента последнего расчёта — сравнение со снимком metrics_json);
      `POST .../scenarios`
      — создать сценарий (тело с type — например второй purchase;
      без type — восстановить недостающие base/purchase/raas;
      201/409/400/404); `PUT .../scenarios/{scenarioId}` — имя и/или
      состав (замещается целиком, ручные строки требуют причину,
      непустой состав в base — 400;
      200/409/400/404); `DELETE .../scenarios/{scenarioId}` — 204
      (каскад схемы V1: состав + расчёты + снимки + корректировки);
      `POST .../scenarios/{scenarioId}/solutions`
      — ручное добавление решения в сценарий: тело
      {solutionId, manualReason (обязательна), quantity?}, 201/404
      (чужой проект или сценарий, несуществующее решение)/409 (дубликат)/
      400 (пустая причина; base — 400 «не содержит решений»). Состав —
      домен сценариев: полный CRUD (PUT/DELETE количества + ручное
      добавление POST) живёт под одним путём `/scenarios/{sid}/solutions`;
      `PUT .../scenarios/{scenarioId}/solutions/{solutionId}` —
      точечное изменение количества решения в составе (тело
      {quantity} 1–10000; 200 — обновлённая строка / 400 / 404);
      `DELETE .../scenarios/{scenarioId}/solutions/{solutionId}` —
      убрать решение из состава (204/404; исторические расчёты
      не трогаются — append-only, §10.8);
      `GET .../assumptions` — каталог допущений с текущими значениями
      (глобальные дефолты assumptions.md §22–23 + переопределения,
      с источником/диапазоном/влиянием);
      `PUT .../assumptions` — карта «код → значение|null» (null —
      сброс в дефолт; 200/400/404, переопределения — V5);
      `POST .../scenarios/{scenarioId}/calculate` — сквозной расчёт
      §2.1–2.13 СИНХРОННО (≤ 10 с), каждый вызов — новая
      строка calculation (append-only) со снимком допущений
      и version_data = SHA-256(входы)[0:12]; 200/400 (нет обязательных
      параметров/нулевые знаменатели — EconomicValidationException со
      списком)/404; `GET .../scenarios/{scenarioId}/calculations` —
      история (свежие сверху, версии данных/модели);
      `GET .../calculations/{calculationId}` — детальный расчёт
      (метрики + снимок + разбивка CAPEX/OPEX + чувствительность +
      корректировки); `POST .../calculations/{calculationId}/adjust`
      — ручная корректировка метрики. Расчёт идентифицируется
      ГЛОБАЛЬНО уникальным calculationId (PK таблицы calculation,
      data_model §10.8): детальный расчёт и корректировка живут под
      `/api/projects/{id}/calculations/...` без повторения
      scenarioId — симметрия обеспечивается уникальностью ключа,
      изоляция (проект+владелец) проверяется по самой строке (что/было/стало/почему/
      кто/когда; порождает НОВЫЙ расчёт, запись — в
      manual_adjustment; 200/400/404); `GET .../compare` — таблица
      сравнения base/purchase/raas по последним расчётам (N_robots,
      CAPEX, OPEX, ΔOPEX, ΔFOT, эффект, окупаемость, ROI,
      TCO + Δ к базовому + интерпретация окупаемости +
      чувствительность).
    - Имитация (все authenticated, изоляция как у проектов — чужой
      404, userId из JWT; KPI-модель — SimulationModel, версия
      «simulation-model-1.0», коэффициенты — assumptions.md §22-бис):
      `POST /api/projects/{id}/scenarios/{scenarioId}/simulation/run`
      — запуск KPI-модели по составу сценария и параметрам склада:
      6 KPI + зоны с загрузкой + сверка с последним расчётом экономики
      по снимку его допущений (effectiveAssumptions из metrics_json;
      предупреждения при расхождении;
      технические пороги модели: расхождение с P_effective — свыше
      1 %, предупреждение о недостижимости — при достижимости
      ниже 100 %; зарядные станции — Ratio_infra из снимка последнего
      расчёта, скорости парка — средневзвешенно по ТТХ с фолбэком
      sim_avg_robot_speed_m_s на строках без ТТХ);
      синхронно (фактически ~200 мс), статусы running → completed/
      failed видны в истории; повторный запуск идемпотентен (новая
      строка истории, KPI детерминированы); 400 — не склад / base /
      пустой состав; 409 — имитация уже выполняется (живой running,
      порог 15 минут — зависший помечается failed);
      `GET .../scenarios/{scenarioId}/simulation` — последний результат
      сценария (200/404);
      `GET .../simulations` — история имитаций проекта;
      `POST .../simulations/{simulationId}/export` — сохранение
      2D-схемы: SVG от клиента санитизируется (без скриптов
      и on*-обработчиков; вырезаются также внешние
      http/https/file/ftp-URI и инлайн style — фрагменты «#id»
      и градиенты сохраняются) и складывается в
      data/simulations/{projectId}/{simulationId}.svg, ссылка — в
      kpi_json.exportUrl этого результата;
      `GET .../simulations/{simulationId}/export` — скачивание файла
      (200/404). Путь сохранения и скачивания один и тот же
      (симметрия POST/GET по simulationId — идентификатор результата
      глобально уникален, сцена́рий в пути не нужна: клиент знает
      simulationId из ответа запуска). Удаление проекта чистит каталог
      схем.
    - Экспорт (все authenticated — выгрузка зарегистрированным
      пользователем; изоляция как у проектов —
      чужой 404, userId из JWT; гейт склада is_calc_enabled; отчёт
      читает ГОТОВЫЕ метрики последних расчётов и не пересчитывает):
      `POST /api/projects/{id}/exports` — генерация отчёта (тело
      {format: pdf|xlsx|csv, theme: light|dark, schemaTheme:
      ui|light|dark}: PDF — 10 разделов с пометкой «предварительная
      оценка» на титуле и в конце, Excel — 7 листов, CSV — плоская
      таблица UTF-8 BOM/«;»; theme — тема страниц PDF; schemaTheme —
      тема 2D-схемы в отчёте: ui — как сохранена в интерфейсе (по
      умолчанию), light/dark — сервер сверяет признак data-theme
      сохранённой схемы с выбором и при отличии перерисовывает её
      серверным рендером (PDF всегда содержит схему выбранной темы;
      выбор — на странице экспорта, «Как в UI» подставляет текущую
      тему интерфейса); 201/400 (не склад, битый формат)/404/409 —
      параллельная генерация того же формата);
      `GET /api/projects/{id}/exports` — история выгрузок (формат,
      имя файла, размер, автор, дата);
      `GET /api/projects/{id}/exports/{exportId}` — скачать файл
      (Content-Type формата, Content-Disposition: attachment с
      filename* для кириллицы имени проекта);
      `DELETE /api/projects/{id}/exports/{exportId}` — удалить
      (файл + строка, 204/404). Файлы — data/exports/{projectId}/
      {exportId}.{ext} (ExportStorage), удаление проекта чистит
      каталог. Пример итогового отчёта — docs/examples/.
- Схема безопасности в OpenAPI — объявлена (`bearer-jwt`, OpenApiConfig)
      и работает: в Swagger UI есть кнопка Authorize.

## 7. База данных и миграции

СУБД — PostgreSQL. Runtime-имя БД — `robomatch`.

Схема описана в четырёх консистентных источниках:
- Flyway-миграции `backend/src/main/resources/db/migration/`.
- `scripts/seed/schema.psql` — ручной путь.
- `docs/data_model.md` — документация.
- `scripts/seed/tests/db_schema.py` — тестовое зеркало.

**Полная модель — в `docs/data_model.md`.** Здесь не дублируется.

Миграции:
- Flyway, применяются автоматически при старте backend.
- Применённые миграции не редактируются — только новые.
- Демо-данные — `V2__demo_data.sql` (минимальный набор для старта).
- `V3__catalog_search_index.sql` — функциональный GIN-индекс
  `lower(name)` для поиска каталога (запрос фильтрует
  LOWER(name), индекс V1 по «голому» name не подходил).
- `V4__parameter_fixed_derived.sql` — колонки is_fixed/is_derived
  object_type_parameter (worst-case константы и производные параметры, выставляются seed-ом
  через parameter_overrides.json).
- `V5__project_assumption.sql` — таблица project_assumption (переопределения
  допущений проекта).
- `V6__roi_precision.sql` — точность roi_pct numeric(12,2).
- `V7__admin_import_log.sql` — таблица admin_import_log (журнал загрузок
  каталога).
- Полный каталог (187 решений) — через seed-скрипт: вручную
  (`python scripts/seed/run.py`) или автоматически — одноразовым сервисом
  `seed` в docker-compose (идемпотентный upsert поверх V2).

Решение «синхронно vs очередь» для имитации: расчёт KPI выполняется СИНХРОННО
в потоке запроса — аналитическая модель в памяти поверх 5–6 SQL
(параметры + состав + ТТХ + допущения + последний расчёт экономики
для сверки), фактическая длительность ~200 мс при 9 роботах — запас
до порога 60 секунд в ~300 раз. Статус running фиксируется в БД
отдельной транзакцией до расчёта (статус выполнения виден пользователю),
завершение — completed/failed; превышение 60 с переводит строку в
failed с предупреждением. Очередь (аналог status='running' + polling
клиентом) НЕ подключалась: не нужна при синхронном укладывании;
при появлении тяжёлых моделей (дискретно-событийная имитация) —
перенос по этому же сценарию без изменения схемы БД (таблица
simulation_result уже поддерживает long-running статусы; живой
running младше 15 минут считается активным и даёт 409 на повторный
запуск — защита от гонки, зависший помечается failed).

## 8. Инфраструктура и развёртывание

### docker-compose.yml

Состав в объёме MVP: одна команда
`docker compose up` поднимает все сервисы.

Сервисы (порядок старта задаётся depends_on + healthcheck; версии образов
запинены — воспроизводимость):
- **db** (postgres:16.15, порт 5432) — healthcheck `pg_isready`, том
  `pgdata` (данные переживают перезапуск).
- **adminer** (adminer:6.0.1, порт 8081) — вспомогательная админка БД.
- **backend** (build `./backend`, порт 8080) — зависит от готовности db;
  healthcheck `curl /api/solutions` (Spring отвечает 200 только после
  полного старта, включая Flyway, — значит схема применена).
- **frontend** (build `./frontend`, порт 3000) — standalone-сборка Next.js
  (`output: "standalone"`, multi-stage Dockerfile на node:24-alpine);
  BFF-роут получает `BACKEND_URL=http://backend:8080`; healthcheck —
  встроенный fetch Node 24 (curl/wget в alpine-образе нет); стартует
  после готовности backend и успешного завершения seed — /catalog
  открывается уже с полным каталогом.
- **seed** (build `./scripts/seed`, одноразовый, python:3.12-slim) —
  идемпотентная загрузка полного каталога из `docs/source` (read-only том)
  и ТТХ из `data/characteristics.json` (внутри образа) после готовности
  backend; повторный `up` обновляет те же строки (upsert), дублей нет.

Принцип: из чистого клона репозитория `docker compose up` даёт
работающую платформу на заранее загруженных данных:
Flyway применяет схему и демо-строки, seed доводит каталог до 187 решений.

Проверка: сквозной прогон Flyway → seed идемпотентно (187 решений /
79 ТТХ / 250 заявок) → BFF → каталог; структурная валидация compose
(47 проверок); финальный `docker compose up` — по чек-листу в README.

### Переменные окружения
- Backend: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`
  (обязателен, >= 32 байт, дефолта нет — fail-fast при старте;
  compose отклоняет запуск без него), `JWT_TTL_HOURS` (24).
- Frontend: `BACKEND_URL` (задаёт docker-compose), `APP_URL`
  (в контейнере не нужен — адрес определяется из заголовков запроса),
  `COOKIE_SECURE` (по умолчанию false — для https-окружения),
  `SESSION_TTL_SECONDS` (86400 = 24 ч, калиброван под TTL JWT).
- Seed: `DATABASE_URL`, `CATALOG_CSV`, `OBJECTS_XLSX`.
- Корневой `.env`: docker compose читает его автоматически и
  интерполирует `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` /
  `JWT_SECRET` / `COOKIE_SECURE` во все сервисы; `.env` в .gitignore,
  пример значений — в `.env.example`.
  Dev-дефолты для локального демо остаются в compose — см. §11.

### Порты и URL
См. §2.

## 9. Безопасность

Реализованные меры безопасности:

- **Разграничение доступа по ролям** — Spring Security, правила путей
  в `SecurityConfig`: permitAll — только
  гостевой минимум (регистрация, вход, каталог `/api/solutions/**`,
  демо-расчёт `/api/demo/**` — чтение и расчёт без сохранения,
  Swagger/OpenAPI, `/error`); `/api/admin/**` — `hasRole('ADMIN')`;
  прочие `/api/**` — `authenticated`; всё остальное — `denyAll()`.
  Роли: guest (нет аутентификации), user, admin; authority —
  `ROLE_USER`/`ROLE_ADMIN`; регистрация всегда создаёт роль user,
  admin выдаёт только seed.
- **Пароли только в хеше** — BCrypt cost 12
  (`BCryptPasswordEncoder`, spring-security-crypto; аргон2 тянет
  BouncyCastle — не оправдан для MVP). Единый cost с seed (python-bcrypt,
  `$2b$` — читается парсером Spring). Хеш не логируется, entity не
  покидает сервис (наружу `UserDto` без хеша).
- **Аутентификация — JWT**: jjwt 0.13.0, HMAC-SHA256, TTL 24 ч,
  доступ-only (refresh — вне MVP). Секрет `JWT_SECRET` — только env,
  >= 32 байт, без дефолта: короткий/пустой — отказ старта с понятным
  сообщением; docker-compose требует переменную (`:?`). Фильтр
  `JwtAuthFilter` валидирует подпись/срок, грузит свежий профиль из БД
  (смена роли/удаление аккаунта применяются сразу). Гость — просто
  отсутствие токена.
- **Токен не попадает в браузер** — BFF-паттерн (см. §5): JWT живёт в
  httpOnly-cookie `robomatch_session`, наружу — только профиль;
  `Authorization: Bearer` ставит серверная сторона Next.js.
- **Изоляция проектов** — userId извлекается только из JWT
  (`CurrentUser.requireUserId()` — fail-fast 401 без токена, риск
  молчаливого null закрыт), все запросы к проектам
  фильтруются по user_id (изоляция заложена в контракт репозитория
  `findAllByUserId`), чужой проект неотличим от несуществующего —
  404, не 403 (не раскрываем существование перебором id).
- **Админка — доступ только роли admin**: `/api/admin/**` — `hasRole('ADMIN')`
  в SecurityConfig (401 без токена, 403 ролью user — IT); страницы
  `/admin/**` дополнительно закрыты серверной проверкой `requireAdmin()`
  (redirect на /projects без утечки данных); BFF-роуты админки
  пробрасывают cookie и ретранслируют 401/403 как есть. Загрузка
  файла каталога: копия в `data/admin-imports/{importId}.{ext}` —
  имя администратора не участвует в пути (path traversal исключён
  конструкцией), лимит 50 МБ (servlet 413 + сервисный 413); in-flight
  повтор — 409; зависший после рестарта 'running' старше 15 минут
  помечается failed (не блокирует навсегда).
- **Защищённый протокол при размещении в сети** — вне локального
  демо: reverse-proxy с TLS + `COOKIE_SECURE=true` (см. §8);
  cookie-флаг отделен от NODE_ENV намеренно.
- **Отсутствие реальных ПДн** — аккаунт = логин + хеш, без email;
  демо-аккаунты — синтетические.
- **Удаление проекта с файлами** — каскад строк — схемой V1
  (`ON DELETE CASCADE`), физическое удаление файлов вложений:
  `DELETE /api/projects/{id}`
  удаляет строку project в транзакции, затем хранилище
  (`AttachmentStorage.deleteProjectFiles`) сносит каталог
  `data/attachments/{projectId}` — best-effort (ошибка файловой системы
  логируется, но не откатывает удаление; строки уже нет, осиротевший
  файл не влияет на работу). Удаление вложения (`DELETE .../attachments/
  {attachmentId}`) удаляет и строку, и файл. Загрузка файлов — белый
  список MIME (xlsx/xls/csv) + лимит `UPLOAD_MAX_BYTES` (дефолт 10 МБ,
  два уровня: servlet 413 и сервисный 413); имя файла пользователя
  санитизируется (path traversal исключён конструкцией пути
  `{projectId}/{attachmentId}_{safeName}`); в БД — относительный путь,
  абсолютный корень — только env `UPLOAD_ROOT`.
- Логирование без чувствительных данных: в логах нет паролей/хешей/
  токенов; ошибки API — единый `ErrorResponse` без стектрейсов наружу.

## 10. Расширяемость

Расширяемость — добавление новых отраслей, объектов, параметров, решений и
расчётных модулей без переработки ядра.

Механизмы:
- **Справочники** — расширяемые таблицы.
- **EAV** — `solution_characteristic`, `object_type_parameter` —
  новые характеристики и параметры без изменения схемы.
- **Модули backend** — отдельные пакеты на каждую предметную область.
- **Слои backend** — единая структура controller → service → repository
  → domain; новая сущность — по образцу существующих.
- **Миграции** — только добавление новых.
- **Seed** — обновление данных без изменения схемы.

Пример — подбор: новый расчётный модуль —
отдельный пакет `selection` с сервисом и тонким контроллером; справочные
данные (веса, маппинг проверок на параметры) — в коде модуля, расширение
справочников (параметры, отрасли) — через БД и overrides seed-а, без правки
ядра. Повторено для экономики: пакет `economics` — чистый движок
формул (EconomicModel без Spring/БД — тестируется unit-ом напрямую) +
сервисы сборки входов/снимков; новый каталог коэффициентов — только через
`docs/assumptions.md` §22–23 (недокументированные коэффициенты запрещены),
новая таблица — миграцией V5 без правки V1.

## 11. Известные ограничения текущей версии

Осознанные границы MVP (не дефекты). Полный журнал вопросов
и их закрытия —
[docs/backlog/closed_questions.md](backlog/closed_questions.md) (группы 1–4).

- Осознанные границы: токен доступа-only
  без refresh (после 24 ч — повторный вход); роль admin не назначается
  через API (только seed — повышение прав исключено); восстановление
  пароля и подтверждение email — вне MVP; контрольный
  `docker compose up` с JWT_SECRET на реальном Docker-демоне — непроверенный
  пункт (верифицировано на живых backend/frontend вне Docker, чек-лист
  в README).
- Границы админки (осознанные):
  - Роль вендора — вне MVP (docs/requirements/roles.md):
    управление каталогом централизовано у админа платформы.
  - Плановое АВТОбновление каталога (преимущество, не обязанность) —
    не реализовано; вместо него «обновить по запросу»
    (`POST /api/admin/catalog/refresh` — повторный импорт последнего
    файла). Автоматический парсинг/ML-валидация — вне MVP
    (автоматика — преимущество, но НЕ замена ручному управлению).
  - Управление «нормативами» экономики не входит: расчётные
    коэффициенты — код-константы (economic_model.md), снимки в
    `calculation_assumption` append-only (история расчётов
    неизменяема); правка дефолтных значений параметров
    `object_type_parameter.default_value_*` отложена
    (расширяемость — миграциями/seed).
  - Источники данных — сущность «источник» не заводилась
    (провенанс живёт в полях source_kind/source_url/source_date самих
    записей — solution, solution_characteristic, solution_case).
  - Валидация по госреестрам — вне MVP.
  - Импорт: повторная загрузка того же файла — 0 изменений
    (идемпотентность), НО повторный импорт НЕ обнуляет 9 зеркальных
    ТТХ-колонок solution и completeness_pct — файл организатора этих
    колонок не содержит, а значения могут быть дозаполнены из
    открытых источников); отличается от seed, где за
    импортом всегда шёл шаг обогащения (зафиксировано как правило).
  - In-flight защита импорта однопроцессная (один 'running' в
    admin_import_log + 409): конкурентный запуск в нескольких
    инстансах backend не блокируется (single-node MVP, docker-compose
    один контейнер backend).
  - Интеграционные тесты backend не читают файлы вне модуля:
    стадия сборки образа (backend/Dockerfile, gradle
    clean build) гоняет тесты в контексте, где есть только backend/ —
    docs/source недоступен. Файл-эталон полного каталога закреплён
    снапшотом backend/src/test/resources/catalog_export_v4.csv
    (побайтово сверяется с docs/source при полном чекауте;
    обновление каталога организатора = обновить снапшот и
    эталонные счётчики data_model §7).
- Модуль `economics` — известные границы: расчёт синхронный
  (вычисления в памяти поверх 5–6 SQL, цель ≤ 10 с; при
  росте нагрузки — перенос в очередь по образцу simulation_result);
  гейт расчёта — только склад (object_type.is_calc_enabled);
  базовый сценарий — только ФОТ целевых групп (без «до/после» по
  операциям); чувствительность — 3 параметра
  (оборудование/операции/труд) с шагами ±20/±10/0: объём операций —
  выбранный парк масштабируется пропорционально Δ%
  (scaledTotal = ceil(selected×(1+Δ))).
- Особенности расчёта и UI экономики: чувствительность по объёму —
  от выбранного парка; пустой состав — ΔFOT = 0; флаг overpowered +
  нейтральная плашка «возможна переплата»; единая формулировка
  недобора для Payback/ROI; интерпретация «Состав пуст»; окупаемость
  < 0,5 года — в месяцах; подпись под ROI > 500% про большой baseline
  ФОТ; кнопка «Управление» на /projects ведёт в карточку проекта;
  единая кнопка «Рассчитать и перейти к дашборду →» на /scenarios
  считает все три сценария подряд.
- Известные границы (аутентификация/экономика): rate limit на
  login отсутствует (bcrypt cost 12 — смягчение), logout без
  серверной инвалидации JWT (профиль перечитывается из БД на каждый
  запрос), дробный payback_horizon округляется HALF_UP
  (numeric(16,4) не должен падать); k_availability=0 — линейный
  fallback с примечанием в чувствительности; предупреждения парка в
  базовом сценарии — инвариант base (data_model.md §10.5).
- Модуль `selection` — известные границы: Score/вклад — пересчёт на лету
  (статусы зафиксированы запуском — после изменения параметров нужен
  перезапуск); у аэропорта/медицины
  нет параметров «нагрузка на пол»/«проезды»/«потолки» — их проверки дают
  needs_check (selection_algorithm.md §4.1, маппинг); полный набор 8
  проверок — склад.
- Модуль `simulation` — известные границы: модель детерминированная
  аналитическая («снимок» KPI поверх состава и параметров — без
  дискретно-событийного движения роботов во времени; расчёт ≤ 60 с,
  фактически миллисекунды); узкие места — по 4 зонам склада
  (приёмка/хранение/отбор/отгрузка), без вложенной топологии
  стеллажей; аэропорт и медицина — вне гейта is_calc_enabled;
  анимация — визуализация долей из kpi_json (декоративная поверх
  расчёта, параметры сценария используются).
- Модуль `export` — известные границы: экспорт только для склада
  (is_calc_enabled) и только зарегистрированным; отчёт
  читает ПОСЛЕДНИЕ расчёты сценариев — сценарий без расчёта помечен
  «не рассчитан» (экономику отчёт не пересчитывает); 2D-схема в PDF —
  только последняя сохранённая (без сохранённой схемы раздел остаётся
  с KPI и заметкой); электронная подпись и логотип не реализованы.
- Валидация входных данных: auth-DTO (@Valid),
  каталог (query-параметры), project-DTO (@Valid),
  значения параметров объекта (тип/диапазон/
  обязательность по метаданным + атомарная валидация файла импорта),
  входы расчёта экономики (26 обязательных
  параметров, диапазоны допущений, нулевые знаменатели —
  EconomicValidationException), входы имитации
  (гейт склада, инвариант base, непустой состав, положительные
  скорость/маршрут/погрузка — понятные 400)
  и запросы экспорта (формат pdf/xlsx/csv по белому списку DTO,
  гейт склада, наличие расчётов, лимит 50 МБ хранилища).
- CORS, профили — не настроены.
- Прод-контур (вне демо): Swagger/OpenAPI открыт без аутентификации —
  для демонстрации API, в общем окружении закрыть профилем;
  security-заголовки (CSP, X-Frame-Options и т.п.) в next.config.ts
  не настроены; rate-limit на /api/auth/* отсутствует.
- Frontend-тесты — e2e-скрипты на Playwright (паттерн verify_*):
  verify_ui_pre_slice.py (18 проверок), verify_simulation_ui.py
  (имитация, 17 проверок, живой стек), verify_export_ui.py (экспорт,
  20 проверок — генерация/скачивание трёх форматов, история, удаление,
  изоляция, пометка «предварительная оценка», живой стек); unit-тесты
  компонентов — отсутствуют (e2e-скрипты проверяют DOM живого стека).
- Эталон формул экономики — docs/economics_golden.md, автосверка
  scripts/verify_economics.py (259 проверок).
- Креды БД: механизм переопределения через корневой `.env`,
  `.env` не коммитится; dev-дефолты `postgres/postgres` остаются
  в docker-compose.yml для локального демо — для сетевого окружения
  задавать реальные значения в `.env`.
- Комментарии `V1__baseline_schema.sql:10` и `schema.psql:3` упоминают
  имя БД `robot_platform` при runtime-имени `robomatch`.
- `docs/assumptions.md` (упоминание `data/organizer`)
  описывает зоны `./data`, которых в репозитории нет — источники лежат
  в `docs/source`; перенос/правка конвенции — отложенное решение.
- Запуск на реальном Docker-демоне в среде разработки не выполнялся
  (демон недоступен) — компоненты и цепочка верифицированы локально:
  полная симуляция seed-контейнера (свежие зависимости по
  requirements.txt, раскладка образа, env compose, схема Flyway) даёт
  exit 0 и идемпотентный повтор, 75/75 тестов. Правило
  `docker compose up -d --build` (compose не пересобирает образы сам) —
  в README и docker-compose.yml. Финальная проверка на Docker-демоне —
  по чек-листу README.

## 12. Связь с другими документами

| Документ                                                           | Что покрывает |
|--------------------------------------------------------------------|---------------|
| `docs/requirements/nonfunctional.md`                               | Стек, Docker, OpenAPI, производительность, безопасность, UI |
| `docs/requirements/roles.md`, `docs/requirements/user-flow.md`     | Роли и путь пользователя |
| `docs/requirements/catalog.md`, `docs/requirements/objects.md`     | Требования к каталогу и параметрам |
| `docs/requirements/economics.md`, `docs/requirements/selection.md` | Экономика и подбор |
| `docs/requirements/simulation.md`, `docs/requirements/export.md`   | Визуализация и экспорт |
| `docs/data_model.md`                                               | Схема БД, 31 таблица, миграции, seed |
| `docs/economic_model.md`                                           | Формулы расчёта |
| `docs/selection_algorithm.md`                                      | Логика подбора |
| `docs/assumptions.md`                                              | Допущения |
| `docs/glossary.md`                                                 | Термины (BFF, DTO, EAV, TRL, Seed) |
| `docs/mvp_scope.md`                                                | Границы MVP |
