package me.yuugao.robomatch.export;

import static org.assertj.core.api.Assertions.assertThat;


import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Консистентность экспорта: PDF, XLSX и CSV строятся из
 * ОДНОЙ модели (проекции metrics_json расчёта — источник №4) —
 * ключевые метрики обязаны совпадать 1:1 во всех источниках:
 * CAPEX, OPEX, ΔOPEX, ΔFOT, Effect_year, Payback, ROI, TCO,
 * N_robots. Плюс структурные инварианты: 7 листов XLSX, шапка CSV,
 * страницы/оглавление/нумерация PDF.
 */
class ExportConsistencyTest {

    // эталонные метрики сценария покупки (модель = проекция
    // metrics_json последнего расчёта — источник №4)
    private static final BigDecimal CAPEX = new BigDecimal("25743025");
    private static final BigDecimal OPEX = new BigDecimal("4200000");
    private static final BigDecimal OPEX_DELTA = new BigDecimal("-158000000");
    private static final BigDecimal DELTA_FOT = new BigDecimal("160000000");
    private static final BigDecimal EFFECT = new BigDecimal("150000000");
    private static final BigDecimal PAYBACK = new BigDecimal("2.5");
    private static final BigDecimal ROI = new BigDecimal("4758.6");
    private static final BigDecimal TCO = new BigDecimal("31000000");
    private static final Integer ROBOTS = 12;
    private final ExcelReportGenerator excel = new ExcelReportGenerator();
    private final CsvReportGenerator csv = new CsvReportGenerator();
    private final PdfReportGenerator pdf = new PdfReportGenerator(null);

    /**
 * Число из листа «Экономика»: строка по метке, колонка сценария
 * покупки (определяется по шапке — колонки идут в порядке
 * сценариев модели).
 */
    private static double xlsxMetric(Sheet sheet, String label) {
        int purchaseColumn = -1;
        for (Row row : sheet) {
            if (row.getCell(0) != null && row.getRowNum() == 0) {
                for (int c = 1; c < row.getLastCellNum(); c += 1) {
                    if ("Покупка оборудования".equals(
                            row.getCell(c).getStringCellValue())) {
                        purchaseColumn = c;
                    }
                }
            }
        }
        assertThat(purchaseColumn).as("колонка покупки в шапке").isPositive();
        for (Row row : sheet) {
            if (row.getCell(0) != null
                    && label.equals(row.getCell(0).getStringCellValue())) {
                return row.getCell(purchaseColumn).getNumericCellValue();
            }
        }
        throw new AssertionError("строка «" + label + "» не найдена");
    }

    // --- источники -----------------------------------------------------

    /**
 * Значение CSV: строка «<раздел>;Покупка оборудования — key;value»
 * (экономика и оборудование — оба раздела допустимы).
 */
    private static String csvValue(String csv, String key) {
        String wanted = "Покупка оборудования — " + key;
        for (String line : csv.split("\r\n")) {
            String[] parts = line.split(";", -1);
            if (parts.length >= 3 && parts[1].equals(wanted)) {
                return parts[2];
            }
        }
        throw new AssertionError("строка CSV «" + key + "» не найдена");
    }

    /**
 * Число из CSV (десятичная запятая).
 */
    private static double csvNumber(String csv, String key) {
        return Double.parseDouble(csvValue(csv, key).replace(',', '.'));
    }

    private static double of(BigDecimal value) {
        return value.doubleValue();
    }

