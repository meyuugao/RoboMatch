package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Вклад одного критерия в итоговый Score решения ( — объяснимое
 * ранжирование: «пользователь должен видеть критерии и вклад ключевых
 * факторов»). Считается на лету по текущим данным каталога
 * (selection_algorithm.md §6; data_model.md §12 — снимки переменного
 * состава не хранятся).
 *
 * @param code код критерия
 * @param name название критерия
 * @param weight вес критерия (сумма весов выборки = 1)
 * @param normalizedValue нормированное значение критерия [0..1]
 * @param contribution вклад в Score = вес x нормированное значение
 * @param evaluated оценён ли критерий у этого решения
 */
@Schema(description = "Вклад критерия ранжирования в итоговую оценку")
public record CriterionContributionDto(
        @Schema(description = "Код критерия", example = "payload")
        String code,

        @Schema(description = "Название критерия", example = "Грузоподъёмность")
        String name,

        @Schema(description = "Вес критерия (сумма весов выборки = 1)",
                example = "0.20")
        java.math.BigDecimal weight,

        @Schema(description = "Нормированное значение критерия [0..1] "
                + "(null — критерий не оценён: нет данных у решения)",
                example = "0.75", nullable = true)
        java.math.BigDecimal normalizedValue,

        @Schema(description = "Вклад в Score = вес x нормированное значение "
                + "(0 для неоценённых)", example = "0.150")
        java.math.BigDecimal contribution,

        @Schema(description = "Оценён ли критерий у этого решения", example = "true")
        boolean evaluated
) {
}
