# Seed-скрипт: каталог решений и параметры объектов

Идемпотентная загрузка данных организатора в PostgreSQL 16 по схеме
`docs/data_model.md` (разделы 2, 3, 4) и правилам нормализации
`docs/assumptions.md` (разделы 1, 2, 3, 4, 7, 8, 9, 10, 12, 13, 14, 15, 16).

Скрипт - часть инфраструктуры, отдельная от Spring Boot: схему БД **не создаёт и
не меняет** (ожидается, что миграции приложения уже применены), только заполняет.

## Что читает

| Источник | Путь (порядок поиска) | Что даёт |
|---|---|---|
| catalog_export_v4.csv | `docs/source/…` (или перем. `CATALOG_CSV`) | решения, применения, кейсы, справочники |
| Датасеты_хакатон.xlsx | `docs/source/…` (или перем. `OBJECTS_XLSX`) | object_type, parameter_type, object_type_parameter, источники данных |
| characteristics.json | `scripts/seed/data/…` (или перем. `CHARACTERISTICS_JSON`) | ТТХ из открытых источников: solution_characteristic (EAV) + 9 зеркальных ТТХ-колонок `solution` |
| parameter_overrides.json | `scripts/seed/data/…` (или перем. `PARAMETER_OVERRIDES_JSON`) | Корректировки параметров поверх XLSX: is_fixed/is_derived, worst-case переименования и дефолты, подсказки, новые параметры. Файл обязателен - отсутствие = ошибка старта |

## Что пишет (порядок с учётом FK)

1. `industry` (9) ← CSV «Отрасль»; ключ идемпотентности: `code`
2. `vendor` (103) ← CSV «компания»; ключ: `name`
3. `region` (24) ← CSV «Регион»; ключ: `name`
4. `solution_type` (11) ← CSV «Тип»; ключ: `name`
5. `solution_subtype` (71) ← CSV «Подтип» + подтипы из assumptions п.4; карта автоправок объединяет 4 пары дублей (assumptions п.10/п.13); ключ: `name`; merge-шаг чистит БД, засеянную старой версией скрипта
6. `process` (95) ← CSV «Сценарий» после разреза по «, » с картой исключений (250 применений; 256 без карты - assumptions п.12); ключ: `code`
7. `object_type` (3) ← листы XLSX + легенда; ключ: `code`; коды - конвенция английского snake_case (assumptions п.15): `warehouse`/`airport`/`hospital`; перед upsert выполняется миграция транслит-кодов старых версий (`code migrated: sklad -> warehouse (N строк)`); после вставки - `is_calc_enabled=true` для склада (`object_type warehouse: is_calc_enabled=true` в логе)
8. `parameter_type` (135) ← XLSX (132, дедуп имён между листами) + 3 новых
   worst-case из `data/parameter_overrides.json`; ключ: `code`
9. `solution_type_subtype_mapping` (62) ← assumptions п.4; ключ: PK
10. `object_type_industry` (7) ← assumptions п.1; ключ: PK
11. `characteristic_type` (27) ← состав - assumptions.md §14; ключ: `code`
12. `solution` (187) ← CSV, дедуп по `external_id` (223 строки → 187); ключ: `external_id`
13. `solution_application` (250) ← зерно CSV (решение × отрасль × процесс); ключ: UNIQUE-тройка
14. `solution_case` (175) + `solution_case_link` (195) ← CSV «Кейсы»; ключи: `name`, PK
15. `solution_characteristic` (80) + 9 зеркальных ТТХ-колонок `solution` ← `data/characteristics.json` - дозаполненные ТТХ из открытых официальных источников: 10 приоритетных решений по складу × 80 значений с провенансом (`source_kind='open_source'`, `source_url`, `source_date`, `is_confirmed=true` - assumptions п.7); ключ: UNIQUE (solution_id, characteristic_type_id); шаг идёт ПОСЛЕ `solution` в той же транзакции (upsert решений сбрасывает зеркальные ТТХ-колонки в NULL - шаг дозаполнения заполняет их заново, data_model.md п.5.8); провенанс карточки (`solution.source_kind`) не меняется - остаётся `organizer_catalog` (п.5.7). Файл отсутствует - шаг пропускается с предупреждением
16. `object_type_parameter` (145) ← XLSX (138) + 7 worst-case из
    `data/parameter_overrides.json` (floor_load_max_kg - склад; шум и
    точность - все 3 типа; флаги is_fixed/is_derived - миграция V4);
    ключ: UNIQUE (object_type_id, parameter_type_id)

