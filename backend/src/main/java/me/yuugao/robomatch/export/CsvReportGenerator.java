package me.yuugao.robomatch.export;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Генератор CSV-выгрузки:
 * одна плоская таблица с колонкой section. Формат — requirements/
 * export.md: UTF-8 с BOM (Excel/LibreOffice открывают без танцев с
 * кодировкой), разделитель «;» (русская локаль Excel), десятичная
 * запятая, кавычки RFC 4180 при спецсимволах.
 *
 * <p>КОЛОНКИ: section;key;value;unit;source — плоская, машиночитаемая
 * альтернатива листам Excel: сводные поля, параметры, решения,
 * оборудование, экономика по сценариям, допущения, источники, даты
 * расчётов и пометка «предварительная оценка».
 *
 * <p>БЕЗОПАСНОСТЬ: файл ориентирован на открытие в
 * Excel/LibreOffice — значения, начинающиеся с =/+/-/@, нейтрализуются
 * префиксом «'» (CSV-инъекция формул/DDE), КРОМЕ чистых чисел (отрицательные
 * суммы остаются числами); десятичная запятая — во всех числовых значениях,
 * а не только в экономике.
 */
@Component
public class CsvReportGenerator {

    /**
 * UTF-8 BOM — Excel распознаёт кодировку.
 */
    private static final byte[] UTF8_BOM =
            {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String SEPARATOR = ";";
    private static final String LINE_END = "\r\n"; // RFC 4180

    /**
 * Десятичная запятая, до 2 знаков (русская типографика).
 */
    private static String decimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.min(Math.max(stripped.scale(), 0), 2);
        return ReportFormats.formatDecimal(stripped.setScale(scale), scale);
    }

    // ------------------------------------------------------------------
    // Внутренние
    // ------------------------------------------------------------------

    /**
 * Числовые значения с ТОЧКОЙ из БД/каталога («2.5», «1.302») —
 * к десятичной запятой (в Excel RU точка
 * распознаётся как дата/текст). Полное совпадение с числовым
 * паттерном — тексты вроде «§2.1–2.13» не трогаем.
 */
    private static String ru(String value) {
        if (value == null) {
            return "";
        }
        return value.matches("-?\\d+\\.\\d+?")
                ? value.replace('.', ',') : value;
    }

    /**
 * Строка RFC 4180: «;»-разделитель, кавычки при спецсимволах +
 * нейтрализация CSV-инъекций: значение, начинающееся
 * с =/+/-/@, получает префикс «'»; чистые числа (в т.ч.
 * отрицательные) не префиксируются — остаются машиночитаемыми.
 */
    private static void line(StringBuilder csv, String... cells) {
        for (int i = 0; i < cells.length; i += 1) {
            if (i > 0) {
                csv.append(SEPARATOR);
            }
            csv.append(escape(neutralizeFormula(cells[i] == null ? ""
                    : cells[i])));
        }
        csv.append(LINE_END);
    }

    /**
 * Префикс «'» для потенциальных формул Excel (кроме чисел).
 */
    private static String neutralizeFormula(String value) {
        if (value.isEmpty() || isPlainNumber(value)) {
            return value;
        }
        char first = value.charAt(0);
        return (first == '=' || first == '+' || first == '-'
                || first == '@' || first == '\t' || first == '\r')
                ? "'" + value : value;
    }

    /**
 * Чистое число (целое/дробное с . или ) — не формула.
 */
    private static boolean isPlainNumber(String value) {
        return value.matches("-?\\d+([.,]\\d+)??");
    }

    private static String escape(String value) {
        boolean needQuotes = value.contains(SEPARATOR)
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needQuotes ? "\"" + escaped + "\"" : escaped;
    }

