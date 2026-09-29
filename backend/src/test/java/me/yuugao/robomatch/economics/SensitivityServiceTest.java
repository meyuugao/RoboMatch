package me.yuugao.robomatch.economics;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.dto.SensitivityRowDto;
import me.yuugao.robomatch.economics.EconomicModel.EconomicsInput;
import me.yuugao.robomatch.economics.EconomicModel.EconomicsResult;
import me.yuugao.robomatch.economics.EconomicModel.ScenarioKind;
import me.yuugao.robomatch.economics.EconomicModel.SolutionLine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unit-тесты чувствительности:
 * 3 параметра × 5 шагов (-20/-10/0/+10/+20), пересчёт Effect/Payback/ROI.
 */
class SensitivityServiceTest {

    private final SensitivityService service = new SensitivityService();

    /**
 * База: состав 3 робота СОВПАДАЕТ с формулой §2.1
 * (need = 40 × 1.15 / (25 × 0.75 × 1.0) = 2.45 → ceil 3) —
 * типовой случай подбора: строка Δ=0 по operations воспроизводит
 * базовый результат.
 */
    private static EconomicsInput input() {
        return new EconomicsInput(ScenarioKind.PURCHASE,
                bd("40"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                null, null,
                List.of(new SolutionLine(1, "Робот", 3, bd("2500000"),
                        bd("6"), bd("1.35"))));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void threeParameters_fiveStepsEach() {
        List<SensitivityRowDto> rows = service.sensitivity(input(),
                EconomicModel.calculate(input()));
        assertThat(rows).hasSize(15);
        assertThat(rows.stream().map(SensitivityRowDto::parameter).distinct()
                .toList()).containsExactly("equipment", "operations", "labor");
        for (String parameter : List.of("equipment", "operations", "labor")) {
            List<BigDecimal> steps = rows.stream()
                    .filter(r -> r.parameter().equals(parameter))
                    .map(SensitivityRowDto::deltaPct).toList();
            assertThat(steps).containsExactly(bd("-20"), bd("-10"), bd("0"),
                    bd("10"), bd("20"));
        }
    }

    @Test
    void zeroStep_reproducesBaseResult() {
        EconomicsInput input = input();
        EconomicsResult base = EconomicModel.calculate(input);
        List<SensitivityRowDto> rows = service.sensitivity(input, base);
        for (SensitivityRowDto row : rows) {
            if (row.deltaPct().signum() == 0) {
                assertThat(row.effectYear())
                        .isEqualByComparingTo(base.effectYear());
                assertThat(row.paybackYears())
                        .isEqualByComparingTo(base.paybackYears());
                assertThat(row.roiPct()).isEqualByComparingTo(base.roiPct());
                assertThat(row.effectDelta()).isEqualByComparingTo("0");
            }
        }
    }

    @Test
    void equipmentMinus20_increasesEffect() {
        // Дешевле оборудование → меньше CAPEX/амортизация/%-статьи OPEX →
        // эффект выше, окупаемость короче
        EconomicsInput input = input();
        EconomicsResult base = EconomicModel.calculate(input);
        List<SensitivityRowDto> rows = service.sensitivity(input, base);
        SensitivityRowDto minus = rows.stream()
                .filter(r -> r.parameter().equals("equipment")
                        && r.deltaPct().compareTo(bd("-20")) == 0)
                .findFirst().orElseThrow();
        SensitivityRowDto plus = rows.stream()
                .filter(r -> r.parameter().equals("equipment")
                        && r.deltaPct().compareTo(bd("20")) == 0)
                .findFirst().orElseThrow();
        assertThat(minus.effectYear()).isGreaterThan(base.effectYear());
        assertThat(plus.effectYear()).isLessThan(base.effectYear());
        assertThat(minus.effectDelta()).isGreaterThan(BigDecimal.ZERO);
        // Окупаемость монотонна по стоимости оборудования
        assertThat(minus.paybackYears()).isLessThan(plus.paybackYears());
    }

    @Test
    void laborPlus20_scalesPayrollSavings() {
        // Дороже труд → больше экономия ФОТ → эффект строго растёт
        // (эффект линейен по зарплате: 20% роста эффекта = 0.2 × ΔFOT)
        EconomicsInput input = input();
        EconomicsResult base = EconomicModel.calculate(input);
        List<SensitivityRowDto> rows = service.sensitivity(input, base);
        SensitivityRowDto plus = rows.stream()
                .filter(r -> r.parameter().equals("labor")
                        && r.deltaPct().compareTo(bd("20")) == 0)
                .findFirst().orElseThrow();
        BigDecimal expectedEffect = base.effectYear()
                .add(base.deltaFot().multiply(bd("0.2")));
        assertThat(plus.effectYear()).isEqualByComparingTo(expectedEffect);
        SensitivityRowDto minus = rows.stream()
                .filter(r -> r.parameter().equals("labor")
                        && r.deltaPct().compareTo(bd("-20")) == 0)
                .findFirst().orElseThrow();
        assertThat(minus.effectYear())
                .isEqualByComparingTo(base.effectYear()
                        .subtract(base.deltaFot().multiply(bd("0.2"))));
    }

    @Test
    void operations_parkScalesProportionally() {
        // (§2.12): выбранный парк масштабируется
        // ПРОПОРЦИОНАЛЬНО Δ%: scaledTotal = ceil(selected × (1+Δ)) —
        // 3 ед.: +10% → ceil(3.3) = 4; +20% → ceil(3.6) = 4; −20% → ceil(2.4) = 3.
        // P_nominal больше не участвует (required = 3 здесь — только
        // рекомендация, база Δ=0 обязана совпадать с основным расчётом)
        EconomicsInput input = input();
        assertThat(service.scaled(input, "operations", bd("10")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(4);
        assertThat(service.scaled(input, "operations", bd("20")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(4);
        assertThat(service.scaled(input, "operations", bd("-20")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(3);
        // примечаний больше нет — парк всегда пропорционален выбранному
        EconomicsResult base = EconomicModel.calculate(input);
        List<SensitivityRowDto> rows = service.sensitivity(input, base);
        assertThat(rows.stream().filter(r -> "operations".equals(r.parameter()))
                .allMatch(r -> r.note() == null)).isTrue();
    }

    @Test
    void operations_sameScaling_whenPNominalMissing() {
        // P_nominal не влияет на масштабирование парка —
        // пропорционально выбранному составу при любом P_nominal
        // (раньше был fallback с примечанием «P_nominal не задан»)
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                null, bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                null, null,
                List.of(new SolutionLine(1, "Робот", 3, bd("2500000"),
                        bd("6"), bd("1.35"))));
        // пропорционально: ceil(3 × 1.2) = 4
        assertThat(service.scaled(input, "operations", bd("20")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(4);
        EconomicsResult base = EconomicModel.calculate(input);
        List<SensitivityRowDto> rows = service.sensitivity(input, base);
        // примечаний нет ни в одной строке (продюсер parkScalingNote
        // удалён вместе с fallback)
        assertThat(rows.stream().filter(r -> "operations".equals(r.parameter()))
                .allMatch(r -> r.note() == null)).isTrue();
    }

    @Test
    void operationsStaff_scalesWithParkNotDelta() {
        // Персонал эксплуатации масштабируется ВМЕСТЕ С ПАРКОМ (доля
        // парка), а не по Δ%: парк 3 → +10%/+20% даёт 4 ед. — персонал
        // ceil(3×4/3) = 4; −20% даёт 3 ед. — персонал 3
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("40"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 3,
                null, null,
                List.of(new SolutionLine(1, "Робот", 3, bd("2500000"),
                        bd("6"), bd("1.35"))));
        assertThat(service.scaled(input, "operations", bd("10"))
                .exploitationStaffCount()).isEqualTo(4);
        assertThat(service.scaled(input, "operations", bd("20"))
                .exploitationStaffCount()).isEqualTo(4);
        assertThat(service.scaled(input, "operations", bd("-20"))
                .exploitationStaffCount()).isEqualTo(3);
    }

    @Test
    void equipmentPlus20_capexExactly120Percent() {
        // Ревью E-01: масштабируется ТОЛЬКО цена (не фактор стоимости):
        // CAPEX-оборудование при +20% — ровно ×1.2 (раньше было ×1.44)
        EconomicsInput input = input();
        EconomicsInput scaled = service.scaled(input, "equipment", bd("20"));
        assertThat(scaled.equipmentCostFactorPct())
                .isEqualByComparingTo(input.equipmentCostFactorPct());
        EconomicsResult base = EconomicModel.calculate(input);
        EconomicsResult plus = EconomicModel.calculate(scaled);
        assertThat(plus.cEquipment())
                .isEqualByComparingTo(base.cEquipment().multiply(bd("1.2"))
                        .setScale(0, java.math.RoundingMode.HALF_UP));
        // и симметрично −20% → ×0.8 (а не ×0.64)
        EconomicsResult minus = EconomicModel.calculate(
                service.scaled(input, "equipment", bd("-20")));
        assertThat(minus.cEquipment())
                .isEqualByComparingTo(base.cEquipment().multiply(bd("0.8"))
                        .setScale(0, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void laborDoesNotScaleOtherEffect() {
        // Ревью E-05: ΔOther — прочие эффекты, от стоимости труда
        // не зависят: при ΔOther > 0 эффект при ±20% меняется ровно
        // на 0.2 × ΔFOT, без вклада ΔOther
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("500000"), 0,
                null, null,
                List.of(new SolutionLine(1, "Робот", 3, bd("2500000"),
                        bd("6"), bd("1.35"))));
        EconomicsResult base = EconomicModel.calculate(input);
        assertThat(base.deltaOther()).isEqualByComparingTo("500000");
        List<SensitivityRowDto> rows = service.sensitivity(input, base);
        SensitivityRowDto plus = rows.stream()
                .filter(r -> r.parameter().equals("labor")
                        && r.deltaPct().compareTo(bd("20")) == 0)
                .findFirst().orElseThrow();
        // ΔEffect = 0.2 × ΔFOT (ΔOther не участвует)
        assertThat(plus.effectYear())
                .isEqualByComparingTo(base.effectYear()
                        .add(base.deltaFot().multiply(bd("0.2"))));
    }

    @Test
    void operationsScaling_multiLinePark_exactTotal() {
        // Многолинейный состав 2+3+1+2+1 (9 единиц): пропорциональное
        // масштабирование — scaledTotal = ceil(9 × (1+Δ)),
        // распределение по строкам методом наибольших остатков
        // (Σ ровно scaledTotal):
        // +10%: ceil(9.9) = 10; +20%: ceil(10.8) = 11;
        // −10%: ceil(8.1) = 9; −20%: ceil(7.2) = 8
        me.yuugao.robomatch.economics.EconomicModel.EconomicsInput input =
                new me.yuugao.robomatch.economics.EconomicModel.EconomicsInput(
                        me.yuugao.robomatch.economics.EconomicModel.ScenarioKind.PURCHASE,
                        bd("136.36"), bd("8030"), bd("730000"),
                        bd("60000000"), bd("1332000"), bd("1.302"),
                        5,
                        bd("0.75"), bd("0.15"), bd("1.0"),
                        bd("25"), bd("1.35"), bd("7"),
                        bd("0.10"), bd("0.35"),
                        bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                        bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                        bd("6"), bd("100"), bd("0"), 0,
                        null, null,
                        java.util.List.of(
                                new SolutionLine(1, "Робот А", 2, bd("2500000"),
                                        bd("6"), bd("1.35")),
                                new SolutionLine(2, "Робот Б", 3, bd("950000"),
                                        bd("6"), null),
                                new SolutionLine(3, "Робот В", 1, bd("1500000"),
                                        bd("6"), null),
                                new SolutionLine(4, "Робот Г", 2, bd("1800000"),
                                        bd("6"), null),
                                new SolutionLine(5, "Робот Д", 1, bd("2200000"),
                                        bd("6"), null)));
        for (BigDecimal[] stepExpected : java.util.List.of(
                new BigDecimal[]{bd("20"), bd("11")},
                new BigDecimal[]{bd("10"), bd("10")},
                new BigDecimal[]{bd("-10"), bd("9")},
                new BigDecimal[]{bd("-20"), bd("8")})) {
            var scaled = service.scaled(input, "operations", stepExpected[0]);
            int total = scaled.lines().stream()
                    .mapToInt(SolutionLine::quantity).sum();
            assertThat(total).as("шаг %s%%", stepExpected[0])
                    .isEqualTo(stepExpected[1].intValueExact());
        }
        // Δ=0: парк не меняется — 9
        assertThat(service.scaled(input, "operations", bd("0")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum()).isEqualTo(9);
        // распределение +10% (scaledTotal = 10 из 2+3+1+2+1): точные доли
        // 2.22/3.33/1.11/2.22/1.11 → полы 2/3/1/2/1 (Σ=9), остаток 1 —
        // строке с наибольшей дробной частью (Робот Б, 0.33) → 2+4+1+2+1 = 10
        var plus10 = service.scaled(input, "operations", bd("10")).lines();
        assertThat(plus10.stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(10);
        assertThat(plus10.get(1).quantity()).isEqualTo(4);
    }

    @Test
    void scaledInput_keepsOtherParametersUntouched() {
        EconomicsInput input = input();
        EconomicsInput scaled = service.scaled(input, "labor", bd("10"));
        // Только ФОТ/зарплата масштабируются; всё остальное — как было
        assertThat(scaled.peakDemandPerHour())
                .isEqualByComparingTo(input.peakDemandPerHour());
        assertThat(scaled.kLoad()).isEqualByComparingTo(input.kLoad());
        assertThat(scaled.tariffRubPerKwh())
                .isEqualByComparingTo(input.tariffRubPerKwh());
        assertThat(scaled.fotBaseYear())
                .isEqualByComparingTo(input.fotBaseYear().multiply(bd("1.1")));
        // ΔOther не масштабируется
        assertThat(scaled.otherAnnualEffectRub())
                .isEqualByComparingTo(input.otherAnnualEffectRub());
    }

    @Test
    void operationsZeroAvailability_proportional_noDivisionByZero() {
        // Ревью B-4/B-3: k_availability = 0 — легальное значение каталога
        // [0..1]; знаменатель формулы §2.1 (P_nominal × K_load ×
        // K_availability) обнуляется — модель оставляет requiredRobots =
        // null и продолжает расчёт. После чувствительность вообще
        // не делит на знаменатель (парк пропорционален выбранному) —
        // исключение невозможно ни при каких ТТХ, строка Δ=0 совпадает
        // с основным расчётом
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("40"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                null, null,
                List.of(new SolutionLine(1, "Робот", 3, bd("2500000"),
                        bd("6"), bd("1.35"))));
        // пропорционально выбранному: ceil(3 × 1.2) = 4 — без исключения
        assertThat(service.scaled(input, "operations", bd("20")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(4);
        // сама модель не падает: requiredRobots = null (знаменатель 0)
        EconomicsResult base = EconomicModel.calculate(input);
        assertThat(base.requiredRobots()).isNull();
        assertThat(base.underpowered()).isFalse();
        // строка Δ=0 воспроизводит основной расчёт, примечаний нет
        List<SensitivityRowDto> rows = service.sensitivity(input, base);
        assertThat(rows.stream().filter(r -> "operations".equals(r.parameter())
                        && r.deltaPct().signum() == 0)
                .allMatch(r -> r.effectYear()
                        .compareTo(base.effectYear()) == 0 && r.note() == null))
                .isTrue();
    }

    /**
 * База
 * чувствительности по объёму (Δ=0) обязана совпадать с основным
 * результатом ПРИ ЛЮБОМ составе — парк масштабируется от
 * selectedRobots, а не от requiredRobots по формуле §2.1.
 */
    @Test
    void operationsBaselineMatchesSelectedRobots() {
        // selected = 5, required = 3 (need = 40×1.15/18.75 = 2.45 → 3):
        // ранее база Δ=0 считалась для парка по формуле (3) и расходилась
        // с основным расчётом (5) на миллионы рублей
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("40"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                null, null,
                List.of(new SolutionLine(1, "Робот", 5, bd("2500000"),
                        bd("6"), bd("1.35"))));
        EconomicsResult base = EconomicModel.calculate(input);
        assertThat(base.selectedRobots()).isEqualTo(5);
        assertThat(base.requiredRobots()).isEqualTo(3);
        List<SensitivityRowDto> rows = service.sensitivity(input, base);
        // Δ=0: effectYear равен основному результату ДО РУБЛЯ,
        // payback/roi совпадают, Δ-эффект — ровно 0
        SensitivityRowDto zero = rows.stream()
                .filter(r -> "operations".equals(r.parameter())
                        && r.deltaPct().signum() == 0)
                .findFirst().orElseThrow();
        assertThat(zero.effectYear()).isEqualByComparingTo(base.effectYear());
        assertThat(zero.paybackYears()).isEqualByComparingTo(
                base.paybackYears());
        assertThat(zero.roiPct()).isEqualByComparingTo(base.roiPct());
        assertThat(zero.effectDelta()).isEqualByComparingTo("0");
        // масштабирование парка от selected = 5:
        // +10% → ceil(5.5) = 6; +20% → ceil(6.0) = 6;
        // −10% → ceil(4.5) = 5; −20% → ceil(4.0) = 4
        assertThat(service.scaled(input, "operations", bd("10")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(6);
        assertThat(service.scaled(input, "operations", bd("20")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(6);
        assertThat(service.scaled(input, "operations", bd("-10")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(5);
        assertThat(service.scaled(input, "operations", bd("-20")).lines()
                .stream().mapToInt(SolutionLine::quantity).sum())
                .isEqualTo(4);
    }

    /**
 * Регрессионный тест — чувствительность по стоимости оборудования
 * корректна при нескольких конфигурациях парка (selected = 1/5/9,
 * required = 3/5): база Δ=0 совпадает с основным результатом.
 * Оборудование не меняет состав — сверка главный/базовый обязана
 * сходиться при любом парке.
 */
    @Test
    void equipmentBaselineMatchesMain() {
        // (selected, required): need = peak×1.15/18.75; peak=40 → 2.45 → 3;
        // peak=80 → 4.91 → 5
        for (Object[] config : java.util.List.of(
                new Object[]{1, bd("40"), 3},
                new Object[]{5, bd("40"), 3},
                new Object[]{9, bd("40"), 3},
                new Object[]{5, bd("80"), 5},
                new Object[]{9, bd("80"), 5})) {
            int selected = (Integer) config[0];
            BigDecimal peak = (BigDecimal) config[1];
            int required = (Integer) config[2];
            EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                    peak, bd("8030"), bd("730000"),
                    bd("60000000"), bd("1332000"), bd("1.302"),
                    5,
                    bd("0.75"), bd("0.15"), bd("1.0"),
                    bd("25"), bd("1.35"), bd("7"),
                    bd("0.10"), bd("0.35"),
                    bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                    bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                    bd("6"), bd("100"), bd("0"), 0,
                    null, null,
                    List.of(new SolutionLine(1, "Робот", selected,
                            bd("2500000"), bd("6"), bd("1.35"))));
            EconomicsResult base = EconomicModel.calculate(input);
            assertThat(base.selectedRobots())
                    .as("selected, конфигурация %s/%s", selected, required)
                    .isEqualTo(selected);
            assertThat(base.requiredRobots())
                    .as("required, конфигурация %s/%s", selected, required)
                    .isEqualTo(required);
            List<SensitivityRowDto> rows = service.sensitivity(input, base);
            SensitivityRowDto zero = rows.stream()
                    .filter(r -> "equipment".equals(r.parameter())
                            && r.deltaPct().signum() == 0)
                    .findFirst().orElseThrow();
            assertThat(zero.effectYear())
                    .as("effectYear Δ=0, конфигурация %s/%s",
                            selected, required)
                    .isEqualByComparingTo(base.effectYear());
            assertThat(zero.paybackYears())
                    .as("payback Δ=0, конфигурация %s/%s",
                            selected, required)
                    .isEqualByComparingTo(base.paybackYears());
            assertThat(zero.roiPct())
                    .as("roi Δ=0, конфигурация %s/%s", selected, required)
                    .isEqualByComparingTo(base.roiPct());
            assertThat(zero.effectDelta()).isEqualByComparingTo("0");
        }
    }
}
