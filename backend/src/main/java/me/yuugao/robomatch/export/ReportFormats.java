package me.yuugao.robomatch.export;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Единое человекочитаемое форматирование отчётов ( - единицы
 * измерения и формат «2 700 000 руб.»; requirements/export.md):
 * даты - локализованные ДД.ММ.ГГГГ; деньги - с разделением разрядов
 * пробелом и «руб.»; десятичные - с запятой (русская типографика);
 * проценты - с «%».
 *
 * <p>DecimalFormat с фиксированными символами (НЕ static: DecimalFormat
 * не потокобезопасен) - экземпляр создаётся на вызов; отчёты
 * генерируются нечасто, накладные расходы несущественны.
 */
public final class ReportFormats {

    /**
 * Даты в отчёте - ДД.ММ.ГГГГ (requirements/export.md).
 */
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    .withZone(ZoneId.systemDefault());
    /**
 * Дата-время расчёта/генерации - ДД.ММ.ГГГГ ЧЧ:ММ.
 */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault());
    /**
 * Дата-время для имени файла - без разделителей.
 */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                    .withZone(ZoneId.systemDefault());

    private ReportFormats() {
    }

    private static DecimalFormatSymbols symbols() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(
                Locale.ROOT);
        symbols.setDecimalSeparator(',');
        symbols.setGroupingSeparator(' ');
        return symbols;
    }

    /**
 * Дата ДД.ММ.ГГГГ; null → «-».
 *
 * @param instant дата (null - прочерк)
 * @return отформатированная дата
 */
    public static String formatDate(Instant instant) {
        return instant == null ? "-" : DATE.format(instant);
    }

    /**
 * Дата-время ДД.ММ.ГГГГ ЧЧ:ММ; null → «-».
 *
 * @param instant момент (null - прочерк)
 * @return отформатированные дата и время
 */
    public static String formatDateTime(Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant);
    }

    /**
 * Штамп для имени файла: yyyyMMdd-HHmm.
 *
 * @param instant момент (null - «unknown»)
 * @return штамп без разделителей
 */
    public static String fileStamp(Instant instant) {
        return instant == null ? "unknown" : FILE_STAMP.format(instant);
    }

    /**
 * Целое число с разрядами: 2 700 000.
 *
 * @param value число (null - прочерк)
 * @return отформатированное целое
 */
    public static String formatInt(Number value) {
        if (value == null) {
            return "-";
        }
        DecimalFormat format = new DecimalFormat("#,##0", symbols());
        return format.format(value.longValue());
    }

    /**
 * Деньги: «2 700 000 руб.» (округление до рублей, §4 модели).
 *
 * @param value сумма (null - прочерк)
 * @return сумма с разрядами и «руб.»
 */
    public static String formatMoney(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        DecimalFormat format = new DecimalFormat("#,##0", symbols());
        return format.format(value.setScale(0,
                java.math.RoundingMode.HALF_UP)) + " руб.";
    }

    /**
 * Дробное число с запятой (до 1 знака): «1,5».
 *
 * @param value число (null - прочерк)
 * @param fractions знаков после запятой
 * @return число с запятой-разделителем
 */
    public static String formatDecimal(BigDecimal value, int fractions) {
        if (value == null) {
            return "-";
        }
        StringBuilder pattern = new StringBuilder("0");
        if (fractions > 0) {
            pattern.append(".");
            pattern.append("0".repeat(fractions));
        }
        DecimalFormat format = new DecimalFormat(pattern.toString(),
                symbols());
        return format.format(value);
    }

    /**
 * Дробное с запятой без хвостовых нулей (до maxFractions знаков):
 * 810 → «810», 41.9 → «41,9», 0.1 → «0,1» -
 * единый десятичный разделитель во всех разделах отчёта.
 *
 * @param value число (null - прочерк)
 * @param maxFractions максимум знаков после запятой
 * @return число без хвостовых нулей
 */
    public static String formatDecimalTrim(BigDecimal value, int maxFractions) {
        if (value == null) {
            return "-";
        }
        StringBuilder pattern = new StringBuilder("0");
        if (maxFractions > 0) {
            pattern.append(".");
            pattern.append("#".repeat(maxFractions));
        }
        DecimalFormat format = new DecimalFormat(pattern.toString(),
                symbols());
        return format.format(value);
    }

    /**
 * Текст окупаемости сценария, единый для всех трёх форматов:
 * базовый сценарий - «не применяется» (сравнение по
 * OPEX/TCO, категория not_applicable - как в UI);
 * роботизированный с эффектом ≤ 0 - «не
 * окупается»; иначе человекочитаемый срок (paybackHuman).
 *
 * @param scenario сценарий отчёта
 * @return текст окупаемости для ячейки отчёта
 */
    public static String paybackText(ReportModel.ScenarioReport scenario) {
        if (scenario.paybackHuman() != null) {
            return scenario.paybackHuman();
        }
        if ("base".equals(scenario.type())) {
            return "не применяется (сравнение по OPEX и TCO)";
        }
        if (scenario.calculated() && scenario.effectYear() != null
                && scenario.effectYear().signum() <= 0) {
            return "не окупается";
        }
        return "-";
    }

    /**
 * Сноска о большом baseline ФОТ при ROI > 500 % (economic_model.md
 * §8: та же сноска предусмотрена в шаблоне отчёта).
 * null - не требуется.
 *
 * @param scenarios сценарии отчёта (поиск ROI > 500 %)
 * @return текст сноски или null
 */
    public static String highRoiNote(
            java.util.List<ReportModel.ScenarioReport> scenarios) {
        boolean high = scenarios.stream().anyMatch(s -> s.calculated()
                && s.roiPct() != null
                && s.roiPct().compareTo(BigDecimal.valueOf(500)) > 0);
        return high ? "Внимание: значение ROI зависит от большого "
                      + "baseline ФОТ - проверьте допущения "
                      + "(раздел 8 методики расчёта)." : null;
    }

    /**
 * Значение с единицей: «1500 кг», «1,5 м/с».
 *
 * @param value число (null - прочерк)
 * @param fractions знаков после запятой
 * @param unit единица измерения (пустая/прочерк - не добавляется)
 * @return число с единицей
 */
    public static String formatWithUnit(BigDecimal value, int fractions,
                                        String unit) {
        String number = formatDecimal(value, fractions);
        return "-".equals(number) ? "-"
                : (unit == null || unit.isBlank() || "-".equals(unit))
                  ? number
                  : number + " " + unit;
    }

    /**
 * Проценты: «75,0 %».
 *
 * @param value процент (null - прочерк)
 * @return число с « %»
 */
    public static String formatPercent(BigDecimal value) {
        return value == null ? "-"
                : formatDecimal(value, 1) + " %";
    }
}
