-- V2: демо-данные каталога — 8 РЕАЛЬНЫХ строк из docs/source/catalog_export_v4.csv
-- (внешние id, названия, компании, регионы, цены — дословно из каталога организатора).
--
-- Назначение: каркас сразу отдаёт непустой /api/solutions даже без запуска
-- python-seed. Для ПОЛНОГО каталога (187 решений) запустите scripts/seed/run.py —
-- он идемпотентен (upsert) и просто обновит эти же строки, дублей не будет:
-- совпадают и external_id, и (vendor_id, name).
--
-- Техника: id генерируются последовательностями (не задаём вручную — иначе
-- sequence «отстанет» и будущие вставки сломаются), FK подставляются подзапросами
-- по уникальным кодам/именам. ON CONFLICT DO NOTHING — повторный прогон безопасен.

-- --- справочники (коды — английские snake_case, как в mappers/code_map.py) ---
INSERT INTO vendor (name)
VALUES ('ООО "Ронави Роботикс"'),
       ('ООО "ГК Автомакон"'),
       ('ООО "АРС"'),
       ('АО "Эйдос Робототехника"'),
       ('ООО "Умный сервис"'),
       ('ООО "Геоскан"'),
       ('ООО "Финко"') ON CONFLICT (name) DO NOTHING;

INSERT INTO solution_type (code, name)
VALUES ('mobile_robots', 'Мобильные роботы'),
       ('autonomous_ground_vehicles', 'Автономные наземные транспортные средства'),
       ('stationary_robotic_systems', 'Стационарные роботизированные системы'),
       ('manipulator_robots', 'Роботы-манипуляторы'),
       ('software', 'ПО') ON CONFLICT (code) DO NOTHING;

INSERT INTO solution_subtype (code, name)
VALUES ('amr', 'AMR'),
       ('fmr', 'FMR'),
       ('smart_storage_system', 'Умная система хранения'),
       ('manipulator_robot', 'Робот-манипулятор'),
       ('goods_sorting_software', 'ПО для сортировки товаров') ON CONFLICT (code) DO NOTHING;

INSERT INTO region (code, name)
VALUES ('moscow', 'Москва'),
       ('tatarstan_republic', 'Республика Татарстан'),
       ('saint_petersburg', 'Санкт-Петербург'),
       ('udmurt_republic', 'Удмуртская Республика') ON CONFLICT (code) DO NOTHING;

-- --- 8 решений каталога ---
INSERT INTO solution (external_id, name, vendor_id, product_class,
                      solution_type_id, solution_subtype_id, region_id,
                      status, price_rub, trl, market_potential, source_kind)
VALUES
    -- 1. AMR для склада
    ('5760e938-9a43-45a7-b8e8-f4f2e6383930', 'Ronavi H1500 (грузоподъемность до 1 500 кг)',
     (SELECT id FROM vendor WHERE name = 'ООО "Ронави Роботикс"'), 'brs',
     (SELECT id FROM solution_type WHERE code = 'mobile_robots'),
     (SELECT id FROM solution_subtype WHERE code = 'amr'),
     (SELECT id FROM region WHERE code = 'moscow'),
     'operation', 2700000.00, 8, 4.0, 'organizer_catalog'),
    -- 2. FMR (погрузчик)
    ('5a36611d-033e-4893-bd49-5d4f776f57dd', 'AK-2000-2',
     (SELECT id FROM vendor WHERE name = 'ООО "ГК Автомакон"'), 'brs',
     (SELECT id FROM solution_type WHERE code = 'autonomous_ground_vehicles'),
     (SELECT id FROM solution_subtype WHERE code = 'fmr'),
     (SELECT id FROM region WHERE code = 'moscow'),
     'operation', 2940000.00, 9, 4.0, 'organizer_catalog'),
    -- 3. Стационарная система хранения
    ('0ece582a-084c-4a8b-99f4-576f0e01b7c8', 'SmartCube',
     (SELECT id FROM vendor WHERE name = 'ООО "АРС"'), 'brs',
     (SELECT id FROM solution_type WHERE code = 'stationary_robotic_systems'),
     (SELECT id FROM solution_subtype WHERE code = 'smart_storage_system'),
     (SELECT id FROM region WHERE code = 'moscow'),
     'operation', 2700000.00, 8, 4.0, 'organizer_catalog'),
    -- 4. Манипулятор
    ('ce44017e-6ada-421d-bbce-6869b33c2cda', 'Модель А25-1720',
     (SELECT id FROM vendor WHERE name = 'АО "Эйдос Робототехника"'), 'brs',
     (SELECT id FROM solution_type WHERE code = 'manipulator_robots'),
     (SELECT id FROM solution_subtype WHERE code = 'manipulator_robot'),
     (SELECT id FROM region WHERE code = 'tatarstan_republic'),
     'operation', 2000000.00, 9, 4.0, 'organizer_catalog'),
    -- 5. ПО для сортировки
    ('05505022-3ef7-43da-8279-718e7c432491', 'Pick by Voice',
     (SELECT id FROM vendor WHERE name = 'ООО "Умный сервис"'), 'software',
     (SELECT id FROM solution_type WHERE code = 'software'),
     (SELECT id FROM solution_subtype WHERE code = 'goods_sorting_software'),
     (SELECT id FROM region WHERE code = 'moscow'),
     'operation', 1000000.00, 8, 5.0, 'organizer_catalog'),
    -- 6-8. БАС (тип/подтип в CSV пустые -> NULL, как в seed)
    ('6c0df6e9-ea39-40d2-93af-7aec13d629c7', 'Геоскан 401',
     (SELECT id FROM vendor WHERE name = 'ООО "Геоскан"'), 'bas',
     NULL, NULL,
     (SELECT id FROM region WHERE code = 'saint_petersburg'),
     'operation', 4000000.00, 9, 4.0, 'organizer_catalog'),
    ('8dbe4684-18ef-49f7-bced-95969703a49a', 'Supercam X4E',
     (SELECT id FROM vendor WHERE name = 'ООО "Финко"'), 'bas',
     NULL, NULL,
     (SELECT id FROM region WHERE code = 'udmurt_republic'),
     'operation', 5038000.00, 8, 4.0, 'organizer_catalog'),
    ('6d9890b0-d95a-4bae-8947-0a09d23c30e5', 'Supercam Х6М2',
     (SELECT id FROM vendor WHERE name = 'ООО "Финко"'), 'bas',
     NULL, NULL,
     (SELECT id FROM region WHERE code = 'udmurt_republic'),
     'rnd', 5500000.00, 7, 4.0, 'organizer_catalog') ON CONFLICT (external_id) DO NOTHING;
