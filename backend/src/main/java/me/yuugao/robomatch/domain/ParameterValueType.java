package me.yuugao.robomatch.domain;

/**
 * Тип значения параметра объекта (data_model.md §4, таблица
 * parameter_type.value_type). Совпадает с типами характеристик
 * каталога (characteristic_type.data_type — конвенция EAV части 1):
 * числовое, логическое или текстовое значение.
 * <p>
 * Источник истины при вводе/импорте: значение обязано
 * соответствовать типу параметра — number записывается в
 * value_numeric, boolean — в value_bool, text — в value_text
 * (CHECK «ровно одно value_* NOT NULL» в V1).
 */
public enum ParameterValueType {
    /**
 * Числовое значение — записывается в value_numeric.
 */
    NUMBER,
    /**
 * Логическое значение — записывается в value_bool.
 */
    BOOLEAN,
    /**
 * Текстовое значение — записывается в value_text.
 */
    TEXT
}
