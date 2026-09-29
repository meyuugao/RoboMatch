"""Явные английские коды справочников (конвенция - assumptions.md §15/§20).

Заменяют транслит-слаги (slugify) во всех справочниках с колонкой code:
industry, process, parameter_type, solution_type, solution_subtype, region.
Словарь строится по русскому названию;lookup - по точному имени, затем по
canonical_key; отсутствие записи - предупреждение УТОЧНИТЬ + транслит-фолбэк
(как в OBJECT_TYPE_CODE для object_type).

Полнота покрытия и уникальность кодов проверяются тестами
(test_code_convention.py) и генератором scripts/gen_code_map.py
(вне репозитория хакатона).
"""
from normalizers.strings import canonical_key, slugify

# --- industry - 9 значений (CSV «Отрасль») ---
INDUSTRY_CODE = {
    "Торговля и услуги": "trade_and_services",
    "Промышленность": "manufacturing",
    "Сельское хозяйство": "agriculture",
    "ЖКХ": "utilities",
    "Строительство": "construction",
    "ТЭК": "fuel_and_energy",
    "Безопасность": "security",
    "Транспорт и логистика": "transport_and_logistics",
    "Лесное хозяйство": "forestry",
}

# --- process - 95 значений (CSV «Сценарий» после разреза; вкл. 2 фрагмента длинного значения) ---
PROCESS_CODE = {
    "Внутрискладская логистика": "warehouse_logistics",
    "Уборка помещений": "indoor_cleaning",
    "Сортировка грузов": "cargo_sorting",
    "Инвентаризация склада": "warehouse_inventory",
    "Доставка последней мили": "last_mile_delivery",
    "Администрирование торгового зала": "sales_floor_administration",
    "Приготовление напитков": "beverage_preparation",
    "Сборка товаров": "goods_assembly",
    "Нестационарная торговля": "mobile_retail",
    "Зарядка электромобилей": "ev_charging",
    "Доставка грузов": "cargo_delivery",
    "Производство изделий": "goods_manufacturing",
    "Внутрипроизводственная логистика": "in_plant_logistics",
    "Укладка заготовок": "billet_stacking",
    "Мониторинг состояния инфраструктуры": "infrastructure_condition_monitoring",
    "Сортировка грузов на производстве": "in_plant_cargo_sorting",
    "Автоматизированный лабораторный анализ пластовых вод": "automated_lab_analysis_of_formation_water",
    "Мониторинг": "monitoring",
    "Мониторинг и контроль внутренних водных объектов для выявления незаконного вылова рыбы": "inland_waters_illegal_fishing_monitoring",
    "Мониторинг состояния посевов и полей": "crops_and_fields_condition_monitoring",
    "Мониторинг и анализ состояния посевов и полей": "crops_and_fields_monitoring_and_analysis",
    "Птицеводство": "poultry_farming",
    "Сбор молока": "milking",
    "Вспашка": "plowing",
    "Сбор урожая": "harvesting",
    "Внесение веществ на поля": "field_application",
    "Внутритрубная диагностика": "in_pipe_diagnostics",
    "Ремонт дорожного покрытия": "road_surface_repair",
    "Уборка улиц": "street_cleaning",
    "Дорожные работы": "road_works",
    "Мойка фасадов": "facade_washing",
    "Мониторинг теплотрасс": "heat_network_monitoring",
    "Проведение работ на воде": "on_water_operations",
    "Мойка окон": "window_washing",
    "Контроль использования земельных участков": "land_use_control",
    "Поиск дефектов зданий и сооружений при помощи ИИ": "ai_building_defect_detection",
    "Контроль карьеров и земляных работ с использованием фотограмметрии": "quarry_and_earthworks_photogrammetry_control",
    "Мониторинг состояния полигонов ТБО на предмет соответствия нормативным требованиям": "landfill_compliance_monitoring",
    "Предпроектные изыскания с использованием фотограмметрии и воздушного лазерного сканирования": "pre_design_survey_photogrammetry_als",
    "Выполнение контрольных мероприятий на этапе строительно-монтажных работ с использованием фотограмметрии": "construction_control_photogrammetry",
    "воздушного лазерного сканирования и тепловизионной съемки": "aerial_laser_scanning_and_thermal_survey",
    "Возведение объектов": "construction",
    "Перемещение грузов": "cargo_handling",
    "Предпроектные изыскания": "pre_design_survey",
    "Демонтаж объектов": "demolition",
    "Мониторинг замкнутых пространств": "confined_space_monitoring",
    "Регулярный мониторинг трубопроводов и детальный анализ": "pipeline_regular_monitoring_and_analysis",
    "Регулярный мониторинг ЛЭП": "power_line_regular_monitoring",
    "Детальный анализ состояния ЛЭП": "power_line_detailed_analysis",
    "Геологический контроль (надзор)": "geological_supervision",
    "Контроль состояния складов и отвалов горнодобывающих компаний с использованием фотограмметрии": "mining_stockpiles_dumps_photogrammetry_control",
    "очистка и ремонт труб": "pipe_cleaning_and_repair",
    "Проведение подводных работ и мониторинга": "underwater_operations_and_monitoring",
    "Мониторинг трубопроводов": "pipeline_monitoring",
    "Мониторинг паводков и затоплений": "flood_monitoring",
    "Геофизическая разведка": "geophysical_survey",
    "Оценка ущерба от ЧС": "emergency_damage_assessment",
    "Поиск пропавших людей": "missing_person_search",
    "Мониторинг ПДД": "traffic_rules_monitoring",
    "Разбор ДТП": "accident_investigation",
    "Патрулирование территории": "territory_patrol",
    "Патрулирование площадных объектов": "site_patrol",
    "Доставка биоматериалов": "biomaterial_delivery",
    "Оказание медицинской помощи": "medical_assistance",
    "Тушение пожаров": "firefighting",
    "Эвакуация пострадавших с места ЧС": "emergency_casualty_evacuation",
    "Обследование подземной инфраструктуры": "underground_infrastructure_inspection",
    "Мониторинг мелководья": "shallow_water_monitoring",
    "Мониторинг и патрулирование": "monitoring_and_patrol",
    "Мониторинг, патрулирование, перевозка грузов": "monitoring_patrol_cargo_transport",
    "Регулярный мониторинг состояния железных дорог и детальный анализ": "railway_regular_monitoring_and_analysis",
    "Перевозка грузов в труднодоступных районах": "hard_to_reach_area_cargo_transport",
    "Ледовый мониторинг": "ice_monitoring",
    "Мониторинг состояния дорожного покрытия": "road_surface_condition_monitoring",
    "Перевозка грузов на закрытых площадках": "enclosed_site_cargo_transport",
    "Городская доставка в постаматы": "parcel_locker_urban_delivery",
    "Мониторинг транспортной инфраструктуры": "transport_infrastructure_monitoring",
    "Доставка в удаленные, труднодоступные районы": "remote_area_delivery",
    "Общественная перевозка пассажиров": "public_passenger_transport",
    "Перевозка грузов - магистральные перевозки": "long_haul_cargo_transport",
    "Коммерческая перевозка пассажиров": "commercial_passenger_transport",
    "Расцепка грузовых вагонов": "freight_car_uncoupling",
    "Обслуживание железнодорожной инфраструктуры и вагонов": "railway_infrastructure_and_rolling_stock_maintenance",
    "Логистика тяжелых грузов на дальние расстояния": "heavy_cargo_long_haul_logistics",
    "Доставка грузов под водой": "underwater_cargo_delivery",
    "Доставка грузов по воде": "water_cargo_delivery",
    "Доставка грузов в труднодоступные регионы": "hard_to_reach_region_cargo_delivery",
    "Мониторинг переправ": "river_crossing_monitoring",
    "Учёт животных и оценка состояния популяций на особо охраняемых природных территориях": "wildlife_population_monitoring_protected_areas",
    "Таксация лесов": "forest_inventory",
    "Таксация лесосек": "logging_site_inventory",
    "Мониторинг пожарной опасности в лесах": "forest_fire_hazard_monitoring",
    "Информационная поддержка наземных сил с применением БПЛА микрокласса": "micro_uav_ground_forces_support",
    "Контроль лесопатологий": "forest_pathology_control",
    "Борьба с незаконными рубками": "illegal_logging_control",
}

