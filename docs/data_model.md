# Модель данных: каталог решений, параметры объектов, домены пользователя

Модель БД PostgreSQL из двух частей. Часть 1 - домены «каталог решений» и
«параметры объектов» (разделы 1–7). Часть 2 - домены «пользователи», «проекты»,
«сценарии», «подбор», «расчёты», «имитация», «экспорт» (разделы 8–13).
Используется seed-скриптом, `selection_algorithm.md`, `economic_model.md`.

Источники: `requirements/roles.md`, `requirements/user-flow.md`,
`requirements/objects.md`, `requirements/catalog.md`, `requirements/economics.md`,
`mvp_scope.md`, `assumptions.md`, `catalog_export_v4.csv` (187 решений),
`Датасеты_хакатон.xlsx` (138 параметров).

Стек: Spring Data JPA + PostgreSQL. Имена - snake_case. Коды перечислений и
справочников - английские snake_case (конвенция assumptions.md §15/§20;
словарь кодов - `scripts/seed/mappers/code_map.py`).
DDL - `schema.psql`; миграции - `backend/src/main/resources/db/migration/`.

Вне рамок: роль вендора, интеграции WMS/ERP/1С, 3D-визуализация,
ML - mvp_scope.md «Вне MVP».

## 1. ERD

```
vendor 1-N solution N-1 solution_type (opt)
region 1-N solution N-1 solution_subtype (opt)
solution 1-N solution_application N-1 industry
solution_application N-1 process
solution 1-N solution_characteristic N-1 characteristic_type
solution M-N solution_case [solution_case_link]
solution_type M-N solution_subtype [solution_type_subtype_mapping]
object_type 1-N object_type_parameter N-1 parameter_type
object_type M-N industry [object_type_industry]

ERD второй части - раздел 9:
user 1-N project N-1 object_type
scenario M-N solution [scenario_solution]
```

## 2. Таблицы каталога

### 2.1. solution - продукт каталога

Назначение: каталожная карточка решения.

| Поле                    | Тип           | Ограничения                                                                            |
|-------------------------|---------------|----------------------------------------------------------------------------------------|
| id                      | bigserial     | PK                                                                                     |
| external_id             | uuid          | NULL, UNIQUE - id из CSV                                                               |
| name                    | text          | NOT NULL                                                                               |
| vendor_id               | bigint        | NOT NULL, FK → vendor.id                                                               |
| product_class           | text          | NOT NULL, CHECK ∈ (brs, bas, software)                                                 |
| solution_type_id        | bigint        | NULL, FK → solution_type.id                                                            |
| solution_subtype_id     | bigint        | NULL, FK → solution_subtype.id                                                         |
| region_id               | bigint        | NULL, FK → region.id                                                                   |
| status                  | text          | NOT NULL, CHECK ∈ (operation, piloting, rnd)                                           |
| description             | text          | NULL                                                                                   |
| price_rub               | numeric(15,2) | NOT NULL                                                                               |
| trl                     | smallint      | NULL, CHECK 1..9                                                                       |
| market_potential        | numeric(3,1)  | NULL, CHECK 2..5                                                                       |
| payload_kg              | numeric(12,3) | NULL                                                                                   |
| mass_kg                 | numeric(12,3) | NULL                                                                                   |
| length_mm               | numeric(12,3) | NULL                                                                                   |
| width_mm                | numeric(12,3) | NULL                                                                                   |
| height_mm               | numeric(12,3) | NULL                                                                                   |
| positioning_accuracy_mm | numeric(12,3) | NULL                                                                                   |
| speed_m_s               | numeric(12,3) | NULL                                                                                   |
| charging_power_kw       | numeric(12,3) | NULL                                                                                   |
| noise_level_dba         | numeric(12,3) | NULL                                                                                   |
| completeness_pct        | smallint      | NULL, CHECK 0..100                                                                     |
| source_kind             | text          | NOT NULL DEFAULT 'organizer_catalog', CHECK ∈ (organizer_catalog, open_source, manual) |
| source_url              | text          | NULL                                                                                   |
| source_date             | date          | NULL                                                                                   |
| created_at              | timestamptz   | NOT NULL DEFAULT now()                                                                 |
| updated_at              | timestamptz   | NOT NULL DEFAULT now()                                                                 |

Связи: N:1 vendor / solution_type / solution_subtype / region; 1:N solution_application, solution_characteristic; M:N solution_case.

UNIQUE (vendor_id, name) - защита от дублей при ручном добавлении.

Индексы: UNIQUE external_id; UNIQUE (vendor_id, name); btree на FK;
btree price_rub, payload_kg, mass_kg, width_mm, trl; btree status
(idx_solution_status); GIN по name (pg_trgm: `CREATE EXTENSION pg_trgm` +
idx_solution_name_trgm - в schema.psql и migrations.sql); функциональный GIN
по выражению lower(name) (idx_solution_name_lower_trgm) - обслуживает поиск
LOWER(name) LIKE '%…%', под который idx_solution_name_trgm не подходит.

Правило: 9 колонок ТТХ - материализованная проекция EAV. Источник истины и провенанс - `solution_characteristic`. Запись идёт в оба места.

### 2.2. solution_application - применение решения

Назначение: связь «решение × отрасль × процесс». Устраняет дубли строк CSV. Хранит альтернативные цены.

| Поле            | Тип           | Ограничения                                  |
|-----------------|---------------|----------------------------------------------|
| id              | bigserial     | PK                                           |
| solution_id     | bigint        | NOT NULL, FK → solution.id ON DELETE CASCADE |
| industry_id     | bigint        | NOT NULL, FK → industry.id                   |
| process_id      | bigint        | NOT NULL, FK → process.id                    |
| offer_price_rub | numeric(15,2) | NULL                                         |
| created_at      | timestamptz   | NOT NULL DEFAULT now()                       |

UNIQUE (solution_id, industry_id, process_id).
Индексы: UNIQUE; (industry_id, process_id); (solution_id).

### 2.3. characteristic_type - метаданные характеристик (EAV)

Назначение: определяет характеристики решений.

| Поле          | Тип       | Ограничения                                                                                          |
|---------------|-----------|------------------------------------------------------------------------------------------------------|
| id            | bigserial | PK                                                                                                   |
| code          | text      | NOT NULL, UNIQUE                                                                                     |
| name          | text      | NOT NULL                                                                                             |
| group_code    | text      | NOT NULL, CHECK ∈ (identification, technical, infrastructure, economic, applicability, data_quality) |
| data_type     | text      | NOT NULL, CHECK ∈ (number, text, boolean, date)                                                      |
| unit          | text      | NULL                                                                                                 |
| is_filterable | boolean   | NOT NULL DEFAULT false                                                                               |
| is_required   | boolean   | NOT NULL DEFAULT false                                                                               |
| sort_order    | integer   | NOT NULL DEFAULT 0                                                                                   |

