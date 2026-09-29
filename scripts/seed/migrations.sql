-- Миграции для существующей БД, засеянной предыдущей версией seed
-- (правки от 2026-09-17: задачи 1а/1б/1в; английские коды справочников,
-- индексы solution.status и GIN pg_trgm — data_model.md п.2.1/п.4).
--
-- Применять ОДИН раз перед очередным запуском seed:
--   psql -U postgres -d robomatch -f scripts/seed/migrations.sql
--   python scripts/seed/run.py
--
-- Скрипт идемпотентен: повторное применение ничего не меняет.
-- Свежая БД (schema.psql + текущий seed) в миграциях не нуждается:
-- английские коды, колонки code и value_date она получает сразу.
--
-- Обоснования: docs/assumptions.md п.13, п.14, п.15; docs/data_model.md п.2.3, п.2.4, п.4.

BEGIN;

-- ============================================================================
-- 1. Коды object_type: транслит -> английский snake_case (assumptions.md п.15)
-- ============================================================================
-- UPDATE затрагивает строки только при отсутствии целевого кода: защищает
-- UNIQUE(code) от патологического случая «есть и sklad, и warehouse»
-- (такой конфликт seed логирует как КОНФЛИКТ и не трогает).

UPDATE object_type SET code = 'warehouse'
WHERE code = 'sklad'
  AND NOT EXISTS (SELECT 1 FROM object_type WHERE code = 'warehouse');

UPDATE object_type SET code = 'airport'
WHERE code = 'aeroport'
  AND NOT EXISTS (SELECT 1 FROM object_type WHERE code = 'airport');

UPDATE object_type SET code = 'hospital'
WHERE code = 'meduchrezhdenie'
  AND NOT EXISTS (SELECT 1 FROM object_type WHERE code = 'hospital');

-- ============================================================================
-- 2. source_date — настоящий DATE (assumptions.md п.14, data_model.md п.2.3/2.4)
-- ============================================================================

-- 2.1. characteristic_type.data_type: расширяем CHECK до 'date'.
--      Снимаем любой прежний CHECK по data_type (имя могло сгенерироваться
--      автоматически), затем добавляем именованный.
DO $$
DECLARE c record;
BEGIN
  FOR c IN
    SELECT conname FROM pg_constraint
    WHERE conrelid = 'characteristic_type'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) ILIKE '%data_type%'
  LOOP
    EXECUTE format('ALTER TABLE characteristic_type DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'characteristic_type'::regclass
                   AND conname = 'characteristic_type_data_type_check') THEN
    ALTER TABLE characteristic_type ADD CONSTRAINT characteristic_type_data_type_check
      CHECK (data_type IN ('number', 'text', 'boolean', 'date'));
  END IF;
END $$;

-- 2.2. solution_characteristic: колонка value_date + CHECK «ровно одно value_*».
ALTER TABLE solution_characteristic ADD COLUMN IF NOT EXISTS value_date date;

DO $$
DECLARE c record;
BEGIN
  FOR c IN
    SELECT conname FROM pg_constraint
    WHERE conrelid = 'solution_characteristic'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) ILIKE '%value_numeric%'
  LOOP
    EXECUTE format('ALTER TABLE solution_characteristic DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                 WHERE conrelid = 'solution_characteristic'::regclass
                   AND conname = 'solution_characteristic_one_value_check') THEN
    ALTER TABLE solution_characteristic ADD CONSTRAINT solution_characteristic_one_value_check CHECK (
         (value_numeric IS NOT NULL)::int
       + (value_text    IS NOT NULL)::int
       + (value_bool    IS NOT NULL)::int
       + (value_date    IS NOT NULL)::int = 1);
  END IF;
END $$;

-- 2.3. Перенос данных: значения source_date из value_text -> value_date.
--      В текущем состоянии seed solution_characteristic пуста (ТТХ при импорте
--      NULL, data_model.md п.5.8) — перенос defensive, на случай ручных строк.
UPDATE solution_characteristic sc
SET value_date  = sc.value_text::date,
    value_text  = NULL
WHERE sc.value_text IS NOT NULL
  AND sc.characteristic_type_id IN (SELECT id FROM characteristic_type
                                    WHERE code = 'source_date');

-- 2.4. Метаданные: source_date теперь date.
UPDATE characteristic_type SET data_type = 'date' WHERE code = 'source_date';

-- ============================================================================
-- 3. Объединение подтипов: 4-я пара «Роботизированный 3D принтер»
--     (assumptions.md п.10/п.13)
-- ============================================================================
-- SQL-миграция НЕ требуется: merge-шаг seed (dicts_repo.merge_solution_subtypes)
-- при ближайшем запуске перенаправит solution.solution_subtype_id на
-- канонический подтип «Роботизированный 3D-принтер», удалит записи mapping
-- и сам дубль. Лог: «solution_subtype: объединено X пар, обновлено Y ссылок».


-- ============================================================================
-- 4. Английские коды справочников: industry / process / parameter_type
--    (конвенция assumptions.md п.20, словарь mappers/code_map.py; транслит-слаги
--    slugify отменены). UPDATE по имени — идемпотентно и не зависит от старого
--    значения кода. Тот же переезд выполняет seed (dicts_repo.migrate_dict_codes),
--    если миграция не применена — здесь это закреплено на уровне схемы.
-- ============================================================================

-- industry: 9 значений
UPDATE industry SET code = 'trade_and_services' WHERE name = 'Торговля и услуги';
UPDATE industry SET code = 'manufacturing' WHERE name = 'Промышленность';
UPDATE industry SET code = 'agriculture' WHERE name = 'Сельское хозяйство';
UPDATE industry SET code = 'utilities' WHERE name = 'ЖКХ';
UPDATE industry SET code = 'construction' WHERE name = 'Строительство';
UPDATE industry SET code = 'fuel_and_energy' WHERE name = 'ТЭК';
UPDATE industry SET code = 'security' WHERE name = 'Безопасность';
UPDATE industry SET code = 'transport_and_logistics' WHERE name = 'Транспорт и логистика';
UPDATE industry SET code = 'forestry' WHERE name = 'Лесное хозяйство';