# --- parameter_type - 132 значения (XLSX, дедуп по имени) ---
PARAMETER_TYPE_CODE = {
    # --- склад ---
    "Общая площадь склада": "total_warehouse_area",
    "Площадь активной (роботизируемой) зоны": "active_zone_area",
    "Высота потолков в зоне хранения": "storage_zone_ceiling_height",
    "Количество этажей (мезонинов)": "mezzanine_levels",
    "Ширина главных проездов": "main_aisle_width",
    "Ширина рабочих проходов между стеллажами": "rack_aisle_width",
    "Тип напольного покрытия": "floor_covering_type",
    "Ровность пола (отклонение)": "floor_flatness_deviation",
    "Количество рабочих смен в сутки": "shifts_per_day",
    "Рабочих дней в году": "working_days_per_year",
    "Продолжительность смены": "shift_duration",
    "Пиковый коэффициент нагрузки": "peak_load_factor",
    "Объём приёмки (поддоны/сутки)": "inbound_pallets_per_day",
    "Объём отгрузки (поддоны/сутки)": "outbound_pallets_per_day",
    "Объём отбора (строк/сутки, всего)": "picking_lines_per_day",
    "Объём отбора (штук/сутки, всего)": "picking_units_per_day",
    "Доля мелкоштучного отбора (piece-pick)": "piece_pick_share",
    "Количество SKU (активных)": "active_sku_count",
    "Доля SKU с быстрым оборотом (A-класс)": "a_class_sku_share",
    "Общая численность персонала склада": "total_warehouse_staff",
    "Из них: отборщики (комплектовщики)": "pickers_count",
    "Из них: операторы погрузчиков": "forklift_operators_count",
    "Из них: операторы упаковочных линий": "packing_line_operators_count",
    "Средняя з/п отборщика (gross)": "picker_salary_gross",
    "Средняя з/п оператора погрузчика (gross)": "forklift_operator_salary_gross",
    "Коэффициент начислений на ФОТ (страховые взносы)": "payroll_insurance_contributions_rate",
    "Средняя выработка отборщика (строк/ч)": "picker_throughput_lines_per_hour",
    "Коэффициент потерь рабочего времени (отпуск, болезнь, текучесть)": "working_time_loss_factor",
    "Средняя длина маршрута отборщика на 1 строку": "picking_route_length_per_line",
    "Протяжённость конвейерной/транспортной системы": "conveyor_length",
    "Тип стеллажной системы": "racking_type",
    "Количество паллетомест": "pallet_positions",
    "Средняя масса грузовой единицы (паллет)": "pallet_unit_weight",
    "Средняя масса штучной единицы (SKU)": "sku_unit_weight",
    "Средние габариты паллеты (Д×Ш×В)": "pallet_dimensions",
    "Средние габариты штучной единицы (Д×Ш×В)": "sku_dimensions",
    "Доля негабаритных/нестандартных грузов": "oversized_cargo_share",
    "Мощность электроснабжения (доступная)": "available_power_capacity",
    "Наличие WMS": "has_wms",
    "Наличие ERP/1С": "has_erp_1c",
    "Планируемый бюджет на роботизацию (CAPEX)": "robotization_capex_budget",
    "Горизонт расчёта окупаемости": "payback_horizon",
    # --- аэропорт ---
    "Суммарная площадь терминала (ов)": "terminal_total_area",
    "Площадь перрона и технических зон": "apron_and_technical_area",
    "Количество терминалов": "terminal_count",
    "Количество выходов на посадку (гейтов)": "gate_count",
    "Количество взлётно-посадочных полос": "runway_count",
    "Пассажиропоток (млн пассажиров/год)": "annual_passenger_traffic_mln",
    "Среднесуточное количество пассажиров": "daily_passengers",
    "Пиковое количество пассажиров в час (PHF)": "peak_passengers_per_hour",
    "Доля трансферных пассажиров": "transfer_passenger_share",
    "Количество стоек регистрации": "check_in_desk_count",
    "Среднесуточное количество рейсов (взлёт+посадка)": "daily_flights",
    "Пиковое количество рейсов в час": "peak_flights_per_hour",
    "Среднее время оборота воздушного судна (TAT)": "aircraft_turnaround_time",
    "Среднее количество операций наземного обслуживания на 1 рейс": "ground_handling_ops_per_flight",
    "Объём перемещения багажа (единиц/сутки)": "baggage_units_per_day",
    "Средняя масса единицы багажа": "avg_baggage_weight",
    "Количество стоек выдачи багажа (каруселей)": "baggage_carousel_count",
    "Объём бортового питания (порций/сутки)": "catering_portions_per_day",
    "Объём заправки воздушных судов (рейсов/сут)": "refueling_flights_per_day",
    "Суточное количество рейсов внутренних грузовых тележек (внутри терминала)": "internal_cart_trips_per_day",
    "Количество уборочных машин (терминал)": "cleaning_machine_count",
    "Площадь, убираемая роботизированной уборкой": "robotic_cleaning_area",
    "Суточный объём вывоза мусора (контейнеров)": "waste_containers_per_day",
    "Численность персонала наземного обслуживания (рамп)": "ramp_staff_count",
    "Численность персонала внутри терминала (логистика, уборка)": "terminal_logistics_staff_count",
    "Средняя з/п сотрудника наземного обслуживания (gross)": "ramp_staff_salary_gross",
    "Средняя з/п уборщика терминала (gross)": "terminal_cleaner_salary_gross",
    "Коэффициент начислений на ФОТ": "payroll_tax_rate",
    "Годовая текучесть (персонал терминала)": "terminal_staff_annual_turnover",
    "Зонирование (количество режимных зон)": "restricted_zone_count",
    "Наличие системы контроля доступа (СКУД)": "has_access_control",
    "Требования по сертификации оборудования для airside": "airside_equipment_certification",
    "Ограничения по уровню шума (зона)": "noise_level_limit_zone",
    "Температура в неотапливаемых зонах (перрон, зима)": "unheated_zone_temperature",
    "Наличие FIDS/AODB системы": "has_fids_aodb",
    "Наличие BMS (системы управления зданием)": "has_bms",
    "Доступная мощность для зарядной инфраструктуры": "charging_infrastructure_power",
    # --- медицина ---
    "Тип медицинского учреждения": "medical_facility_type",
    "Общая площадь здания(й)": "building_total_area",
    "Количество этажей (основной корпус)": "main_building_floor_count",
    "Количество лифтов (грузовых/медицинских)": "elevator_count",
    "Количество коек (стационар)": "inpatient_bed_count",
    "Коечный фонд в эксплуатации (средняя занятость)": "bed_occupancy_rate",
    "Количество операционных": "operating_room_count",
    "Количество амбулаторных посещений в сутки": "daily_outpatient_visits",
    "Режим работы стационара": "inpatient_operation_mode",
    "Режим работы амбулатории": "outpatient_operation_mode",
    "Количество смен медперсонала (уход за пациентами)": "nursing_shifts_per_day",
    "Пиковое время логистической нагрузки": "peak_logistics_time",
    "Количество кормлений в сутки": "meals_per_day",
    "Общее количество порций питания в сутки": "meal_portions_per_day",
    "Среднее расстояние от пищеблока до отделения": "kitchen_to_ward_distance",
    "Количество точек раздачи питания (отделений)": "meal_distribution_point_count",
    "Средняя масса тележки с питанием (брутто)": "meal_trolley_weight_gross",
    "Норматив доставки питания (мин от пищеблока до отделения)": "meal_delivery_time_norm",
    "Объём грязного белья (кг/сутки)": "dirty_linen_kg_per_day",
    "Объём чистого белья на раздачу (кг/сутки)": "clean_linen_kg_per_day",
    "Количество точек сбора/выдачи белья": "linen_collection_point_count",
    "Периодичность смены белья (раз в сутки, в среднем)": "linen_change_frequency",
    "Средняя масса контейнера с бельём": "linen_container_weight",
    "Количество наименований медикаментов в обращении": "medication_name_count",
    "Объём выдачи медикаментов (заявок/сутки)": "medication_request_count_per_day",
    "Количество аптечных точек выдачи (аптека, аптечные склады)": "pharmacy_dispensing_point_count",
    "Количество точек доставки (отделений + ОР + реанимация)": "medication_delivery_point_count",
    "Среднее время комплектации 1 заявки в аптеке": "pharmacy_request_fulfilment_time",
    "Доля срочных (STAT) доставок медикаментов": "stat_delivery_share",
    "Объём доставки расходных материалов (рейсов/сутки)": "consumables_delivery_trips_per_day",
    "Количество биоматериалов (проб) в сутки": "daily_biomaterial_sample_count",
    "Количество клинико-диагностических лабораторий (КДЛ)": "clinical_lab_count",
    "Среднее время доставки пробы (норматив)": "sample_delivery_time_norm",
    "Объём выдачи результатов анализов (рейсов/сутки)": "lab_result_delivery_trips_per_day",
    "Объём медицинских отходов класса А (ненасыщенные)": "medical_waste_class_a_kg_per_day",
    "Объём медицинских отходов класса Б (инфицированные)": "medical_waste_class_b_kg_per_day",
    "Количество точек сбора отходов": "waste_collection_point_count",
    "Периодичность вывоза отходов из отделений": "waste_removal_frequency",
    "Численность санитаров и транспортировщиков": "sanitary_staff_count",
    "Численность сотрудников пищеблока (раздача)": "kitchen_staff_count",
    "Численность сотрудников прачечной (транспорт белья)": "laundry_transport_staff_count",
    "Средняя з/п санитара/транспортировщика (gross)": "sanitary_staff_salary_gross",
    "Средняя з/п сотрудника пищеблока (gross)": "kitchen_staff_salary_gross",
    "Годовая текучесть (немедицинский персонал)": "non_medical_staff_annual_turnover",
    "Обеззараживание робота между рейсами": "robot_disinfection_between_trips",
    "Требования к уровню шума в палатах (ночное время)": "ward_noise_limit_night",
    "Наличие СКУД (контроль доступа по зонам)": "has_zoned_access_control",
    "Требования к материалу поверхностей робота": "robot_surface_material_requirements",
    "Наличие МИС (медицинская информационная система)": "has_mis",
    "Наличие ЛИС (лабораторная информационная система)": "has_lis",
    "Наличие системы управления лифтами (BMS)": "has_elevator_management_system",
    "Ширина коридоров (основных)": "main_corridor_width",
    "Наличие пандусов/подъёмников (для межэтажного AMR без лифта)": "has_ramps_or_lifts_for_amr",
    # --- worst-case имена после переименования (parameter_overrides;
    # исходные имена XLSX выше сохранены - парсинг идёт по ним) ---
    "Максимальная масса грузовой единицы (паллет), кг": "pallet_unit_weight",
    "Максимальная масса штучной единицы (SKU), кг": "sku_unit_weight",
    "Минимальная высота потолков в зоне хранения, м": "storage_zone_ceiling_height",
    "Минимальная ширина главных проездов, м": "main_aisle_width",
    "Минимальная ширина рабочих проходов между стеллажами, м": "rack_aisle_width",
    "Минимальная доступная мощность для зарядки роботов, кВт": "available_power_capacity",
    # --- новые параметры (уточнения организатора-5) ---
    "Максимальная нагрузка на пол (на точку опоры), кг": "floor_load_max_kg",
    "Максимально допустимый уровень шума (в самой тихой зоне), дБА": "max_allowed_noise_dba",
    "Требование к точности позиционирования (в самой требовательной операции), мм":
        "required_positioning_accuracy_mm",
}