Индексы: UNIQUE code; (group_code, sort_order).

Seed: 27 записей - состав и обоснование в `assumptions.md` §14, источник -
`requirements/catalog.md`. Состав по группам:

| Группа         | Записей | Состав (code)                                                                                                                                                                                                                                  |
|----------------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| identification | 2       | country_of_origin, navigation_type                                                                                                                                                                                                             |
| technical      | 13      | зеркальные ТТХ-колонки solution: payload_kg, mass_kg, length_mm, width_mm, height_mm, positioning_accuracy_mm, speed_m_s, charging_power_kw, noise_level_dba; EAV без колонок: autonomy_h, productivity, operating_conditions, lifecycle_years |
| infrastructure | 4       | floor_coverage_req, communication_req, integration_req, service_req                                                                                                                                                                            |
| economic       | 4       | software_cost, implementation_cost, maintenance_cost, acquisition_model                                                                                                                                                                        |
| applicability  | 1       | limitations                                                                                                                                                                                                                                    |
| data_quality   | 3       | source, source_date, confirmation_status                                                                                                                                                                                                       |

Правила:
- 9 зеркальных кодов 1:1 соответствуют ТТХ-колонкам `solution` (§2.1, §5.8); у них `is_filterable = true`; `is_required = true` - у 8 ТТХ (габариты, масса, точность позиционирования, требования к инфраструктуре - полный список и обоснование в assumptions.md §21): payload_kg, mass_kg, length_mm, width_mm, height_mm, positioning_accuracy_mm, charging_power_kw, noise_level_dba.
- Единицы - `assumptions.md` §3 (едины с параметрами объектов: кг, мм, м/с, кВт, дБА, ч, лет, руб.).
- `source_date` - настоящий DATE: `data_type = 'date'`, значение хранится в
  `solution_characteristic.value_date` (assumptions.md §14).
- `sort_order` - порядковый номер внутри группы, непрерывный с 1.
- Идемпотентность: UPSERT по `code`.

### 2.4. solution_characteristic - значения характеристик (EAV)

Назначение: значение + провенанс каждой характеристики.

| Поле                   | Тип           | Ограничения                                                                 |
|------------------------|---------------|-----------------------------------------------------------------------------|
| id                     | bigserial     | PK                                                                          |
| solution_id            | bigint        | NOT NULL, FK → solution.id ON DELETE CASCADE                                |
| characteristic_type_id | bigint        | NOT NULL, FK → characteristic_type.id                                       |
| value_numeric          | numeric(14,4) | NULL                                                                        |
| value_text             | text          | NULL                                                                        |
| value_bool             | boolean       | NULL                                                                        |
| value_date             | date          | NULL - значения характеристик с data_type='date' (source_date)              |
| source_kind            | text          | NOT NULL DEFAULT 'manual', CHECK ∈ (organizer_catalog, open_source, manual) |
| source_url             | text          | NULL                                                                        |
| source_date            | date          | NULL                                                                        |
| is_confirmed           | boolean       | NOT NULL DEFAULT false                                                      |
| created_at             | timestamptz   | NOT NULL DEFAULT now()                                                      |
| updated_at             | timestamptz   | NOT NULL DEFAULT now()                                                      |

CHECK: ровно одно из value_numeric / value_text / value_bool / value_date NOT NULL.
UNIQUE (solution_id, characteristic_type_id).
Индексы: UNIQUE; partial btree (characteristic_type_id, value_numeric); (solution_id).

### 2.5. solution_case - кейсы внедрения

Назначение: справочник кейсов. Многие-ко-многим с решениями.

| Поле        | Тип         | Ограничения            |
|-------------|-------------|------------------------|
| id          | bigserial   | PK                     |
| name        | text        | NOT NULL, UNIQUE       |
| description | text        | NULL                   |
| source_url  | text        | NULL                   |
| source_date | date        | NULL                   |
| created_at  | timestamptz | NOT NULL DEFAULT now() |
| updated_at  | timestamptz | NOT NULL DEFAULT now() |

Индексы: UNIQUE name.

### 2.6. solution_case_link - связь решение ↔ кейс

| Поле        | Тип    | Ограничения                                       |
|-------------|--------|---------------------------------------------------|
| solution_id | bigint | NOT NULL, FK → solution.id ON DELETE CASCADE      |
| case_id     | bigint | NOT NULL, FK → solution_case.id ON DELETE CASCADE |

PK (solution_id, case_id).
Индексы: PK; (case_id).

### 2.7. solution_type_subtype_mapping - связь тип ↔ подтип

| Поле                | Тип    | Ограничения                                          |
|---------------------|--------|------------------------------------------------------|
| solution_type_id    | bigint | NOT NULL, FK → solution_type.id ON DELETE CASCADE    |
| solution_subtype_id | bigint | NOT NULL, FK → solution_subtype.id ON DELETE CASCADE |
| source_note         | text   | NULL                                                 |

PK (solution_type_id, solution_subtype_id).
Индексы: PK; (solution_subtype_id).

Seed: 62 пары из `assumptions.md` §4.

## 3. Таблицы объектов

### 3.1. object_type_industry - маппинг «тип объекта → отрасли»

| Поле           | Тип    | Ограничения                                     |
|----------------|--------|-------------------------------------------------|
| object_type_id | bigint | NOT NULL, FK → object_type.id ON DELETE CASCADE |
| industry_id    | bigint | NOT NULL, FK → industry.id                      |
| source_note    | text   | NOT NULL                                        |

PK (object_type_id, industry_id).
Индексы: PK; (industry_id).

Seed: 7 связей из `assumptions.md` §1.

### 3.2. object_type_parameter - определение параметра типа объекта

| Поле                  | Тип           | Ограничения                                     |
|-----------------------|---------------|-------------------------------------------------|
| id                    | bigserial     | PK                                              |
| object_type_id        | bigint        | NOT NULL, FK → object_type.id ON DELETE CASCADE |
| parameter_type_id     | bigint        | NOT NULL, FK → parameter_type.id                |
| group_name            | text          | NOT NULL                                        |
| is_required           | boolean       | NOT NULL DEFAULT false                          |
| is_fixed              | boolean       | NOT NULL DEFAULT false                          |
| is_derived            | boolean       | NOT NULL DEFAULT false                          |
| default_value_numeric | numeric(16,4) | NULL                                            |
| default_value_text    | text          | NULL                                            |
| default_value_bool    | boolean       | NULL                                            |
| min_value             | numeric(16,4) | NULL                                            |
| max_value             | numeric(16,4) | NULL                                            |
| source_note           | text          | NULL                                            |

CHECK: ровно одно default_value_* NOT NULL.
CHECK (min IS NULL OR max IS NULL OR min ≤ max).
UNIQUE (object_type_id, parameter_type_id).
Индексы: UNIQUE; (object_type_id).

