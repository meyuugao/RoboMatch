package me.yuugao.robomatch.export;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Генератор Excel-отчёта:
 * машиночитаемые таблицы - 7 листов POI XSSF 5.4.1 (Apache 2.0 -
 * свободное ПО,; та же зависимость, что у импорта параметров).
 *
 * <p>ЛИСТЫ: Сводка (титул + версии) · Параметры · Решения · Оборудование ·
 * Экономика (числовые ячейки - считаются дальше в Excel) · Допущения
 * (снимок последнего расчёта) · Источники (ТТХ решений из
 * solution_characteristic с провенансом). Пометка
 * «предварительная оценка» - на листе «Сводка».
 *
 * <p>Единицы измерения - в заголовках колонок; деньги -
 * числом (руб.), проценты - числом.
 */
@Component
public class ExcelReportGenerator {

    /**
 * Числовой кэш стилей текущей книги (создаётся в generate).
 */
    private Formats formats;

    // ------------------------------------------------------------------
    // Листы
    // ------------------------------------------------------------------

    private static String[] headerOf(
            List<ReportModel.ScenarioReport> scenarios) {
        String[] headers = new String[scenarios.size() + 1];
        headers[0] = "Показатель";
        for (int i = 0; i < scenarios.size(); i += 1) {
            headers[i + 1] = scenarios.get(i).name();
        }
        return headers;
    }

