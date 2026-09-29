package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Интерпретация срока окупаемости:
 * числовой результат + категория, а не жёсткий порог «да/нет».
 *
 * <p>При окупаемости менее полугода срок
 * выражается в месяцах (paybackMonths = round(payback × 12, 1), §4) -
 * значение «0,1 г.» рядом с подписью «до 3 лет» нечитаемо; расчёт
 * payback не меняется - поле только для отображения.
 *
 * <p>paybackHuman - человекочитаемая
 * строка по правилу §4 («до 1 месяца» / целые месяцы / «N лет M
 * месяцев» / целые годы; округление вверх, без занижения). Поле
 * paybackMonths сохранено для отчёта и API; UI рендерит
 * человекочитаемо (зеркальная реализация - formatPaybackShort в
 * types/economics.ts).
 *
 * @param paybackYears срок окупаемости, лет (null - не рассчитан)
 * @param paybackMonths окупаемость в месяцах (при сроке менее 0,5 года)
 * @param paybackHuman человекочитаемый срок
 * @param category категория окупаемости
 * @param text текст для пользователя
 */
@Schema(description = "Интерпретация окупаемости")
public record InterpretationDto(
        @Schema(description = "Срок окупаемости, лет (null - не рассчитан)",
                example = "2.4")
        BigDecimal paybackYears,

        @Schema(description = "Окупаемость в месяцах - только при сроке "
                + "менее 0,5 года (round(payback × 12, 1)); "
                + "иначе null", example = "1.2")
        BigDecimal paybackMonths,

        @Schema(description = "Человекочитаемый срок: «до 1 "
                + "месяца» | «N месяцев» | «N год M месяцев» | «N лет» - "
                + "целые, округление вверх", example = "2 месяца")
        String paybackHuman,

        @Schema(description = "Категория: fast | moderate | long | none | "
                + "undefined | not_applicable | underpowered | "
                + "empty_composition", example = "fast",
                allowableValues = {"fast", "moderate", "long", "none",
                        "undefined", "not_applicable", "underpowered",
                        "empty_composition"})
        String category,

        @Schema(description = "Текст для пользователя",
                example = "Быстрая окупаемость - до 3 лет")
        String text
) {
}