У решений вне приоритетной десятки ТТХ остаются NULL (импорт каталога их не
содержит - data_model.md п.5.8); `noise_level_dba` не публикуется ни одним
производителем линейки - NULL у всех (границы применимости - Доп 4).
Метаданные характеристик (`characteristic_type`) сеются - 27 записей.

17. `user` (2) ← демо-аккаунты `admin`/`user` (bcrypt cost 12, роли
    admin/user); ключ: `login`, DO NOTHING - пароли не сбрасываются.
    Легаси-аккаунт `test_economics` (владелец E2E-проектов в старых версиях
    seed) удаляется вместе с его данными
18. E2E-проекты экономики (`repositories/test_projects_repo.py`):
    5 проектов «E2E: *» у `user` - `project` (5) + `scenario`
    (15: base/purchase/raas; base всегда пустой - инвариант приложения) +
    `scenario_solution` (16) + `project_parameter_value` (185; фиксированные
    и производные параметры не вставляются - значения fixed живут в
    дефолтах метаданных, их строки игнорируются приложением, п.28) + `project_assumption` (7:
    P_nominal 90 оп/ч; проект «Пиковая нагрузка» - k_load 0.70/k_reserve
    0.20; «Edge case» - robot_power_consumption_kw 2.2 кВт). Составы ссылаются
    на решения каталога по `external_id`
    (Ronavi H1500/SR, AMR 100/800/1500). Всё - ON CONFLICT DO NOTHING:
    повторные прогоны не меняют счётчики и не трогают то, что изменено
    через UI. Подбор и расчёты по этим проектам гонит живой backend
    (`scripts/verify_economics.py`, эталон `docs/economics_golden.md`).

## Запуск

```bash
cd <корень проекта>
python -m venv .venv && . .venv/bin/activate
pip install -r scripts/seed/requirements.txt

export DATABASE_URL='postgresql+psycopg2://user:password@localhost:5432/robotics_platform'
python scripts/seed/run.py
```

Повторный запуск безопасен: все вставки - `INSERT .. ON CONFLICT .. DO UPDATE`
по естественным ключам, дубликатов не возникает.

### Переменные окружения

| Переменная | Обязательна | Назначение |
|---|---|---|
| `DATABASE_URL` | да | полный URL PostgreSQL (креды только здесь, не в коде) |
| `CATALOG_CSV` | нет | путь к CSV, если лежит не в стандартных местах |
| `OBJECTS_XLSX` | нет | путь к XLSX |
| `CHARACTERISTICS_JSON` | нет | путь к JSON с ТТХ из открытых источников (по умолчанию `scripts/seed/data/characteristics.json`; отсутствие файла - не ошибка, шаг пропускается) |
| `TEST_DATABASE_URL` | нет | БД для интеграционных тестов (иначе SQLite во tmp-каталоге) |

## Гарантии

- **Одна транзакция на весь импорт**: любое исключение - откат, БД остаётся чистой.
- **Идемпотентность**: второй запуск обновляет те же строки (в логе `вставлено 0`).
- **Логи**: каждый шаг - сколько строк прочитано / отправлено / вставлено / обновлено;
  конфликты (цены, опечатки, похожие названия) - с префиксами `КОНФЛИКТ` / `УТОЧНИТЬ` / `ПОХОЖИЕ НАЗВАНИЯ`.

## Тесты

