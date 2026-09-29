package me.yuugao.robomatch.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination
        .PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline
        .PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline
        .PDOutlineItem;
import org.springframework.stereotype.Component;

import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Генератор PDF-отчёта: человекочитаемый единый
 * документ. Apache PDFBox 3.x (Apache 2.0 — свободное ПО);
 * кириллица — встроенные TTF Noto Sans Regular+Bold (OFL,
 * resources/fonts) через PDType0Font; без шрифтового файла — честный
 * отказ генерации (кириллица стандартных 14 шрифтов PDF не покрывает).
 *
 * <p>СТРУКТУРА (10 разделов + пометка на титуле и в
 * конце): титул → параметры объекта → выбранные решения → состав
 * оборудования → расчёт экономики (3 сценария + Δ к базовому + версии
 * данных/модели + флаги парка) → имитация 2D с KPI (если запускалась)
 * → ограничения (+ предупреждения расчётов) → источники данных →
 * даты расчётов → пометка «предварительная оценка».
 *
 * <p>ВЕРСТКА: A4, поля 50 pt, поток с автопереносом страниц; таблицы —
 * сетка с переносом текста в ячейках; числа — ReportFormats (пробел
 * разрядов, запятая-десятичная, «руб.»).
 */
@Component
public class PdfReportGenerator {

    // ------------------------------------------------------------------
    // Метрики страницы
    // ------------------------------------------------------------------

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 50f;
    private static final float CONTENT_WIDTH = PAGE.getWidth() - 2
            * MARGIN;
    private static final float FONT_BODY = 9f;
    private static final float FONT_SMALL = 8f;
    private static final float LEADING = 1.35f;
    private static final float GAP_MEDIUM = 10f;
    private static final float GAP_SMALL = 5f;
    private static final Palette LIGHT = new Palette(
            null,
            new float[]{0f, 0f, 0f},
            new float[]{0.95f, 0.96f, 0.98f},
            new float[]{0.75f, 0.79f, 0.85f},
            new float[]{0.8f, 0.83f, 0.87f},
            new float[]{0.93f, 0.95f, 0.97f},
            new float[]{0.75f, 0.79f, 0.85f},
            // акцент заголовков разделов — изумрудный (акцент UI)
            new float[]{0.02f, 0.467f, 0.412f},   // #057a69
            // зебра-полосы строк таблиц
            new float[]{0.955f, 0.965f, 0.975f},
            new float[]{0.45f, 0.5f, 0.55f});
    private static final Palette DARK = new Palette(
            new float[]{0.059f, 0.09f, 0.165f},   // #0f172a (slate-900)
            new float[]{0.886f, 0.91f, 0.941f},   // #e2e8f0 (slate-200)
            new float[]{0.118f, 0.161f, 0.231f},  // #1e293b (slate-800)
            new float[]{0.278f, 0.333f, 0.412f},  // #475569 (slate-600)
            new float[]{0.2f, 0.255f, 0.318f},    // #334155 (slate-700)
            new float[]{0.118f, 0.161f, 0.231f},  // #1e293b
            new float[]{0.278f, 0.333f, 0.412f},
            new float[]{0.204f, 0.784f, 0.6f},    // #34d399 (emerald-400)
            new float[]{0.086f, 0.125f, 0.196f},  // #162032 (slate-850)
            new float[]{0.55f, 0.6f, 0.66f});
    /**
 * Разделы отчёта для оглавления (номера = заголовки разделов).
 */
    private static final List<String> SECTIONS = List.of(
            "1. Параметры объекта",
            "2. Выбранные решения",
            "3. Состав оборудования",
            "4. Расчёт экономики",
            "5. Имитация 2D и KPI",
            "6. Ограничения",
            "7. Источники данных",
            "8. Дата расчёта");
    /**
 * AWT-проба покрытия глифов: Noto Sans не
 * содержит эмодзи/CJK/стрелок — без фильтра PDType0Font падал на
 * showText и весь экспорт давал 500. Ленивая инициализация;
 * статично — шрифт один на все отчёты.
 */
    private static volatile Font glyphProbe;
    private final SvgRasterizer svgRasterizer;

    /**
 * Внедряет растеризатор 2D-схем (раздел 6 отчёта).
 *
 * @param svgRasterizer SVG -> PNG для схемы имитации
 */
    public PdfReportGenerator(SvgRasterizer svgRasterizer) {
        this.svgRasterizer = svgRasterizer;
    }