    private ReportModel model() {
        ReportModel.ScenarioReport purchase =
                new ReportModel.ScenarioReport("purchase",
                        "Покупка оборудования", true,
                        Instant.parse("2026-09-24T08:20:00Z"), "002d9870f4a0",
                        "economic-model-1.0", ROBOTS, 10, 4, CAPEX, OPEX,
                        OPEX_DELTA, DELTA_FOT, EFFECT, PAYBACK,
                        "2,5 года (до 1 месяца амортизации не входит)",
                        ROI, TCO, CAPEX, TCO.add(new BigDecimal("5000000")),
                        EFFECT, false, false,
                        List.of(new ReportModel.SolutionLine(
                                "ООО «Ронави»", "Ronavi H1500", 5,
                                new BigDecimal("2700000"),
                                new BigDecimal("13500000"), false, null)),
                        List.of(), null);
        ReportModel.ScenarioReport base =
                new ReportModel.ScenarioReport("base",
                        "Текущий процесс без роботизации", true,
                        Instant.parse("2026-09-24T08:20:00Z"), "002d9870f4a0",
                        "economic-model-1.0", 0, null, 0, null,
                        new BigDecimal("162000000"),
                        new BigDecimal("162000000"), null, null, null, null,
                        null, new BigDecimal("810000000"), null, null, null,
                        false, false, List.of(), List.of(), null);
        return new ReportModel(3L, "E2E: Максимум — 5 решений", "Склад",
                Instant.parse("2026-09-24T12:00:00Z"), "user", 5,
                List.of(), List.of(base, purchase), null,
                ReportDataBuilder.COMMON_LIMITATIONS, List.of(), List.of(),
                ReportDataBuilder.PRELIMINARY_NOTE);
    }