# --- solution_type - 11 значений (CSV «Тип») ---
SOLUTION_TYPE_CODE = {
    "Мобильные роботы": "mobile_robots",
    "Роботы-манипуляторы": "manipulator_robots",
    "Стационарные роботизированные системы": "stationary_robotic_systems",
    "Антропоморфные роботы": "humanoid_robots",
    "Автономные наземные транспортные средства": "autonomous_ground_vehicles",
    "Морские роботы": "marine_robots",
    "БАС": "uas",
    "ПО": "software",
    "ПО БРС": "brs_software",
    "Другое": "other",
    "Мобильные манипуляторы": "mobile_manipulators",
}

# --- solution_subtype - 71 значение (CSV 68 + 3 из маппинга §4) ---
SOLUTION_SUBTYPE_CODE = {
    # --- из CSV (68 после карты автоправок) ---
    "AMR": "amr",
    "FMR": "fmr",
    "Робот-штабелер": "stacker_robot",
    "Беспилотный тягач": "unmanned_tow_tractor",
    "Умная система хранения": "smart_storage_system",
    "Беспилотный погрузчик": "unmanned_forklift",
    "Робот-уборщик": "cleaning_robot",
    "Робот-манипулятор": "manipulator_robot",
    "Кран-штабелёр": "stacker_crane",
    "Шаттл": "shuttle",
    "Мобильный робот-комплектовщик": "mobile_picking_robot",
    "Манипулятор": "manipulator",
    "ПО для сортировки товаров": "goods_sorting_software",
    "Робот-инвентаризатор": "inventory_robot",
    "Робот-ровер": "rover_robot",
    "Антропоморфный робот": "humanoid_robot",
    "Робо-кафе": "robo_cafe",
    "Интеллектуальный автобус - вендинговый аппарат": "smart_bus_vending_machine",
    "Робот-курьер": "courier_robot",
    "Коллаборативный робот-манипулятор": "collaborative_manipulator_robot",
    "Сварочный робот": "welding_robot",
    "Роботизированная установка": "robotic_unit",
    "Роботизированная пристаночная ячейка": "robotic_machine_cell",
    "Дельта-робот": "delta_robot",
    "Роботизированная тележка": "robotic_cart",
    "Робот-укладчик": "stacking_robot",
    "Робот-паук": "spider_robot",
    "Робот-сортировщик": "sorting_robot",
    "Самолет": "fixed_wing_uas",
    "Конвейер автоматизированной вакцинации": "automated_vaccination_conveyor",
    "Колесный робот": "wheeled_robot",
    "Роботизированная доильная установка": "robotic_milking_unit",
    "Беспилотный трактор": "unmanned_tractor",
    "Агробот": "agrobot",
    "Робот для сбора плодов": "fruit_picking_robot",
    "Робот для анализа сельхозземель": "farmland_analysis_robot",
    "Мультиротор": "multicopter",
    "Мобильный робототехнический комплекс высокой проходимости": "high_mobility_mobile_robot_system",
    "Внутритрубные роботизированные системы": "in_pipe_robotic_systems",
    "Роботы для диагностики теплосетей": "heat_network_diagnostics_robots",
    "Роботизированная теплоинспекция трубопроводов": "robotic_pipeline_thermal_inspection",
    "Беспилотный бульдозер": "unmanned_bulldozer",
    "Беспилотный каток": "unmanned_road_roller",
    "Беспилотный асфальтоукладчик": "unmanned_asphalt_paver",
    "Беспилотный сервисный катамаран": "unmanned_service_catamaran",
    "Привязной робот на базе октокоптера": "tethered_octocopter_robot",
    "Роботизированный 3D-принтер": "robotic_3d_printer",
    "Роботизированные погрузочные краны": "robotic_loading_crane",
    "Роботизированный сварочный комплекс": "robotic_welding_complex",
    "БАС мультироторного типа": "multicopter_uas",
    "Демонтажный робот": "demolition_robot",
    "ТНПА": "rov",
    "Модульная надводная многофункциональная платформа": "modular_surface_platform",
    "Многофункциональный бэзэкипажный катер": "multifunctional_unmanned_boat",
    "Беспилотный катамаран": "unmanned_catamaran",
    "Охранный робот": "security_robot",
    "Роботизированный лафетный ствол": "robotic_fire_monitor",
    "Беспилотный катер": "unmanned_boat",
    "Безэкипажный катер": "crewless_boat",
    "Беспилотный грузовик": "unmanned_truck",
    "Универсальная автономная роботизированная платформа": "universal_autonomous_robot_platform",
    "Беспилотный трамвай": "unmanned_tram",
    "Беспилотное метро": "unmanned_metro",
    "Беспилотное такси": "robotaxi",
    "Робот-расцепщик": "uncoupling_robot",
    "Робот": "robot",
    "Ровер": "rover",
    "VTOL": "vtol",
    # --- только из маппинга assumptions.md §4 (3) ---
    "Робот-тягач": "tug_robot",
    "Мобильный манипулятор": "mobile_manipulator",
    "Интеллектуальный автобус": "smart_bus",
}

