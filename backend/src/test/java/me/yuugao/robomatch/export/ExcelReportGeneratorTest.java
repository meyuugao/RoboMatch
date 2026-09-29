package me.yuugao.robomatch.export;

import static org.assertj.core.api.Assertions.assertThat;


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
 * Unit-тест генератора Excel: машиночитаемые
 * таблицы - 7 листов, заголовки колонок с единицами,
 * числовые ячейки метрик, кириллица читается обратно через POI.
 */
class ExcelReportGeneratorTest {

    private final ExcelReportGenerator generator = new ExcelReportGenerator();

    private ReportModel model() {
        List<ReportModel.ScenarioReport> scenarios = List.of(
                scenario("base", "Текущий процесс без роботизации"),
                scenario("purchase", "Покупка оборудования"),
                scenario("raas", "Роботы как услуга"));
        return new ReportModel(3L, "E2E: Максимум - 5 решений", "Склад",
                Instant.parse("2026-09-24T12:00:00Z"), "user", 5,
                List.of(new ReportModel.ParameterRow("shifts_per_day",
                        "Смен в сутки", "2", "смен", "по умолчанию")),
                scenarios, null, ReportDataBuilder.COMMON_LIMITATIONS,
                List.of(new ReportModel.AssumptionRow("k_load",
                        "Коэффициент загрузки робота (K_load)", "0.75",
                        "доля", "0.70…0.85", "снимок расчёта")),
                List.of(new ReportModel.SourceRow("ООО «Ронави»",
                        "Ronavi H1500", "organizer_catalog",
                        "catalog_export_v4.csv", "2026-09-17",
                        List.of(new ReportModel.CharacteristicRow(
                                "payload_kg", "Грузоподъёмность",
                                "1500", "кг", "open_source",
                                "ronavi-robotics.ru", "2026-09-19")))),
                ReportDataBuilder.PRELIMINARY_NOTE);
    }

    private ReportModel.ScenarioReport scenario(String type, String name) {
        boolean robotized = !"base".equals(type);
        return new ReportModel.ScenarioReport(type, name, true,
                Instant.parse("2026-09-24T08:20:00Z"), "002d9870f4a0",
                "economic-model-1.0", robotized ? 12 : 0,
                robotized ? 10 : null, robotized ? 4 : 0,
                robotized ? new BigDecimal("25743025") : null,
                new BigDecimal("4200000"), new BigDecimal("-158000000"),
                new BigDecimal("160000000"),
                new BigDecimal("150000000"),
                robotized ? new BigDecimal("0.1") : null,
                robotized ? "до 1 месяца" : null,
                robotized ? new BigDecimal("4758.6") : null,
                new BigDecimal("31000000"),
                robotized ? new BigDecimal("25743025") : null,
                new BigDecimal("-69000000"),
                new BigDecimal("150000000"), false, false,
                robotized ? List.of(new ReportModel.SolutionLine(
                        "ООО «Ронави»", "Ronavi H1500", 12,
                        new BigDecimal("2500000"),
                        new BigDecimal("30000000"), true,
                        "проверено на объекте")) : List.of(),
                List.of(), null);
    }

