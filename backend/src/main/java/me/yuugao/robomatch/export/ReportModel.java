package me.yuugao.robomatch.export;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Модель отчёта: единый источник данных для всех генераторов
 * (PDF/Excel/CSV). Собирается {@link ReportDataBuilder} из последних
 * расчётов сценариев и текущих данных проекта.
 *
 * <p>ГЛАВНЫЙ ИНВАРИАНТ: отчёт ГОТОВЫЕ метрики читает и НЕ пересчитывает
 * (источник правды формул — economic_model.md; воспроизведение — по
 * version_data/version_model). Окупаемость отображается
 * человекочитаемо по правилу §4 (InterpretationFormatter) — строкой
 * paybackHuman, числовое поле сохранено рядом.
 *
 * <p>ПОМЕТКА: preliminaryNote печатается на титуле и в конце
 * каждого отчёта — «предварительная оценка, требует верификации при
 * обследовании объекта».
 *
 * @param projectId идентификатор проекта
 * @param projectName название проекта (титульный лист)
 * @param objectTypeName название типа объекта (титульный лист)
 * @param generatedAt момент генерации отчёта
 * @param authorLogin логин автора (кто сформировал отчёт)
 * @param horizonYears горизонт расчёта, лет
 * @param parameters раздел 2
 * @param scenarios сценарии base/purchase/raas — разделы 3–5, 9
 * @param simulation раздел 6: имитация 2D + KPI (null — не запускалась)
 * @param limitations раздел 7: ограничения (общие + сработавшие
 * предупреждения)
 * @param assumptions раздел 8: допущения расчёта (снимок)
 * @param sources раздел 8: источники данных использованных решений
 * @param preliminaryNote пометка — на титуле и в конце
 */