-- process: 95 значений
UPDATE process SET code = 'warehouse_logistics' WHERE name = 'Внутрискладская логистика';
UPDATE process SET code = 'indoor_cleaning' WHERE name = 'Уборка помещений';
UPDATE process SET code = 'cargo_sorting' WHERE name = 'Сортировка грузов';
UPDATE process SET code = 'warehouse_inventory' WHERE name = 'Инвентаризация склада';
UPDATE process SET code = 'last_mile_delivery' WHERE name = 'Доставка последней мили';
UPDATE process SET code = 'sales_floor_administration' WHERE name = 'Администрирование торгового зала';
UPDATE process SET code = 'beverage_preparation' WHERE name = 'Приготовление напитков';
UPDATE process SET code = 'goods_assembly' WHERE name = 'Сборка товаров';
UPDATE process SET code = 'mobile_retail' WHERE name = 'Нестационарная торговля';
UPDATE process SET code = 'ev_charging' WHERE name = 'Зарядка электромобилей';
UPDATE process SET code = 'cargo_delivery' WHERE name = 'Доставка грузов';
UPDATE process SET code = 'goods_manufacturing' WHERE name = 'Производство изделий';
UPDATE process SET code = 'in_plant_logistics' WHERE name = 'Внутрипроизводственная логистика';
UPDATE process SET code = 'billet_stacking' WHERE name = 'Укладка заготовок';
UPDATE process SET code = 'infrastructure_condition_monitoring' WHERE name = 'Мониторинг состояния инфраструктуры';
UPDATE process SET code = 'in_plant_cargo_sorting' WHERE name = 'Сортировка грузов на производстве';
UPDATE process SET code = 'automated_lab_analysis_of_formation_water' WHERE name = 'Автоматизированный лабораторный анализ пластовых вод';
UPDATE process SET code = 'monitoring' WHERE name = 'Мониторинг';
UPDATE process SET code = 'inland_waters_illegal_fishing_monitoring' WHERE name = 'Мониторинг и контроль внутренних водных объектов для выявления незаконного вылова рыбы';
UPDATE process SET code = 'crops_and_fields_condition_monitoring' WHERE name = 'Мониторинг состояния посевов и полей';
UPDATE process SET code = 'crops_and_fields_monitoring_and_analysis' WHERE name = 'Мониторинг и анализ состояния посевов и полей';
UPDATE process SET code = 'poultry_farming' WHERE name = 'Птицеводство';
UPDATE process SET code = 'milking' WHERE name = 'Сбор молока';
UPDATE process SET code = 'plowing' WHERE name = 'Вспашка';
UPDATE process SET code = 'harvesting' WHERE name = 'Сбор урожая';
UPDATE process SET code = 'field_application' WHERE name = 'Внесение веществ на поля';
UPDATE process SET code = 'in_pipe_diagnostics' WHERE name = 'Внутритрубная диагностика';
UPDATE process SET code = 'road_surface_repair' WHERE name = 'Ремонт дорожного покрытия';
UPDATE process SET code = 'street_cleaning' WHERE name = 'Уборка улиц';
UPDATE process SET code = 'road_works' WHERE name = 'Дорожные работы';
UPDATE process SET code = 'facade_washing' WHERE name = 'Мойка фасадов';
UPDATE process SET code = 'heat_network_monitoring' WHERE name = 'Мониторинг теплотрасс';
UPDATE process SET code = 'on_water_operations' WHERE name = 'Проведение работ на воде';
UPDATE process SET code = 'window_washing' WHERE name = 'Мойка окон';
UPDATE process SET code = 'land_use_control' WHERE name = 'Контроль использования земельных участков';
UPDATE process SET code = 'ai_building_defect_detection' WHERE name = 'Поиск дефектов зданий и сооружений при помощи ИИ';
UPDATE process SET code = 'quarry_and_earthworks_photogrammetry_control' WHERE name = 'Контроль карьеров и земляных работ с использованием фотограмметрии';
UPDATE process SET code = 'landfill_compliance_monitoring' WHERE name = 'Мониторинг состояния полигонов ТБО на предмет соответствия нормативным требованиям';
UPDATE process SET code = 'pre_design_survey_photogrammetry_als' WHERE name = 'Предпроектные изыскания с использованием фотограмметрии и воздушного лазерного сканирования';
UPDATE process SET code = 'construction_control_photogrammetry' WHERE name = 'Выполнение контрольных мероприятий на этапе строительно-монтажных работ с использованием фотограмметрии';
UPDATE process SET code = 'aerial_laser_scanning_and_thermal_survey' WHERE name = 'воздушного лазерного сканирования и тепловизионной съемки';
UPDATE process SET code = 'construction' WHERE name = 'Возведение объектов';
UPDATE process SET code = 'cargo_handling' WHERE name = 'Перемещение грузов';
UPDATE process SET code = 'pre_design_survey' WHERE name = 'Предпроектные изыскания';
UPDATE process SET code = 'demolition' WHERE name = 'Демонтаж объектов';
UPDATE process SET code = 'confined_space_monitoring' WHERE name = 'Мониторинг замкнутых пространств';
UPDATE process SET code = 'pipeline_regular_monitoring_and_analysis' WHERE name = 'Регулярный мониторинг трубопроводов и детальный анализ';
UPDATE process SET code = 'power_line_regular_monitoring' WHERE name = 'Регулярный мониторинг ЛЭП';
UPDATE process SET code = 'power_line_detailed_analysis' WHERE name = 'Детальный анализ состояния ЛЭП';
UPDATE process SET code = 'geological_supervision' WHERE name = 'Геологический контроль (надзор)';
UPDATE process SET code = 'mining_stockpiles_dumps_photogrammetry_control' WHERE name = 'Контроль состояния складов и отвалов горнодобывающих компаний с использованием фотограмметрии';
UPDATE process SET code = 'pipe_cleaning_and_repair' WHERE name = 'очистка и ремонт труб';
UPDATE process SET code = 'underwater_operations_and_monitoring' WHERE name = 'Проведение подводных работ и мониторинга';
UPDATE process SET code = 'pipeline_monitoring' WHERE name = 'Мониторинг трубопроводов';
UPDATE process SET code = 'flood_monitoring' WHERE name = 'Мониторинг паводков и затоплений';
UPDATE process SET code = 'geophysical_survey' WHERE name = 'Геофизическая разведка';
UPDATE process SET code = 'emergency_damage_assessment' WHERE name = 'Оценка ущерба от ЧС';
UPDATE process SET code = 'missing_person_search' WHERE name = 'Поиск пропавших людей';
UPDATE process SET code = 'traffic_rules_monitoring' WHERE name = 'Мониторинг ПДД';
UPDATE process SET code = 'accident_investigation' WHERE name = 'Разбор ДТП';
UPDATE process SET code = 'territory_patrol' WHERE name = 'Патрулирование территории';
UPDATE process SET code = 'site_patrol' WHERE name = 'Патрулирование площадных объектов';
UPDATE process SET code = 'biomaterial_delivery' WHERE name = 'Доставка биоматериалов';
UPDATE process SET code = 'medical_assistance' WHERE name = 'Оказание медицинской помощи';
UPDATE process SET code = 'firefighting' WHERE name = 'Тушение пожаров';
UPDATE process SET code = 'emergency_casualty_evacuation' WHERE name = 'Эвакуация пострадавших с места ЧС';
UPDATE process SET code = 'underground_infrastructure_inspection' WHERE name = 'Обследование подземной инфраструктуры';
UPDATE process SET code = 'shallow_water_monitoring' WHERE name = 'Мониторинг мелководья';
UPDATE process SET code = 'monitoring_and_patrol' WHERE name = 'Мониторинг и патрулирование';
UPDATE process SET code = 'monitoring_patrol_cargo_transport' WHERE name = 'Мониторинг, патрулирование, перевозка грузов';
UPDATE process SET code = 'railway_regular_monitoring_and_analysis' WHERE name = 'Регулярный мониторинг состояния железных дорог и детальный анализ';
UPDATE process SET code = 'hard_to_reach_area_cargo_transport' WHERE name = 'Перевозка грузов в труднодоступных районах';
UPDATE process SET code = 'ice_monitoring' WHERE name = 'Ледовый мониторинг';
UPDATE process SET code = 'road_surface_condition_monitoring' WHERE name = 'Мониторинг состояния дорожного покрытия';
UPDATE process SET code = 'enclosed_site_cargo_transport' WHERE name = 'Перевозка грузов на закрытых площадках';
UPDATE process SET code = 'parcel_locker_urban_delivery' WHERE name = 'Городская доставка в постаматы';
UPDATE process SET code = 'transport_infrastructure_monitoring' WHERE name = 'Мониторинг транспортной инфраструктуры';
UPDATE process SET code = 'remote_area_delivery' WHERE name = 'Доставка в удаленные, труднодоступные районы';
UPDATE process SET code = 'public_passenger_transport' WHERE name = 'Общественная перевозка пассажиров';
UPDATE process SET code = 'long_haul_cargo_transport' WHERE name = 'Перевозка грузов - магистральные перевозки';
UPDATE process SET code = 'commercial_passenger_transport' WHERE name = 'Коммерческая перевозка пассажиров';
UPDATE process SET code = 'freight_car_uncoupling' WHERE name = 'Расцепка грузовых вагонов';
UPDATE process SET code = 'railway_infrastructure_and_rolling_stock_maintenance' WHERE name = 'Обслуживание железнодорожной инфраструктуры и вагонов';
UPDATE process SET code = 'heavy_cargo_long_haul_logistics' WHERE name = 'Логистика тяжелых грузов на дальние расстояния';
UPDATE process SET code = 'underwater_cargo_delivery' WHERE name = 'Доставка грузов под водой';
UPDATE process SET code = 'water_cargo_delivery' WHERE name = 'Доставка грузов по воде';
UPDATE process SET code = 'hard_to_reach_region_cargo_delivery' WHERE name = 'Доставка грузов в труднодоступные регионы';
UPDATE process SET code = 'river_crossing_monitoring' WHERE name = 'Мониторинг переправ';
UPDATE process SET code = 'wildlife_population_monitoring_protected_areas' WHERE name = 'Учёт животных и оценка состояния популяций на особо охраняемых природных территориях';
UPDATE process SET code = 'forest_inventory' WHERE name = 'Таксация лесов';
UPDATE process SET code = 'logging_site_inventory' WHERE name = 'Таксация лесосек';
UPDATE process SET code = 'forest_fire_hazard_monitoring' WHERE name = 'Мониторинг пожарной опасности в лесах';
UPDATE process SET code = 'micro_uav_ground_forces_support' WHERE name = 'Информационная поддержка наземных сил с применением БПЛА микрокласса';
UPDATE process SET code = 'forest_pathology_control' WHERE name = 'Контроль лесопатологий';
UPDATE process SET code = 'illegal_logging_control' WHERE name = 'Борьба с незаконными рубками';