Правило: `is_required` - одно поле. Обязательные для экономики и обязательные для подбора объединяются в один список. Списки - в `assumptions.md` §2.

`is_fixed` - параметр-константа (коэффициент начислений на ФОТ 1.302, рабочих
дней в году 365, наличие WMS): не редактируется, значение - в default_value_*.
`is_derived` - производный (объём отбора штук/сутки = строки/сутки × 1.5,
площадь активной зоны = общая площадь × 0.5): вычисляется на чтении.
Выставляются seed-ом через `scripts/seed/data/parameter_overrides.json`
(XLSX организатора не меняется); формулы зашиты
в код, в БД не хранятся. Подробности и обоснования - `assumptions.md` §25–28.

Seed: 145 строк (138 из XLSX + 7 worst-case: floor_load_max_kg - склад;
max_allowed_noise_dba и required_positioning_accuracy_mm - все 3 типа).
Обязательных: склад 31, аэропорт 26, медицина 30.

## 4. Справочники

| Таблица             | Поля                                                                                                   | Seed                     |
|---------------------|--------------------------------------------------------------------------------------------------------|--------------------------|
| industry            | id, code UNIQUE, name UNIQUE                                                                           | 9                        |
| vendor              | id, name UNIQUE, created_at                                                                            | 103                      |
| process             | id, code UNIQUE, name UNIQUE, is_active                                                                | 95                       |
| solution_type       | id, code UNIQUE, name UNIQUE                                                                           | 11                       |
| solution_subtype    | id, code UNIQUE, name UNIQUE                                                                           | 71                       |
| region              | id, code UNIQUE, name UNIQUE                                                                           | 24                       |
| object_type         | id, code UNIQUE, name, is_calc_enabled DEFAULT false, data_source_note                                 | 3; true - только склад   |
| parameter_type      | id, code UNIQUE, name, unit, value_type CHECK ∈ (number, boolean, text)                                | 135 (132 XLSX + 3 новых) |
| characteristic_type | id, code UNIQUE, name, group_code CHECK, data_type CHECK, unit, is_filterable, is_required, sort_order | 27                       |

Правило `object_type.is_calc_enabled`: DEFAULT false; после seed `true` только у склада
(«Склад», код `warehouse`) - mvp_scope.md: полный расчёт и имитация в MVP - только для склада.
seed повторным запуском флаг не сбрасывает (устанавливается в той же транзакции после UPSERT).

Конвенция кодов - английский snake_case для ВСЕХ справочников (assumptions.md §15/§20):
`object_type` - `warehouse` (Склад), `airport` (Аэропорт), `hospital` (Медучреждение);
industry, process, parameter_type, solution_type, solution_subtype, region - явный словарь
`scripts/seed/mappers/code_map.py` (9+95+141+11+71+24 записи - 351, из них
141 = 132 XLSX + 6 алиасов worst-case переименований + 3 новых; фолбэк - транслит с
предупреждением УТОЧНИТЬ). Транслит-коды ранних версий seed мигрируются
автоматически (`dicts_repo.migrate_dict_codes`; SQL - `scripts/seed/migrations.sql`).

Число 71 = 68 уникальных подтипов CSV после объединения 4 пар дублей (assumptions.md §13;
каноническая форма «Роботизированный 3D-принтер» входит в 68 - она есть в CSV)
+ 3 подтипа, присутствующих только в маппинге assumptions.md §4 («Робот-тягач»,
«Мобильный манипулятор», «Интеллектуальный автобус»).

## 5. Правила нормализации

### CSV (зерно строки = решение × применение)

1. Дедупликация по external_id: 223 строки → 187 solution.
2. Поля решения из группы дублей: name; vendor (upsert по имени); тип; статус; solution_type; solution_subtype (с картой автоправок - assumptions.md §10/§13); region; УГТ → trl; Рын Потенциал → market_potential; description - самое длинное непустое.
3. Сценарий → process: разрез по «, » с картой исключений; trim; карта опечаток. Итог - 250 применений (256 без карты исключений - assumptions.md §12).
4. Отрасль → industry. Каждая тройка (решение, отрасль, процесс) → solution_application; цена строки → offer_price_rub.
5. Цена: «2 700 000,00» → numeric. Конфликт цен: solution.price_rub = минимальная, альтернативы - offer_price_rub.
6. Числа: раздельный парсинг десятичных разделителей.
7. Провенанс карточки: source_kind='organizer_catalog', source_url='файл организатора', source_date=дата импорта. Колонки is_confirmed у solution нет - подтверждённость отслеживается только у значений ТТХ (§2.4); непроверенная карточка помечается источником manual (админка).
8. 9 ТТХ-колонок при импорте NULL; при дозаполнении - колонка + EAV-строка. Метаданные характеристик (characteristic_type) сеются - 27 записей, см. §2.3.

### XLSX (зерно строки = определение параметра)

9. Лист → object_type. Секции «▌» → group_name. Строка → parameter_type + object_type_parameter. «-» в min/max → NULL. Формулы → кэш-значение.
10. Лист «Легенда»: «Источники данных» → object_type.data_source_note. Блок «Важные допущения» → `assumptions.md`, не в БД.
11. Маппинг «тип объекта → отрасли»: seed 7 связей.

### Нормализация строк

- ё → е (для поиска, не для отображения).
- Дефисы и тире → обычный дефис.
- Trim пробелов, удаление дублирующих пробелов.
- Карта опечаток - в `assumptions.md` §10 (процессы и подтипы: «Доставка биоматериаловм»,
  четыре объединения подтипов).
- Известное ограничение данных: тип «Мобильные манипуляторы» существует в CSV, но не имеет
  пар «тип-подтип» в маппинге assumptions.md §4 (тип без подтипов - assumptions.md §16);
  в `solution_type_subtype_mapping` записей для него нет.

### Неполные данные

- Отсутствие значения = NULL.
- is_confirmed=false - маркирует неподтверждённое.
- Статус «требует проверки» - атрибут подбора (`selection_result`, §10.7), не каталога.

## 6. Точки расширения

Реализованы во второй части документа (разделы 8–13):

- `project` N:1 `object_type` - пользовательские объекты (§10.2).
- `project_parameter_value` N:1 `object_type_parameter` - значения параметров пользователя (§10.3).
- `scenario` N:1 `project` - сценарии расчёта (§10.5).
- `scenario_solution` M:N `solution` - состав оборудования (§10.6).
- `calculation` N:1 `scenario` - расчёты (§10.8).

Внешние ключи: `object_type_parameter.id` - целевой для `project_parameter_value`.

## 7. Фактическое наполнение после seed

Счётчики после прогона `scripts/seed/run.py` на `catalog_export_v4.csv` (187 решений,
223 строки) и `Датасеты_хакатон.xlsx`. Повторный запуск не меняет ни одного счётчика
(идемпотентность - UPSERT по естественным ключам).

