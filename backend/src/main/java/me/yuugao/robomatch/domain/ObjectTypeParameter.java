package me.yuugao.robomatch.domain;

import java.math.BigDecimal;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Определение параметра типа объекта» — метаданные параметра
 * для конкретного типа объекта (data_model.md §3.2, таблица
 * object_type_parameter; 145 строк из seed (138 XLSX + 7 worst-case,
 *, из них обязательных:
 * склад 31 / аэропорт 26 / медицина 30 — assumptions.md §2).
 * <p>
 * Именно эти метаданные управляют формой ввода и валидацией значений
 * проекта: is_required, default_value_* (ровно одно
 * NOT NULL — CHECK схемы), min_value/max_value, source_note (источник
 * норматива — показывается пользователю), group_name (секции «▌»
 * датасета организатора: общие, режим работы, операции, ...).
 * <p>
 * Worst-case флаги: is_fixed — константа
 * (не редактируется), is_derived — вычисляется из других параметров.
 * Выставляются seed-ом через parameter_overrides.json.
 * <p>
 * FK — Long-колонки без @ManyToOne (единообразие с Solution/Project).
 * UNIQUE (object_type_id, parameter_type_id) отражён в @Table — на нём
 * построен UPSERT значений проекта.
 */
@Entity
@Table(name = "object_type_parameter", uniqueConstraints = @UniqueConstraint(
        name = "object_type_parameter_object_type_id_parameter_type_id_key",
        columnNames = {"object_type_id", "parameter_type_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ObjectTypeParameter {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Тип объекта (склад/аэропорт/медучреждение). FK -> object_type.id.
 */
    @Column(name = "object_type_id", nullable = false)
    private Long objectTypeId;

    /**
 * Ссылка на справочник имён/единиц/типов. FK -> parameter_type.id.
 */
    @Column(name = "parameter_type_id", nullable = false)
    private Long parameterTypeId;

    /**
 * Группа параметра (секция «▌» датасета: «Общие», «Режим работы», ...).
 */
    @Column(name = "group_name", nullable = false, columnDefinition = "text")
    private String groupName;

    /**
 * Обязательность (assumptions.md §2): без обязательных расчёт не запустится.
 */
    @Column(name = "is_required", nullable = false)
    private Boolean isRequired;

    /**
 * Параметр-константа: не редактируется ни в
 * форме, ни импортом (коэффициент начислений на ФОТ 1.302, рабочих
 * дней в году 365, наличие WMS). Значение — в default_value_*;
 * пользовательские строки project_parameter_value игнорируются.
 */
    @Column(name = "is_fixed", nullable = false)
    @Builder.Default
    private Boolean isFixed = false;

    /**
 * Производный параметр: значение вычисляется
 * из других параметров (объём отбора штук/сутки = строки/сутки x 1.5;
 * площадь активной зоны = общая площадь x 0.5 — примечания датасета).
 * PUT/DELETE значения — 400, currentValue считается на GET.
 */
    @Column(name = "is_derived", nullable = false)
    @Builder.Default
    private Boolean isDerived = false;

    /**
 * Значение по умолчанию для числовых параметров; NULL для других типов.
 */
    @Column(name = "default_value_numeric", precision = 16, scale = 4)
    private BigDecimal defaultValueNumeric;

    /**
 * Значение по умолчанию для текстовых параметров.
 */
    @Column(name = "default_value_text", columnDefinition = "text")
    private String defaultValueText;

    /**
 * Значение по умолчанию для логических параметров.
 */
    @Column(name = "default_value_bool")
    private Boolean defaultValueBool;

    /**
 * Минимум допустимого диапазона (только для числовых).
 */
    @Column(name = "min_value", precision = 16, scale = 4)
    private BigDecimal minValue;

    /**
 * Максимум допустимого диапазона (только для числовых).
 */
    @Column(name = "max_value", precision = 16, scale = 4)
    private BigDecimal maxValue;

    /**
 * Источник норматива.
 */
    @Column(name = "source_note", columnDefinition = "text")
    private String sourceNote;
}