    private String pdfText(ReportModel model) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.generate(model))) {
            return new PDFTextStripper().getText(document)
                    .replace('\u00A0', ' ');
        }
    }

    private Sheet economicsSheet(ReportModel model) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(excel.generate(model)))) {
            return workbook.getSheet("Экономика");
        }
    }

    private String csvText(ReportModel model) {
        String text = new String(csv.generate(model),
                java.nio.charset.StandardCharsets.UTF_8);
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }

    // --- метрики: 1:1 в PDF (текст), XLSX (ячейка), CSV (значение) ----

    @Test
    void capex_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        String text = pdfText(model);
        assertThat(text).contains("25 743 025");
        assertThat(xlsxMetric(economicsSheet(model), "CAPEX, руб."))
                .isEqualTo(of(CAPEX));
        assertThat(csvNumber(csvText(model), "CAPEX"))
                .isEqualTo(of(CAPEX));
    }

    @Test
    void opex_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        assertThat(pdfText(model)).contains("4 200 000");
        assertThat(xlsxMetric(economicsSheet(model), "OPEX годовой, руб."))
                .isEqualTo(of(OPEX));
        assertThat(csvNumber(csvText(model), "OPEX годовой"))
                .isEqualTo(of(OPEX));
    }

    @Test
    void opexDelta_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        assertThat(pdfText(model)).contains("−158 000 000");
        assertThat(xlsxMetric(economicsSheet(model),
                "ΔOPEX к базовому, руб.")).isEqualTo(of(OPEX_DELTA));
        assertThat(csvNumber(csvText(model), "ΔOPEX к базовому"))
                .isEqualTo(of(OPEX_DELTA));
    }

    @Test
    void deltaFot_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        assertThat(pdfText(model)).contains("160 000 000");
        assertThat(xlsxMetric(economicsSheet(model), "ΔFOT, руб./год"))
                .isEqualTo(of(DELTA_FOT));
        assertThat(csvNumber(csvText(model), "ΔFOT"))
                .isEqualTo(of(DELTA_FOT));
    }

    @Test
    void effectYear_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        assertThat(pdfText(model)).contains("150 000 000");
        assertThat(xlsxMetric(economicsSheet(model), "Годовой эффект, руб."))
                .isEqualTo(of(EFFECT));
        assertThat(csvNumber(csvText(model), "Годовой эффект"))
                .isEqualTo(of(EFFECT));
    }

    @Test
    void payback_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        assertThat(pdfText(model)).contains("2,5");
        assertThat(xlsxMetric(economicsSheet(model), "Срок окупаемости, лет"))
                .isEqualTo(of(PAYBACK));
        assertThat(csvNumber(csvText(model), "срок окупаемости, лет"))
                .isEqualTo(of(PAYBACK));
    }

    @Test
    void roi_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        assertThat(pdfText(model)).contains("4758,6");
        assertThat(xlsxMetric(economicsSheet(model), "ROI за горизонт, %"))
                .isEqualTo(of(ROI));
        assertThat(csvNumber(csvText(model), "ROI за горизонт"))
                .isEqualTo(of(ROI));
    }

    @Test
    void tco_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        assertThat(pdfText(model)).contains("31 000 000");
        assertThat(xlsxMetric(economicsSheet(model), "TCO на горизонте, руб."))
                .isEqualTo(of(TCO));
        assertThat(csvNumber(csvText(model), "TCO на горизонте"))
                .isEqualTo(of(TCO));
    }

    @Test
    void robots_consistentAcrossAllFormats() throws Exception {
        ReportModel model = model();
        assertThat(pdfText(model)).contains("12");
        try (Workbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(excel.generate(model)))) {
            Sheet equipment = workbook.getSheet("Оборудование");
            double robots = -1;
            for (Row row : equipment) {
                if (row.getCell(0) != null && "Покупка оборудования".equals(
                        row.getCell(0).getStringCellValue())) {
                    robots = row.getCell(1).getNumericCellValue();
                }
            }
            assertThat(robots).isEqualTo(ROBOTS.doubleValue());
        }
        assertThat(csvValue(csvText(model), "роботы")).isEqualTo("12");
    }

    // --- структурные инварианты форматов -------------------------------

    @Test
    void xlsx_hasSevenSheetsFreezeFilterAndFormats() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(excel.generate(model())))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(7);
            Sheet economics = workbook.getSheet("Экономика");
            assertThat(economics.getPaneInformation().isFreezePane())
                    .as("закрепление шапки").isTrue();
            assertThat(((org.apache.poi.xssf.usermodel.XSSFSheet) economics)
                    .getCTWorksheet().getAutoFilter())
                    .as("автофильтр").isNotNull();
            assertThat(economics.getSheetConditionalFormatting()
                    .getNumConditionalFormattings())
                    .as("условное форматирование Δ-строк")
                    .isGreaterThanOrEqualTo(1);
            // числовые форматы: деньги/проценты/годы
            String capexFormat = null;
            String roiFormat = null;
            String paybackFormat = null;
            for (Row row : economics) {
                if (row.getCell(0) == null) {
                    continue;
                }
                String label = row.getCell(0).getStringCellValue();
                if (label.equals("CAPEX, руб.") && row.getCell(2) != null) {
                    capexFormat = row.getCell(2).getCellStyle()
                            .getDataFormatString();
                }
                if (label.equals("ROI за горизонт, %")
                        && row.getCell(2) != null) {
                    roiFormat = row.getCell(2).getCellStyle()
                            .getDataFormatString();
                }
                if (label.equals("Срок окупаемости, лет")
                        && row.getCell(2) != null) {
                    paybackFormat = row.getCell(2).getCellStyle()
                            .getDataFormatString();
                }
            }
            assertThat(capexFormat).isEqualTo("# ##0");
            assertThat(roiFormat).isEqualTo("0.0 %");
            assertThat(paybackFormat).isEqualTo("0.0");
        }
    }

    @Test
    void csv_bomSemicolonDecimalCommaAndRfc4180() {
        byte[] bytes = csv.generate(model());
        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);   // UTF-8 BOM
        String text = csvText(model());
        assertThat(text).startsWith("section;key;value;unit;source");
        assertThat(text).contains("\r\n");             // RFC 4180
        assertThat(csvValue(text, "CAPEX")).isEqualTo("25743025");
        assertThat(csvValue(text, "ROI за горизонт")).isEqualTo("4758,6");
    }

    @Test
    void pdf_tocPageNumbersAndSections() throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.generate(model()))) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            assertThat(document.getDocumentCatalog().getDocumentOutline())
                    .as("закладки-оглавление").isNotNull();
            assertThat(document.getPage(0).getAnnotations())
                    .as("кликабельные ссылки оглавления")
                    .isNotEmpty();
            String text = pdfText(model());
            assertThat(text).contains("Содержание");
            assertThat(text).contains("Страница 1 из "
                    + document.getNumberOfPages());
        }
    }
}
