package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Строка состава сценария в ответе (data_model.md §10.6): решение каталога
 * с количеством, ценой и признаком ручного добавления.
 *
 * @param solutionId идентификатор решения каталога
 * @param solutionName название решения
 * @param vendorName вендор
 * @param quantity количество единиц
 * @param priceRub цена единицы с НДС, руб.
 * @param sumRub сумма по строке, руб.
 * @param manual добавлено вручную
 * @param manualReason причина ручного добавления
 */
@Schema(description = "Решение в составе сценария")
public record ScenarioSolutionDto(
        @Schema(description = "Идентификатор решения каталога", example = "42")
        Long solutionId,

        @Schema(description = "Название решения", example = "Ronavi H1500")
        String solutionName,

        @Schema(description = "Вендор", example = "ООО «Ронави Роботикс»")
        String vendorName,

        @Schema(description = "Количество единиц", example = "3")
        Integer quantity,

        @Schema(description = "Цена единицы с НДС, руб. (уточнения организатора)",
                example = "2500000.00")
        BigDecimal priceRub,

        @Schema(description = "Сумма по строке, руб.", example = "7500000.00")
        BigDecimal sumRub,

        @Schema(description = "Добавлено вручную", example = "false")
        boolean manual,

        @Schema(description = "Причина ручного добавления (при manual=true)",
                example = "Планируем расширение проходов до 3 м")
        String manualReason
) {
}
