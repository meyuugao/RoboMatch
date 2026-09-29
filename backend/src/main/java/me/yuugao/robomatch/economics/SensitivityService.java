package me.yuugao.robomatch.economics;

import me.yuugao.robomatch.dto.SensitivityRowDto;
import me.yuugao.robomatch.economics.EconomicModel.EconomicsInput;
import me.yuugao.robomatch.economics.EconomicModel.EconomicsResult;
import me.yuugao.robomatch.economics.EconomicModel.SolutionLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Сервис чувствительности: для
 * каждого параметра из набора «стоимость оборудования / объём операций /
 * стоимость труда» пересчитывает Effect_year, Payback и ROI при
 * изменении параметра на Δ% с шагами -20 / -10 / 0 / +10 / +20.
 *
 * <p>МАСШТАБИРОВАНИЕ: расчёт повторяется на изменённом входе — цены
 * оборудования умножаются на (1 + Δ) (фактор стоимости НЕ трогается:
 * масштабируется ровно один рычаг, иначе — квадратичный эффект);
 * стоимость труда масштабирует ФОТ контура и зарплату (ΔOther — прочие
 * эффекты, НЕ масштабируется: не зависит от труда).
 *
 * <p>ОБЪЁМ ОПЕРАЦИЙ: чувствительность по объёму
 * отвечает на вопрос «что будет с экономикой, если парк увеличить
 * пропорционально» — выбранный состав масштабируется пропорционально
 * Δ%: scaledTotal = ceil(selectedRobots × (1 + Δ)), распределение по
 * строкам состава — методом наибольших остатков (Σ ровно scaledTotal);
 * годовой объём операций × (1 + Δ); персонал эксплуатации — доля парка
 * (не доля Δ%). P_nominal в масштабировании НЕ участвует: строка Δ=0
 * воспроизводит основной результат при ЛЮБОМ составе (раньше парк
 * пересчитывался по формуле §2.1 от масштабированной нагрузки — база
 * Δ=0 расходилась с основным расчётом, когда selected ≠ required).
 * Поле note в SensitivityRowDto сохранено для обратной совместимости
 * исторических расчётов (append-only) и всегда null в новых строках.
 *
 * <p>Чистый класс без БД: тестируется unit-ом напрямую.
 */
@org.springframework.stereotype.Service
public class SensitivityService {

    // Параметры чувствительности: код + название.
    /**
 * Код: стоимость оборудования (цены × (1+Δ)).
 */
    public static final String EQUIPMENT = "equipment";
    /**
 * Код: объём операций (состав и объём × (1+Δ)).
 */
    public static final String OPERATIONS = "operations";
    /**
 * Код: стоимость труда (ФОТ и зарплата × (1+Δ)).
 */
    public static final String LABOR = "labor";

    private static BigDecimal scale(BigDecimal value, BigDecimal factor) {
        return value == null ? null : value.multiply(factor);
    }

    /**
 * Таблица чувствительности для входа и базового результата: 3 параметра
 * × 5 шагов Δ% = до 15 строк.
 *
 * @param input вход основного расчёта (масштабируется по рычагу)
 * @param base результат основного расчёта (строка Δ=0)
 * @return строки чувствительности (3 параметра × 5 шагов)
 */
    public List<SensitivityRowDto> sensitivity(EconomicsInput input,
                                               EconomicsResult base) {
        List<SensitivityRowDto> rows = new ArrayList<>();
        rows.addAll(rowsFor(EQUIPMENT, input, base));
        rows.addAll(rowsFor(OPERATIONS, input, base));
        rows.addAll(rowsFor(LABOR, input, base));
        return rows;
    }

    private List<SensitivityRowDto> rowsFor(String parameter,
                                            EconomicsInput input,
                                            EconomicsResult base) {
        List<SensitivityRowDto> rows = new ArrayList<>();
        for (BigDecimal step : EconomicModel.SENSITIVITY_STEPS) {
            EconomicsResult result = EconomicModel.calculate(
                    scaled(input, parameter, step));
            rows.add(new SensitivityRowDto(parameter, step,
                    result.effectYear(),
                    base.effectYear() == null || result.effectYear() == null
                            ? null
                            : result.effectYear().subtract(base.effectYear()),
                    result.paybackYears(), result.roiPct(), null));
        }
        return rows;
    }

