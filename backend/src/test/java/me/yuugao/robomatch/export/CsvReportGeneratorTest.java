package me.yuugao.robomatch.export;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Unit-тест генератора CSV: UTF-8 с BOM,
 * разделитель «;», запятая в десятичных дробях, кавычки RFC 4180,
 * пометка «предварительная оценка» - плоская машиночитаемая
 * альтернатива листам Excel (requirements/export.md).
 */
class CsvReportGeneratorTest {

    private final CsvReportGenerator generator = new CsvReportGenerator();

    private ReportModel model() {
        return new ReportModel(3L, "E2E: Максимум; 5 решений", "Склад",
                Instant.parse("2026-09-24T12:00:00Z"), "user", 5,
                List.of(new ReportModel.ParameterRow("shifts_per_day",
                        "Смен в сутки", "2", "смен", "по умолчанию")),
                List.of(scenario()),
                null,
                List.of(),
                List.of(),
                List.of(),
                ReportDataBuilder.PRELIMINARY_NOTE);
    }

    private ReportModel.ScenarioReport scenario() {
        return new ReportModel.ScenarioReport("purchase",
                "Покупка оборудования", true,
                Instant.parse("2026-09-24T08:20:00Z"), "002d9870f4a0",
                "economic-model-1.0", 12, 10, 4,
                new BigDecimal("25743025"), new BigDecimal("4200000"),
                new BigDecimal("-158000000"), new BigDecimal("160000000"),
                new BigDecimal("150000000"), new BigDecimal("0.1"),
                "до 1 месяца", new BigDecimal("4758.6"),
                new BigDecimal("31000000"), new BigDecimal("25743025"),
                new BigDecimal("-69000000"), new BigDecimal("150000000"),
                false, false, List.of(), List.of("Состав пуст"), null);
    }

    private String asString(byte[] csv) {
        // без BOM
        return new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    void utf8BomPresent() {
        byte[] csv = generator.generate(model());
        assertThat(csv.length).isGreaterThan(3);
        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
    }

    @Test
    void semicolonSeparator_andFlatColumns() {
        String csv = asString(generator.generate(model()));
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo("section;key;value;unit;source");
        // каждая строка - ровно 4 разделителя (5 колонок), если нет
        // закавыченных «;» внутри; числа - БЕЗ разрядов (машиночитаемо)
        assertThat(csv).contains("Экономика;Покупка оборудования - "
                + "CAPEX;25743025;руб.");
    }

    @Test
    void decimalComma_notDot() {
        String csv = asString(generator.generate(model()));
        // ROI 4758.6 → «4758,6» (запятая), окупаемость 0,1 лет
        assertThat(csv).contains("4758,6");
        assertThat(csv).contains("- срок окупаемости, лет;0,1");
        assertThat(csv).doesNotContain("4758.6");
    }

    @Test
    void rfc4180Quotes_escapedSpecials() {
        // имя проекта содержит «;» и кавычки - вся ячейка закавычена
        String csv = asString(generator.generate(model()));
        assertThat(csv).contains("\"E2E: Максимум; 5 решений\"");
    }

    @Test
    void preliminaryNoteRowPresent() {
        String csv = asString(generator.generate(model()));
        assertThat(csv).contains(ReportDataBuilder.PRELIMINARY_NOTE);
    }

    @Test
    void warningsAndPaybackHumanIncluded() {
        String csv = asString(generator.generate(model()));
        assertThat(csv).contains("Предупреждения;Покупка оборудования;"
                + "Состав пуст");
        assertThat(csv).contains("до 1 месяца");
    }

    @Test
    void formulaInjection_neutralized() {
        // имя проекта с формулой Excel - префикс «'»;
        // чистые отрицательные числа остаются числами
        ReportModel full = model();
        ReportModel poisoned = new ReportModel(full.projectId(),
                "=HYPERLINK(\"http://evil\",\"x\")",
                full.objectTypeName(), full.generatedAt(),
                full.authorLogin(), full.horizonYears(),
                full.parameters(), full.scenarios(), full.simulation(),
                full.limitations(), full.assumptions(), full.sources(),
                full.preliminaryNote());
        String csv = asString(generator.generate(poisoned));
        assertThat(csv).contains("'=HYPERLINK");
        assertThat(csv).doesNotContain(";=HYPERLINK");
        // отрицательные суммы не префиксируются - машиночитаемость
        assertThat(csv).contains("-158000000");
    }

    @Test
    void decimalComma_inParametersAndAssumptions() {
        // значения с точкой из БД - к запятой
        ReportModel full = model();
        ReportModel dotted = new ReportModel(full.projectId(),
                full.projectName(), full.objectTypeName(),
                full.generatedAt(), full.authorLogin(),
                full.horizonYears(),
                List.of(new ReportModel.ParameterRow("pallet_unit_weight",
                        "Вес паллеты", "2.5", "кг", "по умолчанию")),
                full.scenarios(), full.simulation(), full.limitations(),
                List.of(new ReportModel.AssumptionRow("k_load",
                        "Коэффициент загрузки", "0.75", "доля",
                        "0.70…0.85", "снимок")),
                full.sources(), full.preliminaryNote());
        String csv = asString(generator.generate(dotted));
        assertThat(csv).contains("2,5");
        assertThat(csv).contains("0,75");
        assertThat(csv).doesNotContain("2.5;");
    }

    @Test
    void baseScenarioPayback_notApplicable() {
        // единая формулировка с UI - «не применяется»
        ReportModel full = model();
        ReportModel withBase = new ReportModel(full.projectId(),
                full.projectName(), full.objectTypeName(),
                full.generatedAt(), full.authorLogin(),
                full.horizonYears(), full.parameters(),
                List.of(new ReportModel.ScenarioReport("base",
                        "Текущий процесс без роботизации", true,
                        Instant.parse("2026-09-24T08:20:00Z"), "v",
                        "economic-model-1.0", 0, null, 0, null,
                        BigDecimal.ZERO, null, null, BigDecimal.ZERO,
                        null, null, null, null, null, null, null, false,
                        false, List.of(), List.of(), null)),
                full.simulation(), full.limitations(), full.assumptions(),
                full.sources(), full.preliminaryNote());
        String csv = asString(generator.generate(withBase));
        assertThat(csv).contains("- срок окупаемости;не применяется "
                + "(сравнение по OPEX и TCO)");
    }
}
