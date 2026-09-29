package me.yuugao.robomatch.export;

import static org.assertj.core.api.Assertions.assertThat;


import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit-тест генератора PDF: отчёт
 * создаётся, кириллица рендерится (PDFTextStripper), все обязательные
 * блоки на месте — включая пометку «предварительная оценка» на титуле
 * И в конце. Модель — фикстура без БД.
 */
class PdfReportGeneratorTest {

    private final PdfReportGenerator generator =
            new PdfReportGenerator(new SvgRasterizer());

    // ------------------------------------------------------------------
    // Фикстуры
    // ------------------------------------------------------------------

    /**
 * Пересечение слов на одной строке (одна страница, близкий y):
 * следующее слово должно начинаться не раньше конца предыдущего
 * (допуск 0,75 pt — кернинг/округления).
 */
    private static void assertNoOverlaps(List<WordBox> words) {
        List<WordBox> sorted = new ArrayList<>(words);
        sorted.sort((a, b) -> {
            int byPage = Integer.compare(a.page(), b.page());
            if (byPage != 0) {
                return byPage;
            }
            int byY = Float.compare(b.y(), a.y());
            if (byY != 0) {
                return byY;
            }
            return Float.compare(a.x(), b.x());
        });
        for (int i = 0; i < sorted.size(); i += 1) {
            WordBox current = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j += 1) {
                WordBox next = sorted.get(j);
                if (next.page() != current.page()) {
                    break;
                }
                // разные строки (допуск по базовой линии)
                if (Math.abs(next.y() - current.y()) > 2.5f) {
                    break;
                }
                float overlap = current.x() + current.width() - next.x();
                assertThat(overlap)
                        .as("слова пересекаются: «%s» и «%s» (%.2f pt)",
                                current.text(), next.text(), overlap)
                        .isLessThanOrEqualTo(0.75f);
            }
        }
    }

    /**
 * Полная модель: 3 сценария с расчётами + имитация без схемы.
 */
    private ReportModel fullModel() {
        List<ReportModel.ParameterRow> parameters = List.of(
                new ReportModel.ParameterRow("total_warehouse_area",
                        "Общая площадь склада", "20000", "кв. м",
                        "пользователь"),
                new ReportModel.ParameterRow("shifts_per_day",
                        "Смен в сутки", "2", "смен", "по умолчанию"));
        List<ReportModel.ScenarioReport> scenarios = List.of(
                scenario("base", "Текущий процесс без роботизации", true),
                scenario("purchase", "Покупка оборудования", true),
                scenario("raas", "Роботы как услуга", true));
        ReportModel.SimulationReport simulation = new ReportModel
                .SimulationReport("Покупка оборудования",
                Instant.parse("2026-09-24T10:00:00Z"),
                List.of(new ReportModel.KpiRow("Загрузка роботов",
                        "83,3 %")), null,
                "2D-схема не сохранялась — сохраните её на странице "
                        + "имитации.");
        return new ReportModel(3L, "E2E: Максимум — 5 решений", "Склад",
                Instant.parse("2026-09-24T12:00:00Z"), "user", 5,
                parameters, scenarios, simulation,
                ReportDataBuilder.COMMON_LIMITATIONS,
                List.of(new ReportModel.AssumptionRow("k_load",
                        "Коэффициент загрузки робота (K_load)", "0.75",
                        "доля", "0.70…0.85", "снимок расчёта")),
                List.of(new ReportModel.SourceRow("ООО «Ронави»",
                        "Ronavi H1500", "organizer_catalog",
                        "catalog_export_v4.csv", "2026-09-17", List.of())),
                ReportDataBuilder.PRELIMINARY_NOTE);
    }

    private ReportModel.ScenarioReport scenario(String type, String name,
                                                boolean calculated) {
        return new ReportModel.ScenarioReport(type, name, calculated,
                calculated ? Instant.parse("2026-09-24T08:20:00Z") : null,
                calculated ? "002d9870f4a0" : null,
                calculated ? "economic-model-1.0" : null,
                "purchase".equals(type) ? 12 : 0,
                "purchase".equals(type) ? 10 : null,
                "purchase".equals(type) ? 4 : 0,
                "purchase".equals(type) ? new BigDecimal("25743025") : null,
                new BigDecimal("4200000"),
                new BigDecimal("-158000000"),
                new BigDecimal("160000000"),
                new BigDecimal("150000000"),
                "purchase".equals(type) ? new BigDecimal("0.1") : null,
                "purchase".equals(type) ? "до 1 месяца" : null,
                "purchase".equals(type) ? new BigDecimal("4758.6") : null,
                new BigDecimal("31000000"),
                new BigDecimal("25743025"),
                new BigDecimal("-69000000"),
                new BigDecimal("150000000"),
                false, false,
                "purchase".equals(type)
                        ? List.of(new ReportModel.SolutionLine(
                        "ООО «Ронави»", "Ronavi H1500", 12,
                        new BigDecimal("2500000"),
                        new BigDecimal("30000000"), false, null))
                        : List.of(),
                List.of(),
                null);
    }

    // ------------------------------------------------------------------
    // Кейсы
    // ------------------------------------------------------------------

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String raw = new PDFTextStripper().getText(document);
            // PDFTextStripper разбивает по визуальным строкам — для
            // проверок оборачиваемого текста нормализуем пробелы
            return raw.replaceAll("\\s+", " ");
        }
    }

    @Test
    void pdfCreated_nonEmpty_validHeader() {
        byte[] pdf = generator.generate(fullModel());
        assertThat(pdf).isNotNull().isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets
                .US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void darkTheme_sameContent_differsFromLight() throws Exception {
        // тёмная тема — тот же контент (все разделы и пометки),
        // но другие байты (заливка страниц + цвета текста/таблиц)
        byte[] light = generator.generate(fullModel(), false);
        byte[] dark = generator.generate(fullModel(), true);
        assertThat(dark).isNotNull().isNotEmpty();
        assertThat(new String(dark, 0, 5, java.nio.charset.StandardCharsets
                .US_ASCII)).isEqualTo("%PDF-");
        assertThat(dark).isNotEqualTo(light);
        String text = textOf(dark);
        assertThat(text).contains("Отчёт предынвестиционной оценки");
        assertThat(text).contains("E2E: Максимум — 5 решений");
        assertThat(text).contains("1. Параметры объекта");
        assertThat(text).contains("Предварительная оценка, требует "
                + "верификации");
    }

    @Test
    void cyrillicRenders_projectNamePresent() throws Exception {
        String text = textOf(generator.generate(fullModel()));
        // кириллица извлекается — шрифт встроен и корректен
        assertThat(text).contains("Отчёт предынвестиционной оценки");
        assertThat(text).contains("E2E: Максимум — 5 решений");
        assertThat(text).contains("Покупка оборудования");
    }

    @Test
    void preliminaryNote_onTitleAndAtEnd() throws Exception {
        String text = textOf(generator.generate(fullModel()));
        int first = text.indexOf("Предварительная оценка, требует "
                + "верификации");
        int last = text.lastIndexOf("Предварительная оценка, требует "
                + "верификации");
        assertThat(first).isGreaterThan(0);
        assertThat(last).isGreaterThan(first); // минимум два вхождения:
        // титул + финальная пометка
    }

    @Test
    void reportContainsAllTenSections() throws Exception {
        String text = textOf(generator.generate(fullModel()));
        assertThat(text).contains("1. Параметры объекта");
        assertThat(text).contains("2. Выбранные решения");
        assertThat(text).contains("3. Состав оборудования");
        assertThat(text).contains("4. Расчёт экономики");
        assertThat(text).contains("5. Имитация 2D и KPI");
        assertThat(text).contains("6. Ограничения");
        assertThat(text).contains("7. Источники данных");
        assertThat(text).contains("8. Дата расчёта");
        assertThat(text).contains("Версии расчёта");
        assertThat(text).contains("25 743 025"); // деньги с разрядами
        assertThat(text).contains("до 1 месяца"); // окупаемость (§4)
    }

    @Test
    void noCalculation_economicsSectionExplains() throws Exception {
        List<ReportModel.ScenarioReport> empty = List.of(new ReportModel
                .ScenarioReport("base", "Текущий процесс", false, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, false,
                false, List.of(), List.of(),
                "не заданы обязательные параметры объекта"));
        ReportModel model = new ReportModel(1L, "Проект без расчётов",
                "Склад", Instant.now(), "user", null, List.of(), empty,
                null, List.of("Платформа не заменяет детальное "
                + "проектирование."), List.of(), List.of(),
                ReportDataBuilder.PRELIMINARY_NOTE);
        String text = textOf(generator.generate(model));
        assertThat(text).contains("Расчёт не выполнен");
        assertThat(text).contains("не заданы обязательные параметры");
        assertThat(text).doesNotContain("Имитация 2D и KPI"); // раздел 6
    }

    @Test
    void noSimulation_sectionSkipped() throws Exception {
        ReportModel model = fullModel();
        ReportModel without = new ReportModel(model.projectId(),
                model.projectName(), model.objectTypeName(),
                model.generatedAt(), model.authorLogin(),
                model.horizonYears(), model.parameters(),
                model.scenarios(), null, model.limitations(),
                model.assumptions(), model.sources(),
                model.preliminaryNote());
        String text = textOf(generator.generate(without));
        assertThat(text).doesNotContain("Имитация 2D и KPI");
        assertThat(text).doesNotContain("Загрузка роботов");
    }

    @Test
    void simulationWithoutSchema_noteInsteadOfImage() throws Exception {
        String text = textOf(generator.generate(fullModel()));
        assertThat(text).contains("Загрузка роботов");
        assertThat(text).contains("2D-схема не сохранялась");
    }

    @Test
    void underpoweredFlag_drawnAsWarning() throws Exception {
        List<ReportModel.ScenarioReport> scenarios = new ArrayList<>(
                fullModel().scenarios());
        ReportModel.ScenarioReport purchase = scenarios.get(1);
        ReportModel.ScenarioReport flagged = new ReportModel.ScenarioReport(
                purchase.type(), purchase.name(), purchase.calculated(),
                purchase.calculatedAt(), purchase.versionData(),
                purchase.versionModel(), 3, 10, purchase.nInfra(),
                purchase.capex(), purchase.opexYear(), purchase.opexDelta(),
                purchase.deltaFot(), purchase.effectYear(),
                purchase.paybackYears(), purchase.paybackHuman(),
                purchase.roiPct(), purchase.tco(), purchase.capexDeltaToBase(),
                purchase.tcoDeltaToBase(), purchase.effectDeltaToBase(),
                true, false, purchase.composition(),
                purchase.warnings(), null);
        scenarios.set(1, flagged);
        ReportModel model = fullModel();
        ReportModel withFlag = new ReportModel(model.projectId(),
                model.projectName(), model.objectTypeName(),
                model.generatedAt(), model.authorLogin(),
                model.horizonYears(), model.parameters(), scenarios,
                model.simulation(), model.limitations(),
                model.assumptions(), model.sources(),
                model.preliminaryNote());
        String text = textOf(generator.generate(withFlag));
        assertThat(text).contains("меньше требуемого по пиковой нагрузке");
        assertThat(text).contains("окупаемость и ROI не отражают");
    }

    @Test
    void multiPageReport_rendersWithoutOverflow() throws Exception {
        // много параметров + предупреждений — перенос страниц
        List<ReportModel.ParameterRow> parameters = new ArrayList<>();
        for (int i = 0; i < 120; i += 1) {
            parameters.add(new ReportModel.ParameterRow(
                    "param_" + i, "Параметр №" + i, String.valueOf(i),
                    "ед.", "по умолчанию"));
        }
        ReportModel model = fullModel();
        ReportModel big = new ReportModel(model.projectId(),
                model.projectName(), model.objectTypeName(),
                model.generatedAt(), model.authorLogin(),
                model.horizonYears(), parameters, model.scenarios(),
                model.simulation(), model.limitations(),
                model.assumptions(), model.sources(),
                model.preliminaryNote());
        byte[] pdf = generator.generate(big);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
        }
        String text = textOf(pdf);
        assertThat(text).contains("Параметр №119");
        assertThat(text).contains("Предварительная оценка"); // и на
        // последней странице пометка осталась
    }

    @Test
    void highRoi_footnotePresent() throws Exception {
        // ROI покупки 4758,6 % > 500 — сноска
        // economic_model.md §8 в шаблоне отчёта
        String text = textOf(generator.generate(fullModel()));
        assertThat(text).contains("baseline ФОТ — проверьте допущения");
    }

    @Test
    void lowRoi_noFootnote() throws Exception {
        ReportModel model = fullModel();
        List<ReportModel.ScenarioReport> calm = model.scenarios().stream()
                .map(s -> new ReportModel.ScenarioReport(s.type(), s.name(),
                        s.calculated(), s.calculatedAt(), s.versionData(),
                        s.versionModel(), s.selectedRobots(),
                        s.requiredRobots(), s.nInfra(), s.capex(),
                        s.opexYear(), s.opexDelta(), s.deltaFot(),
                        s.effectYear(), s.paybackYears(), s.paybackHuman(),
                        new BigDecimal("75.0"), s.tco(),
                        s.capexDeltaToBase(), s.tcoDeltaToBase(),
                        s.effectDeltaToBase(), s.underpowered(),
                        s.overpowered(), s.composition(), s.warnings(),
                        s.calcFailureReason()))
                .toList();
        ReportModel calmModel = new ReportModel(model.projectId(),
                model.projectName(), model.objectTypeName(),
                model.generatedAt(), model.authorLogin(),
                model.horizonYears(), model.parameters(), calm,
                model.simulation(), model.limitations(), model.assumptions(),
                model.sources(), model.preliminaryNote());
        String text = textOf(generator.generate(calmModel));
        assertThat(text).doesNotContain("baseline ФОТ — проверьте допущения");
    }

    @Test
    void briefConclusion_present() throws Exception {
        // краткое заключение — текстом
        String text = textOf(generator.generate(fullModel()));
        assertThat(text).contains("Краткое заключение");
        assertThat(text).contains("быстрая окупаемость"); // категория §4
    }

    @Test
    void baseScenarioPayback_notApplicable() throws Exception {
        // единая формулировка с UI
        String text = textOf(generator.generate(fullModel()));
        assertThat(text).contains("не применяется (сравнение по OPEX и TCO)");
    }

    @Test
    void exoticGlyphs_strippedInsteadOfCrash() throws Exception {
        // эмодзи/CJK/стрелки, которых нет в Noto Sans,
        // больше не роняют генерацию 500-й — фильтр по canDisplay
        ReportModel model = fullModel();
        ReportModel spicy = new ReportModel(model.projectId(),
                "Проект ✓ 😀 中 WTF →", model.objectTypeName(),
                model.generatedAt(), model.authorLogin(),
                model.horizonYears(), model.parameters(), model.scenarios(),
                model.simulation(), model.limitations(), model.assumptions(),
                model.sources(), model.preliminaryNote());
        byte[] pdf = generator.generate(spicy); // не падает
        assertThat(pdf.length).isGreaterThan(1000);
        String text = textOf(pdf);
        assertThat(text).contains("Проект"); // кириллица жива
    }

    @Test
    void kpiValues_useDecimalComma() throws Exception {
        // 41,9 % с запятой, не «41.9 %»
        String text = textOf(generator.generate(fullModel()));
        assertThat(text).contains("83,3 %");
        assertThat(text).doesNotContain("83.3 %");
    }

    @Test
    void textLayer_hasNoTzReferences() throws Exception {
        // формулировки для читателя — без отсылок к документам
        String text = textOf(generator.generate(fullModel()));
        assertThat(text).doesNotContain("ТЗ");
        assertThat(text).contains("Предварительная оценка, требует "
                + "верификации при обследовании объекта");
    }

    @Test
    void calcFailureReason_printedInEconomicsSection() throws Exception {
        List<ReportModel.ScenarioReport> scenarios = new ArrayList<>();
        scenarios.add(new ReportModel.ScenarioReport("base",
                "Текущий процесс", true,
                Instant.parse("2026-09-24T08:20:00Z"), "v",
                "economic-model-1.0", 0, null, 0, null,
                new BigDecimal("162000000"), new BigDecimal("162000000"),
                null, null, null, null, null, new BigDecimal("810000000"),
                null, null, null, false, false, List.of(), List.of(),
                null));
        scenarios.add(new ReportModel.ScenarioReport("purchase",
                "Покупка оборудования", false, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, false, List.of(),
                List.of(), "не заданы обязательные параметры объекта"));
        ReportModel model = fullModel();
        ReportModel mixed = new ReportModel(model.projectId(),
                model.projectName(), model.objectTypeName(),
                model.generatedAt(), model.authorLogin(),
                model.horizonYears(), model.parameters(), scenarios,
                model.simulation(), model.limitations(), model.assumptions(),
                model.sources(), model.preliminaryNote());
        String text = textOf(generator.generate(mixed));
        assertThat(text).contains("Расчёт не выполнен (сценарий "
                + "«Покупка оборудования»)");
        assertThat(text).contains("не заданы обязательные параметры");
    }

    @Test
    void longUnbreakableValues_wrappedInsideCells() throws Exception {
        // патологические данные: URL без пробелов длиннее колонки
        // источников, длинные названия решений — не вылезают за колонку
        ReportModel model = fullModel();
        ReportModel spicy = new ReportModel(model.projectId(),
                "Проект с длинными значениями", model.objectTypeName(),
                model.generatedAt(), model.authorLogin(),
                model.horizonYears(), model.parameters(), model.scenarios(),
                model.simulation(), model.limitations(), model.assumptions(),
                List.of(new ReportModel.SourceRow(
                        "ООО «ОченьДлинныйПроизводительСозданДляТестаОбёртки»",
                        "Робот-доставщик-очень-длинное-название-без-пробелов"
                                + "-для-проверки-переноса-в-ячейке",
                        "open_source",
                        "https://very-long-vendor-domain.example.com/"
                                + "products/robot-model-with-long-path/"
                                + "specifications.html",
                        "2026-09-17", List.of())),
                model.preliminaryNote());
        byte[] pdf = generator.generate(spicy);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<WordBox> words = wordBoxes(document);
            // каждое слово умещается в ширину контента (никаких
            // «вылезаний» за поля страницы)
            for (WordBox word : words) {
                assertThat(word.width())
                        .as("слово шире контента: %s", word.text())
                        .isLessThanOrEqualTo(495f + 0.5f);
            }
            // и слова не пересекаются по горизонтали в своей строке
            assertNoOverlaps(words);
        }
    }

    // ------------------------------------------------------------------
    // Инфраструктура bbox-проверки вёрстки
    // ------------------------------------------------------------------

    @Test
    void fullReport_noOverlappingTextBboxes() throws Exception {
        // инвариант вёрстки: ни один текстовый блок не пересекается
        // с другим (таблицы, абзацы, плашки — общий поток)
        byte[] pdf = generator.generate(fullModel());
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertNoOverlaps(wordBoxes(document));
        }
    }

    private List<WordBox> wordBoxes(PDDocument document) throws Exception {
        // группируем глифы строки в слова по разрыву пробела (курсор —
        // изменяемый держатель: анонимный класс требует final-захват)
        List<WordBox> boxes = new ArrayList<>();
        for (int page = 0; page < document.getNumberOfPages(); page += 1) {
            final int pageNumber = page;
            final WordCursor cursor = new WordCursor();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text,
                                           List<org.apache.pdfbox.text
                                                   .TextPosition> positions) {
                    for (org.apache.pdfbox.text.TextPosition pos
                            : positions) {
                        String unicode = pos.getUnicode();
                        if (unicode == null) {
                            continue;
                        }
                        if (unicode.isBlank()) {
                            cursor.flush(boxes, pageNumber);
                            continue;
                        }
                        cursor.append(unicode, pos.getXDirAdj(),
                                pos.getYDirAdj(), pos.getEndX(),
                                pos.getHeightDir());
                    }
                    cursor.flush(boxes, pageNumber);
                }
            };
            stripper.setStartPage(page + 1);
            stripper.setEndPage(page + 1);
            stripper.getText(document);
        }
        return boxes;
    }

    /**
 * Прямоугольник слова в координатах страницы (PDF, y снизу).
 */
    private record WordBox(String text, int page, float x, float y,
                           float width, float height) {
    }

    /**
 * Держатель накопленного слова (глифы до пробела).
 */
    private static final class WordCursor {
        private final StringBuilder text = new StringBuilder();
        private float startX;
        private float y;
        private float endX;
        private float maxHeight;

        void append(String unicode, float glyphX, float glyphY,
                    float glyphEndX, float glyphHeight) {
            if (text.length() == 0) {
                startX = glyphX;
                y = glyphY;
                endX = glyphX;
            }
            text.append(unicode);
            endX = Math.max(endX, glyphEndX);
            maxHeight = Math.max(maxHeight, glyphHeight);
        }

        void flush(List<WordBox> boxes, int page) {
            if (text.length() > 0) {
                boxes.add(new WordBox(text.toString(), page, startX, y,
                        endX - startX, maxHeight));
                text.setLength(0);
                maxHeight = 0;
            }
        }
    }
}