    /**
 * Генерация CSV-выгрузки из модели отчёта: одна плоская таблица
 * section;key;value;unit;source (сводка, параметры, решения,
 * оборудование, экономика, допущения, источники, ограничения).
 *
 * @param model модель отчёта (готовые метрики, без пересчёта)
 * @return байты CSV: UTF-8 с BOM, «;»-разделитель, CRLF (RFC 4180)
 */
    public byte[] generate(ReportModel model) {
        StringBuilder csv = new StringBuilder(16 * 1024);
        line(csv, "section", "key", "value", "unit", "source");

        // --- Сводка -----------------------------------------------------
        line(csv, "Сводка", "Проект", model.projectName(), "", "");
        line(csv, "Сводка", "Тип объекта", model.objectTypeName(), "", "");
        line(csv, "Сводка", "Дата генерации отчёта",
                ReportFormats.formatDateTime(model.generatedAt()), "", "");
        line(csv, "Сводка", "Автор", model.authorLogin(), "", "");
        if (model.horizonYears() != null) {
            line(csv, "Сводка", "Горизонт расчёта",
                    model.horizonYears().toString(), "лет", "");
        }
        line(csv, "Сводка", "Пометка", model.preliminaryNote(), "", "предварительная оценка");

        // --- Параметры объекта ------------------------------------------
        for (ReportModel.ParameterRow parameter : model.parameters()) {
            line(csv, "Параметры", parameter.code() + " — "
                            + parameter.name(), ru(parameter.value()),
                    parameter.unit(), parameter.source());
        }

        // --- Решения -----------------------------------------------------
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            for (ReportModel.SolutionLine line : scenario.composition()) {
                line(csv, "Решения", scenario.name() + " — "
                                + line.vendorName() + " — " + line.solutionName(),
                        String.valueOf(line.quantity() == null ? ""
                                : line.quantity()),
                        "ед.",
                        (line.manual() ? "добавлено вручную"
                                : "подбор")
                                + (line.manualReason() == null ? ""
                                : ": " + line.manualReason()));
                if (line.priceRub() != null) {
                    line(csv, "Решения", scenario.name() + " — "
                                    + line.solutionName() + " — цена за ед.",
                            decimal(line.priceRub()), "руб.",
                            "каталог");
                }
                if (line.totalRub() != null) {
                    line(csv, "Решения", scenario.name() + " — "
                                    + line.solutionName() + " — сумма",
                            decimal(line.totalRub()), "руб.", "");
                }
            }
        }