| Таблица                       | Строк | Комментарий                                                                                              |
|-------------------------------|-------|----------------------------------------------------------------------------------------------------------|
| solution                      | 187   | дедуп 223 строк по external_id (23 группы дублей)                                                        |
| solution_application          | 250   | принятое число; 256 без карты исключений - assumptions §12                                               |
| object_type_parameter         | 145   | склад 42, аэропорт 39, медицина 57                                                                       |
| solution_case                 | 175   | уникальные тексты кейсов                                                                                 |
| solution_case_link            | 195   | связи решение ↔ кейс                                                                                     |
| object_type_industry          | 7     | assumptions §1                                                                                           |
| solution_type_subtype_mapping | 62    | assumptions §4                                                                                           |
| characteristic_type           | 27    | состав - §2.3, assumptions §14                                                                           |
| solution_characteristic       | 79    | дозаполнение ТТХ из открытых источников; 10 приоритетных решений склада                                  |

Справочники: industry 9, vendor 103, region 24, solution_type 11, solution_subtype 71,
process 95, object_type 3 (коды warehouse / airport / hospital -
assumptions §15; is_calc_enabled=true только у склада),
parameter_type 135 (132 XLSX + 3 новых worst-case).

# Часть 2. Домены пользователя

## 8. Состав второй части

Вторая часть покрывает путь пользователя от регистрации до экспорта
(user-flow.md, шаги 1–8): тип объекта → параметры → подбор → сравнение → расчёт →
what-if → имитация → сохранение и экспорт. Каждая сущность согласована с
`requirements/*`; всего 14 сущностей.

| #  | Сущность                | Назначение                                                       | Обоснование (источник)                                            |
|----|-------------------------|------------------------------------------------------------------|-------------------------------------------------------------------|
| 1  | user                    | учётные записи и роли                                            | requirements/roles.md                                             |
| 2  | project                 | объект пользователя (склад/аэропорт/медучреждение) с параметрами | user-flow.md шаги 1–2                                             |
| 3  | project_parameter_value | значения параметров объекта пользователя                         | requirements/objects.md                                           |
| 4  | project_attachment      | загруженные Excel/CSV исходники параметров                       | requirements/objects.md                                           |
| 5  | scenario                | сценарий расчёта (базовый / покупка / RaaS)                      | requirements/economics.md                                         |
| 6  | scenario_solution       | состав оборудования сценария                                     | requirements/economics.md, requirements/selection.md              |
| 7  | selection_result        | результат подбора с объяснением                                  | user-flow.md шаг 3                                                |
| 8  | calculation             | расчёт экономики с версионированием                              | requirements/economics.md                                         |
| 9  | calculation_assumption  | допущения расчёта (снимок)                                       | requirements/economics.md                                         |
| 10 | manual_adjustment       | ручная корректировка метрик с фиксацией                          | requirements/economics.md                                         |
| 11 | simulation_result       | результат 2D-имитации со статусом                                | mvp_scope.md «Имитация»                                           |
| 12 | export                  | выгрузки (PDF/XLSX/CSV)                                          | user-flow.md шаг 8                                                |
| 13 | project_assumption      | текущие переопределения допущений экономики                      | requirements/economics.md                                         |
| 14 | admin_import_log        | история импортов каталога организатора через админку             | requirements/catalog.md                                           |

Не вводятся (вне MVP): роль вендора («опционально»,
mvp_scope.md «Вне MVP»), журнал аудита действий (только «логирование
без чувствительных данных»), 3D-визуализация (вне MVP).

MVP-границы: полный путь (подбор → расчёт → имитация → экспорт) - только для
склада (mvp_scope.md); гейт - `object_type.is_calc_enabled` из части 1 (§4).
Для аэропорта и медицины - шаги 1–3 (mvp_scope.md).

Правила сформулированы в `requirements/*` - `selection.md`, `simulation.md`,
`export.md`; сущности второй части согласованы с ними.
Детальные правила подбора - `docs/selection_algorithm.md`.

## 9. ERD второй части

```
user 1-N project N-1 object_type                    (часть 1)
project 1-N project_parameter_value N-1 object_type_parameter   (часть 1)
project 1-N project_attachment
project 1-N scenario
scenario M-N solution [scenario_solution]           (solution - часть 1)
project 1-N selection_result N-1 solution           (часть 1)
scenario 1-N calculation
calculation 1-N calculation_assumption
calculation 1-N manual_adjustment N-1 user          (автор корректировки)
scenario 1-N simulation_result
project 1-N export N-1 user                        (кто выгрузил)
project 1-N project_assumption
user 1-N admin_import_log                          (кто загрузил таблицу каталога)
```

## 10. Таблицы второй части

### 10.1. user - учётные записи

Назначение: аутентификация и роли. Зарегистрированный пользователь создаёт
проекты; гость работает без сохранения (mvp_scope.md «демо-расчёт
без сохранения»); админ актуализирует каталог.

| Поле          | Тип         | Ограничения                                           |
|---------------|-------------|-------------------------------------------------------|
| id            | bigserial   | PK                                                    |
| login         | text        | NOT NULL, UNIQUE                                      |
| password_hash | text        | NOT NULL - хеш argon2/bcrypt                          |
| role          | text        | NOT NULL DEFAULT 'user', CHECK ∈ (guest, user, admin) |
| created_at    | timestamptz | NOT NULL DEFAULT now()                                |

Индексы: UNIQUE login.

Правила: пароли только в хеше; сессией управляет Next.js BFF,
Spring Boot валидирует JWT. Гость - непрошедший аутентификацию:
строки в таблице обычно не имеет (демо-расчёт не сохраняется, mvp_scope.md);
значение `guest` в CHECK сохранено для полноты ролевой модели.
Роль вендора - вне MVP (mvp_scope.md).

### 10.2. project - объект пользователя

Назначение: объект пользователя (склад/аэропорт/медучреждение) с параметрами,
сценариями и результатами. Точки входа полного пути (user-flow.md шаг 1–2).

| Поле           | Тип         | Ограничения                                                 |
|----------------|-------------|-------------------------------------------------------------|
| id             | bigserial   | PK                                                          |
| user_id        | bigint      | NOT NULL, FK → user.id ON DELETE CASCADE                    |
| object_type_id | bigint      | NOT NULL, FK → object_type.id                               |
| name           | text        | NOT NULL                                                    |
| description    | text        | NULL                                                        |
| status         | text        | NOT NULL DEFAULT 'draft', CHECK ∈ (draft, active, archived) |
| created_at     | timestamptz | NOT NULL DEFAULT now()                                      |
| updated_at     | timestamptz | NOT NULL DEFAULT now()                                      |

UNIQUE (user_id, name). Индексы: UNIQUE; (user_id); (object_type_id).