# --- region - 24 значения (CSV «Регион») ---
REGION_CODE = {
    "Москва": "moscow",
    "Санкт-Петербург": "saint_petersburg",
    "Ярославская область": "yaroslavl_region",
    "Ростовская область": "rostov_region",
    "Республика Татарстан": "tatarstan_republic",
    "Московская область": "moscow_region",
    "Пермский край": "perm_krai",
    "Рязанская область": "ryazan_region",
    "Пензенская область": "penza_region",
    "Удмуртская Республика": "udmurt_republic",
    "Нижегородская область": "nizhny_novgorod_region",
    "Чувашская Республика": "chuvash_republic",
    "Новосибирская область": "novosibirsk_region",
    "Челябинская область": "chelyabinsk_region",
    "Краснодарский край": "krasnodar_krai",
    "Брянская область": "bryansk_region",
    "Севастополь": "sevastopol",
    "Омская область": "omsk_region",
    "Красноярский край": "krasnoyarsk_krai",
    "Сахалинская область": "sakhalin_region",
    "Калужская обл": "kaluga_region",
    "Свердловская область": "sverdlovsk_region",
    "Ленинградская область": "leningrad_region",
    "Томская область": "tomsk_region",
}


# Порядок lookup: точное имя -> canonical_key -> (вызывающий код решает фолбэк).
_CODE_TABLES: dict[str, dict[str, str]] = {
    "industry": INDUSTRY_CODE,
    "process": PROCESS_CODE,
    "parameter_type": PARAMETER_TYPE_CODE,
    "solution_type": SOLUTION_TYPE_CODE,
    "solution_subtype": SOLUTION_SUBTYPE_CODE,
    "region": REGION_CODE,
}


