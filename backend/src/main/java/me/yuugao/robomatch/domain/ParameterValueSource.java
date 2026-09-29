package me.yuugao.robomatch.domain;

/**
 * Происхождение значения параметра проекта (data_model.md §10.3,
 * CHECK source IN ('manual','import')):
 * - MANUAL - ручной ввод через форму;
 * - IMPORT - импорт из Excel/CSV по шаблону.
 * <p>
 * Источник последней записи: повторный ручной ввод или повторный импорт
 * перезаписывает source актуальным способом (UNIQUE (project_id,
 * object_type_parameter_id) - одно значение на параметр).
 */
public enum ParameterValueSource {
    /**
 * Ручной ввод через форму.
 */
    MANUAL,
    /**
 * Импорт из Excel/CSV по шаблону.
 */
    IMPORT
}