    /**
 * Кликабельные ссылки оглавления → первые страницы разделов
 * (разделы, которых нет в отчёте — без ссылки, обычный текст).
 */
    private static void linkTableOfContents(PDDocument document, PdfFlow flow)
            throws IOException {
        for (TocLine line : flow.tocLines) {
            Integer target = flow.sectionPages.get(line.title());
            if (target == null) {
                continue;
            }
            PDPage page = document.getPage(line.pageIndex());
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(MARGIN, line.bottom(),
                    CONTENT_WIDTH, line.top() - line.bottom()));
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(document.getPage(target));
            link.setDestination(destination);
            page.getAnnotations().add(link);
        }
    }

    /**
 * Закладки-Outline по разделам (боковая панель просмотрщика).
 */
    private static void outlineOf(PDDocument document, PdfFlow flow)
            throws IOException {
        PDDocumentOutline outline = new PDDocumentOutline();
        document.getDocumentCatalog().setDocumentOutline(outline);
        for (TocLine line : flow.tocLines) {
            Integer target = flow.sectionPages.get(line.title());
            if (target == null) {
                continue;
            }
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(line.title());
            item.setDestination(document.getPage(target));
            outline.addLast(item);
        }
    }

    // ------------------------------------------------------------------
    // Оглавление (кликабельное) и нумерация страниц
    // ------------------------------------------------------------------

    /**
 * Нумерация страниц «Страница N из M» — колонтитул каждой страницы.
 */
    private static void stampPageNumbers(PDDocument document,
                                         PdfFontSet fonts, Palette palette) throws IOException {
        int total = document.getNumberOfPages();
        for (int i = 0; i < total; i += 1) {
            PDPage page = document.getPage(i);
            try (PDPageContentStream stream = new PDPageContentStream(
                    document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                String label = "Страница " + (i + 1) + " из " + total;
                float width = fonts.regular().getStringWidth(label) / 1000f
                        * 8f;
                stream.beginText();
                stream.setFont(fonts.regular(), 8f);
                stream.setNonStrokingColor(palette.footerText()[0],
                        palette.footerText()[1], palette.footerText()[2]);
                stream.newLineAtOffset(
                        (PAGE.getWidth() - width) / 2, MARGIN / 2 - 2f);
                stream.showText(label);
                stream.endText();
            }
        }
    }

    private static String columnTitle(ReportModel.ScenarioReport scenario) {
        return scenario.calculated() ? scenario.name()
                : scenario.name() + " (не рассчитан)";
    }

    private static List<String> metricRow(String title,
                                          List<ReportModel.ScenarioReport> scenarios,
                                          MetricGetter getter, MetricFormatter formatter) {
        List<String> cells = new ArrayList<>();
        cells.add(title);
        for (ReportModel.ScenarioReport scenario : scenarios) {
            BigDecimal value = scenario.calculated() ? getter.get(scenario)
                    : null;
            cells.add(formatter.format(value));
        }
        return cells;
    }

    private static String paybackOf(ReportModel.ScenarioReport scenario) {
        return ReportFormats.paybackText(scenario);
    }

    /**
 * Заключение по сценарию (категории economic_model.md §4,
 *): эффект + окупаемость словами.
 */
    private static String conclusionOf(ReportModel.ScenarioReport scenario) {
        StringBuilder text = new StringBuilder("Сценарий «")
                .append(scenario.name()).append("»: ");
        if (scenario.effectYear() == null
                || scenario.effectYear().signum() <= 0) {
            return text.append("годовой эффект не достигается — "
                            + "роботизация не окупается при текущих допущениях.")
                    .toString();
        }
        text.append("годовой эффект ")
                .append(ReportFormats.formatMoney(
                        scenario.effectYear()));
        if (scenario.paybackHuman() != null) {
            text.append(", окупаемость — ").append(scenario.paybackHuman())
                    .append(" (").append(paybackCategory(
                            scenario.paybackYears())).append(")");
        }
        return text.append(".").toString();
    }

    // ------------------------------------------------------------------
    // Раздел 1: титул (+ пометка)
    // ------------------------------------------------------------------

    /**
 * Категория окупаемости — economic_model.md §4 (дословно).
 */
    private static String paybackCategory(BigDecimal paybackYears) {
        if (paybackYears == null) {
            return "срок не определён";
        }
        if (paybackYears.compareTo(BigDecimal.valueOf(3)) <= 0) {
            return "быстрая окупаемость";
        }
        if (paybackYears.compareTo(BigDecimal.valueOf(5)) <= 0) {
            return "умеренная";
        }
        return "долгая, требует обоснования";
    }

    // Раздел 2: параметры объекта --------------------------------------

    private static String money(BigDecimal value) {
        return ReportFormats.formatMoney(value);
    }

    // Раздел 3: выбранные решения --------------------------------------

    private static String moneySigned(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        String formatted = ReportFormats.formatMoney(value.abs());
        return value.signum() < 0 ? "−" + formatted
                : "+" + formatted;
    }

    // Раздел 4: состав оборудования ------------------------------------

    private static String percent(BigDecimal value) {
        return ReportFormats.formatPercent(value);
    }

    // Раздел 5: расчёт экономики ---------------------------------------

    private static String sourceKindOf(String kind) {
        return switch (kind == null ? "" : kind) {
            case "organizer_catalog" -> "каталог организатора";
            case "open_source" -> "открытый источник";
            case "manual" -> "внесено вручную";
            default -> kind == null ? "—" : kind;
        };
    }

    // Раздел 6: имитация 2D + KPI ---------------------------------------

    private static PdfFontSet loadFonts(PDDocument document)
            throws IOException {
        try (InputStream regular = PdfReportGenerator.class
                .getResourceAsStream("/fonts/NotoSans-Regular.ttf");
             InputStream bold = PdfReportGenerator.class
                     .getResourceAsStream("/fonts/NotoSans-Bold.ttf")) {
            if (regular == null || bold == null) {
                throw new IllegalStateException(
                        "Шрифты Noto Sans не найдены в resources/fonts — "
                                + "PDF с кириллицей не может быть сгенерирован");
            }
            return new PdfFontSet(
                    PDType0Font.load(document, regular, true),
                    PDType0Font.load(document, bold, true));
        }
    }

    // Раздел 7: ограничения ---------------------------------------------

    /**
 * Сгенерировать PDF-байты модели отчёта (светлая тема).
 *
 * @param model модель отчёта (готовые метрики, без пересчёта)
 * @return байты PDF (разделы)
 */
    public byte[] generate(ReportModel model) {
        return generate(model, false);
    }

    // Раздел 8: источники данных ------------------------------------------

    /**
 * Сгенерировать PDF-байты модели отчёта в выбранной теме
 * (тёмная тема): dark — сланцевые страницы со светлым текстом,
 * light — прежний вид. Состав разделов идентичен.
 *
 * @param model модель отчёта (готовые метрики, без пересчёта)
 * @param dark тема: true — тёмная, false — светлая
 * @return байты PDF (разделы)
 */
    public byte[] generate(ReportModel model, boolean dark) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfFontSet fonts = loadFonts(document);
            PdfFlow flow = new PdfFlow(document, fonts,
                    dark ? DARK : LIGHT);
            drawTitle(flow, model);
            drawTableOfContents(flow, model);
            drawParameters(flow, model);
            drawSolutions(flow, model);
            drawEquipment(flow, model);
            drawEconomics(flow, model);
            drawSimulation(flow, model);
            drawLimitations(flow, model);
            drawSources(flow, model);
            drawCalculationDates(flow, model);
            drawFinalNote(flow, model);
            flow.close();
            // пост-обработка: кликабельное оглавление (ссылки-аннотации
            // и закладки-Outline), нумерация страниц «N из M»
            linkTableOfContents(document, flow);
            outlineOf(document, flow);
            stampPageNumbers(document, fonts, flow.palette);
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Не удалось сгенерировать PDF-отчёт", ex);
        }
    }

    // Раздел 9: даты расчётов ---------------------------------------------

    /**
 * Страница «Содержание» после титула: строки-разделы; позиции
 * запоминаются в flow.tocLines, ссылки добавляются после рендера.
 * Раздел «5. Имитация» попадает в оглавление только когда
 * имитация запускалась (раздел иначе пропускается — граница ).
 */
    private void drawTableOfContents(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.pageHeading("Содержание");
        for (String section : SECTIONS) {
            if ("5. Имитация 2D и KPI".equals(section)
                    && model.simulation() == null) {
                continue;
            }
            flow.tocLine(section);
        }
        flow.spacer(GAP_MEDIUM);
    }

    // Раздел 10: финальная пометка ----------------------------

    private void drawTitle(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.heading("Отчёт предынвестиционной оценки роботизации",
                16f, true);
        flow.spacer(GAP_MEDIUM);
        flow.keyValueLine("Проект:", model.projectName());
        flow.keyValueLine("Тип объекта:", model.objectTypeName());
        flow.keyValueLine("Дата генерации отчёта:",
                ReportFormats.formatDateTime(model.generatedAt()));
        flow.keyValueLine("Автор:", model.authorLogin());
        if (model.horizonYears() != null) {
            flow.keyValueLine("Горизонт расчёта:",
                    model.horizonYears() + " лет");
        }
        flow.spacer(GAP_MEDIUM);
        flow.noteBox(model.preliminaryNote() + ".");
    }

    // ------------------------------------------------------------------
    // Вспомогательные форматтеры
    // ------------------------------------------------------------------

    private void drawParameters(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.pageHeading("1. Параметры объекта");
        if (model.parameters().isEmpty()) {
            flow.paragraph("Параметры объекта не заполнены.");
            return;
        }
        Table table = flow.table(new float[]{0.20f, 0.34f, 0.20f, 0.12f,
                        0.14f},
                List.of("Код", "Название", "Значение", "Ед.", "Источник"));
        for (ReportModel.ParameterRow row : model.parameters()) {
            table.row(List.of(row.code(), row.name(), row.value(),
                    row.unit(), row.source()));
        }
        table.finish();
    }

    private void drawSolutions(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.pageHeading("2. Выбранные решения");
        boolean any = false;
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            if (scenario.composition().isEmpty()) {
                continue;
            }
            any = true;
            flow.heading("Сценарий «" + scenario.name() + "»", 10.5f, true);
            Table table = flow.table(new float[]{0.22f, 0.30f, 0.08f,
                            0.15f, 0.15f, 0.10f},
                    List.of("Производитель", "Решение", "Кол-во, ед.",
                            "Цена за ед.", "Сумма", "Источник строки"));
            for (ReportModel.SolutionLine line : scenario.composition()) {
                String origin = line.manual()
                        ? "добавлено вручную"
                          + (line.manualReason() == null ? ""
                             : ": " + line.manualReason())
                        : "подбор";
                table.row(List.of(
                        line.vendorName(), line.solutionName(),
                        ReportFormats.formatInt(line.quantity()),
                        ReportFormats.formatMoney(line.priceRub()),
                        ReportFormats.formatMoney(line.totalRub()),
                        origin));
            }
            table.finish();
        }
        if (!any) {
            flow.paragraph("Решения в сценарии не выбраны — добавьте их "
                    + "подбором (шаг 3) или вручную.");
        }
    }

    private void drawEquipment(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.pageHeading("3. Состав оборудования");
        Table table = flow.table(new float[]{0.30f, 0.17f, 0.18f, 0.35f},
                List.of("Сценарий", "Роботы, ед.", "Инфраструктура, ед.",
                        "Разбивка по решениям"));
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            StringBuilder breakdown = new StringBuilder();
            for (ReportModel.SolutionLine line : scenario.composition()) {
                if (!breakdown.isEmpty()) {
                    breakdown.append("; ");
                }
                breakdown.append(line.solutionName()).append(" — ")
                        .append(ReportFormats.formatInt(line.quantity()))
                        .append(" ед.");
            }
            table.row(List.of(
                    scenario.name(),
                    scenario.selectedRobots() == null ? "—"
                            : ReportFormats.formatInt(
                            scenario.selectedRobots()),
                    scenario.nInfra() == null ? "—"
                            : ReportFormats.formatInt(scenario.nInfra()),
                    breakdown.isEmpty() ? "—" : breakdown.toString()));
        }
        table.finish();
        flow.paragraphSmall("Инфраструктура — зарядные станции: "
                + "N_infra = ceil(N_robots × Ratio_infra) — "
                + "раздел 2.2 методики расчёта.");
    }

    private void drawEconomics(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.pageHeading("4. Расчёт экономики");
        List<ReportModel.ScenarioReport> calculated = model.scenarios()
                .stream().filter(ReportModel.ScenarioReport::calculated).toList();
        if (calculated.isEmpty()) {
            flow.paragraph("Расчёт не выполнен.");
            for (ReportModel.ScenarioReport scenario : model.scenarios()) {
                if (scenario.calcFailureReason() != null) {
                    flow.noteBox("Расчёт не выполнен (сценарий «"
                            + scenario.name() + "»): "
                            + scenario.calcFailureReason());
                }
            }
            return;
        }
        List<String> headers = new ArrayList<>(List.of("Показатель"));
        model.scenarios().forEach(s -> headers.add(columnTitle(s)));
        // доли колонок: широкая метрика + равные колонки сценариев
        // (количество сценариев не захардкожено — состав из модели)
        int columns = headers.size();
        float[] fractions = new float[columns];
        fractions[0] = columns > 1
                ? Math.max(0.22f, 0.31f - 0.045f * (columns - 3)) : 1f;
        float rest = columns > 1 ? (1f - fractions[0]) / (columns - 1) : 0f;
        for (int i = 1; i < columns; i += 1) {
            fractions[i] = rest;
        }
        Table table = flow.table(fractions, headers);
        List<ReportModel.ScenarioReport> scenarios = model.scenarios();
        table.row(metricRow("CAPEX, руб.", scenarios,
                ReportModel.ScenarioReport::capex,
                PdfReportGenerator::money));
        table.row(metricRow("OPEX годовой, руб.", scenarios,
                ReportModel.ScenarioReport::opexYear,
                PdfReportGenerator::money));
        table.row(metricRow("ΔOPEX к базовому, руб.", scenarios,
                ReportModel.ScenarioReport::opexDelta,
                PdfReportGenerator::moneySigned));
        table.row(metricRow("ΔFOT (экономия ФОТ), руб./год", scenarios,
                ReportModel.ScenarioReport::deltaFot,
                PdfReportGenerator::moneySigned));
        table.row(metricRow("Годовой эффект, руб.", scenarios,
                ReportModel.ScenarioReport::effectYear,
                PdfReportGenerator::money));
        List<String> paybackCells = new ArrayList<>(List.of("Срок окупаемости"));
        scenarios.forEach(s -> paybackCells.add(
                ReportFormats.paybackText(s)));
        table.row(paybackCells);
        table.row(metricRow("ROI за горизонт, %", scenarios,
                ReportModel.ScenarioReport::roiPct,
                PdfReportGenerator::percent));
        table.row(metricRow("TCO на горизонте, руб.", scenarios,
                ReportModel.ScenarioReport::tco,
                PdfReportGenerator::money));
        table.row(metricRow("ΔCAPEX к базовому, руб.", scenarios,
                ReportModel.ScenarioReport::capexDeltaToBase,
                PdfReportGenerator::moneySigned));
        table.row(metricRow("ΔTCO к базовому, руб.", scenarios,
                ReportModel.ScenarioReport::tcoDeltaToBase,
                PdfReportGenerator::moneySigned));
        table.row(metricRow("Δ эффекта к базовому, руб.", scenarios,
                ReportModel.ScenarioReport::effectDeltaToBase,
                PdfReportGenerator::moneySigned));
        table.finish();

        // сценарии, чей автозапуск перед отчётом не удался — явная
        // причина в разделе экономики (не молчаливый пропуск)
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            if (!scenario.calculated()
                    && scenario.calcFailureReason() != null) {
                flow.noteBox("Расчёт не выполнен (сценарий «"
                        + scenario.name() + "»): "
                        + scenario.calcFailureReason());
            }
        }

        // флаги парка (плашки — те же формулировки, что в UI)
        for (ReportModel.ScenarioReport scenario : calculated) {
            if (scenario.underpowered()) {
                flow.noteBox("Внимание: парк сценария «" + scenario.name()
                        + "» меньше требуемого по пиковой нагрузке ("
                        + ReportFormats.formatInt(scenario.selectedRobots())
                        + " из "
                        + ReportFormats.formatInt(scenario.requiredRobots())
                        + " ед.) — окупаемость и ROI не отражают "
                        + "достижение заявленной производительности.");
            }
            if (scenario.overpowered()) {
                flow.noteBox("Информация: парк сценария «" + scenario.name()
                        + "» превышает требуемый по пиковой нагрузке — "
                        + "возможна переплата за избыточный парк.");
            }
        }
        // сноска о большом baseline ФОТ при высоком ROI
        // (economic_model.md §8)
        String roiNote = ReportFormats.highRoiNote(calculated);
        if (roiNote != null) {
            flow.noteBox(roiNote);
        }

        // краткое заключение: текстовая
        // интерпретация по каждому рассчитанному сценарию — категории
        // economic_model.md §4, без новых формулировок
        flow.spacer(GAP_SMALL);
        flow.heading("Краткое заключение", 10f, true);
        for (ReportModel.ScenarioReport scenario : calculated) {
            flow.paragraphSmall(conclusionOf(scenario));
        }

        // версии данных/модели (воспроизведение расчёта)
        flow.spacer(GAP_SMALL);
        flow.heading("Версии расчёта", 10f, true);
        for (ReportModel.ScenarioReport scenario : calculated) {
            flow.paragraphSmall(scenario.name() + ": данные "
                    + scenario.versionData() + ", модель "
                    + scenario.versionModel() + ".");
        }
    }

    private void drawSimulation(PdfFlow flow, ReportModel model)
            throws IOException {
        ReportModel.SimulationReport simulation = model.simulation();
        if (simulation == null) {
            return; // данных имитации нет вовсе — раздел пропускается
        }
        flow.pageHeading("5. Имитация 2D и KPI");
        if (simulation.scenarioName() != null) {
            flow.paragraphSmall("Последний запуск: сценарий «"
                    + simulation.scenarioName() + "», "
                    + ReportFormats.formatDateTime(simulation.startedAt())
                    + " (модель simulation-model-1.0).");
        }
        if (!simulation.kpi().isEmpty()) {
            Table table = flow.table(new float[]{0.55f, 0.45f},
                    List.of("KPI", "Значение"));
            for (ReportModel.KpiRow kpi : simulation.kpi()) {
                table.row(List.of(kpi.name(), kpi.value()));
            }
            table.finish();
        }
        if (simulation.schemaPng() != null) {
            flow.image(simulation.schemaPng(), "2D-схема склада — "
                    + "экспортированная визуализация");
        } else if (simulation.schemaNote() != null) {
            flow.noteBox(simulation.schemaNote());
        }
    }

    private void drawLimitations(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.pageHeading("6. Ограничения");
        for (String limitation : model.limitations()) {
            flow.bullet(limitation);
        }
    }

    private void drawSources(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.pageHeading("7. Источники данных");
        if (model.sources().isEmpty()) {
            flow.paragraph("Решения не выбраны — источники каталога не "
                    + "использовались.");
        } else {
            Table table = flow.table(new float[]{0.20f, 0.30f, 0.14f,
                            0.22f, 0.14f},
                    List.of("Производитель", "Решение", "Тип источника",
                            "Ссылка", "Дата"));
            for (ReportModel.SourceRow source : model.sources()) {
                table.row(List.of(source.vendorName(), source.solutionName(),
                        sourceKindOf(source.sourceKind()),
                        source.sourceUrl(), source.sourceDate()));
            }
            table.finish();
            flow.paragraphSmall("ТТХ решений с провенансом — в Excel-версии "
                    + "отчёта (лист «Источники»); подтверждённость "
                    + "и даты — как в карточках каталога.");
        }
        flow.spacer(GAP_SMALL);
        flow.bullet("Допущения расчёта — каталог допущений, разделы 22–23 "
                + "(снимок значений — в Excel-версии, лист «Допущения»).");
        flow.bullet("Формулы расчёта — методика экономической модели, "
                + "разделы 2.1–2.13.");
    }

    private void drawCalculationDates(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.pageHeading("8. Дата расчёта");
        Table table = flow.table(new float[]{0.34f, 0.33f, 0.33f},
                List.of("Сценарий", "Дата расчёта", "Версия данных"));
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            table.row(List.of(scenario.name(),
                    scenario.calculated()
                            ? ReportFormats.formatDateTime(
                            scenario.calculatedAt())
                            : "не рассчитан",
                    scenario.calculated() ? scenario.versionData() : "—"));
        }
        table.finish();
    }

    private void drawFinalNote(PdfFlow flow, ReportModel model)
            throws IOException {
        flow.spacer(GAP_MEDIUM);
        flow.noteBox(model.preliminaryNote()
                + ". Отчёт подготовлен платформой RoboMatch для "
                + "обсуждения с руководством, техническими специалистами "
                + "или потенциальными поставщиками.");
    }

    private interface MetricGetter {
        BigDecimal get(ReportModel.ScenarioReport scenario);
    }

    private interface MetricFormatter {
        String format(BigDecimal value);
    }

    // ------------------------------------------------------------------
    // Шрифты
    // ------------------------------------------------------------------

    /**
 * Цветовая палитра PDF-отчёта (тёмная тема): один и тот
 * же контент в обеих темах. RGB-компоненты 0..1. Светлая тема —
 * белый фон и чёрный текст (как до темы); тёмная — сланцевый фон
 * #0f172a и светлый текст #e2e8f0 (контраст WCAG AA).
 * pageBackground == null — фон не заливается (белый лист PDF).
 */
    private record Palette(float[] pageBackground, float[] text,
                           float[] noteBackground, float[] noteBorder,
                           float[] tableGrid,
                           float[] tableHeaderBackground,
                           float[] tableHeaderBorder,
                           float[] accent, float[] zebra,
                           float[] footerText) {
    }

    /**
 * Пара шрифтов документа (Regular + Bold, subsets).
 */
    record PdfFontSet(PDFont regular, PDFont bold) {
    }

    // ==================================================================

    /**
 * Строка оглавления: заголовок, страница и вертикальные границы
 * (для ссылки-аннотации после рендера).
 */
    record TocLine(String title, int pageIndex, float bottom, float top) {
    }

    // Поток вёрстки: курсор по странице, автоперенос, таблицы, заметки
    // ==================================================================

    /**
 * Простой движок поточной вёрстки поверх PDFBox.
 */
    static final class PdfFlow {

        /**
 * Страницы первых вхождений разделов (0-based) — для
 * кликабельного оглавления и закладок-Outline.
 */
        final java.util.Map<String, Integer> sectionPages
                = new java.util.LinkedHashMap<>();
        /**
 * Строки страницы «Содержание» (позиции для ссылок).
 */
        final java.util.List<TocLine> tocLines = new ArrayList<>();
        private final PDDocument document;
        private final PdfFontSet fonts;
        private final Palette palette;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;

        PdfFlow(PDDocument document, PdfFontSet fonts, Palette palette)
                throws IOException {
            this.document = document;
            this.fonts = fonts;
            this.palette = palette;
            newPage();
        }

        /**
 * Удаление символов, которых нет в шрифте отчёта (Noto Sans;
 * без фильтра showText ронял весь экспорт
 * 500-й на эмодзи/CJK/стрелках). Проба — AWT-копия того же
 * TTF (canDisplay), ленивая; недоступна — текст как есть.
 */
        private static String sanitizeGlyphs(String text) {
            Font probe = glyphProbe();
            if (probe == null) {
                return text;
            }
            StringBuilder builder = new StringBuilder(text.length());
            text.codePoints().forEach(codePoint -> {
                if (codePoint == ' ' || probe.canDisplay(codePoint)) {
                    builder.appendCodePoint(codePoint);
                } else {
                    builder.append(' ');
                }
            });
            return builder.toString();
        }

        private static Font glyphProbe() {
            if (glyphProbe == null) {
                try (InputStream fontStream = PdfReportGenerator.class
                        .getResourceAsStream(
                                "/fonts/NotoSans-Regular.ttf")) {
                    if (fontStream != null) {
                        glyphProbe = Font.createFont(Font.TRUETYPE_FONT,
                                fontStream);
                    }
                } catch (Exception ex) {
                    // без пробы — не блокируем отчёт (см. javadoc)
                }
            }
            return glyphProbe;
        }

        /**
 * Основной цвет текста текущей темы (см. Palette).
 */
        private void applyTextColor() throws IOException {
            stream.setNonStrokingColor(palette.text()[0],
                    palette.text()[1], palette.text()[2]);
        }

        void close() throws IOException {
            closeStream();
        }

        private void newPage() throws IOException {
            closeStream();
            page = new PDPage(PAGE);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            // тёмная тема: заливка всей страницы до контента (светлая —
            // белый лист, заливка не нужна)
            if (palette.pageBackground() != null) {
                stream.setNonStrokingColor(palette.pageBackground()[0],
                        palette.pageBackground()[1],
                        palette.pageBackground()[2]);
                stream.addRect(0, 0, PAGE.getWidth(), PAGE.getHeight());
                stream.fill();
            }
            y = PAGE.getHeight() - MARGIN;
        }

        private void closeStream() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        float remaining() {
            return y - MARGIN;
        }

        void ensureSpace(float height) throws IOException {
            if (remaining() < height) {
                newPage();
            }
        }

        void spacer(float height) throws IOException {
            ensureSpace(height);
            y -= height;
        }

        /**
 * Крупный заголовок раздела (с отступом сверху): акцентный
 * цвет + фиксация страницы раздела для оглавления/закладок.
 */
        void pageHeading(String text) throws IOException {
            ensureSpace(FONT_BODY * 2 * LEADING + GAP_MEDIUM);
            spacer(GAP_SMALL);
            heading(text, 12.5f, true, palette.accent());
            sectionPages.putIfAbsent(text, document.getNumberOfPages() - 1);
            spacer(GAP_SMALL);
        }

        /**
 * Строка оглавления: позиция запоминается для кликабельной
 * ссылки (ссылки ставятся после рендера — цели известны).
 */
        void tocLine(String title) throws IOException {
            ensureSpace(FONT_BODY * LEADING + 2f);
            float top = y;
            y -= FONT_BODY * LEADING;
            stream.beginText();
            stream.setFont(fonts.regular(), FONT_BODY);
            applyTextColor();
            stream.newLineAtOffset(MARGIN + 6f, y + 3f);
            stream.showText(title);
            stream.endText();
            tocLines.add(new TocLine(title,
                    document.getNumberOfPages() - 1, y, top));
        }

        void heading(String text, float size, boolean bold)
                throws IOException {
            heading(text, size, bold, null);
        }

        /**
 * Заголовок с явным цветом (null — обычный цвет текста).
 */
        void heading(String text, float size, boolean bold, float[] color)
                throws IOException {
            List<String> lines = wrap(text, size, bold, CONTENT_WIDTH);
            PDFont font = bold ? fonts.bold() : fonts.regular();
            for (String line : lines) {
                ensureSpace(size * LEADING);
                y -= size * LEADING;
                stream.beginText();
                stream.setFont(font, size);
                if (color != null) {
                    stream.setNonStrokingColor(color[0], color[1],
                            color[2]);
                } else {
                    applyTextColor();
                }
                stream.newLineAtOffset(MARGIN, y);
                stream.showText(line);
                stream.endText();
            }
        }

        void paragraph(String text) throws IOException {
            writeWrapped(text, FONT_BODY, false);
        }

        void paragraphSmall(String text) throws IOException {
            writeWrapped(text, FONT_SMALL, false);
        }

        void bullet(String text) throws IOException {
            writeWrapped("• " + text, FONT_BODY, false);
        }

        private void writeWrapped(String text, float size, boolean bold)
                throws IOException {
            for (String line : wrap(text, size, bold, CONTENT_WIDTH
                    - 8f)) {
                ensureSpace(size * LEADING + 1);
                y -= size * LEADING;
                stream.beginText();
                stream.setFont(bold ? fonts.bold() : fonts.regular(), size);
                applyTextColor();
                stream.newLineAtOffset(MARGIN + 4f, y);
                stream.showText(line);
                stream.endText();
            }
            spacer(2f);
        }

        void keyValueLine(String key, String value) throws IOException {
            String text = key + " " + (value == null ? "—" : value);
            for (String line : wrap(text, FONT_BODY + 0.5f, false,
                    CONTENT_WIDTH)) {
                ensureSpace((FONT_BODY + 0.5f) * LEADING);
                y -= (FONT_BODY + 0.5f) * LEADING;
                stream.beginText();
                stream.setFont(fonts.regular(), FONT_BODY + 0.5f);
                applyTextColor();
                stream.newLineAtOffset(MARGIN, y);
                stream.showText(line);
                stream.endText();
            }
        }

        /**
 * Заметка-плашка: фон и рамка цвета темы, текст темы.
 */
        void noteBox(String text) throws IOException {
            List<String> lines = wrap(text, FONT_SMALL, false,
                    CONTENT_WIDTH - 16f);
            float height = lines.size() * FONT_SMALL * LEADING + 10f;
            ensureSpace(height + GAP_SMALL);
            float boxTop = y;
            float boxHeight = height;
            stream.setNonStrokingColor(palette.noteBackground()[0],
                    palette.noteBackground()[1],
                    palette.noteBackground()[2]);
            stream.addRect(MARGIN, boxTop - boxHeight, CONTENT_WIDTH,
                    boxHeight);
            stream.fill();
            stream.setStrokingColor(palette.noteBorder()[0],
                    palette.noteBorder()[1], palette.noteBorder()[2]);
            stream.setLineWidth(0.7f);
            stream.addRect(MARGIN, boxTop - boxHeight, CONTENT_WIDTH,
                    boxHeight);
            stream.stroke();
            float textY = boxTop - 6f;
            for (String line : lines) {
                textY -= FONT_SMALL * LEADING;
                stream.beginText();
                stream.setFont(fonts.regular(), FONT_SMALL);
                applyTextColor();
                stream.newLineAtOffset(MARGIN + 8f, textY);
                stream.showText(line);
                stream.endText();
            }
            y = boxTop - boxHeight - GAP_SMALL;
        }

        /**
 * Картинка схемы по ширине контента.
 */
        void image(byte[] png, String caption) throws IOException {
            ensureSpace(60f);
            PDImageXObject image = LosslessFactory.createFromImage(
                    document, javax.imageio.ImageIO.read(
                            new java.io.ByteArrayInputStream(png)));
            float width = CONTENT_WIDTH;
            float height = width * image.getHeight() / image.getWidth();
            ensureSpace(height + 16f);
            y -= height;
            stream.drawImage(image, MARGIN, y, width, height);
            y -= 14f;
            for (String line : wrap(caption, FONT_SMALL, false,
                    CONTENT_WIDTH)) {
                ensureSpace(FONT_SMALL * LEADING);
                y -= FONT_SMALL * LEADING;
                stream.beginText();
                stream.setFont(fonts.regular(), FONT_SMALL);
                applyTextColor();
                stream.newLineAtOffset(MARGIN, y);
                stream.showText(line);
                stream.endText();
            }
        }

        /**
 * Начать таблицу: доли колонок (сумма = 1) + заголовки.
 */
        Table table(float[] fractions, List<String> headers)
                throws IOException {
            Table table = new Table(this, fractions);
            table.header(headers);
            return table;
        }

        List<String> wrap(String text, float size, boolean bold,
                          float width) throws IOException {
            PDFont font = bold ? fonts.bold() : fonts.regular();
            List<String> lines = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                lines.add("—");
                return lines;
            }
            text = sanitizeGlyphs(text);
            StringBuilder current = new StringBuilder();
            for (String word : text.split("\\s+")) {
                // слово длиннее колонки (URL, длинное название без
                // пробелов) — режем по символам: в ячейку не вылезает
                while (widthOf(font, size, word) > width && word.length() > 1) {
                    int cut = word.length();
                    while (cut > 1 && widthOf(font, size,
                            word.substring(0, cut)) > width) {
                        cut -= 1;
                    }
                    String head = word.substring(0, cut);
                    if (!current.isEmpty()) {
                        lines.add(current.toString());
                        current = new StringBuilder();
                    }
                    lines.add(head);
                    word = word.substring(cut);
                }
                String candidate = current.isEmpty() ? word
                        : current + " " + word;
                if (widthOf(font, size, candidate) <= width
                        || current.isEmpty()) {
                    current = new StringBuilder(candidate);
                } else {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                }
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }

        float widthOf(PDFont font, float size, String text)
                throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }

        void drawCellText(String text, float x, float baselineY,
                          float size, boolean bold) throws IOException {
            stream.beginText();
            stream.setFont(bold ? fonts.bold() : fonts.regular(), size);
            applyTextColor();
            stream.newLineAtOffset(x, baselineY);
            stream.showText(text);
            stream.endText();
        }
    }

    /**
 * Таблица с сеткой и переносом текста в ячейках.
 */
    static final class Table {

        private static final float PADDING = 3f;
        private final PdfFlow flow;
        private final float[] widths;
        /**
 * Счётчик строк данных — зебра-полосы (каждая вторая).
 */
        private int dataRowIndex;

        Table(PdfFlow flow, float[] fractions) {
            this.flow = flow;
            this.widths = new float[fractions.length];
            for (int i = 0; i < fractions.length; i += 1) {
                widths[i] = fractions[i] * CONTENT_WIDTH;
            }
        }

        /**
 * Символы вне BMP и прочее, что не отрисует шрифт, — гасим.
 */
        private static String sanitize(String value) {
            if (value == null) {
                return "—";
            }
            StringBuilder builder = new StringBuilder(value.length());
            value.codePoints().forEach(codePoint -> {
                if (codePoint == 0x2028 || codePoint == 0x2029
                        || codePoint == '\n' || codePoint == '\r') {
                    builder.append(' ');
                } else if (codePoint >= 0x20) {
                    builder.appendCodePoint(codePoint);
                }
            });
            return builder.isEmpty() ? "—" : builder.toString();
        }

        void header(List<String> cells) throws IOException {
            row(cells, true);
        }

        void row(List<String> cells) throws IOException {
            row(cells, false);
        }

        private void row(List<String> cells, boolean header)
                throws IOException {
            if (cells.size() != widths.length) {
                throw new IllegalArgumentException(
                        "Количество ячеек не совпадает с колонками");
            }
            // перенос текста в ячейках
            List<List<String>> wrapped = new ArrayList<>();
            for (int i = 0; i < cells.size(); i += 1) {
                wrapped.add(flow.wrap(sanitize(cells.get(i)),
                        header ? FONT_SMALL : FONT_BODY, header,
                        widths[i] - 2 * PADDING));
            }
            int maxLines = wrapped.stream().mapToInt(List::size).max()
                    .orElse(1);
            float lineHeight = (header ? FONT_SMALL : FONT_BODY) * LEADING;
            float rowHeight = maxLines * lineHeight + 2 * PADDING;
            flow.ensureSpace(rowHeight);
            float top = flow.y;
            float bottom = top - rowHeight;
            // сетка
            float x = MARGIN;
            for (float width : widths) {
                flow.stream.setStrokingColor(flow.palette.tableGrid()[0],
                        flow.palette.tableGrid()[1],
                        flow.palette.tableGrid()[2]);
                flow.stream.setLineWidth(0.5f);
                flow.stream.addRect(x, bottom, width, rowHeight);
                flow.stream.stroke();
                x += width;
            }
            // зебра-полосы данных (читаемость широких таблиц)
            if (!header && dataRowIndex % 2 == 1) {
                flow.stream.setNonStrokingColor(flow.palette.zebra()[0],
                        flow.palette.zebra()[1], flow.palette.zebra()[2]);
                flow.stream.addRect(MARGIN, bottom, CONTENT_WIDTH,
                        rowHeight);
                flow.stream.fill();
            }
            if (!header) {
                dataRowIndex += 1;
            }
            // заливка шапки
            if (header) {
                flow.stream.setNonStrokingColor(
                        flow.palette.tableHeaderBackground()[0],
                        flow.palette.tableHeaderBackground()[1],
                        flow.palette.tableHeaderBackground()[2]);
                flow.stream.addRect(MARGIN, bottom, CONTENT_WIDTH,
                        rowHeight);
                flow.stream.fill();
                flow.stream.setStrokingColor(
                        flow.palette.tableHeaderBorder()[0],
                        flow.palette.tableHeaderBorder()[1],
                        flow.palette.tableHeaderBorder()[2]);
                flow.stream.setLineWidth(0.5f);
                flow.stream.addRect(MARGIN, bottom, CONTENT_WIDTH,
                        rowHeight);
                flow.stream.stroke();
            }
            // текст
            float cellX = MARGIN;
            for (int i = 0; i < cells.size(); i += 1) {
                float baseline = top - PADDING - lineHeight + 2f;
                for (String line : wrapped.get(i)) {
                    flow.drawCellText(line, cellX + PADDING, baseline,
                            header ? FONT_SMALL : FONT_BODY, header);
                    baseline -= lineHeight;
                }
                cellX += widths[i];
            }
            flow.y = bottom;
        }

        void finish() throws IOException {
            flow.spacer(GAP_SMALL);
        }
    }
}