    /**
 * Вход с изменённым на Δ% параметром.
 */
    EconomicsInput scaled(EconomicsInput input, String parameter,
                          BigDecimal deltaPct) {
        BigDecimal factor = BigDecimal.ONE.add(
                deltaPct.divide(BigDecimal.valueOf(100), 6,
                        RoundingMode.HALF_UP));
        return switch (parameter) {
            case EQUIPMENT -> new EconomicsInput(input.kind(),
                    input.peakDemandPerHour(), input.hoursYear(),
                    input.operationsYear(), input.fotBaseYear(),
                    input.salaryYearYear(), input.payrollRate(),
                    input.horizonYears(), input.kLoad(), input.kReserve(),
                    input.kAvailability(), input.pNominalPerHour(),
                    input.powerConsumptionKw(), input.tariffRubPerKwh(),
                    input.reserveRate(), input.ratioInfra(),
                    input.cInfraPct(), input.cSoftwarePct(),
                    input.cIntegrationPct(), input.cCommissioningPct(),
                    input.cTrainingPct(), input.cServicePct(),
                    input.cLicensesPct(), input.cCommunicationRubPerRobotYear(),
                    input.cConsumablesPct(), input.cRepairPct(),
                    input.amortizationYears(),
                    // цены ×(1+Δ); equipment_cost_factor_pct НЕ трогаем —
                    // иначе CAPEX масштабируется дважды: ×(1+Δ)²
                    input.equipmentCostFactorPct(),
                    input.otherAnnualEffectRub(),
                    input.exploitationStaffCount(), input.raas(),
                    input.loan(), scaleLines(input, factor));
            case OPERATIONS -> {
                // выбранный парк масштабируется ПРОПОРЦИОНАЛЬНО Δ%
                // (ceil(selectedRobots × (1+Δ)) + наибольшие остатки);
                // персонал эксплуатации масштабируется ВМЕСТЕ С ПАРКОМ
                // (доля парка, а не доля Δ%); P_nominal не участвует —
                // база Δ=0 совпадает с основным расчётом при любом составе
                List<SolutionLine> scaledLines =
                        scaledComposition(input, factor);
                int scaledTotal = scaledLines.stream()
                        .mapToInt(SolutionLine::quantity).sum();
                yield new EconomicsInput(input.kind(),
                        scale(input.peakDemandPerHour(), factor),
                        input.hoursYear(),
                        scale(input.operationsYear(), factor),
                        input.fotBaseYear(), input.salaryYearYear(),
                        input.payrollRate(), input.horizonYears(),
                        input.kLoad(), input.kReserve(), input.kAvailability(),
                        input.pNominalPerHour(), input.powerConsumptionKw(),
                        input.tariffRubPerKwh(), input.reserveRate(),
                        input.ratioInfra(), input.cInfraPct(),
                        input.cSoftwarePct(), input.cIntegrationPct(),
                        input.cCommissioningPct(), input.cTrainingPct(),
                        input.cServicePct(), input.cLicensesPct(),
                        input.cCommunicationRubPerRobotYear(),
                        input.cConsumablesPct(), input.cRepairPct(),
                        input.amortizationYears(), input.equipmentCostFactorPct(),
                        input.otherAnnualEffectRub(),
                        scaledStaff(input, scaledTotal), input.raas(),
                        input.loan(), scaledLines);
            }
            case LABOR -> new EconomicsInput(input.kind(),
                    input.peakDemandPerHour(), input.hoursYear(),
                    input.operationsYear(),
                    scale(input.fotBaseYear(), factor),
                    scale(input.salaryYearYear(), factor),
                    input.payrollRate(), input.horizonYears(),
                    input.kLoad(), input.kReserve(), input.kAvailability(),
                    input.pNominalPerHour(), input.powerConsumptionKw(),
                    input.tariffRubPerKwh(), input.reserveRate(),
                    input.ratioInfra(), input.cInfraPct(), input.cSoftwarePct(),
                    input.cIntegrationPct(), input.cCommissioningPct(),
                    input.cTrainingPct(), input.cServicePct(),
                    input.cLicensesPct(), input.cCommunicationRubPerRobotYear(),
                    input.cConsumablesPct(), input.cRepairPct(),
                    input.amortizationYears(), input.equipmentCostFactorPct(),
                    // ΔOther — прочие эффекты, от стоимости труда
                    // не зависят — НЕ масштабируем
                    input.otherAnnualEffectRub(),
                    input.exploitationStaffCount(), input.raas(),
                    input.loan(), input.lines());
            default -> throw new IllegalArgumentException(
                    "Неизвестный параметр чувствительности: " + parameter);
        };
    }

