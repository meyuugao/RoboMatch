package me.yuugao.robomatch.economics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Константы гостевого демо-расчёта (роль «гость», сценарий из mvp_scope.md:
 * каталог → выбор типа объекта → демо-расчёт на демо-наборе данных без
 * сохранения). Единственный источник значений демо-набора «Склад»:
 * базовые значения листа «Склад» демо-датасета организатора.
 * <p>
 * Значения НИКОГДА не пишутся в БД: расчёт выполняется в памяти
 * (DemoCalculationService), результат помечается признаком демо
 * и не сохраняется.
 * <p>
 * Состав демо — роботы паллетной транспортировки из каталога
 * (цена и ТТХ читаются из каталога только для чтения).
 */
final class DemoConstants {

    private DemoConstants() {
    }

    /** Код типа объекта демо-расчёта (единственный расчётный тип MVP). */
    static final String OBJECT_TYPE_CODE = "warehouse";

    /** Горизонт расчёта окупаемости демо, лет. */
    static final int HORIZON_YEARS = 5;

    /**
 * Номинальная производительность робота демо-состава, операций/час:
 * обязательное допущение без значения по умолчанию в каталоге —
 * для демо фиксируется здесь (паспортная производительность
 * паллетного робота демо-состава).
 */
    static final String ROBOT_NOMINAL_PRODUCTIVITY = "90";

    /**
 * Параметры объекта «Склад» — базовые значения демо-набора данных
 * (коды соответствуют словарю параметров платформы). Производные
 * (active_zone_area, picking_units_per_day) — уже в базовых значениях
 * набора, как в демо-датасете.
 */
    static final Map<String, String> PARAMS = buildParams();

    /**
 * Демонстрационный состав (роботизированные сценарии): паллетные
 * AMR из каталога; цена и мощность зарядки читаются из каталога.
 */
    static final List<DemoLine> COMPOSITION = List.of(
            new DemoLine("Ronavi H1500 (грузоподъемность до 1 500 кг)",
                    "ООО \"Ронави Роботикс\"", 3));

    /**
 * Параметры для показа на демо-странице (код → заголовок и единица):
 * ключевые значения демо-набора без перегрузки интерфейса.
 */
    static final Map<String, DisplayParam> DISPLAY = buildDisplay();

    /**
 * Строка демо-состава: решение каталога по имени и вендору + количество.
 *
 * @param solutionName имя решения каталога
 * @param vendorName имя вендора
 * @param quantity количество роботов в демо-составе
 */
    record DemoLine(String solutionName, String vendorName, int quantity) {
    }

    /**
 * Параметр для отображения на демо-странице.
 *
 * @param title человекочитаемое название
 * @param unit единица измерения (может быть пустой)
 */
    record DisplayParam(String title, String unit) {
    }

    private static Map<String, String> buildParams() {
        Map<String, String> params = new LinkedHashMap<>();
        // Общие параметры объекта
        params.put("total_warehouse_area", "20000");
        params.put("active_zone_area", "10000");
        params.put("storage_zone_ceiling_height", "10");
        params.put("main_aisle_width", "3.5");
        params.put("rack_aisle_width", "2.8");
        params.put("floor_flatness_deviation", "3");
        // Режим работы
        params.put("shifts_per_day", "2");
        params.put("working_days_per_year", "365");
        params.put("shift_duration", "11");
        params.put("peak_load_factor", "1.5");
        // Операции: объём и производительность
        params.put("inbound_pallets_per_day", "1000");
        params.put("outbound_pallets_per_day", "1000");
        params.put("picking_lines_per_day", "100000");
        params.put("picking_units_per_day", "150000");
        params.put("active_sku_count", "2000");
        // Персонал контура роботизации
        params.put("total_warehouse_staff", "180");
        params.put("pickers_count", "100");
        params.put("forklift_operators_count", "25");
        params.put("picker_throughput_lines_per_hour", "150");
        params.put("picker_salary_gross", "100000");
        params.put("forklift_operator_salary_gross", "120000");
        params.put("payroll_insurance_contributions_rate", "1.302");
        // Хранение и грузы
        params.put("racking_type", "Фронтальные паллетные");
        params.put("pallet_positions", "20000");
        params.put("pallet_unit_weight", "800");
        params.put("pallet_dimensions", "1200×800×1600");
        return Collections.unmodifiableMap(params);
    }

    private static Map<String, DisplayParam> buildDisplay() {
        Map<String, DisplayParam> display = new LinkedHashMap<>();
        display.put("total_warehouse_area",
                new DisplayParam("Общая площадь склада", "м²"));
        display.put("active_zone_area",
                new DisplayParam("Площадь активной (роботизируемой) зоны", "м²"));
        display.put("shifts_per_day",
                new DisplayParam("Количество рабочих смен в сутки", "смен"));
        display.put("shift_duration",
                new DisplayParam("Продолжительность смены", "ч"));
        display.put("peak_load_factor",
                new DisplayParam("Пиковый коэффициент нагрузки", ""));
        display.put("inbound_pallets_per_day",
                new DisplayParam("Объём приёмки", "поддон/сут"));
        display.put("outbound_pallets_per_day",
                new DisplayParam("Объём отгрузки", "поддон/сут"));
        display.put("picking_lines_per_day",
                new DisplayParam("Объём отбора", "строк/сут"));
        display.put("total_warehouse_staff",
                new DisplayParam("Общая численность персонала склада", "чел."));
        display.put("pickers_count",
                new DisplayParam("Из них: отборщики", "чел."));
        display.put("forklift_operators_count",
                new DisplayParam("Из них: операторы погрузчиков", "чел."));
        display.put("picker_salary_gross",
                new DisplayParam("Средняя зарплата отборщика", "руб./мес."));
        display.put("forklift_operator_salary_gross",
                new DisplayParam("Средняя зарплата оператора погрузчика", "руб./мес."));
        display.put("pallet_positions",
                new DisplayParam("Количество паллетомест", "паллето-мест"));
        display.put("active_sku_count",
                new DisplayParam("Количество активных SKU", "SKU"));
        return Collections.unmodifiableMap(new LinkedHashMap<>(display));
    }
}