-- parameter_type: 132 значения
UPDATE parameter_type SET code = 'total_warehouse_area' WHERE name = 'Общая площадь склада';
UPDATE parameter_type SET code = 'active_zone_area' WHERE name = 'Площадь активной (роботизируемой) зоны';
UPDATE parameter_type SET code = 'storage_zone_ceiling_height' WHERE name = 'Высота потолков в зоне хранения';
UPDATE parameter_type SET code = 'mezzanine_levels' WHERE name = 'Количество этажей (мезонинов)';
UPDATE parameter_type SET code = 'main_aisle_width' WHERE name = 'Ширина главных проездов';
UPDATE parameter_type SET code = 'rack_aisle_width' WHERE name = 'Ширина рабочих проходов между стеллажами';
UPDATE parameter_type SET code = 'floor_covering_type' WHERE name = 'Тип напольного покрытия';
UPDATE parameter_type SET code = 'floor_flatness_deviation' WHERE name = 'Ровность пола (отклонение)';
UPDATE parameter_type SET code = 'shifts_per_day' WHERE name = 'Количество рабочих смен в сутки';
UPDATE parameter_type SET code = 'working_days_per_year' WHERE name = 'Рабочих дней в году';
UPDATE parameter_type SET code = 'shift_duration' WHERE name = 'Продолжительность смены';
UPDATE parameter_type SET code = 'peak_load_factor' WHERE name = 'Пиковый коэффициент нагрузки';
UPDATE parameter_type SET code = 'inbound_pallets_per_day' WHERE name = 'Объём приёмки (поддоны/сутки)';
UPDATE parameter_type SET code = 'outbound_pallets_per_day' WHERE name = 'Объём отгрузки (поддоны/сутки)';
UPDATE parameter_type SET code = 'picking_lines_per_day' WHERE name = 'Объём отбора (строк/сутки, всего)';
UPDATE parameter_type SET code = 'picking_units_per_day' WHERE name = 'Объём отбора (штук/сутки, всего)';
UPDATE parameter_type SET code = 'piece_pick_share' WHERE name = 'Доля мелкоштучного отбора (piece-pick)';
UPDATE parameter_type SET code = 'active_sku_count' WHERE name = 'Количество SKU (активных)';
UPDATE parameter_type SET code = 'a_class_sku_share' WHERE name = 'Доля SKU с быстрым оборотом (A-класс)';
UPDATE parameter_type SET code = 'total_warehouse_staff' WHERE name = 'Общая численность персонала склада';
UPDATE parameter_type SET code = 'pickers_count' WHERE name = 'Из них: отборщики (комплектовщики)';
UPDATE parameter_type SET code = 'forklift_operators_count' WHERE name = 'Из них: операторы погрузчиков';
UPDATE parameter_type SET code = 'packing_line_operators_count' WHERE name = 'Из них: операторы упаковочных линий';
UPDATE parameter_type SET code = 'picker_salary_gross' WHERE name = 'Средняя з/п отборщика (gross)';
UPDATE parameter_type SET code = 'forklift_operator_salary_gross' WHERE name = 'Средняя з/п оператора погрузчика (gross)';
UPDATE parameter_type SET code = 'payroll_insurance_contributions_rate' WHERE name = 'Коэффициент начислений на ФОТ (страховые взносы)';
UPDATE parameter_type SET code = 'picker_throughput_lines_per_hour' WHERE name = 'Средняя выработка отборщика (строк/ч)';
UPDATE parameter_type SET code = 'working_time_loss_factor' WHERE name = 'Коэффициент потерь рабочего времени (отпуск, болезнь, текучесть)';
UPDATE parameter_type SET code = 'picking_route_length_per_line' WHERE name = 'Средняя длина маршрута отборщика на 1 строку';
UPDATE parameter_type SET code = 'conveyor_length' WHERE name = 'Протяжённость конвейерной/транспортной системы';
UPDATE parameter_type SET code = 'racking_type' WHERE name = 'Тип стеллажной системы';
UPDATE parameter_type SET code = 'pallet_positions' WHERE name = 'Количество паллетомест';
UPDATE parameter_type SET code = 'pallet_unit_weight' WHERE name = 'Средняя масса грузовой единицы (паллет)';
UPDATE parameter_type SET code = 'sku_unit_weight' WHERE name = 'Средняя масса штучной единицы (SKU)';
UPDATE parameter_type SET code = 'pallet_dimensions' WHERE name = 'Средние габариты паллеты (Д×Ш×В)';
UPDATE parameter_type SET code = 'sku_dimensions' WHERE name = 'Средние габариты штучной единицы (Д×Ш×В)';
UPDATE parameter_type SET code = 'oversized_cargo_share' WHERE name = 'Доля негабаритных/нестандартных грузов';
UPDATE parameter_type SET code = 'available_power_capacity' WHERE name = 'Мощность электроснабжения (доступная)';
UPDATE parameter_type SET code = 'has_wms' WHERE name = 'Наличие WMS';
UPDATE parameter_type SET code = 'has_erp_1c' WHERE name = 'Наличие ERP/1С';
UPDATE parameter_type SET code = 'robotization_capex_budget' WHERE name = 'Планируемый бюджет на роботизацию (CAPEX)';
UPDATE parameter_type SET code = 'payback_horizon' WHERE name = 'Горизонт расчёта окупаемости';
UPDATE parameter_type SET code = 'terminal_total_area' WHERE name = 'Суммарная площадь терминала (ов)';
UPDATE parameter_type SET code = 'apron_and_technical_area' WHERE name = 'Площадь перрона и технических зон';
UPDATE parameter_type SET code = 'terminal_count' WHERE name = 'Количество терминалов';
UPDATE parameter_type SET code = 'gate_count' WHERE name = 'Количество выходов на посадку (гейтов)';
UPDATE parameter_type SET code = 'runway_count' WHERE name = 'Количество взлётно-посадочных полос';
UPDATE parameter_type SET code = 'annual_passenger_traffic_mln' WHERE name = 'Пассажиропоток (млн пассажиров/год)';
UPDATE parameter_type SET code = 'daily_passengers' WHERE name = 'Среднесуточное количество пассажиров';
UPDATE parameter_type SET code = 'peak_passengers_per_hour' WHERE name = 'Пиковое количество пассажиров в час (PHF)';
UPDATE parameter_type SET code = 'transfer_passenger_share' WHERE name = 'Доля трансферных пассажиров';
UPDATE parameter_type SET code = 'check_in_desk_count' WHERE name = 'Количество стоек регистрации';
UPDATE parameter_type SET code = 'daily_flights' WHERE name = 'Среднесуточное количество рейсов (взлёт+посадка)';
UPDATE parameter_type SET code = 'peak_flights_per_hour' WHERE name = 'Пиковое количество рейсов в час';
UPDATE parameter_type SET code = 'aircraft_turnaround_time' WHERE name = 'Среднее время оборота воздушного судна (TAT)';
UPDATE parameter_type SET code = 'ground_handling_ops_per_flight' WHERE name = 'Среднее количество операций наземного обслуживания на 1 рейс';
UPDATE parameter_type SET code = 'baggage_units_per_day' WHERE name = 'Объём перемещения багажа (единиц/сутки)';
UPDATE parameter_type SET code = 'avg_baggage_weight' WHERE name = 'Средняя масса единицы багажа';
UPDATE parameter_type SET code = 'baggage_carousel_count' WHERE name = 'Количество стоек выдачи багажа (каруселей)';
UPDATE parameter_type SET code = 'catering_portions_per_day' WHERE name = 'Объём бортового питания (порций/сутки)';
UPDATE parameter_type SET code = 'refueling_flights_per_day' WHERE name = 'Объём заправки воздушных судов (рейсов/сут)';
UPDATE parameter_type SET code = 'internal_cart_trips_per_day' WHERE name = 'Суточное количество рейсов внутренних грузовых тележек (внутри терминала)';
UPDATE parameter_type SET code = 'cleaning_machine_count' WHERE name = 'Количество уборочных машин (терминал)';
UPDATE parameter_type SET code = 'robotic_cleaning_area' WHERE name = 'Площадь, убираемая роботизированной уборкой';
UPDATE parameter_type SET code = 'waste_containers_per_day' WHERE name = 'Суточный объём вывоза мусора (контейнеров)';
UPDATE parameter_type SET code = 'ramp_staff_count' WHERE name = 'Численность персонала наземного обслуживания (рамп)';
UPDATE parameter_type SET code = 'terminal_logistics_staff_count' WHERE name = 'Численность персонала внутри терминала (логистика, уборка)';
UPDATE parameter_type SET code = 'ramp_staff_salary_gross' WHERE name = 'Средняя з/п сотрудника наземного обслуживания (gross)';
UPDATE parameter_type SET code = 'terminal_cleaner_salary_gross' WHERE name = 'Средняя з/п уборщика терминала (gross)';
UPDATE parameter_type SET code = 'payroll_tax_rate' WHERE name = 'Коэффициент начислений на ФОТ';
UPDATE parameter_type SET code = 'terminal_staff_annual_turnover' WHERE name = 'Годовая текучесть (персонал терминала)';
UPDATE parameter_type SET code = 'restricted_zone_count' WHERE name = 'Зонирование (количество режимных зон)';
UPDATE parameter_type SET code = 'has_access_control' WHERE name = 'Наличие системы контроля доступа (СКУД)';
UPDATE parameter_type SET code = 'airside_equipment_certification' WHERE name = 'Требования по сертификации оборудования для airside';
UPDATE parameter_type SET code = 'noise_level_limit_zone' WHERE name = 'Ограничения по уровню шума (зона)';
UPDATE parameter_type SET code = 'unheated_zone_temperature' WHERE name = 'Температура в неотапливаемых зонах (перрон, зима)';
UPDATE parameter_type SET code = 'has_fids_aodb' WHERE name = 'Наличие FIDS/AODB системы';
UPDATE parameter_type SET code = 'has_bms' WHERE name = 'Наличие BMS (системы управления зданием)';
UPDATE parameter_type SET code = 'charging_infrastructure_power' WHERE name = 'Доступная мощность для зарядной инфраструктуры';
UPDATE parameter_type SET code = 'medical_facility_type' WHERE name = 'Тип медицинского учреждения';
UPDATE parameter_type SET code = 'building_total_area' WHERE name = 'Общая площадь здания(й)';
UPDATE parameter_type SET code = 'main_building_floor_count' WHERE name = 'Количество этажей (основной корпус)';
UPDATE parameter_type SET code = 'elevator_count' WHERE name = 'Количество лифтов (грузовых/медицинских)';
UPDATE parameter_type SET code = 'inpatient_bed_count' WHERE name = 'Количество коек (стационар)';
UPDATE parameter_type SET code = 'bed_occupancy_rate' WHERE name = 'Коечный фонд в эксплуатации (средняя занятость)';
UPDATE parameter_type SET code = 'operating_room_count' WHERE name = 'Количество операционных';
UPDATE parameter_type SET code = 'daily_outpatient_visits' WHERE name = 'Количество амбулаторных посещений в сутки';
UPDATE parameter_type SET code = 'inpatient_operation_mode' WHERE name = 'Режим работы стационара';
UPDATE parameter_type SET code = 'outpatient_operation_mode' WHERE name = 'Режим работы амбулатории';
UPDATE parameter_type SET code = 'nursing_shifts_per_day' WHERE name = 'Количество смен медперсонала (уход за пациентами)';
UPDATE parameter_type SET code = 'peak_logistics_time' WHERE name = 'Пиковое время логистической нагрузки';
UPDATE parameter_type SET code = 'meals_per_day' WHERE name = 'Количество кормлений в сутки';
UPDATE parameter_type SET code = 'meal_portions_per_day' WHERE name = 'Общее количество порций питания в сутки';
UPDATE parameter_type SET code = 'kitchen_to_ward_distance' WHERE name = 'Среднее расстояние от пищеблока до отделения';
UPDATE parameter_type SET code = 'meal_distribution_point_count' WHERE name = 'Количество точек раздачи питания (отделений)';
UPDATE parameter_type SET code = 'meal_trolley_weight_gross' WHERE name = 'Средняя масса тележки с питанием (брутто)';
UPDATE parameter_type SET code = 'meal_delivery_time_norm' WHERE name = 'Норматив доставки питания (мин от пищеблока до отделения)';
UPDATE parameter_type SET code = 'dirty_linen_kg_per_day' WHERE name = 'Объём грязного белья (кг/сутки)';
UPDATE parameter_type SET code = 'clean_linen_kg_per_day' WHERE name = 'Объём чистого белья на раздачу (кг/сутки)';
UPDATE parameter_type SET code = 'linen_collection_point_count' WHERE name = 'Количество точек сбора/выдачи белья';
UPDATE parameter_type SET code = 'linen_change_frequency' WHERE name = 'Периодичность смены белья (раз в сутки, в среднем)';
UPDATE parameter_type SET code = 'linen_container_weight' WHERE name = 'Средняя масса контейнера с бельём';
UPDATE parameter_type SET code = 'medication_name_count' WHERE name = 'Количество наименований медикаментов в обращении';
UPDATE parameter_type SET code = 'medication_request_count_per_day' WHERE name = 'Объём выдачи медикаментов (заявок/сутки)';
UPDATE parameter_type SET code = 'pharmacy_dispensing_point_count' WHERE name = 'Количество аптечных точек выдачи (аптека, аптечные склады)';
UPDATE parameter_type SET code = 'medication_delivery_point_count' WHERE name = 'Количество точек доставки (отделений + ОР + реанимация)';
UPDATE parameter_type SET code = 'pharmacy_request_fulfilment_time' WHERE name = 'Среднее время комплектации 1 заявки в аптеке';
UPDATE parameter_type SET code = 'stat_delivery_share' WHERE name = 'Доля срочных (STAT) доставок медикаментов';
UPDATE parameter_type SET code = 'consumables_delivery_trips_per_day' WHERE name = 'Объём доставки расходных материалов (рейсов/сутки)';
UPDATE parameter_type SET code = 'daily_biomaterial_sample_count' WHERE name = 'Количество биоматериалов (проб) в сутки';
UPDATE parameter_type SET code = 'clinical_lab_count' WHERE name = 'Количество клинико-диагностических лабораторий (КДЛ)';
UPDATE parameter_type SET code = 'sample_delivery_time_norm' WHERE name = 'Среднее время доставки пробы (норматив)';
UPDATE parameter_type SET code = 'lab_result_delivery_trips_per_day' WHERE name = 'Объём выдачи результатов анализов (рейсов/сутки)';
UPDATE parameter_type SET code = 'medical_waste_class_a_kg_per_day' WHERE name = 'Объём медицинских отходов класса А (ненасыщенные)';
UPDATE parameter_type SET code = 'medical_waste_class_b_kg_per_day' WHERE name = 'Объём медицинских отходов класса Б (инфицированные)';
UPDATE parameter_type SET code = 'waste_collection_point_count' WHERE name = 'Количество точек сбора отходов';
UPDATE parameter_type SET code = 'waste_removal_frequency' WHERE name = 'Периодичность вывоза отходов из отделений';
UPDATE parameter_type SET code = 'sanitary_staff_count' WHERE name = 'Численность санитаров и транспортировщиков';
UPDATE parameter_type SET code = 'kitchen_staff_count' WHERE name = 'Численность сотрудников пищеблока (раздача)';
UPDATE parameter_type SET code = 'laundry_transport_staff_count' WHERE name = 'Численность сотрудников прачечной (транспорт белья)';
UPDATE parameter_type SET code = 'sanitary_staff_salary_gross' WHERE name = 'Средняя з/п санитара/транспортировщика (gross)';
UPDATE parameter_type SET code = 'kitchen_staff_salary_gross' WHERE name = 'Средняя з/п сотрудника пищеблока (gross)';
UPDATE parameter_type SET code = 'non_medical_staff_annual_turnover' WHERE name = 'Годовая текучесть (немедицинский персонал)';
UPDATE parameter_type SET code = 'robot_disinfection_between_trips' WHERE name = 'Обеззараживание робота между рейсами';
UPDATE parameter_type SET code = 'ward_noise_limit_night' WHERE name = 'Требования к уровню шума в палатах (ночное время)';
UPDATE parameter_type SET code = 'has_zoned_access_control' WHERE name = 'Наличие СКУД (контроль доступа по зонам)';
UPDATE parameter_type SET code = 'robot_surface_material_requirements' WHERE name = 'Требования к материалу поверхностей робота';
UPDATE parameter_type SET code = 'has_mis' WHERE name = 'Наличие МИС (медицинская информационная система)';
UPDATE parameter_type SET code = 'has_lis' WHERE name = 'Наличие ЛИС (лабораторная информационная система)';
UPDATE parameter_type SET code = 'has_elevator_management_system' WHERE name = 'Наличие системы управления лифтами (BMS)';
UPDATE parameter_type SET code = 'main_corridor_width' WHERE name = 'Ширина коридоров (основных)';
UPDATE parameter_type SET code = 'has_ramps_or_lifts_for_amr' WHERE name = 'Наличие пандусов/подъёмников (для межэтажного AMR без лифта)';