    /**
 * Объём операций: выбранный парк масштабируется
 * ПРОПОРЦИОНАЛЬНО Δ%: scaledTotal = ceil(selectedRobots × (1 + Δ));
 * распределение по строкам состава — методом наибольших остатков,
 * Σ ровно scaledTotal. P_nominal и формула §2.1
 * здесь не участвуют: чувствительность по объёму показывает экономику
 * пропорционально увеличенного парка, а строка Δ=0 воспроизводит
 * основной результат при любом составе (раньше парк брался по формуле
 * от масштабированной нагрузки — база расходилась с основным
 * расчётом при selected ≠ required).
 */
    private List<SolutionLine> scaledComposition(EconomicsInput input,
                                                 BigDecimal factor) {
        if (input.lines().isEmpty()) {
            return input.lines();
        }
        List<SolutionLine> lines = input.lines();
        int total = lines.stream().mapToInt(SolutionLine::quantity).sum();
        // scaledTotal = ceil(selectedRobots × (1 + Δ)) — пропорционально
        // выбранному составу; округление вверх (§4)
        int scaledTotal = BigDecimal.valueOf(total).multiply(factor)
                .setScale(0, RoundingMode.CEILING).intValueExact();
        if (total == 0 || scaledTotal == total) {
            return input.lines();
        }
        // точные (вещественные) доли: q_i x scaledTotal / total
        BigDecimal[] exact = new BigDecimal[lines.size()];
        int[] units = new int[lines.size()];
        int floorSum = 0;
        for (int i = 0; i < lines.size(); i++) {
            exact[i] = BigDecimal.valueOf(lines.get(i).quantity())
                    .multiply(BigDecimal.valueOf(scaledTotal))
                    .divide(BigDecimal.valueOf(total), 6,
                            RoundingMode.HALF_UP);
            units[i] = exact[i].setScale(0, RoundingMode.DOWN)
                    .intValueExact();
            floorSum += units[i];
        }
        // остаток — строкам с наибольшей дробной частью (при равенстве —
        // строкам с большим количеством, порядок стабилен); Σ = scaledTotal
        int remaining = scaledTotal - floorSum;
        Integer[] order = new Integer[lines.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> {
            int byFraction = exact[a].remainder(BigDecimal.ONE)
                    .compareTo(exact[b].remainder(BigDecimal.ONE));
            if (byFraction != 0) {
                return -byFraction;
            }
            return Integer.compare(lines.get(b).quantity(),
                    lines.get(a).quantity());
        });
        for (int k = 0; k < remaining && k < order.length; k++) {
            units[order[k]] += 1;
        }
        List<SolutionLine> scaled = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            scaled.add(new SolutionLine(lines.get(i).solutionId(),
                    lines.get(i).name(), units[i], lines.get(i).priceRub(),
                    lines.get(i).lifetimeYears(),
                    lines.get(i).chargingPowerKw()));
        }
        return scaled;
    }

    /**
 * Персонал эксплуатации при изменении объёма операций: масштабируется
 * ВМЕСТЕ С ПАРКОМ (доля фактического парка scaledTotal/total, а не
 * доля Δ%): больше роботов — больше операторов парка; ФОТ контура
 * не меняется (объём роботизируемого контура от парка не зависит).
 */
    private int scaledStaff(EconomicsInput input, int scaledTotal) {
        int total = input.lines().stream()
                .mapToInt(SolutionLine::quantity).sum();
        if (total <= 0 || input.exploitationStaffCount() <= 0) {
            return input.exploitationStaffCount();
        }
        return BigDecimal.valueOf(input.exploitationStaffCount())
                .multiply(BigDecimal.valueOf(scaledTotal))
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.CEILING).intValueExact();
    }

    private List<SolutionLine> scaleLines(EconomicsInput input,
                                          BigDecimal factor) {
        return input.lines().stream()
                .map(line -> new SolutionLine(line.solutionId(), line.name(),
                        line.quantity(),
                        line.priceRub().multiply(factor), line.lifetimeYears(),
                        line.chargingPowerKw()))
                .toList();
    }
}