def code_for(table: str, name: str) -> tuple[str | None, bool]:
    """Явный код справочника по названию.

    Возвращает (code, explicit): code=None - записи нет (вызывающий код
    применяет транслит-фолбэк slugify с предупреждением), иначе
    (код, найден ли явный словарь).
    """
    mapping = _CODE_TABLES.get(table)
    if mapping is None:
        return None, False
    code = mapping.get(name)
    if code is None:
        key = canonical_key(name)
        if key is not None:
            for k, v in mapping.items():
                if canonical_key(k) == key:
                    code = v
                    break
    return code, True


def validate() -> None:
    """Самопроверка словарей (вызывается тестами): покрытие и уникальность.

    Биекция «имя -> код» требуется для всех справочников, КРОМЕ
    parameter_type: после worst-case переименований
    (parameter_overrides.json) у одного кода законно два имени - исходное
    из XLSX (по нему идёт парсинг) и переименованное (оно в БД). Для
    parameter_type проверяется обратная уникальность: код -> ровно один
    НЕ-алиас... фактически достаточно запретить дубли КАНОНИЧЕСКИХ имён
    (это даёт dict) и формат кодов; случайные дубли кодов ловятся
    тестом покрытия данных (имя без ожидаемого кода).
    """
    for table, mapping in _CODE_TABLES.items():
        codes = list(mapping.values())
        if table == "parameter_type":
            # алиасы переименований допустимы: каждый повторяющийся код обязан
            # встречаться и в исходной (XLSX) записи - иначе это опечатка
            from collections import Counter
            counts = Counter(codes)
            assert set(counts.values()) <= {1, 2}, f"{table}: код больше чем в 2 записях"
        else:
            assert len(codes) == len(set(codes)), f"{table}: дубли кодов"
    # англ. коды: только [a-z0-9_]
    for table, mapping in _CODE_TABLES.items():
        for name, code in mapping.items():
            assert code and code == code.lower(), (table, name, code)
            assert all(ch.isascii() and (ch.isalnum() or ch == "_") for ch in code), (table, code)