Правила:
- Изоляция: пользователь видит только свои проекты - все выборки
  через `project.user_id`; дочерние сущности доступны только через проект.
- Расчёт/имитация запускаются только при `object_type.is_calc_enabled = true`
  (часть 1, §4 - MVP: только склад).
- Копирование проекта - сервисная операция приложения (глубокое
  копирование параметров и сценариев), не структура БД.
- Удаление - каскадно (§12), вместе с файлами.
- Статусы `draft/active/archived` - допущение (lifecycle не задан,
  assumptions.md §18): минимальный жизненный цикл для списков проектов.
- `project_parameter_value` обязателен для расчёта по `is_required`
  (assumptions.md §2: без обязательных расчёт не запускается).

### 10.3. project_parameter_value - значения параметров проекта

Назначение: введённые пользователем значения параметров своего объекта
(ручной ввод, импорт Excel/CSV).

| Поле                     | Тип           | Ограничения                                         |
|--------------------------|---------------|-----------------------------------------------------|
| id                       | bigserial     | PK                                                  |
| project_id               | bigint        | NOT NULL, FK → project.id ON DELETE CASCADE         |
| object_type_parameter_id | bigint        | NOT NULL, FK → object_type_parameter.id             |
| value_numeric            | numeric(16,4) | NULL                                                |
| value_text               | text          | NULL                                                |
| value_bool               | boolean       | NULL                                                |
| source                   | text          | NOT NULL DEFAULT 'manual', CHECK ∈ (manual, import) |
| updated_at               | timestamptz   | NOT NULL DEFAULT now()                              |

CHECK: ровно одно из value_numeric / value_text / value_bool NOT NULL
(конвенция EAV части 1, §3.2).
UNIQUE (project_id, object_type_parameter_id). Индексы: UNIQUE; (project_id).

Правила:
- Валидация типов, единиц, обязательности и диапазонов - по метаданным
  `object_type_parameter` (`is_required`, `min_value`, `max_value`, `value_type`
  параметра); единицы едины с каталогом (assumptions.md §3).
- Значения по умолчанию НЕ дублируются: пользовательские строки создаются
  только для введённых значений; дефолты читаются из
  `object_type_parameter.default_value_*`.
- `source`: `manual` - ввод через форму, `import` - загрузка из
  Excel/CSV по шаблону.
- FK на `object_type_parameter` без CASCADE: определения параметров -
  справочные данные части 1.

### 10.4. project_attachment - вложения проекта

Назначение: загруженные исходники параметров (Excel/CSV по шаблону);
история загрузок; удаление вместе с проектом.

| Поле        | Тип         | Ограничения                                         |
|-------------|-------------|-----------------------------------------------------|
| id          | bigserial   | PK                                                  |
| project_id  | bigint      | NOT NULL, FK → project.id ON DELETE CASCADE         |
| file_name   | text        | NOT NULL - имя файла при загрузке                   |
| file_path   | text        | NOT NULL - путь в хранилище                         |
| mime_type   | text        | NULL                                                |
| size_bytes  | bigint      | NULL, CHECK (size_bytes IS NULL OR size_bytes >= 0) |
| uploaded_at | timestamptz | NOT NULL DEFAULT now()                              |

Индексы: (project_id).

Правила: ожидаемые форматы - Excel/CSV; белый список MIME и лимит
размера - на уровне приложения (лимиты не заданы), БД хранит фактические
атрибуты. Файлы - в файловом хранилище, в БД только ссылка. Ошибки загрузки
не должны терять проект: разбор файла - до записи значений.

### 10.5. scenario - сценарий расчёта

Назначение: вариант роботизации для сравнения. Требование: «сравнивать не менее
трёх сценариев: текущий процесс без роботизации и два варианта роботизации»;
виды - базовый, покупка, RaaS.

| Поле       | Тип         | Ограничения                                 |
|------------|-------------|---------------------------------------------|
| id         | bigserial   | PK                                          |
| project_id | bigint      | NOT NULL, FK → project.id ON DELETE CASCADE |
| type       | text        | NOT NULL, CHECK ∈ (base, purchase, raas)    |
| name       | text        | NOT NULL                                    |
| created_at | timestamptz | NOT NULL DEFAULT now()                      |
| updated_at | timestamptz | NOT NULL DEFAULT now()                      |

UNIQUE (project_id, name). Индексы: UNIQUE; (project_id).

Правила: `base` - «текущий процесс без роботизации», `purchase` - «покупка
оборудования», `raas` - «роботы как услуга»
(кодирование - assumptions.md §18). Не менее трёх сценариев в проекте
(по одному каждого типа) - инвариант приложения, не БД (могут быть и несколько
`purchase` с разным составом). Условия RaaS (структура платежей, срок
контракта, CAPEX/OPEX) - в допущениях расчёта (§10.9), не в сценарии.
Инвариант приложения: сценарий `base` НЕ содержит
решений - по определению «текущий процесс без роботизации»,
иначе теряется смысл `ΔOPEX = OPEX_year_rob − OPEX_year_base`
(economic_model.md §2.5). Не БД-CHECK, а правило приложения: ручное
добавление решения и PUT состава с непустым списком для `base`
→ 400 «Базовый сценарий не содержит решений по определению»; в UI
базовый сценарий не предлагается для добавления.

### 10.6. scenario_solution - состав оборудования сценария

Назначение: решения, входящие в сценарий, с количеством; результат подбора
и ручные добавления.

| Поле          | Тип         | Ограничения                                  |
|---------------|-------------|----------------------------------------------|
| scenario_id   | bigint      | NOT NULL, FK → scenario.id ON DELETE CASCADE |
| solution_id   | bigint      | NOT NULL, FK → solution.id                   |
| quantity      | integer     | NOT NULL DEFAULT 1, CHECK (quantity > 0)     |
| is_manual     | boolean     | NOT NULL DEFAULT false                       |
| manual_reason | text        | NULL - причина ручного добавления            |
| created_at    | timestamptz | NOT NULL DEFAULT now()                       |

PK (scenario_id, solution_id). Индексы: PK; (solution_id).

Правила: количество - «требуемое количество роботов и вспомогательного
оборудования». Ручное добавление решения - с предупреждением и
причиной: `is_manual=true` + заполненный
`manual_reason` (заполняемость контролирует приложение). FK на `solution`
без CASCADE: удаление решения из каталога не должно молча опустошать сценарии.
Точечное изменение состава:
`DELETE /api/projects/{id}/scenarios/{scenarioId}/solutions/{solutionId}` -
удаление одной строки (204/404) и
`PUT .../solutions/{solutionId}` с `{"quantity": N}` - изменение количества
(200/400/404; N 1–10000). Оба - authenticated, изоляция как у проектов
(чужой - 404). Исторические расчёты НЕ трогаются (append-only, §10.8):
старый расчёт воспроизводим со старым составом; ответ сценариев помечает
`compositionChanged`/`lastCalculatedAt` - «состав изменён с момента
расчёта» (бейдж UI). Замена состава целиком - PUT сценария (§10.5).

