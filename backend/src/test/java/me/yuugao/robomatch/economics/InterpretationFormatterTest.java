package me.yuugao.robomatch.economics;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Человекочитаемое отображение
 * окупаемости - целые месяцы/годы, округление ВВЕРХ (не занижаем срок),
 * без дробей. Правило - economic_model.md §4 (раньше допускались
 * дробные месяцы «1,2 мес.»). Логика расчёта payback
 * не меняется - тестируется только форматирование.
 */
class InterpretationFormatterTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void lessThanOneMonth_isUnderOneMonth() {
        // 0,05 г. = 0,6 мес. → «до 1 месяца»
        assertThat(InterpretationFormatter.formatPayback(bd("0.05")))
                .isEqualTo("до 1 месяца");
    }

    @Test
    void overOneMonth_roundsUpToWholeMonths() {
        // 0,1 г. = 1,2 мес. → «2 месяца» (вверх, не «1,2 мес.»)
        assertThat(InterpretationFormatter.formatPayback(bd("0.1")))
                .isEqualTo("2 месяца");
    }

    @Test
    void halfYear_isWholeMonths() {
        // 0,5 г. = 6 мес. → «6 месяцев»
        assertThat(InterpretationFormatter.formatPayback(bd("0.5")))
                .isEqualTo("6 месяцев");
    }

    @Test
    void nearTwelveMonths_ceilsToTwelveNotYear() {
        // 0,95 г. = 11,4 мес. → «12 месяцев» (правило §4: 11,4 → 12)
        assertThat(InterpretationFormatter.formatPayback(bd("0.95")))
                .isEqualTo("12 месяцев");
    }

    @Test
    void oneQuarterYear_isYearAndMonths() {
        // 1,25 г. = 1 год 3 мес. → «1 год 3 месяца»
        assertThat(InterpretationFormatter.formatPayback(bd("1.25")))
                .isEqualTo("1 год 3 месяца");
    }

    @Test
    void wholeYears_noMonthsSuffix() {
        // 2,0 г. → «2 года» (0 месяцев - без «0 месяцев»)
        assertThat(InterpretationFormatter.formatPayback(bd("2.0")))
                .isEqualTo("2 года");
    }

    @Test
    void overFiveYears_ceilsToNextYear() {
        // 5,1 г. → «6 лет» (вверх, не занижаем)
        assertThat(InterpretationFormatter.formatPayback(bd("5.1")))
                .isEqualTo("6 лет");
    }

    @Test
    void wholeAndTenYears_areWholeYears() {
        // 7,0 г. → «7 лет»; 10,0 г. → «10 лет»
        assertThat(InterpretationFormatter.formatPayback(bd("7.0")))
                .isEqualTo("7 лет");
        assertThat(InterpretationFormatter.formatPayback(bd("10.0")))
                .isEqualTo("10 лет");
    }

    @Test
    void nullAndZero_returnNull() {
        // не рассчитан / неположительный - статус, а не число
        assertThat(InterpretationFormatter.formatPayback(null)).isNull();
        assertThat(InterpretationFormatter.formatPayback(BigDecimal.ZERO))
                .isNull();
    }

    @Test
    void elevenMonthsWord_andFourYearsWord_plural() {
        // склонения: 11 месяцев, 1 месяц; 4 года, 21 год
        assertThat(InterpretationFormatter.monthWord(11)).isEqualTo("месяцев");
        assertThat(InterpretationFormatter.monthWord(1)).isEqualTo("месяц");
        assertThat(InterpretationFormatter.monthWord(2)).isEqualTo("месяца");
        assertThat(InterpretationFormatter.yearWord(4)).isEqualTo("года");
        assertThat(InterpretationFormatter.yearWord(21)).isEqualTo("год");
        // граница ветки месяцев: чуть меньше года - «12 месяцев»
        // (11,999 мес. округляются вверх до 12, но не до года)
        assertThat(InterpretationFormatter.formatPayback(bd("0.9999")))
                .isEqualTo("12 месяцев");
    }
}