-- ============================================================================
-- 5. Колонка code у region / solution_type / solution_subtype
--    (конвенция английских кодов — data_model.md п.4)
-- ============================================================================
ALTER TABLE region ADD COLUMN IF NOT EXISTS code text;
ALTER TABLE solution_type ADD COLUMN IF NOT EXISTS code text;
ALTER TABLE solution_subtype ADD COLUMN IF NOT EXISTS code text;

-- region: 24 значения
UPDATE region SET code = 'moscow' WHERE name = 'Москва';
UPDATE region SET code = 'saint_petersburg' WHERE name = 'Санкт-Петербург';
UPDATE region SET code = 'yaroslavl_region' WHERE name = 'Ярославская область';
UPDATE region SET code = 'rostov_region' WHERE name = 'Ростовская область';
UPDATE region SET code = 'tatarstan_republic' WHERE name = 'Республика Татарстан';
UPDATE region SET code = 'moscow_region' WHERE name = 'Московская область';
UPDATE region SET code = 'perm_krai' WHERE name = 'Пермский край';
UPDATE region SET code = 'ryazan_region' WHERE name = 'Рязанская область';
UPDATE region SET code = 'penza_region' WHERE name = 'Пензенская область';
UPDATE region SET code = 'udmurt_republic' WHERE name = 'Удмуртская Республика';
UPDATE region SET code = 'nizhny_novgorod_region' WHERE name = 'Нижегородская область';
UPDATE region SET code = 'chuvash_republic' WHERE name = 'Чувашская Республика';
UPDATE region SET code = 'novosibirsk_region' WHERE name = 'Новосибирская область';
UPDATE region SET code = 'chelyabinsk_region' WHERE name = 'Челябинская область';
UPDATE region SET code = 'krasnodar_krai' WHERE name = 'Краснодарский край';
UPDATE region SET code = 'bryansk_region' WHERE name = 'Брянская область';
UPDATE region SET code = 'sevastopol' WHERE name = 'Севастополь';
UPDATE region SET code = 'omsk_region' WHERE name = 'Омская область';
UPDATE region SET code = 'krasnoyarsk_krai' WHERE name = 'Красноярский край';
UPDATE region SET code = 'sakhalin_region' WHERE name = 'Сахалинская область';
UPDATE region SET code = 'kaluga_region' WHERE name = 'Калужская обл';
UPDATE region SET code = 'sverdlovsk_region' WHERE name = 'Свердловская область';
UPDATE region SET code = 'leningrad_region' WHERE name = 'Ленинградская область';
UPDATE region SET code = 'tomsk_region' WHERE name = 'Томская область';

