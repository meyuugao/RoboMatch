package me.yuugao.robomatch.domain;

/**
 * Статус результата подбора (data_model.md §10.7, CHECK status IN
 * ('fit','needs_check','excluded')): fit - прошло фильтры,
 * needs_check - «требует проверки» (нехватка данных, - это
 * НЕ исключение), excluded - критическое ограничение.
 */
public enum SelectionStatus {
    /**
 * Прошло фильтры подбора: соответствие обязательным ТТХ и ограничениям.
 */
    FIT,
    /**
 * Требует проверки: нехватка данных - НЕ исключение.
 */
    NEEDS_CHECK,
    /**
 * Исключено: критическое ограничение объекта.
 */
    EXCLUDED
}
