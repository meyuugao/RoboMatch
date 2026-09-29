package me.yuugao.robomatch.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Результат подбора одного решения с объяснением:
 * статус, причина включения/исключения, место в ранжировании, Score,
 * вклад критериев и недостающие данные.
 * <p>
 * status/reason/rank - из selection_result (зафиксированы запуском);
 * score/criteriaContribution - пересчёт на лету по текущему каталогу
 * (только для fit); missingData - пересчёт по текущим данным объекта
 * и ТТХ (только для needs_check). Пользователь изменил параметры -
 * statuses актуализируются повторным запуском подбора.
 *
 * @param solutionId идентификатор решения
 * @param solutionName название решения
 * @param vendorName производитель
 * @param solutionTypeName тип решения
 * @param status статус: fit | needs_check | excluded
 * @param reason причина включения/исключения
 * @param rank место в ранжировании fit-решений
 * @param score итоговая оценка [0..1]
 * @param criteriaContribution вклад критериев в Score
 * @param missingData недостающие данные
 */
@Schema(description = "Результат подбора одного решения с объяснением")
public record SelectionResultDto(
        @Schema(description = "Идентификатор решения", example = "42")
        Long solutionId,

        @Schema(description = "Название решения", example = "Ronavi H1500")
        String solutionName,

        @Schema(description = "Производитель", example = "ООО «Ронави Роботикс»",
                nullable = true)
        String vendorName,

        @Schema(description = "Тип решения (справочник каталога; - "
                + "«применимые типы решений и конкретные продукты»)",
                example = "Мобильные роботы", nullable = true)
        String solutionTypeName,

        @Schema(description = "Статус: fit - подходит, needs_check - требует "
                + "проверки (нехватка данных,), excluded - исключено "
                + "(критическое ограничение)", example = "fit",
                allowableValues = {"fit", "needs_check", "excluded"})
        String status,

        @Schema(description = "Причина включения/исключения (понятный текст)",
                example = "Все обязательные ТТХ пройдены", nullable = true)
        String reason,

        @Schema(description = "Место в ранжировании fit-решений (1 - лучший; "
                + "null для needs_check/excluded)", example = "1", nullable = true)
        Integer rank,

        @Schema(description = "Итоговая оценка [0..1] (только для fit; "
                + "пересчитывается по текущему каталогу)", example = "0.63",
                nullable = true)
        java.math.BigDecimal score,

        @Schema(description = "Вклад критериев в Score (только для fit, "
                + ")", nullable = true,
                example = "[{\"code\": \"payload\", \"contribution\": 0.150}]")
        List<CriterionContributionDto> criteriaContribution,

        @Schema(description = "Недостающие данные (только для needs_check; "
                + ")", nullable = true,
                example = "[\"Грузоподъёмность не указана\"]")
        List<String> missingData
) {
}