```bash
cd scripts/seed
python -m pytest tests/ -v          # по умолчанию SQLite, БД не нужна
# на реальном PostgreSQL:
TEST_DATABASE_URL='postgresql+psycopg2://user:pass@localhost:5432/testdb' python -m pytest tests/ -v
```

Покрытие: нормализация цены («2 700 000,00» → Decimal), нормализация строк
(пробелы/тире/ё), дедупликация по external_id (223 → 187), разрезание сценариев
с картой исключений, EAV-инвариант «ровно одно default_value_*», счётчики
параметров (145/135, обязательные 31/26/30), идемпотентность двойного запуска,
`characteristic_type` = 27 (состав групп, флаги, sort_order, зеркальные ТТХ-коды,
`source_date` = date), объединение подтипов (нет «Робот уборщик»/«наводной»/
«3D принтер» без дефиса, FK-ссылки валидны, legacy-чистка), `is_calc_enabled`
только у склада, конвенция кодов `object_type` (warehouse/airport/hospital +
миграция транслита + конфликтный кейс).

`tests/db_schema.py` - тестовое зеркало схемы data_model.md (в проде схему
создаёт Spring Boot; тесты создают её сами, чтобы проверять CHECK/UNIQUE).

## Принятые интерпретации (нет прямых указаний в документации)

1. `source_url` = имя файла организатора (`catalog_export_v4.csv`) - конкретизация
   «файл организатора» из data_model.md п.5.7. Кейсам присвоен тот же провенанс.
2. Коды ВСЕХ справочников - английские snake_case по явному словарю
   `mappers/code_map.py` (assumptions.md п.20: industry 9, process 95,
   parameter_type 132+3, solution_type 11, solution_subtype 71, region 24 -
   348 кода (345 уникальных + 3 алиаса worst-case переименований);
   region/solution_type/solution_subtype имеют колонку `code` UNIQUE). Отсутствие
   записи в словаре - предупреждение `УТОЧНИТЬ` и транслит-фолбэк `slugify`.
   Легаси-транслит (`sklad`, `torgovlya_i_uslugi`, `vnutriskladskaya_logistika`, …)
   мигрируется автоматически: по имени до upsert (`code migrated by name: …`),
   колонки code добавляет `migrations.sql`.
3. `value_type` параметров выводится из данных: числовой min/max или числовое
   базовое → `number`; имя на «Наличие» + значение «Да/Нет…» → `boolean`
   (уточнение вида «Да (OSDP)» остаётся только в XLSX и логе); остальное → `text`.
4. `object_type.is_calc_enabled = true` только для склада - устанавливается
   шагом seed после UPSERT object_type (mvp_scope.md: полный расчёт - только
   склад; assumptions.md п.14); аэропорт и медицина - false (DEFAULT).
   Флаг ставится по имени листа «Склад» - не зависит от кода.
5. «Мониторинг, патрулирование, перевозка грузов» не разрезается: фрагменты
   «патрулирование», «перевозка грузов» - со строчной буквы, не самостоятельные
   названия процессов. Занесено в карту исключений, подтверждать у организатора.
6. Дубли подтипов («Робот уборщик», «Робот инвентаризатор», «наводная» платформа,
   «Роботизированный 3D принтер») удаляются, а не деактивируются: у
   `solution_subtype` нет колонки `is_active`, менять схему нельзя (assumptions.md п.13).
7. `source_date` (дата актуализации) - настоящий DATE: `characteristic_type.data_type='date'`,
   значение в `solution_characteristic.value_date` (assumptions.md п.14).
8. «Мобильные манипуляторы» - тип без подтипов (assumptions.md п.16): в CSV есть
   тип, но пар «тип-подтип» нет; в логе - `УТОЧНИТЬ: Мобильные манипуляторы - тип без
   подтипов, оставлен как есть`.

Генератор `scripts/gen_code_map.py` в репозиторий не входит -
соответствующая запись есть в журнале закрытых вопросов.