### 10.7. selection_result - результат подбора

Назначение: итог подбора по параметрам проекта с объяснимым статусом и рангом
(user-flow.md шаг 3: «показывает конкретные продукты и объясняет причины
включения или исключения»).

| Поле        | Тип         | Ограничения                                    |
|-------------|-------------|------------------------------------------------|
| id          | bigserial   | PK                                             |
| project_id  | bigint      | NOT NULL, FK → project.id ON DELETE CASCADE    |
| solution_id | bigint      | NOT NULL, FK → solution.id                     |
| status      | text        | NOT NULL, CHECK ∈ (fit, needs_check, excluded) |
| reason      | text        | NULL - объяснение включения/исключения         |
| rank        | integer     | NULL, CHECK (rank IS NULL OR rank >= 1)        |
| created_at  | timestamptz | NOT NULL DEFAULT now()                         |

UNIQUE (project_id, solution_id). Индексы: UNIQUE; (project_id, status);
(solution_id).

Правила: статусы - критическое ограничение → `excluded`
(«решение исключено»); нехватка данных ТТХ → `needs_check` («требует
проверки» - это НЕ исключение); прошло фильтры → `fit`.
`rank` - место в объяснимом ранжировании («критерии и вклад
факторов видны пользователю» - вклад факторов в отчёте/интерфейсе).
Подбор выполняется по параметрам проекта (сущность привязана к `project`,
не к сценарию: параметры - атрибут проекта, §10.3); перезапуск подбора -
UPSERT по UNIQUE (project_id, solution_id), несостоявшиеся пары удаляются.
Алгоритм - `docs/selection_algorithm.md`; требования и статусы - `requirements/selection.md`.

### 10.8. calculation - расчёт экономики

Назначение: результат расчёта сценария с версионированием. Требование:
«повторного открытия проекта и воспроизведения ранее выполненного расчета
с указанием версии исходных данных и расчетной модели».

| Поле           | Тип           | Ограничения                                      |
|----------------|---------------|--------------------------------------------------|
| id             | bigserial     | PK                                               |
| scenario_id    | bigint        | NOT NULL, FK → scenario.id ON DELETE CASCADE     |
| version_data   | text          | NOT NULL - версия исходных данных                |
| version_model  | text          | NOT NULL - версия расчётной модели               |
| calculated_at  | timestamptz   | NOT NULL DEFAULT now()                           |
| total_capex    | numeric(18,2) | NULL - САРЕХ, руб.                               |
| total_opex     | numeric(18,2) | NULL - годовой ОРЕХ, руб.                        |
| opex_delta_rub | numeric(18,2) | NULL - изменение ОРЕХ к базовому сценарию, руб.  |
| effect_year    | numeric(18,2) | NULL - чистый годовой экономический эффект, руб. |
| payback_years  | numeric(10,2) | NULL - простой срок окупаемости, лет             |
| roi_pct        | numeric(12,2) | NULL - ROI, %                                    |
| tco_rub        | numeric(18,2) | NULL - ТСО на горизонте ≥5 лет, руб.             |
| metrics_json   | jsonb         | NULL - детальная разбивка (см. правила)          |

Индексы: (scenario_id, calculated_at DESC).

Правила:
- Метрики-колонки - показатели, нужные для таблицы сравнения сценариев
  и сортировки: CAPEX (оборудование + инфраструктура + ПО +
  интеграция + пусконаладка + обучение + резерв), годовой OPEX (сервис,
  лицензии, электроэнергия, связь, расходники, ремонт, персонал эксплуатации),
  изменение OPEX к базовому, годовой эффект (сокращение затрат + доп. доход −
  доп. OPEX), окупаемость = CAPEX / годовой эффект, ROI = накопленный эффект /
  CAPEX × 100%, ТСО на горизонте не менее 5 лет. Интерпретация окупаемости -
  «до 3 / 3–5 / более 5 лет» (mvp_scope.md) - на уровне приложения.
- `metrics_json` - снимок переменного состава: разбивка CAPEX/OPEX по укрупнённым
  статьям, требуемое количество роботов по каждому решению сценария,
  чувствительность минимум к трём параметрам (стоимость оборудования,
  объём операций, стоимость труда). Обоснование JSONB - §12.
- `version_data` / `version_model` - версия данных (SHA-256[0:12] канона
  входов) и версия модели (economic-model-1.0; формат - economic_model.md §5):
  снимок версий записывается в каждом расчёте.
- История: каждый пересчёт - НОВАЯ строка (append-only); старые не меняются -
  воспроизведение расчёта. Скорректированные значения - тоже новый
  расчёт (§10.10).
- Производительность: расчёт ≤ 10 с; доступен только для склада
  (гейт `object_type.is_calc_enabled`, часть 1 §4).
- Недокументированные коэффициенты запрещены: все допущения -
  в `calculation_assumption`.

### 10.9. calculation_assumption - допущения расчёта

Назначение: снимок допущений конкретного расчёта. Требования: «документировать
дополнительные допущения»; пользователь меняет ключевые допущения;
все допущения доступны пользователю в интерфейсе или отчёте;
«команда формирует обоснованные допущения, явно описывает их
и указывает влияние на результаты».

| Поле           | Тип       | Ограничения                                                                 |
|----------------|-----------|-----------------------------------------------------------------------------|
| id             | bigserial | PK                                                                          |
| calculation_id | bigint    | NOT NULL, FK → calculation.id ON DELETE CASCADE                             |
| name           | text      | NOT NULL - параметр допущения                                               |
| value          | text      | NOT NULL - значение (строковое представление)                               |
| unit           | text      | NULL                                                                        |
| source_kind    | text      | NOT NULL DEFAULT 'manual', CHECK ∈ (organizer_catalog, open_source, manual) |
| source_url     | text      | NULL                                                                        |
| source_date    | date      | NULL                                                                        |
| impact_note    | text      | NULL - влияние на результат                                                 |

UNIQUE (calculation_id, name). Индексы: UNIQUE; (calculation_id).

Правила: формат «что / значение / источник / влияние» повторяет структуру
assumptions.md. Состав снимка = каталог допущений (33 кода) минус
необязательные без значения на момент расчёта (например,
robot_power_consumption_kw, loan_rate_pct) - строки снимка только для
заданных; примеры в OpenAPI иллюстративные, не норматив. Провенанс -
конвенция части 1 (source_kind/source_url/source_date, §2.4). Изменяемые пользователем
допущения: стоимость персонала, режим работы, производительность,
стоимость оборудования, стоимость обслуживания, коэффициент загрузки, горизонт
расчёта; параметры RaaS (структура платежей, срок контракта, распределение
CAPEX/OPEX) и амортизации - тем же списком.
Изменение допущения → новый расчёт с новым снимком (append-only, §10.8);
текущие значения редактируются в UI (mvp_scope.md), попадают в расчёт.