    private Workbook read(byte[] xlsx) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx));
    }

    @Test
    void allSevenSheetsPresent() throws Exception {
        try (Workbook workbook = read(generator.generate(model()))) {
            assertThat(workbook).isNotNull();
            assertThat(workbook.getNumberOfSheets()).isEqualTo(7);
            assertThat(workbook.getSheetName(0)).isEqualTo("Сводка");
            assertThat(workbook.getSheetName(1)).isEqualTo("Параметры");
            assertThat(workbook.getSheetName(2)).isEqualTo("Решения");
            assertThat(workbook.getSheetName(3)).isEqualTo("Оборудование");
            assertThat(workbook.getSheetName(4)).isEqualTo("Экономика");
            assertThat(workbook.getSheetName(5)).isEqualTo("Допущения");
            assertThat(workbook.getSheetName(6)).isEqualTo("Источники");
        }
    }

    @Test
    void summarySheet_containsPreliminaryNoteAndVersions() throws Exception {
        try (Workbook workbook = read(generator.generate(model()))) {
            Sheet sheet = workbook.getSheet("Сводка");
            StringBuilder all = new StringBuilder();
            sheet.forEach(row -> row.forEach(cell -> all.append(cell)
                    .append('|')));
            assertThat(all.toString()).contains("E2E: Максимум - 5 решений");
            assertThat(all.toString()).contains(
                    ReportDataBuilder.PRELIMINARY_NOTE);
            assertThat(all.toString()).contains("economic-model-1.0");
            assertThat(all.toString()).contains("002d9870f4a0");
        }
    }

    @Test
    void economicsSheet_numericCellsAndHeadersWithUnits() throws Exception {
        try (Workbook workbook = read(generator.generate(model()))) {
            Sheet sheet = workbook.getSheet("Экономика");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue())
                    .isEqualTo("Показатель");
            assertThat(header.getCell(2).getStringCellValue())
                    .isEqualTo("Покупка оборудования");
            // числовая ячейка CAPEX покупки - читается как число
            Row capex = findRow(sheet, "CAPEX, руб.");
            assertThat(capex).isNotNull();
            assertThat(capex.getCell(2).getNumericCellValue())
                    .isEqualTo(25_743_025.0);
            // ROI - дробное число, не текст
            Row roi = findRow(sheet, "ROI за горизонт, %");
            assertThat(roi.getCell(2).getNumericCellValue())
                    .isEqualTo(4758.6);
            // человекочитаемая окупаемость - текстом
            Row payback = findRow(sheet, "Срок окупаемости");
            assertThat(payback.getCell(2).getStringCellValue())
                    .isEqualTo("до 1 месяца");
        }
    }

    @Test
    void solutionsSheet_manualFlagAndReason() throws Exception {
        try (Workbook workbook = read(generator.generate(model()))) {
            Sheet sheet = workbook.getSheet("Решения");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(3).getStringCellValue())
                    .contains("Количество");
            assertThat(header.getCell(4).getStringCellValue())
                    .contains("руб.");
            Row line = sheet.getRow(1);
            assertThat(line.getCell(0).getStringCellValue())
                    .isEqualTo("Покупка оборудования");
            assertThat(line.getCell(1).getStringCellValue())
                    .isEqualTo("ООО «Ронави»"); // кириллица читается
            assertThat(line.getCell(6).getStringCellValue()).isEqualTo("да");
            assertThat(line.getCell(7).getStringCellValue())
                    .isEqualTo("проверено на объекте");
        }
    }

    @Test
    void sourcesSheet_containsCharacteristicProvenance() throws Exception {
        try (Workbook workbook = read(generator.generate(model()))) {
            Sheet sheet = workbook.getSheet("Источники");
            Row header = sheet.getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 12);
            Row row = sheet.getRow(1);
            assertThat(row.getCell(1).getStringCellValue())
                    .isEqualTo("Ronavi H1500");
            assertThat(row.getCell(5).getStringCellValue())
                    .isEqualTo("payload_kg");
            assertThat(row.getCell(7).getStringCellValue())
                    .isEqualTo("1500");
            assertThat(row.getCell(9).getStringCellValue())
                    .isEqualTo("open_source");
        }
    }

    @Test
    void emptyScenarios_stillValidWorkbook() throws Exception {
        ReportModel full = model();
        ReportModel empty = new ReportModel(full.projectId(),
                full.projectName(), full.objectTypeName(),
                full.generatedAt(), full.authorLogin(), null,
                List.of(), List.of(), null, List.of(), List.of(),
                List.of(), full.preliminaryNote());
        try (Workbook workbook = read(generator.generate(empty))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(7);
            Sheet economics = workbook.getSheet("Экономика");
            assertThat(economics.getRow(0).getCell(0)
                    .getStringCellValue()).isEqualTo("Показатель");
            // без сценариев - только колонка показателя, файл валиден
            assertThat(economics.getRow(1).getCell(0)
                    .getStringCellValue()).isEqualTo("CAPEX, руб.");
            assertThat(economics.getRow(1).getLastCellNum())
                    .isEqualTo((short) 1);
        }
    }

    private Row findRow(Sheet sheet, String title) {
        for (Row row : sheet) {
            if (row.getCell(0) != null
                    && title.equals(row.getCell(0).getStringCellValue())) {
                return row;
            }
        }
        return null;
    }
}