public record ReportModel(
        Long projectId,
        String projectName,
        String objectTypeName,
        Instant generatedAt,
        String authorLogin,
        Integer horizonYears,
        List<ParameterRow> parameters,
        List<ScenarioReport> scenarios,
        SimulationReport simulation,
        List<String> limitations,
        List<AssumptionRow> assumptions,
        List<SourceRow> sources,
        String preliminaryNote
) {

    /**
 * Параметр объекта: код, название, значение, единица, источник.
 *
 * @param code код параметра
 * @param name название параметра
 * @param value значение (человекочитаемой строкой)
 * @param unit единица измерения
 * @param source источник значения
 */
    public record ParameterRow(String code, String name, String value,
                               String unit, String source) {
    }

    /**
 * Сценарий отчёта: показатели последнего расчёта + состав решений
 * (выбранные решения — раздел 3; состав оборудования — раздел 4).
 *
 * @param type тип сценария (base/purchase/raas)
 * @param name название сценария
 * @param calculated был ли расчёт (false — раздел не заполняется)
 * @param calculatedAt момент последнего расчёта
 * @param versionData version_data расчёта ( —
 * воспроизведение)
 * @param versionModel version_model расчёта
 * @param selectedRobots роботов выбрано (итог подбора)
 * @param requiredRobots роботов требуется по расчёту
 * @param nInfra зарядные станции (§2.2 экономики)
 * @param capex CAPEX, руб.
 * @param opexYear OPEX за год, руб.
 * @param opexDelta ΔOPEX к базовому сценарию, руб.
 * @param deltaFot ΔФОТ (изменение фонда оплаты труда), руб.
 * @param effectYear годовой эффект, руб.
 * @param paybackYears окупаемость, лет (числовое поле;
 * отображение — paybackHuman)
 * @param paybackHuman окупаемость человекочитаемо (правило §4
 * экономической модели)
 * @param roiPct ROI, %
 * @param tco TCO за горизонт, руб.
 * @param capexDeltaToBase разница CAPEX с базовым сценарием, руб.
 * @param tcoDeltaToBase разница TCO с базовым сценарием, руб.
 * @param effectDeltaToBase разница годового эффекта с базовым, руб.
 * @param underpowered предупреждение: роботов меньше требуемого
 * @param overpowered предупреждение: избыток роботов
 * @param composition состав оборудования (строки SolutionLine)
 * @param warnings предупреждения расчёта (раздел 7)
 * @param calcFailureReason причина, почему автозапуск расчёта перед
 * отчётом не удался (null — расчёт есть или
 * не требовался); печатается в разделе
 * экономики: «Расчёт не выполнен: …»
 */
    public record ScenarioReport(
            String type,
            String name,
            boolean calculated,
            Instant calculatedAt,
            String versionData,
            String versionModel,
            Integer selectedRobots,
            Integer requiredRobots,
            Integer nInfra,
            BigDecimal capex,
            BigDecimal opexYear,
            BigDecimal opexDelta,
            BigDecimal deltaFot,
            BigDecimal effectYear,
            BigDecimal paybackYears,
            String paybackHuman,
            BigDecimal roiPct,
            BigDecimal tco,
            BigDecimal capexDeltaToBase,
            BigDecimal tcoDeltaToBase,
            BigDecimal effectDeltaToBase,
            boolean underpowered,
            boolean overpowered,
            List<SolutionLine> composition,
            List<String> warnings,
            String calcFailureReason
    ) {
    }

    /**
 * Решение в составе сценария (раздел 3).
 *
 * @param vendorName вендор решения
 * @param solutionName название решения
 * @param quantity количество, шт.
 * @param priceRub цена за единицу, руб.
 * @param totalRub стоимость позиции (цена × количество), руб.
 * @param manual добавлено вручную, а не подбором
 * @param manualReason причина ручного добавления
 */
    public record SolutionLine(String vendorName, String solutionName,
                               Integer quantity, BigDecimal priceRub,
                               BigDecimal totalRub, boolean manual,
                               String manualReason) {
    }

    /**
 * Раздел 6: имитация — KPI строками + PNG-схема (null — не сохранена).
 *
 * @param scenarioName название сценария имитации
 * @param startedAt момент запуска имитации
 * @param kpi 6 KPI имитации строками «имя = значение»
 * @param schemaPng PNG 2D-схемы склада (null — схема не сохранена)
 * @param schemaNote подпись к схеме (условия и пояснение; при
 * невозможности построить схему — «Схема не
 * сохранена: причина»)
 */
    public record SimulationReport(
            String scenarioName,
            Instant startedAt,
            List<KpiRow> kpi,
            byte[] schemaPng,
            String schemaNote
    ) {
    }

    /**
 * KPI имитации человекочитаемой строкой (6 KPI).
 *
 * @param name название KPI
 * @param value значение (отформатированной строкой)
 */
    public record KpiRow(String name, String value) {
    }

    /**
 * Допущение расчёта: значение последнего расчёта.
 *
 * @param name код допущения
 * @param title человекочитаемое название
 * @param value значение из снимка расчёта
 * @param unit единица измерения
 * @param range допустимый диапазон
 * @param source источник допущения
 */
    public record AssumptionRow(String name, String title,
                                String value, String unit, String range,
                                String source) {
    }

    /**
 * Источник данных использованного решения (раздел 8).
 *
 * @param vendorName вендор решения
 * @param solutionName название решения
 * @param sourceKind вид источника (каталог организатора и т.п.)
 * @param sourceUrl ссылка на источник
 * @param sourceDate дата источника
 * @param characteristics ТТХ решения с провенансом (лист «Источники»)
 */
    public record SourceRow(String vendorName, String solutionName,
                            String sourceKind, String sourceUrl,
                            String sourceDate,
                            List<CharacteristicRow> characteristics) {
    }

    /**
 * ТТХ решения с провенансом (лист «Источники» Excel).
 *
 * @param code код характеристики (characteristic_type)
 * @param name название ТТХ
 * @param value значение
 * @param unit единица измерения
 * @param sourceKind вид источника
 * @param sourceUrl ссылка на источник
 * @param sourceDate дата источника
 */
    public record CharacteristicRow(String code, String name,
                                    String value, String unit,
                                    String sourceKind, String sourceUrl,
                                    String sourceDate) {
    }
}