### 10.10. manual_adjustment - ручная корректировка с фиксацией

Назначение: «Автоматически рассчитываемые значения должны быть
доступны для просмотра и при необходимости ручной корректировки с фиксацией
изменения».

| Поле           | Тип           | Ограничения                                     |
|----------------|---------------|-------------------------------------------------|
| id             | bigserial     | PK                                              |
| calculation_id | bigint        | NOT NULL, FK → calculation.id ON DELETE CASCADE |
| metric_name    | text          | NOT NULL - какая метрика скорректирована        |
| original_value | numeric(18,4) | NULL - до корректировки                         |
| new_value      | numeric(18,4) | NOT NULL - после                                |
| reason         | text          | NOT NULL - почему                               |
| author_user_id | bigint        | NOT NULL, FK → user.id                          |
| created_at     | timestamptz   | NOT NULL DEFAULT now()                          |

Индексы: (calculation_id); (author_user_id).

Правила: фиксация «что / было / стало / почему / кто / когда» - лог-строка
с автором и причиной. Корректировка порождает НОВЫЙ расчёт (скорректированное
значение записывается в новый `calculation`, история не переписывается, §10.8);
`manual_adjustment` хранит саму запись о вмешательстве. Метрики расчёта
числовые (§10.8) - значения numeric.

### 10.11. simulation_result - результат имитации

Назначение: 2D-имитация работы выбранного решения. Требования: «проверяет
достижимость заявленной производительности, загрузку оборудования, простои
и узкие места»; «до 60 секунд. Пользователь должен видеть статус
выполнения».

| Поле        | Тип         | Ограничения                                                      |
|-------------|-------------|------------------------------------------------------------------|
| id          | bigserial   | PK                                                               |
| scenario_id | bigint      | NOT NULL, FK → scenario.id ON DELETE CASCADE                     |
| kpi_json    | jsonb       | NULL - KPI имитации (снимок)                                     |
| started_at  | timestamptz | NOT NULL DEFAULT now()                                           |
| finished_at | timestamptz | NULL                                                             |
| status      | text        | NOT NULL DEFAULT 'running', CHECK ∈ (running, completed, failed) |

Индексы: (scenario_id); partial (status) WHERE status = 'running'.

Правила: статусы исполнения - запуск/пауза/перезапуск это UI-режимы
(mvp_scope.md «Имитация»), в БД записываются `running` (запущена) →
`completed` / `failed` («статус выполнения»). KPI - «связанные с
расчётом» (mvp_scope.md): достижимость производительности, загрузка
оборудования, простои, узкие места; состав зависит от типа объекта → jsonb
(§12). Имитация - только для склада (гейт `is_calc_enabled`, MVP).
Визуализация «подтверждает расчёт, а не является декоративной анимацией»
(user-flow.md шаг 7): результаты имитации сравниваются с `calculation`.

Поведение: `running` пишется
отдельной транзакцией ДО расчёта (статус виден пользователю), модель
синхронная аналитическая (~200 мс, порог 60 с - решение
«синхронно vs очередь» в architecture.md §7); повторный запуск - новая
строка (история, KPI детерминированы - идемпотентность); живой `running`
младше 15 минут даёт 409 (гонка), зависший помечается `failed`. Состав
`kpi_json` (задан DTO SimulationRunDto, версия модели
`simulation-model-1.0` в поле `version`): 6 KPI
(declaredThroughputPerHour, actualThroughputPerHour, utilizationPct,
idlePct, zones[{code, name, flowPerHour, robotTimeSharePct}] - узкие
места по 4 зонам с максимумом-бутылочным горлышком, achievabilityPct),
разбивки для 2D-схемы (fleetSpeedMs, capacityPerRobotPerHour,
effectivePerRobotPerHour - P_effective из economic_model.md §2.1 для сверки,
peakDemandPerHour, avgCycleTimeSec, inbound/outboundCycleTimeSec,
dailyTravelKmPerRobot, chargingStations - ceil по economic_model.md §2.2,
movingPct/handlingPct - статусные доли роботов), сценарий и состав (composition),
входы (inputs: маршрут/погрузка/скорость/режим), warnings (сверка с
последним расчётом экономики: состав, недобор парка,
расхождение производительностей) и служебное `exportUrl` -
ссылка на сохранённую SVG-схему, файл в
`data/simulations/{projectId}/{simulationId}.svg`, удаляется вместе
с проектом. История - GET /simulations по проекту с
изоляцией через родительский проект (§12).

### 10.12. export - выгрузки

Назначение: сгенерированные отчёты. Требования: отчёт содержит «параметры
объекта, выбранные решения, состав оборудования, расчет экономики, ограничения,
источники данных и дату расчета»; пометка «предварительная оценка,
требует верификации»; user-flow.md шаг 8: PDF и Excel/CSV.

| Поле                | Тип         | Ограничения                                   |
|---------------------|-------------|-----------------------------------------------|
| id                  | bigserial   | PK                                            |
| project_id          | bigint      | NOT NULL, FK → project.id ON DELETE CASCADE   |
| format              | text        | NOT NULL, CHECK ∈ (pdf, xlsx, csv)            |
| file_path           | text        | NOT NULL - путь в хранилище                   |
| created_at          | timestamptz | NOT NULL DEFAULT now()                        |
| created_by_user_id  | bigint      | NOT NULL, FK → user.id                        |

Индексы: (project_id); (created_by_user_id).

Правила: форматы - PDF-отчёт и Excel/CSV с расчётными таблицами (mvp_scope.md
«Экспорт»). Выгружает зарегистрированный пользователь; демо-путь
гостя без сохранения (mvp_scope.md). В отчёте обязательны источники данных и
дата расчёта - берутся из `calculation` (version_data/version_model,
calculated_at) и провенанса каталога (часть 1). Файлы - в хранилище
(например `data/exports/`), в БД - ссылка и история выгрузок.

Поведение: entity `Export` +
`ExportRepository` (история проекта `findAllByProjectIdOrderByCreatedAtDescIdDesc`,
одиночная выборка с изоляцией через join на project по userId - как у
имитации, §12); файлы - `ExportStorage` по образцу AttachmentStorage:
`EXPORT_ROOT` (env, дефолт ./data/exports), путь {projectId}/{exportId}.{ext}
только из серверных идентификаторов, белый список форматов (pdf/xlsx/csv) и
лимит по умолчанию 50 МБ (env EXPORT_MAX_BYTES), path traversal исключён
конструкцией и resolve()-проверкой;
удаление проекта - каскад строк БД + `deleteProjectFiles` best-effort.
Отчёт читает последние
расчёты сценариев (append-only §10.8) и не пересчитывает; пометка
пишется на титуле и в конце каждого отчёта.