    private static Object[] textRowOf(String title,
                                      List<ReportModel.ScenarioReport> scenarios,
                                      java.util.function.Function<ReportModel.ScenarioReport, String>
                                              extractor) {
        Object[] cells = new Object[scenarios.size() + 1];
        cells[0] = title;
        for (int i = 0; i < scenarios.size(); i += 1) {
            cells[i + 1] = extractor.apply(scenarios.get(i));
        }
        return cells;
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        // акцентный фон шапок - светлый изумрудный (как акцент UI),
        // текст остаётся чёрным (контраст WCAG AA)
        style.setFillForegroundColor(
                IndexedColors.LIGHT_TURQUOISE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    /**
 * Титульная строка (жирная, крупнее).
 */
    private static int title(Sheet sheet, int rowIndex, String text) {
        Row row = sheet.createRow(rowIndex);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        sheet.setColumnWidth(0, 26 * 256);
        return rowIndex + 1;
    }

    private static void row(Sheet sheet, int rowIndex, String key,
                            String value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value == null ? "-" : value);
    }

    /**
 * Сгенерировать книгу: каждый вызов - своя книга; стиль шапки -
 * ЛОКАЛЬНЫЙ на книгу (общее поле в синглтоне
 * роняло параллельные экспорты разных проектов - CellStyle чужой
 * книги недопустим).
 *
 * @param model модель отчёта (готовые метрики, без пересчёта)
 * @return байты XLSX (7 листов)
 */
    public byte[] generate(ReportModel model) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            this.formats = new Formats(workbook);
            summarySheet(workbook, model, headerStyle);
            parametersSheet(workbook, model, headerStyle);
            solutionsSheet(workbook, model, headerStyle);
            equipmentSheet(workbook, model, headerStyle);
            economicsSheet(workbook, model, headerStyle);
            assumptionsSheet(workbook, model, headerStyle);
            sourcesSheet(workbook, model, headerStyle);
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Не удалось сгенерировать Excel-отчёт", ex);
        }
    }

    private void summarySheet(Workbook workbook, ReportModel model,
                              CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Сводка");
        int rowIndex = 0;
        rowIndex = title(sheet, rowIndex, "Отчёт предынвестиционной "
                + "оценки роботизации");
        row(sheet, rowIndex++, "Проект", model.projectName());
        row(sheet, rowIndex++, "Тип объекта", model.objectTypeName());
        row(sheet, rowIndex++, "Дата генерации отчёта",
                ReportFormats.formatDateTime(model.generatedAt()));
        row(sheet, rowIndex++, "Автор", model.authorLogin());
        row(sheet, rowIndex++, "Горизонт расчёта, лет",
                model.horizonYears() == null ? "-"
                        : String.valueOf(model.horizonYears()));
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            if (scenario.calculated()) {
                row(sheet, rowIndex++,
                        "Версия расчёта - " + scenario.name(),
                        "данные " + scenario.versionData() + ", модель "
                                + scenario.versionModel() + ", "
                                + ReportFormats.formatDateTime(
                                scenario.calculatedAt()));
            }
        }
        // сноска о большом baseline ФОТ (economic_model.md §8)
        String roiNote = ReportFormats.highRoiNote(model.scenarios());
        if (roiNote != null) {
            row(sheet, rowIndex++, "Сноска о ROI", roiNote);
        }
        row(sheet, rowIndex++, "Пометка",
                model.preliminaryNote());
        // ширины по содержимому сводки: колонка-ключ и колонка-значение
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 64 * 256);
    }

    // ------------------------------------------------------------------
    // Вспомогательные
    // ------------------------------------------------------------------

    private void parametersSheet(Workbook workbook, ReportModel model,
                                 CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Параметры");
        Table table = table(sheet, headerStyle, "Код", "Название",
                "Значение", "Единица", "Источник");
        for (ReportModel.ParameterRow parameter : model.parameters()) {
            table.textRow(parameter.code(), parameter.name(),
                    parameter.value(), parameter.unit(), parameter.source());
        }
        table.finish();
    }

    private void solutionsSheet(Workbook workbook, ReportModel model,
                                CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Решения");
        Table table = table(sheet, headerStyle, "Сценарий",
                "Производитель", "Решение",
                "Количество, ед.", "Цена за ед., руб.", "Сумма, руб.",
                "Добавлено вручную", "Причина");
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            for (ReportModel.SolutionLine line : scenario.composition()) {
                table.row(new String[]{null, null, null, Formats.COUNT,
                                Formats.MONEY, Formats.MONEY, null, null},
                        scenario.name(), line.vendorName(),
                        line.solutionName(),
                        line.quantity() == null ? null
                                : line.quantity().doubleValue(),
                        line.priceRub() == null ? null
                                : line.priceRub().doubleValue(),
                        line.totalRub() == null ? null
                                : line.totalRub().doubleValue(),
                        line.manual() ? "да" : "нет",
                        line.manualReason() == null ? ""
                                : line.manualReason());
            }
        }
        table.finish();
    }

    private void equipmentSheet(Workbook workbook, ReportModel model,
                                CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Оборудование");
        Table table = table(sheet, headerStyle, "Сценарий", "Роботы, ед.",
                "Инфраструктура, ед.", "Разбивка по решениям");
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            StringBuilder breakdown = new StringBuilder();
            for (ReportModel.SolutionLine line : scenario.composition()) {
                if (!breakdown.isEmpty()) {
                    breakdown.append("; ");
                }
                breakdown.append(line.solutionName()).append(" × ")
                        .append(line.quantity());
            }
            table.row(new String[]{null, Formats.COUNT, Formats.COUNT,
                            null},
                    scenario.name(),
                    scenario.selectedRobots() == null ? null
                            : scenario.selectedRobots().doubleValue(),
                    scenario.nInfra() == null ? null
                            : scenario.nInfra().doubleValue(),
                    breakdown.isEmpty() ? "-" : breakdown.toString());
        }
        table.finish();
    }

    private void economicsSheet(Workbook workbook, ReportModel model,
                                CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Экономика");
        List<ReportModel.ScenarioReport> scenarios = model.scenarios();
        Table table = table(sheet, headerStyle, headerOf(scenarios));
        metric(table, scenarios, "CAPEX, руб.",
                ReportModel.ScenarioReport::capex, Formats.MONEY);
        metric(table, scenarios, "OPEX годовой, руб.",
                ReportModel.ScenarioReport::opexYear, Formats.MONEY);
        metric(table, scenarios, "ΔOPEX к базовому, руб.",
                ReportModel.ScenarioReport::opexDelta, Formats.MONEY);
        metric(table, scenarios, "ΔFOT, руб./год",
                ReportModel.ScenarioReport::deltaFot, Formats.MONEY);
        metric(table, scenarios, "Годовой эффект, руб.",
                ReportModel.ScenarioReport::effectYear, Formats.MONEY);
        table.textRow(textRowOf("Срок окупаемости", scenarios,
                ReportFormats::paybackText));
        metric(table, scenarios, "Срок окупаемости, лет",
                ReportModel.ScenarioReport::paybackYears, Formats.YEARS);
        metric(table, scenarios, "ROI за горизонт, %",
                ReportModel.ScenarioReport::roiPct, Formats.PERCENT);
        metric(table, scenarios, "TCO на горизонте, руб.",
                ReportModel.ScenarioReport::tco, Formats.MONEY);
        metric(table, scenarios, "ΔCAPEX к базовому, руб.",
                ReportModel.ScenarioReport::capexDeltaToBase, Formats.MONEY);
        metric(table, scenarios, "ΔTCO к базовому, руб.",
                ReportModel.ScenarioReport::tcoDeltaToBase, Formats.MONEY);
        metric(table, scenarios, "Δ эффекта к базовому, руб.",
                ReportModel.ScenarioReport::effectDeltaToBase, Formats.MONEY);
        table.textRow(textRowOf("Дата расчёта", scenarios,
                s -> s.calculated()
                        ? ReportFormats.formatDateTime(s.calculatedAt())
                        : "-"));
        table.textRow(textRowOf("Версия данных", scenarios,
                s -> s.calculated() ? s.versionData() : "-"));
        // сноска о большом baseline ФОТ
        String roiNote = ReportFormats.highRoiNote(scenarios);
        if (roiNote != null) {
            table.textRow(roiNote);
        }
        table.finish();
    }

    private void assumptionsSheet(Workbook workbook, ReportModel model,
                                  CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Допущения");
        Table table = table(sheet, headerStyle, "Код", "Название",
                "Значение", "Единица", "Диапазон", "Источник значений");
        for (ReportModel.AssumptionRow assumption : model.assumptions()) {
            table.textRow(assumption.name(), assumption.title(),
                    assumption.value(), assumption.unit(),
                    assumption.range(), assumption.source());
        }
        table.finish();
    }

    private void sourcesSheet(Workbook workbook, ReportModel model,
                              CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Источники");
        Table table = table(sheet, headerStyle, "Производитель",
                "Решение",
                "Источник решения", "Ссылка", "Дата", "ТТХ код",
                "ТТХ название", "Значение ТТХ", "Единица",
                "Источник ТТХ", "Ссылка ТТХ", "Дата ТТХ");
        for (ReportModel.SourceRow source : model.sources()) {
            if (source.characteristics().isEmpty()) {
                table.textRow(source.vendorName(), source.solutionName(),
                        source.sourceKind(), source.sourceUrl(),
                        source.sourceDate(), "", "", "", "", "", "", "");
                continue;
            }
            for (ReportModel.CharacteristicRow characteristic
                    : source.characteristics()) {
                table.textRow(source.vendorName(), source.solutionName(),
                        source.sourceKind(), source.sourceUrl(),
                        source.sourceDate(), characteristic.code(),
                        characteristic.name(), characteristic.value(),
                        characteristic.unit(), characteristic.sourceKind(),
                        characteristic.sourceUrl(),
                        characteristic.sourceDate());
            }
        }
        table.finish();
    }

    private void metric(Table table,
                        List<ReportModel.ScenarioReport> scenarios, String title,
                        ScenarioGetter getter, String format) {
        int width = scenarios.size() + 1;
        Object[] cells = new Object[width];
        String[] formats = new String[width];
        formats[0] = null;
        for (int i = 0; i < width - 1; i += 1) {
            formats[i + 1] = format;
        }
        cells[0] = title;
        for (int i = 0; i < scenarios.size(); i += 1) {
            ReportModel.ScenarioReport scenario = scenarios.get(i);
            BigDecimal value = scenario.calculated() ? getter.get(scenario)
                    : null;
            cells[i + 1] = value == null ? null : value.doubleValue();
        }
        table.row(formats, cells);
    }

    private Table table(Sheet sheet, CellStyle headerStyle,
                        String... headers) {
        return new Table(sheet, headerStyle, formats, headers);
    }

    private interface ScenarioGetter {
        BigDecimal get(ReportModel.ScenarioReport scenario);
    }

    /**
 * Числовые форматы листа (
 * годы 0,0, штуки 0) - кэш стилей на книгу.
 */
    static final class Formats {

        /**
 * Деньги: пробел-разделитель тысяч, без копеек.
 */
        static final String MONEY = "# ##0";
        /**
 * Проценты: одна десятичная, суффикс « %» (значение уже в %).
 */
        static final String PERCENT = "0.0" + " %";
        /**
 * Годы: одна десятичная.
 */
        static final String YEARS = "0.0";
        /**
 * Штук/единиц: целое.
 */
        static final String COUNT = "0";

        private final Workbook workbook;
        private final java.util.Map<String, CellStyle> cache
                = new java.util.HashMap<>();

        Formats(Workbook workbook) {
            this.workbook = workbook;
        }

        CellStyle of(String format) {
            return cache.computeIfAbsent(format, f -> {
                CellStyle style = workbook.createCellStyle();
                style.setDataFormat(workbook.createDataFormat()
                        .getFormat(f));
                return style;
            });
        }
    }

    /**
 * Общая для листов табличная обёртка: шапка + строки; стиль -
 * ЛОКАЛЬНЫЙ на книгу (см. generate). Ширина колонок - по
 * содержимому (максимум длины отображаемого значения + запас,
 * в границах 10–55 символов), на лист включается автофильтр,
 * строки-«Δ» получают условное форматирование (отрицательные -
 * красным, положительные - зелёным).
 */
    private static final class Table {

        private final Sheet sheet;
        private final CellStyle headerStyle;
        private final Formats formats;
        private final java.util.List<String> columnWidths
                = new java.util.ArrayList<>();
        private final java.util.List<Integer> deltaRows
                = new java.util.ArrayList<>();
        private int rowIndex;

        Table(Sheet sheet, CellStyle headerStyle, Formats formats,
              String... headers) {
            this.sheet = sheet;
            this.headerStyle = headerStyle;
            this.formats = formats;
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i += 1) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                note(i, headers[i].length());
            }
            sheet.createFreezePane(0, 1);
            rowIndex = 1;
        }

        /**
 * Ориентировочная длина отображаемого числа (для ширины).
 */
        private static int displayLength(Number number, String format) {
            double v = number.doubleValue();
            if (Formats.PERCENT.equals(format)) {
                return String.format(Locale.ROOT, "%.1f %%", v).length();
            }
            if (Formats.YEARS.equals(format)) {
                return String.format(Locale.ROOT, "%.1f", v).length();
            }
            if (Formats.COUNT.equals(format)) {
                return String.format(Locale.ROOT, "%.0f", v).length();
            }
            // деньги: группировка пробелом по 3 разряда
            return String.format(Locale.ROOT, "%,.0f", v)
                    .replace(",", " ").length();
        }

        void textRow(Object... cells) {
            row(null, cells);
        }

        void row(Object... cells) {
            row(null, cells);
        }

        void row(String[] numberFormats, Object... cells) {
            Row row = sheet.createRow(rowIndex);
            boolean delta = cells.length > 0 && cells[0] != null
                    && String.valueOf(cells[0]).startsWith("\u0394");
            if (delta) {
                deltaRows.add(rowIndex);
            }
            for (int i = 0; i < cells.length && i < 256; i += 1) {
                Cell cell = row.createCell(i);
                Object value = cells[i];
                if (value == null) {
                    cell.setBlank();
                    note(i, 1);
                } else if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                    String format = numberFormats != null
                            && i < numberFormats.length
                            ? numberFormats[i] : null;
                    if (format != null) {
                        cell.setCellStyle(formats.of(format));
                    }
                    note(i, displayLength(number, format));
                } else {
                    String text = String.valueOf(value);
                    cell.setCellValue(text);
                    note(i, text.length());
                }
            }
            rowIndex += 1;
        }

        private void note(int column, int length) {
            while (columnWidths.size() <= column) {
                columnWidths.add(" ".repeat(10));
            }
            if (length > columnWidths.get(column).length()) {
                columnWidths.set(column, " ".repeat(Math.max(10, length)));
            }
        }

        void finish() {
            for (int i = 0; i < columnWidths.size(); i += 1) {
                int chars = Math.min(55, columnWidths.get(i).length() + 2);
                sheet.setColumnWidth(i, chars * 256);
            }
            int lastColumn = Math.max(0, columnWidths.size() - 1);
            if (rowIndex > 1) {
                sheet.setAutoFilter(new org.apache.poi.ss.util
                        .CellRangeAddress(0, rowIndex - 1, 0, lastColumn));
            }
            if (!deltaRows.isEmpty() && lastColumn >= 1) {
                org.apache.poi.ss.usermodel.SheetConditionalFormatting cf
                        = sheet.getSheetConditionalFormatting();
                for (int r : deltaRows) {
                    org.apache.poi.ss.util.CellRangeAddress region =
                            new org.apache.poi.ss.util.CellRangeAddress(
                                    r, r, 1, lastColumn);
                    org.apache.poi.ss.usermodel.ConditionalFormattingRule
                            negative = cf.createConditionalFormattingRule(
                            org.apache.poi.ss.usermodel.ComparisonOperator.LT,
                            "0");
                    org.apache.poi.ss.usermodel.FontFormatting negativeFont =
                            negative.createFontFormatting();
                    negativeFont.setFontColorIndex(
                            org.apache.poi.ss.usermodel.IndexedColors.RED
                                    .getIndex());
                    org.apache.poi.ss.usermodel.ConditionalFormattingRule
                            positive = cf.createConditionalFormattingRule(
                            org.apache.poi.ss.usermodel.ComparisonOperator.GT,
                            "0");
                    org.apache.poi.ss.usermodel.FontFormatting positiveFont =
                            positive.createFontFormatting();
                    positiveFont.setFontColorIndex(
                            org.apache.poi.ss.usermodel.IndexedColors.GREEN
                                    .getIndex());
                    cf.addConditionalFormatting(
                            new org.apache.poi.ss.util.CellRangeAddress[]{
                                    region},
                            new org.apache.poi.ss.usermodel
                                    .ConditionalFormattingRule[]{
                                    negative, positive});
                }
            }
        }
    }

}