        // --- Оборудование -------------------------------------------------
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            line(csv, "Оборудование", scenario.name() + " — роботы",
                    scenario.selectedRobots() == null ? "—"
                            : scenario.selectedRobots().toString(),
                    "ед.", "metrics_json последнего расчёта");
            line(csv, "Оборудование", scenario.name() + " — инфраструктура",
                    scenario.nInfra() == null ? "—"
                            : scenario.nInfra().toString(),
                    "ед.", "раздел 2.2 методики расчёта");
        }

        // --- Экономика (по сценариям, готовые метрики) --------------------
        for (ReportModel.ScenarioReport scenario : model.scenarios()) {
            String source = scenario.calculated()
                    ? "расчёт " + ReportFormats.formatDateTime(
                    scenario.calculatedAt()) + " ("
                      + scenario.versionData() + ", "
                      + scenario.versionModel() + ")"
                    : "не рассчитан";
            money(csv, scenario, "CAPEX", scenario.capex(), source);
            money(csv, scenario, "OPEX годовой", scenario.opexYear(),
                    source);
            money(csv, scenario, "ΔOPEX к базовому", scenario.opexDelta(),
                    source);
            money(csv, scenario, "ΔFOT", scenario.deltaFot(), source);
            money(csv, scenario, "Годовой эффект", scenario.effectYear(),
                    source);
            money(csv, scenario, "TCO на горизонте", scenario.tco(), source);
            money(csv, scenario, "ΔCAPEX к базовому",
                    scenario.capexDeltaToBase(), source);
            money(csv, scenario, "ΔTCO к базовому",
                    scenario.tcoDeltaToBase(), source);
            money(csv, scenario, "Δ эффекта к базовому",
                    scenario.effectDeltaToBase(), source);
            line(csv, "Экономика", scenario.name()
                            + " — срок окупаемости",
                    ReportFormats.paybackText(scenario),
                    "", source);
            // сноска о большом baseline ФОТ при высоком ROI
            // (economic_model.md §8)
            if (scenario.calculated() && scenario.roiPct() != null
                    && scenario.roiPct().compareTo(
                    java.math.BigDecimal.valueOf(500)) > 0) {
                line(csv, "Экономика", scenario.name()
                                + " — сноска о ROI",
                        "значение зависит от большого baseline ФОТ — "
                                + "проверьте допущения", "",
                        "раздел 8 методики расчёта");
            }
            number(csv, scenario, " — срок окупаемости, лет",
                    scenario.paybackYears(), "лет", source);
            number(csv, scenario, " — ROI за горизонт",
                    scenario.roiPct(), "%", source);
            number(csv, scenario, " — роботов выбрано",
                    scenario.selectedRobots() == null ? null
                            : BigDecimal.valueOf(scenario.selectedRobots()),
                    "ед.", source);
            if (scenario.requiredRobots() != null) {
                number(csv, scenario, " — роботов требуется по пиковой",
                        BigDecimal.valueOf(scenario.requiredRobots()),
                        "ед.", "раздел 2.1 методики расчёта");
            }
            line(csv, "Экономика", scenario.name() + " — флаг недобора парка",
                    scenario.underpowered() ? "да" : "нет", "", source);
            line(csv, "Экономика", scenario.name()
                            + " — флаг превышения парка",
                    scenario.overpowered() ? "да" : "нет", "", source);
            line(csv, "Экономика", scenario.name() + " — дата расчёта",
                    scenario.calculated()
                            ? ReportFormats.formatDateTime(
                            scenario.calculatedAt())
                            : "—",
                    "", source);
            for (String warning : scenario.warnings()) {
                line(csv, "Предупреждения", scenario.name(), warning, "",
                        source);
            }
        }

        // --- Имитация (если запускалась) -----------------------------------
        ReportModel.SimulationReport simulation = model.simulation();
        if (simulation != null) {
            for (ReportModel.KpiRow kpi : simulation.kpi()) {
                line(csv, "Имитация 2D", kpi.name(), kpi.value(), "",
                        "сценарий «" + simulation.scenarioName() + "», "
                                + "simulation-model-1.0");
            }
        }

        // --- Допущения -----------------------------------------------------
        for (ReportModel.AssumptionRow assumption : model.assumptions()) {
            line(csv, "Допущения", assumption.name() + " — "
                            + assumption.title(), ru(assumption.value()),
                    assumption.unit(), assumption.source());
        }

        // --- Источники ------------------------------------------------------
        for (ReportModel.SourceRow source : model.sources()) {
            line(csv, "Источники", source.vendorName() + " — "
                            + source.solutionName(), source.sourceKind(),
                    "ссылка: " + source.sourceUrl() + "; дата: "
                            + source.sourceDate(),
                    "каталог решений");
            for (ReportModel.CharacteristicRow characteristic
                    : source.characteristics()) {
                line(csv, "Источники ТТХ", source.solutionName() + " — "
                                + characteristic.code() + " — "
                                + characteristic.name(),
                        ru(characteristic.value()),
                        characteristic.unit(),
                        characteristic.sourceKind() + "; "
                                + characteristic.sourceUrl() + "; "
                                + characteristic.sourceDate());
            }
        }

        // --- Ограничения -----------------------------------------------------
        for (String limitation : model.limitations()) {
            line(csv, "Ограничения", "ограничение", limitation, "",
                    "раздел 8 методики расчёта; каталог допущений "
                            + "(разделы 22–23)");
        }

        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    private void money(StringBuilder csv, ReportModel.ScenarioReport scenario,
                       String metric, BigDecimal value, String source) {
        line(csv, "Экономика", scenario.name() + " — " + metric,
                value == null ? "—" : decimal(value), "руб.", source);
    }

    private void number(StringBuilder csv,
                        ReportModel.ScenarioReport scenario, String suffix,
                        BigDecimal value, String unit, String source) {
        line(csv, "Экономика", scenario.name() + suffix,
                value == null ? "—" : decimal(value), unit, source);
    }
}