### 10.13. project_assumption - текущие допущения экономики

Назначение: хранение текущих переопределений допущений экономики проекта
(«пользователь изменяет ключевые допущения» - персистентность
между сессиями и расчётами). `calculation_assumption` хранит только
снимок конкретного расчёта (§10.9) и не является текущим состоянием.

| Поле       | Тип         | Ограничения                                 |
|------------|-------------|---------------------------------------------|
| id         | bigserial   | PK                                          |
| project_id | bigint      | NOT NULL, FK → project.id ON DELETE CASCADE |
| name       | text        | NOT NULL - код допущения (каталог §22–23)   |
| value      | text        | NOT NULL - значение                         |
| updated_at | timestamptz | NOT NULL DEFAULT now()                      |

UNIQUE (project_id, name). Индексы: UNIQUE; (project_id).

Правила: структура повторяет `calculation_assumption` (name/value) без
провенанса - провенанс у допущения постоянен (assumptions.md §22–23)
и попадает в снимок расчёта. Дефолты НЕ дублируются (как у параметров,
§12): отсутствие строки = дефолт каталога; сброс переопределения -
удаление строки. Эффективное значение (переопределение → дефолт)
снимком попадает в каждый расчёт. DDL: `schema.psql`; поддержка в seed:
`scripts/seed/tests/db_schema.py` (14 таблиц Части 2).

### 10.14. admin_import_log - история импортов каталога

Назначение: журнал загрузок таблицы каталога организатора через админку
(«ручное управление», «обновление по запросу»). Каждая загрузка - одна строка;
`status='running'` на время импорта → `'completed'` (с `summary_json`) или
`'failed'`. Единственный 'running' = признак in-flight импорта: повторный
запуск → 409; зависший после рестарта старше 15 минут помечается failed.

| Поле              | Тип         | Ограничения                                  |
|-------------------|-------------|----------------------------------------------|
| id                | bigserial   | PK                                           |
| file_name         | text        | NOT NULL - имя, как его назвал администратор |
| file_path         | text        | NOT NULL - копия в data/admin-imports/{id}.{ext} (для refresh); имя пользователя в пути НЕ участвует |
| size_bytes        | bigint      | NOT NULL - снимок размера (лимит 50 МБ → 413)|
| started_at        | timestamptz | NOT NULL DEFAULT now()                       |
| finished_at       | timestamptz | NULL (у running)                             |
| status            | text        | NOT NULL DEFAULT 'running' CHECK ∈ {running, completed, failed} |
| created_by_user_id| bigint      | NOT NULL, FK → user.id                       |
| summary_json      | jsonb       | NULL - счётчики CatalogImportSummary (added/updated/skipped по сущностям) |

Индексы: (started_at DESC) - история и «последний файл» для refresh.

Правила: строка 'running' записывается ОТДЕЛЬНОЙ транзакцией до импорта -
журнал переживает откат данных (при ошибках файла статус failed остаётся
в истории). `summary_json` пишется через `@JdbcTypeCode(SqlTypes.JSON)`
(PostgreSQL не принимает varchar в jsonb напрямую - та же конвенция, что
у simulation_result.kpi_json и calculation.metrics_json). Повторный
импорт того же файла: все сущности skipped (идемпотентность, §5);
зеркальные ТТХ-колонки solution повторным импортом НЕ обнуляются (файл
организатора их не содержит - правило описано в architecture.md §11).
DDL: `schema.psql`; поддержка в seed: `scripts/seed/tests/db_schema.py`
(14 таблиц Части 2).

## 11. Связь с первой частью

| Связь                                           | Тип | Назначение                                                                   |
|-------------------------------------------------|-----|------------------------------------------------------------------------------|
| project → object_type                           | N:1 | тип объекта проекта; `is_calc_enabled` гейтит расчёт и имитацию (MVP: склад) |
| project_parameter_value → object_type_parameter | N:1 | определение параметра: единица, `value_type`, `is_required`, min/max, дефолт |
| scenario_solution → solution                    | M:N | состав оборудования сценария из каталога                                     |
| selection_result → solution                     | N:1 | результат подбора по решению каталога                                        |

Общие конвенции обеих частей: snake_case; bigserial PK; timestamptz
created_at/updated_at DEFAULT now(); CHECK-перечисления английских кодов;
провенанс source_kind/source_url/source_date; EAV-инвариант «ровно одно
value_*». Каталог (часть 1) - справочные данные: FK из части 2 на таблицы
каталога без ON DELETE CASCADE (удаление решений/определений параметров -
административная операция с предварительной проверкой ссылок).

## 12. Сквозные правила второй части

- **Изоляция**: пользователь видит только свои проекты - через
  `project.user_id`; параметры, вложения, сценарии, подбор, расчёты, имитации
  и экспорты доступны только через родительский проект (все запросы -
  с join на project и фильтром по пользователю).
- **Каскадное удаление**: project → project_parameter_value, project_attachment,
  scenario, selection_result, export; scenario → scenario_solution, calculation,
  simulation_result; calculation → calculation_assumption, manual_adjustment.
  Удаление проекта - вместе с загруженными файлами: файлы
  вложений и экспортов удаляются хранилищем по каскадным ссылкам БД.
- **Версионирование расчёта**: `calculation` append-only - каждый
  пересчёт новая строка с `version_data` + `version_model`; допущения
  снимком в `calculation_assumption`; корректировки - в `manual_adjustment`
  и новом расчёте. Воспроизведение = чтение старой строки и её снимков.
- **JSON против нормализации**: jsonb только для снимков переменного состава -
  `metrics_json` (разбивка CAPEX/OPEX по статьям, количество роботов по
  решениям, чувствительность) и `kpi_json` (состав KPI зависит
  от типа объекта). Всё, что фильтруется, сортируется, участвует в
  FK/UNIQUE - обычные колонки. Каталог части 1 обходится без JSON - тот же
  подход. Отчётные выборки читают jsonb как есть (специальные GIN-индексы
  по этим полям не нужны).
- **Дефолты параметров** не дублируются в проекте - читаются из
  `object_type_parameter.default_value_*`. Дефолты допущений
  экономики - аналогично: каталог задан кодом (assumptions.md §22–23),
  в `project_assumption` хранятся ТОЛЬКО переопределения пользователя
  (отсутствие строки = дефолт).
- **Гость**: без строки user, демо-расчёт не сохраняется (mvp_scope.md);
  «коммерчески чувствительные данные» гостем не сохраняются (roles.md).
- **Производительность**: расчёт ≤ 10 с, имитация ≤ 60 с со статусом;
  индекс (scenario_id, calculated_at DESC) - история расчётов
  сценария без сортировки.
- **Логирование**: без чувствительных данных (в лог - идентификаторы
  и статусы, не значения параметров пользователя).