-- solution_type: 11 значений
UPDATE solution_type SET code = 'mobile_robots' WHERE name = 'Мобильные роботы';
UPDATE solution_type SET code = 'manipulator_robots' WHERE name = 'Роботы-манипуляторы';
UPDATE solution_type SET code = 'stationary_robotic_systems' WHERE name = 'Стационарные роботизированные системы';
UPDATE solution_type SET code = 'humanoid_robots' WHERE name = 'Антропоморфные роботы';
UPDATE solution_type SET code = 'autonomous_ground_vehicles' WHERE name = 'Автономные наземные транспортные средства';
UPDATE solution_type SET code = 'marine_robots' WHERE name = 'Морские роботы';
UPDATE solution_type SET code = 'uas' WHERE name = 'БАС';
UPDATE solution_type SET code = 'software' WHERE name = 'ПО';
UPDATE solution_type SET code = 'brs_software' WHERE name = 'ПО БРС';
UPDATE solution_type SET code = 'other' WHERE name = 'Другое';
UPDATE solution_type SET code = 'mobile_manipulators' WHERE name = 'Мобильные манипуляторы';

-- solution_subtype: 71 значение
UPDATE solution_subtype SET code = 'amr' WHERE name = 'AMR';
UPDATE solution_subtype SET code = 'fmr' WHERE name = 'FMR';
UPDATE solution_subtype SET code = 'stacker_robot' WHERE name = 'Робот-штабелер';
UPDATE solution_subtype SET code = 'unmanned_tow_tractor' WHERE name = 'Беспилотный тягач';
UPDATE solution_subtype SET code = 'smart_storage_system' WHERE name = 'Умная система хранения';
UPDATE solution_subtype SET code = 'unmanned_forklift' WHERE name = 'Беспилотный погрузчик';
UPDATE solution_subtype SET code = 'cleaning_robot' WHERE name = 'Робот-уборщик';
UPDATE solution_subtype SET code = 'manipulator_robot' WHERE name = 'Робот-манипулятор';
UPDATE solution_subtype SET code = 'stacker_crane' WHERE name = 'Кран-штабелёр';
UPDATE solution_subtype SET code = 'shuttle' WHERE name = 'Шаттл';
UPDATE solution_subtype SET code = 'mobile_picking_robot' WHERE name = 'Мобильный робот-комплектовщик';
UPDATE solution_subtype SET code = 'manipulator' WHERE name = 'Манипулятор';
UPDATE solution_subtype SET code = 'goods_sorting_software' WHERE name = 'ПО для сортировки товаров';
UPDATE solution_subtype SET code = 'inventory_robot' WHERE name = 'Робот-инвентаризатор';
UPDATE solution_subtype SET code = 'rover_robot' WHERE name = 'Робот-ровер';
UPDATE solution_subtype SET code = 'humanoid_robot' WHERE name = 'Антропоморфный робот';
UPDATE solution_subtype SET code = 'robo_cafe' WHERE name = 'Робо-кафе';
UPDATE solution_subtype SET code = 'smart_bus_vending_machine' WHERE name = 'Интеллектуальный автобус - вендинговый аппарат';
UPDATE solution_subtype SET code = 'courier_robot' WHERE name = 'Робот-курьер';
UPDATE solution_subtype SET code = 'collaborative_manipulator_robot' WHERE name = 'Коллаборативный робот-манипулятор';
UPDATE solution_subtype SET code = 'welding_robot' WHERE name = 'Сварочный робот';
UPDATE solution_subtype SET code = 'robotic_unit' WHERE name = 'Роботизированная установка';
UPDATE solution_subtype SET code = 'robotic_machine_cell' WHERE name = 'Роботизированная пристаночная ячейка';
UPDATE solution_subtype SET code = 'delta_robot' WHERE name = 'Дельта-робот';
UPDATE solution_subtype SET code = 'robotic_cart' WHERE name = 'Роботизированная тележка';
UPDATE solution_subtype SET code = 'stacking_robot' WHERE name = 'Робот-укладчик';
UPDATE solution_subtype SET code = 'spider_robot' WHERE name = 'Робот-паук';
UPDATE solution_subtype SET code = 'sorting_robot' WHERE name = 'Робот-сортировщик';
UPDATE solution_subtype SET code = 'fixed_wing_uas' WHERE name = 'Самолет';
UPDATE solution_subtype SET code = 'automated_vaccination_conveyor' WHERE name = 'Конвейер автоматизированной вакцинации';
UPDATE solution_subtype SET code = 'wheeled_robot' WHERE name = 'Колесный робот';
UPDATE solution_subtype SET code = 'robotic_milking_unit' WHERE name = 'Роботизированная доильная установка';
UPDATE solution_subtype SET code = 'unmanned_tractor' WHERE name = 'Беспилотный трактор';
UPDATE solution_subtype SET code = 'agrobot' WHERE name = 'Агробот';
UPDATE solution_subtype SET code = 'fruit_picking_robot' WHERE name = 'Робот для сбора плодов';
UPDATE solution_subtype SET code = 'farmland_analysis_robot' WHERE name = 'Робот для анализа сельхозземель';
UPDATE solution_subtype SET code = 'multicopter' WHERE name = 'Мультиротор';
UPDATE solution_subtype SET code = 'high_mobility_mobile_robot_system' WHERE name = 'Мобильный робототехнический комплекс высокой проходимости';
UPDATE solution_subtype SET code = 'in_pipe_robotic_systems' WHERE name = 'Внутритрубные роботизированные системы';
UPDATE solution_subtype SET code = 'heat_network_diagnostics_robots' WHERE name = 'Роботы для диагностики теплосетей';
UPDATE solution_subtype SET code = 'robotic_pipeline_thermal_inspection' WHERE name = 'Роботизированная теплоинспекция трубопроводов';
UPDATE solution_subtype SET code = 'unmanned_bulldozer' WHERE name = 'Беспилотный бульдозер';
UPDATE solution_subtype SET code = 'unmanned_road_roller' WHERE name = 'Беспилотный каток';
UPDATE solution_subtype SET code = 'unmanned_asphalt_paver' WHERE name = 'Беспилотный асфальтоукладчик';
UPDATE solution_subtype SET code = 'unmanned_service_catamaran' WHERE name = 'Беспилотный сервисный катамаран';
UPDATE solution_subtype SET code = 'tethered_octocopter_robot' WHERE name = 'Привязной робот на базе октокоптера';
UPDATE solution_subtype SET code = 'robotic_3d_printer' WHERE name = 'Роботизированный 3D-принтер';
UPDATE solution_subtype SET code = 'robotic_loading_crane' WHERE name = 'Роботизированные погрузочные краны';
UPDATE solution_subtype SET code = 'robotic_welding_complex' WHERE name = 'Роботизированный сварочный комплекс';
UPDATE solution_subtype SET code = 'multicopter_uas' WHERE name = 'БАС мультироторного типа';
UPDATE solution_subtype SET code = 'demolition_robot' WHERE name = 'Демонтажный робот';
UPDATE solution_subtype SET code = 'rov' WHERE name = 'ТНПА';
UPDATE solution_subtype SET code = 'modular_surface_platform' WHERE name = 'Модульная надводная многофункциональная платформа';
UPDATE solution_subtype SET code = 'multifunctional_unmanned_boat' WHERE name = 'Многофункциональный бэзэкипажный катер';
UPDATE solution_subtype SET code = 'unmanned_catamaran' WHERE name = 'Беспилотный катамаран';
UPDATE solution_subtype SET code = 'security_robot' WHERE name = 'Охранный робот';
UPDATE solution_subtype SET code = 'robotic_fire_monitor' WHERE name = 'Роботизированный лафетный ствол';
UPDATE solution_subtype SET code = 'unmanned_boat' WHERE name = 'Беспилотный катер';
UPDATE solution_subtype SET code = 'crewless_boat' WHERE name = 'Безэкипажный катер';
UPDATE solution_subtype SET code = 'unmanned_truck' WHERE name = 'Беспилотный грузовик';
UPDATE solution_subtype SET code = 'universal_autonomous_robot_platform' WHERE name = 'Универсальная автономная роботизированная платформа';
UPDATE solution_subtype SET code = 'unmanned_tram' WHERE name = 'Беспилотный трамвай';
UPDATE solution_subtype SET code = 'unmanned_metro' WHERE name = 'Беспилотное метро';
UPDATE solution_subtype SET code = 'robotaxi' WHERE name = 'Беспилотное такси';
UPDATE solution_subtype SET code = 'uncoupling_robot' WHERE name = 'Робот-расцепщик';
UPDATE solution_subtype SET code = 'robot' WHERE name = 'Робот';
UPDATE solution_subtype SET code = 'rover' WHERE name = 'Ровер';
UPDATE solution_subtype SET code = 'vtol' WHERE name = 'VTOL';
UPDATE solution_subtype SET code = 'tug_robot' WHERE name = 'Робот-тягач';
UPDATE solution_subtype SET code = 'mobile_manipulator' WHERE name = 'Мобильный манипулятор';
UPDATE solution_subtype SET code = 'smart_bus' WHERE name = 'Интеллектуальный автобус';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM region WHERE code IS NULL)
  OR EXISTS (SELECT 1 FROM solution_type WHERE code IS NULL)
  OR EXISTS (SELECT 1 FROM solution_subtype WHERE code IS NULL) THEN
    RAISE EXCEPTION 'region/solution_type/solution_subtype: есть строки без кода — пополнить code_map.py и миграцию';
  END IF;
