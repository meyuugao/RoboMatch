package me.yuugao.robomatch.economics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Человекочитаемое отображение срока окупаемости (2026-09-24):
 * дробные месяцы «1,2 мес.»
 * заменяются целыми с округлением ВВЕРХ, чтобы не занижать срок.
 *
 * <p>ПРАВИЛО (economic_model.md §4, источник правды):
 * <ul>
 * <li>payback &lt; 1/12 года - «до 1 месяца»;</li>
 * <li>1 мес. ≤ payback &lt; 12 мес. - целые месяцы, округление вверх:
 * 1,2 мес. → «2 месяца», 11,4 мес. → «12 месяцев»;</li>
 * <li>1 год ≤ payback &lt; 5 лет - годы и месяцы:
 * «1 год 3 месяца»; 0 месяцев - только годы («2 года»);</li>
 * <li>payback ≥ 5 лет - целые годы, округление вверх:
 * 5,1 г. → «6 лет», 7,0 г. → «7 лет».</li>
 * </ul>
 *
 * <p>Только форматирование вывода: логика расчёта payback в
 * {@link EconomicModel} не меняется. Поле paybackMonths в DTO
 * сохраняется как есть (для отчёта и API); человекочитаемая строка -
 * дополнительно в InterpretationDto.paybackHuman и в UI (зеркальная
 * реализация formatPaybackShort в types/economics.ts).
 *
 * <p>Склонение русских слов: 1 месяц / 2-4 месяца / 5-12 месяцев;
 * 1 год / 2-4 года / 5-20 лет.
 */
public final class InterpretationFormatter {

    /**
 * Граница «до 1 месяца»: 1/12 года.
 */
    private static final BigDecimal ONE_MONTH_YEARS
            = BigDecimal.ONE.divide(BigDecimal.valueOf(12), 8,
            RoundingMode.HALF_UP);
    /**
 * Граница месяцев и лет: 12 месяцев = 1 год.
 */
    private static final BigDecimal ONE_YEAR = BigDecimal.ONE;
    /**
 * Граница «лет с месяцами» и «целых лет»: 5 лет.
 */
    private static final BigDecimal FIVE_YEARS = BigDecimal.valueOf(5);

    private InterpretationFormatter() {
    }

    /**
 * Человекочитаемый срок окупаемости. null - не рассчитан
 * (выводит статус, а не число).
 *
 * @param paybackYears срок окупаемости в годах (null/≤0 - null)
 * @return строка по правилу §4 (месяцы/годы, округление вверх)
 */
    public static String formatPayback(BigDecimal paybackYears) {
        if (paybackYears == null || paybackYears.signum() <= 0) {
            return null;
        }
        if (paybackYears.compareTo(ONE_MONTH_YEARS) < 0) {
            return "до 1 месяца";
        }
        if (paybackYears.compareTo(ONE_YEAR) < 0) {
            // 1..12 месяцев: округление вверх, не занижаем срок
            int months = paybackYears.multiply(BigDecimal.valueOf(12))
                    .setScale(0, RoundingMode.CEILING).intValueExact();
            return months + " " + monthWord(months);
        }
        if (paybackYears.compareTo(FIVE_YEARS) < 0) {
            int years = paybackYears.setScale(0, RoundingMode.FLOOR)
                    .intValueExact();
            // месяцы остатка - округление вверх (без занижения);
            // 12 после округления - переход в следующий год
            int months = paybackYears.subtract(BigDecimal.valueOf(years))
                    .multiply(BigDecimal.valueOf(12))
                    .setScale(0, RoundingMode.CEILING).intValueExact();
            if (months >= 12) {
                years += 1;
                months = 0;
            }
            if (months == 0) {
                return years + " " + yearWord(years);
            }
            return years + " " + yearWord(years) + " " + months + " "
                    + monthWord(months);
        }
        // от 5 лет: целые годы, округление вверх
        int years = paybackYears.setScale(0, RoundingMode.CEILING)
                .intValueExact();
        return years + " " + yearWord(years);
    }

    /**
 * Склонение: 1 месяц, 2-4 месяца, 5-12 месяцев.
 */
    static String monthWord(int months) {
        if (months % 100 >= 11 && months % 100 <= 14) {
            return "месяцев";
        }
        return switch (months % 10) {
            case 1 -> "месяц";
            case 2, 3, 4 -> "месяца";
            default -> "месяцев";
        };
    }

    /**
 * Склонение: 1 год, 2-4 года, 5-20 лет (и любое N лет).
 */
    static String yearWord(int years) {
        if (years % 100 >= 11 && years % 100 <= 14) {
            return "лет";
        }
        return switch (years % 10) {
            case 1 -> "год";
            case 2, 3, 4 -> "года";
            default -> "лет";
        };
    }
}