END $$;

ALTER TABLE region ALTER COLUMN code SET NOT NULL;
ALTER TABLE solution_type ALTER COLUMN code SET NOT NULL;
ALTER TABLE solution_subtype ALTER COLUMN code SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_region_code ON region(code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_solution_type_code ON solution_type(code);
CREATE UNIQUE INDEX IF NOT EXISTS uq_solution_subtype_code ON solution_subtype(code);

-- ============================================================================
-- 6. Индексы solution: status (фильтрация по статусу) и GIN pg_trgm по name
--    (data_model.md п.2.1; в schema.psql создаются с самого начала)
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_solution_status ON solution(status);
CREATE INDEX IF NOT EXISTS idx_solution_name_trgm ON solution USING GIN (name gin_trgm_ops);

-- ============================================================================
-- 7. Фиксированные и производные параметры (задача A1, миграция V4 backend;
--    в schema.psql колонки есть с самого начала). Легаси-БД без колонок
--    падают на upsert object_type_parameter (seed пишет is_fixed/is_derived).
-- ============================================================================
ALTER TABLE object_type_parameter
    ADD COLUMN IF NOT EXISTS is_fixed boolean NOT NULL DEFAULT false;
ALTER TABLE object_type_parameter
    ADD COLUMN IF NOT EXISTS is_derived boolean NOT NULL DEFAULT false;

COMMIT;
