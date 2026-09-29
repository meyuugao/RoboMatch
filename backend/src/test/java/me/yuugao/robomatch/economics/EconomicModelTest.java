package me.yuugao.robomatch.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import me.yuugao.robomatch.economics.EconomicModel.*;
import me.yuugao.robomatch.exception.EconomicValidationException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unit-тесты чистого движка экономики - формулы economic_model.md
 * §2.1-2.13 и граничные случаи. Без Spring: вход-выход.
 *
 * <p>Числовые ожидания пересчитаны вручную по формулам документа;
 * округления - §4: N_robots вверх, стоимости до рублей, Payback
 * и ROI до десятых.
 */
class EconomicModelTest {

    // Проверяемые сценарные данные (типовой склад из датасета):
    // 3 робота по 2 500 000 руб., срок службы 6 лет, зарядка 1.35 кВт
    private static final List<SolutionLine> LINES = List.of(
            new SolutionLine(1, "Робот А", 2, bd("2500000"), bd("6"),
                    bd("1.35")),
            new SolutionLine(2, "Робот Б", 1, bd("2500000"), bd("6"),
                    bd("1.35")));

    /**
 * Компактный конструктор входа покупки со значениями по умолчанию.
 */
    private static EconomicsInput purchase() {
        return purchase(ScenarioKind.PURCHASE, bd("60000000"), 5, null, null);
    }

    private static EconomicsInput purchase(ScenarioKind kind,
                                           BigDecimal fotBase, int horizon,
                                           RaasTerms raas, LoanTerms loan) {
        return new EconomicsInput(kind,
                bd("136.36"), bd("8030"), bd("730000"),
                fotBase, bd("1332000"), bd("1.302"),
                horizon,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                raas, loan, LINES);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // ==================================================================
    // 2.1: требуемое количество роботов - всегда вверх
    // ==================================================================

    @Test
    void requiredRobots_ceil_underpoweredFlag() {
        // need = 136.36 × 1.15 / (25 × 0.75 × 1.0) = 156.81 / 18.75 = 8.36 → 9
        EconomicsResult result = EconomicModel.calculate(purchase());
        assertThat(result.requiredRobots()).isEqualTo(9);
        assertThat(result.selectedRobots()).isEqualTo(3);
        // состав меньше требуемого - флаг + предупреждение
        // (мягкая плашка, не исключение)
        assertThat(result.underpowered()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("меньше"));
        assertThat(result.warnings()).anyMatch(
                w -> w.contains("не отражает достижение"));
    }

    @Test
    void requiredRobots_sufficientPark_noFlag() {
        // Состав покрывает требуемое - флага нет, предупреждения нет
        List<SolutionLine> big = List.of(
                new SolutionLine(1, "Робот А", 5, bd("2500000"), bd("6"),
                        bd("1.35")),
                new SolutionLine(2, "Робот Б", 4, bd("2500000"), bd("6"),
                        bd("1.35")));
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"), bd("60000000"),
                bd("1332000"), bd("1.302"), 5,
                bd("0.75"), bd("0.15"), bd("1.0"), bd("25"), bd("1.35"),
                bd("7"), bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0, null, null, big);
        EconomicsResult result = EconomicModel.calculate(input);
        assertThat(result.selectedRobots()).isEqualTo(9);
        assertThat(result.requiredRobots()).isEqualTo(9);
        assertThat(result.underpowered()).isFalse();
        assertThat(result.warnings()).noneMatch(w -> w.contains("меньше"));
    }

    @Test
    void overpowered_flag() {
        // selected > required - нейтральный флаг
        // overpowered (5 ед. при требуемых 3): информация
        // «возможна переплата», НЕ ошибка - в warnings не добавляется
        List<SolutionLine> five = List.of(new SolutionLine(1, "Робот", 5,
                bd("2500000"), bd("6"), bd("1.35")));
        // need = 40 × 1.15 / (25 × 0.75 × 1.0) = 2.45 → 3
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("40"), bd("8030"), bd("730000"), bd("60000000"),
                bd("1332000"), bd("1.302"), 5,
                bd("0.75"), bd("0.15"), bd("1.0"), bd("25"), bd("1.35"),
                bd("7"), bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0, null, null, five);
        EconomicsResult r = EconomicModel.calculate(input);
        assertThat(r.selectedRobots()).isEqualTo(5);
        assertThat(r.requiredRobots()).isEqualTo(3);
        assertThat(r.overpowered()).isTrue();
        assertThat(r.underpowered()).isFalse();
        // нейтральный флаг: предупреждений о превышении нет
        assertThat(r.warnings()).noneMatch(w -> w.contains("превыша"));
    }

    // ==================================================================
    // Пустой состав - ΔFOT = 0
    // ==================================================================

    @Test
    void emptyComposition_zeroDeltaFot() {
        // Пустой состав роботизированного сценария - замещения персонала
        // нет, ΔFOT обнуляется (раньше полный FOT_base попадал в эффект
        // без единого робота и получался Effect_gross = +203 112 000).
        // Проверяются оба роботизированных
        // вида: PURCHASE и RAAS (§2.6: ΔFOT только при selected > 0)
        for (ScenarioKind kind : List.of(ScenarioKind.PURCHASE,
                ScenarioKind.RAAS)) {
            EconomicsInput input = new EconomicsInput(kind,
                    bd("136.36"), bd("8030"), bd("730000"), bd("60000000"),
                    bd("1332000"), bd("1.302"), 5,
                    bd("0.75"), bd("0.15"), bd("1.0"), bd("25"), bd("1.35"),
                    bd("7"), bd("0.10"), bd("0.35"),
                    bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                    bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                    bd("6"), bd("100"), bd("0"), 0,
                    kind == ScenarioKind.RAAS
                            ? new RaasTerms("fixed", bd("1.5"), 3, false,
                            null)
                            : null,
                    null, List.of());
            EconomicsResult r = EconomicModel.calculate(input);
            assertThat(r.selectedRobots()).as("kind %s", kind).isZero();
            assertThat(r.deltaFot()).as("kind %s", kind)
                    .isEqualByComparingTo("0");
            // предупреждение дополнено причиной обнуления ΔFOT
            assertThat(r.warnings()).as("kind %s", kind)
                    .anyMatch(w -> w.contains("ΔFOT обнулён"));
        }
    }

    // ==================================================================
    // A9: Effect_gross - пустой состав и нормальный состав
    // ==================================================================

    @Test
    void effectGross_emptyComposition_zero() {
        // A9 (покрытие ): Effect_gross при пустом составе =
        // 0 + ΔOther − nonPayrollOpex; статьи пустого состава нулевые
        // (nonPayrollOpex = 0) → Effect_gross = ΔOther, амортизация 0 →
        // Effect_year = Effect_gross
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"), bd("60000000"),
                bd("1332000"), bd("1.302"), 5,
                bd("0.75"), bd("0.15"), bd("1.0"), bd("25"), bd("1.35"),
                bd("7"), bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("500000"), 0, null, null, List.of());
        EconomicsResult r = EconomicModel.calculate(input);
        assertThat(r.deltaOther()).isEqualByComparingTo("500000");
        // nonPayrollOpex пустого состава = 0 → −nonPayrollOpex не меняет ΔOther
        assertThat(r.effectGross()).isEqualByComparingTo("500000");
        assertThat(r.amortYear()).isEqualByComparingTo("0");
        assertThat(r.effectYear()).isEqualByComparingTo("500000");
    }

    @Test
    void effectGross_normal() {
        // A9: при selected > 0 формула §2.6 сохраняется дословно:
        // Effect_gross = ΔFOT + ΔOther − nonPayrollOpex
        // (nonPayrollOpex = OPEX без ФОТ персонала эксплуатации)
        EconomicsResult r = EconomicModel.calculate(purchase());
        assertThat(r.selectedRobots()).isEqualTo(3);
        BigDecimal nonPayrollOpex = r.opexTotal().subtract(r.cStaff());
        assertThat(r.effectGross()).isEqualByComparingTo(
                r.deltaFot().add(r.deltaOther()).subtract(nonPayrollOpex));
        // ΔFOT не обнулен: экономия ФОТ от замещения персонала есть
        assertThat(r.deltaFot()).isEqualByComparingTo("60000000");
    }

    @Test
    void requiredRobots_zeroPeak_zeroRobots() {
        // Пиковая потребность 0 → требуемое 0; выбранный 0 - валидный
        // случай (пустой состав): CAPEX = 0, окупаемость не определена
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("0"), bd("8030"), bd("0"), bd("60000000"),
                bd("1332000"), bd("1.302"), 5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"), bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0, null, null, List.of());
        EconomicsResult result = EconomicModel.calculate(input);
        assertThat(result.selectedRobots()).isZero();
        assertThat(result.requiredRobots()).isZero();
        assertThat(result.underpowered()).isFalse();
        assertThat(result.capexTotal()).isZero();
        assertThat(result.paybackYears()).isNull();
        assertThat(result.roiPct()).isNull();
    }

    // ==================================================================
    // 2.2: вспомогательное оборудование - вверх
    // ==================================================================

    @Test
    void nInfra_ceiling() {
        // 3 робота × 0.35 = 1.05 → 2 станции
        assertThat(EconomicModel.calculate(purchase()).nInfra()).isEqualTo(2);
    }

    // ==================================================================
    // 2.3: CAPEX по статьям + резерв как процент от суммы
    // ==================================================================

    @Test
    void capex_breakdown() {
        // C_equipment = 3 × 2 500 000 = 7 500 000
        // C_infra = 10% = 750 000; C_software = 15% = 1 125 000;
        // C_integration = 15% = 1 125 000; C_commissioning = 7.5% = 562 500;
        // C_training = 3% = 225 000; сумма = 11 287 500
        // C_reserve = 10% = 1 128 750; CAPEX = 12 416 250
        EconomicsResult r = EconomicModel.calculate(purchase());
        assertThat(r.cEquipment()).isEqualByComparingTo("7500000");
        assertThat(r.cInfra()).isEqualByComparingTo("750000");
        assertThat(r.cSoftware()).isEqualByComparingTo("1125000");
        assertThat(r.cIntegration()).isEqualByComparingTo("1125000");
        assertThat(r.cCommissioning()).isEqualByComparingTo("562500");
        assertThat(r.cTraining()).isEqualByComparingTo("225000");
        assertThat(r.cReserve()).isEqualByComparingTo("1128750");
        assertThat(r.capexTotal()).isEqualByComparingTo("12416250");
    }

    // ==================================================================
    // 2.4: OPEX, электроэнергия, персонал
    // ==================================================================

    @Test
    void opex_breakdown() {
        // C_electricity = 3 × 1.35 × 8030 × 7 = 227 650.5 → 227 651
        // C_communication = 3 × 36 000 = 108 000
        // C_service = 10% × 7.5М = 750 000; C_licenses = 5% = 375 000;
        // C_consumables = 2% = 150 000; C_repair = 3.5% = 262 500;
        // C_staff = 0 (персонал эксплуатации не задан)
        EconomicsResult r = EconomicModel.calculate(purchase());
        assertThat(r.cElectricity()).isEqualByComparingTo("227651");
        assertThat(r.cCommunication()).isEqualByComparingTo("108000");
        assertThat(r.cService()).isEqualByComparingTo("750000");
        assertThat(r.cLicenses()).isEqualByComparingTo("375000");
        assertThat(r.cConsumables()).isEqualByComparingTo("150000");
        assertThat(r.cRepair()).isEqualByComparingTo("262500");
        assertThat(r.cStaff()).isEqualByComparingTo("0");
        // OPEX = 750000+375000+227651+108000+150000+262500+0 = 1 873 151
        assertThat(r.opexTotal()).isEqualByComparingTo("1873151");
    }

    @Test
    void opex_staffUsesPayrollRate() {
        // C_staff = 2 × 1 332 000 × 1.302 = 3 468 528
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"), bd("60000000"),
                bd("1332000"), bd("1.302"), 5,
                bd("0.75"), bd("0.15"), bd("1.0"), bd("25"), bd("1.35"),
                bd("7"), bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 2, null, null, LINES);
        assertThat(EconomicModel.calculate(input).cStaff())
                .isEqualByComparingTo("3468528");
    }

    // ==================================================================
    // 2.5-2.6: ΔOPEX, ΔFOT, эффект (без двойного счёта ФОТ)
    // ==================================================================

    @Test
    void effect_noDoubleCountingOfPayroll() {
        // ΔFOT = 60 000 000 − 0 = 60 000 000
        // nonPayrollOpex = 750000+375000+227651+108000+150000+262500 = 1 873 151
        // effectGross = 60 000 000 + 0 − 1 873 151 = 58 126 849
        // amort = 12 416 250 / 6 = 2 069 375
        // effectYear = 56 057 474
        EconomicsResult r = EconomicModel.calculate(purchase());
        assertThat(r.deltaFot()).isEqualByComparingTo("60000000");
        assertThat(r.effectGross()).isEqualByComparingTo("58126849");
        assertThat(r.amortYear()).isEqualByComparingTo("2069375");
        assertThat(r.effectYear()).isEqualByComparingTo("56057474");
        // Полное ΔOPEX (с ФОТ): 1 873 151 − 60 000 000 = −58 126 849
        assertThat(r.opexDelta()).isEqualByComparingTo("-58126849");
    }

    // ==================================================================
    // 2.7-2.8: окупаемость и ROI
    // ==================================================================

    @Test
    void paybackAndRoi() {
        // Payback = 12 416 250 / 56 057 474 = 0.2215 → 0.2
        // ROI = 56 057 474 × 5 / 12 416 250 × 100 = 2257.4%
        EconomicsResult r = EconomicModel.calculate(purchase());
        assertThat(r.paybackYears()).isEqualByComparingTo("0.2");
        assertThat(r.roiPct()).isEqualByComparingTo("2257.4");
    }

    @Test
    void paybackNull_whenEffectNotPositive() {
        // Эффект ≤ 0 → Payback не выводится (§2.7), предупреждение
        EconomicsInput input = purchase(ScenarioKind.PURCHASE, bd("1000000"),
                5, null, null);
        EconomicsResult r = EconomicModel.calculate(input);
        assertThat(r.effectYear().signum()).isLessThanOrEqualTo(0);
        assertThat(r.paybackYears()).isNull();
        assertThat(r.warnings()).anyMatch(w -> w.contains("не окупается"));
    }

    // ==================================================================
    // 2.9: TCO и замены по сроку службы
    // ==================================================================

    @Test
    void tco_withoutReplacements_whenLifetimeCoversHorizon() {
        // Lifetime 6 ≥ Horizon 5 → замен нет
        // TCO = 12 416 250 + 1 873 151 × 5 + 0 = 21 782 005
        EconomicsResult r = EconomicModel.calculate(purchase());
        assertThat(r.replacementsRub()).isEqualByComparingTo("0");
        assertThat(r.tcoRub()).isEqualByComparingTo("21782005");
    }

    @Test
    void tco_withReplacements_whenLifetimeShorterThanHorizon() {
        // Lifetime 3, Horizon 7: замен = floor((7-1)/3) = 2 → 2 × 7 500 000
        // TCO = CAPEX(12 416 250) + OPEX×7 + 15 000 000
        List<SolutionLine> shortLife = List.of(new SolutionLine(1, "Робот",
                3, bd("2500000"), bd("3"), bd("1.35")));
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"), bd("60000000"),
                bd("1332000"), bd("1.302"), 7,
                bd("0.75"), bd("0.15"), bd("1.0"), bd("25"), bd("1.35"),
                bd("7"), bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("3"), bd("100"), bd("0"), 0, null, null, shortLife);
        EconomicsResult r = EconomicModel.calculate(input);
        assertThat(r.replacementsRub()).isEqualByComparingTo("15000000");
        assertThat(r.tcoRub()).isEqualByComparingTo(bd("12416250")
                .add(r.opexTotal().multiply(bd("7")))
                .add(bd("15000000")));
    }

    // ==================================================================
    // Базовый сценарий: изменений нет
    // ==================================================================

    @Test
    void baseScenario_zeroChanges() {
        EconomicsResult r = EconomicModel.calculate(
                purchase(ScenarioKind.BASE, bd("60000000"), 5, null, null));
        assertThat(r.selectedRobots()).isZero();
        assertThat(r.capexTotal()).isZero();
        // OPEX базового = ФОТ контура
        assertThat(r.opexTotal()).isEqualByComparingTo("60000000");
        assertThat(r.opexDelta()).isEqualByComparingTo("0");
        assertThat(r.effectYear()).isEqualByComparingTo("0");
        assertThat(r.paybackYears()).isNull();
        // TCO = OPEX × горизонт
        assertThat(r.tcoRub()).isEqualByComparingTo("300000000");
    }

    // ==================================================================
    // Граничные случаи (economic_model.md §6)
    // ==================================================================

    @Test
    void lifetimeZero_blocksCalculation() {
        // Срок службы 0 - знаменатель амортизации: блокировка расчёта
        List<SolutionLine> zeroLife = List.of(new SolutionLine(1, "Робот",
                1, bd("2500000"), bd("0"), null));
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"), bd("60000000"),
                bd("1332000"), bd("1.302"), 5,
                bd("0.75"), bd("0.15"), bd("1.0"), bd("25"), bd("1.35"),
                bd("7"), bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("0"), bd("100"), bd("0"), 0, null, null, zeroLife);
        assertThatThrownBy(() -> EconomicModel.calculate(input))
                .isInstanceOf(EconomicValidationException.class)
                .hasMessageContaining("Срок службы");
    }

    @Test
    void equipmentCostFactor_appliesToPrices() {
        // «стоимость оборудования»: фактор 90% → цены × 0.9
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"), bd("60000000"),
                bd("1332000"), bd("1.302"), 5,
                bd("0.75"), bd("0.15"), bd("1.0"), bd("25"), bd("1.35"),
                bd("7"), bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("90"), bd("0"), 0, null, null, LINES);
        assertThat(EconomicModel.calculate(input).cEquipment())
                .isEqualByComparingTo("6750000");
    }

    // ==================================================================
    // 2.10: заёмное финансирование (аннуитет)
    // ==================================================================

    @Test
    void loan_annuityReducesEffect_beforePaybackAndRoi() {
        // Кредит 10 000 000, ставка 20%, срок 3 года:
        // r = 0.2; (1.2)^3 = 1.728; платёж = 10М × 0.2 × 1.728 / 0.728
        // = 4 747 252.7 → 4 747 253
        LoanTerms loan = new LoanTerms(bd("10000000"), bd("20"), 3);
        EconomicsResult base = EconomicModel.calculate(purchase(
                ScenarioKind.PURCHASE, bd("60000000"), 5, null, null));
        EconomicsResult r = EconomicModel.calculate(purchase(
                ScenarioKind.PURCHASE, bd("60000000"), 5, null, loan));
        assertThat(r.loan()).isNotNull();
        assertThat(r.loan().debtPaymentYear())
                .isEqualByComparingTo("4747253");
        // Effect_year = Effect_gross − Amort − аннуитет;
        // LoanResult хранит оба значения - до и после обслуживания долга
        assertThat(r.loan().effectYearBeforeDebt())
                .isEqualByComparingTo(base.effectYear());
        assertThat(r.loan().effectYearAfterDebt())
                .isEqualByComparingTo(base.effectYear()
                        .subtract(bd("4747253")));
        assertThat(r.effectYear())
                .isEqualByComparingTo(r.loan().effectYearAfterDebt());
        // Payback/ROI - от эффекта ПОСЛЕ обслуживания долга:
        // effect = 56 057 474 − 4 747 253 = 51 310 221
        // Payback = 12 416 250 / 51 310 221 = 0.24 → 0.2
        assertThat(r.paybackYears()).isEqualByComparingTo("0.2");
        // ROI = 51 310 221 × 5 / 12 416 250 × 100 = 2066.3%
        assertThat(r.roiPct()).isEqualByComparingTo("2066.3");
    }

    @Test
    void loan_makesEffectNegative_paybackNull() {
        // Аннуитет больше эффекта: Effect_after ≤ 0 → не окупается (§2.7),
        // ROI отрицательный - числа остаются, окупаемость - нет.
        // Аннуитет 150М/20%/3 года = 150М × 0.474725… = 71 208 791 >
        // Effect_before = 56 057 474 → Effect_after < 0
        LoanTerms loan = new LoanTerms(bd("150000000"), bd("20"), 3);
        EconomicsResult r = EconomicModel.calculate(purchase(
                ScenarioKind.PURCHASE, bd("60000000"), 5, null, loan));
        assertThat(r.loan()).isNotNull();
        assertThat(r.loan().effectYearBeforeDebt()).isEqualByComparingTo("56057474");
        assertThat(r.effectYear().signum()).isLessThan(0);
        assertThat(r.paybackYears()).isNull();
        assertThat(r.warnings()).anyMatch(w -> w.contains("не окупается"));
    }

    @Test
    void tco_raas_buyout_includes_post_contract_opex() {
        // (§2.9): RaaS с выкупом - платежи идут годы контракта
        // (min(контракт, горизонт)), а электроэнергия/связь/персонал -
        // ВЕСЬ горизонт; выкуп единоразово в CAPEX_raas.
        // Состав 3 × 2.5М, lifetime 6, контракт 3, ставка 2%:
        // paymentYear = 12 × 50 000 × 3 = 1 800 000;
        // CAPEX_raas = 3 × 1 250 000 = 3 750 000;
        // opexNonPayment = 227 651 + 108 000 + 0 = 335 651;
        // TCO = 3 750 000 + 1 800 000 × 3 + 335 651 × 5 = 10 828 255
        // (раньше: 3 750 000 + 2 135 651 × 3 = 10 156 953 -
        // прочий OPEX обрывался датой контракта)
        RaasTerms terms = new RaasTerms("fixed", bd("2.0"), 3, true, null);
        EconomicsResult r = EconomicModel.calculate(purchase(
                ScenarioKind.RAAS, bd("60000000"), 5, terms, null));
        assertThat(r.raas().paymentYears()).isEqualTo(3);
        assertThat(r.tcoRub()).isEqualByComparingTo("10828255");
        // Разложение TCO по компонентам (для сверки формулы)
        assertThat(r.tcoRub()).isEqualByComparingTo(
                r.raas().capexRaas()
                        .add(r.raas().paymentYear().multiply(bd("3")))
                        .add(r.cElectricity().add(r.cCommunication())
                                .add(r.cStaff()).multiply(bd("5"))));
        // Без выкупа формула вырождается в OPEX × горизонт -
        // платежи весь срок, выкупа нет (регрессия A12)
        EconomicsResult noBuyout = EconomicModel.calculate(purchase(
                ScenarioKind.RAAS, bd("60000000"), 5,
                new RaasTerms("fixed", bd("2.0"), 3, false, null), null));
        assertThat(noBuyout.raas().paymentYears()).isEqualTo(5);
        assertThat(noBuyout.tcoRub())
                .isEqualByComparingTo(noBuyout.opexTotal().multiply(bd("5")));
    }

    @Test
    void underpowered_raas_sameWarningAsPurchase() {
        // у RaaS тот же признак недобора и предупреждение,
        // платежи считаются от выбранного состава (не от требуемого)
        RaasTerms terms = new RaasTerms("fixed", bd("2.0"), 3, false, null);
        EconomicsResult r = EconomicModel.calculate(purchase(
                ScenarioKind.RAAS, bd("60000000"), 5, terms, null));
        assertThat(r.selectedRobots()).isEqualTo(3);
        assertThat(r.requiredRobots()).isEqualTo(9);
        assertThat(r.underpowered()).isTrue();
        assertThat(r.warnings()).anyMatch(w -> w.contains("меньше"));
        // Платежи за выбранный состав: 12 × 50 000 × 3 (не 9!) = 1.8М
        assertThat(r.raas().paymentYear()).isEqualByComparingTo("1800000");
    }

    // ==================================================================
    // Округления (§4)
    // ==================================================================

    @Test
    void moneyRoundsToRuble_halfUp() {
        assertThat(EconomicModel.money(bd("227651.55")))
                .isEqualByComparingTo("227652");
        assertThat(EconomicModel.money(bd("227651.44")))
                .isEqualByComparingTo("227651");
    }

    // ==================================================================
    // Граничные случаи
    // ==================================================================

    @Test
    void paybackCappedAtOneMillionYears() {
        // экстремально малый эффект при большом CAPEX -
        // Payback обрезается (колонка numeric(10,2) не переполняется),
        // выводится предупреждение «не окупается в разумный срок».
        // Обычный случай - число есть: ФОТ 60 000 060 → payback 0.2 года
        EconomicsResult normal = EconomicModel.calculate(purchase(
                ScenarioKind.PURCHASE, bd("60000060"), 5, null, null));
        assertThat(normal.paybackYears()).isNotNull();
        // Крошечный эффект: ΔFOT = 3 942 532 (персонал 0), OPEX_без_ФОТ
        // = 1 873 151, амортизация = 2 069 375 → Effect_year = 6 руб./год
        // → Payback ≈ 2.07 млн лет > 1 млн → null + предупреждение
        EconomicsResult capped = EconomicModel.calculate(purchase(
                ScenarioKind.PURCHASE, bd("3942532"), 5, null, null));
        assertThat(capped.paybackYears()).isNull();
        assertThat(capped.warnings())
                .anyMatch(w -> w.contains("не окупается в разумный срок"));
    }

    @Test
    void lifetimeBelowOneYear_docFormulaNoCrash() {
        // Срок службы 0.5 года: формула §2.9 ДОСЛОВНО -
        // floor((Horizon-1)/Lifetime) = floor(4/0.5) = 8 замен парка
        // (обрезание Lifetime до целого + ежегодная
        // замена E-09 - отступление от формулы; теперь вещественный floor,
        // без ArithmeticException)
        List<SolutionLine> shortLived = List.of(
                new SolutionLine(1, "Робот А", 3, bd("1000000"),
                        bd("0.5"), bd("1.35")));
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("0.5"), bd("100"), bd("0"), 0,
                null, null, shortLived);
        // amortization_years = 0.5, lifetime ТТХ = 0.5 → weighted = 0.5
        EconomicsResult r = EconomicModel.calculate(input);
        // Replacements = floor(4/0.5) = 8 полных замен парка 3 000 000
        assertThat(r.replacementsRub()).isEqualByComparingTo("24000000");
        assertThat(r.tcoRub()).isNotNull();
    }

    @Test
    void replacements_fractionalLifetime_realFloor() {
        // дробный средневзвешенный срок 5.33 года, горизонт 6:
        // floor((6-1)/5.33) = floor(0.938) = 0 замен; обрезание до 5
        // давало 5/5 = 1 замену - расхождение с формулой §2.9
        List<SolutionLine> mix = List.of(
                new SolutionLine(1, "Робот А", 2, bd("1000000"),
                        bd("5"), bd("1.35")),
                new SolutionLine(2, "Робот Б", 1, bd("1000000"),
                        bd("6"), bd("1.35")));
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                6,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                null, null, mix);
        EconomicsResult r = EconomicModel.calculate(input);
        assertThat(r.replacementsRub()).isEqualByComparingTo("0");
    }

    @Test
    void roiBeyondBillionPercent_cappedNullWithWarning() {
        // |ROI| > 1 млрд % (эффект велик при крошечном
        // CAPEX) - null + предупреждение, вместо переполнения колонки
        // numeric(12,2) (раньше numeric(7,2) падала уже на 100 000 %)
        List<SolutionLine> tiny = List.of(
                new SolutionLine(1, "Робот А", 1, bd("1"),
                        bd("6"), bd("1.35")));
        EconomicsInput input = new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                10,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                null, null, tiny);
        EconomicsResult r = EconomicModel.calculate(input);
        assertThat(r.roiPct()).isNull();
        assertThat(r.warnings())
                .anyMatch(w -> w.contains("ROI вне представимого диапазона"));
    }

    @Test
    void baseScenario_noParkWarnings_byDefinition() {
        // Ревью B-7/B-3: базовый сценарий не содержит решений ПО ОПРЕДЕЛЕНИЮ
        // (инвариант base, data_model.md §10.5) - при
        // заданном P_nominal (required = 9) и пустом составе он раньше
        // получал предупреждения «Парк меньше требуемого (0 < 9)» и
        // «Состав пуст» в API/compare; теперь проверки парка к base
        // не применяются. Роботизированные сценарии предупреждение
        // сохраняют (см. requiredRobots_ceil_underpoweredFlag).
        EconomicsInput input = new EconomicsInput(ScenarioKind.BASE,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                null, null, List.of());
        EconomicsResult result = EconomicModel.calculate(input);
        assertThat(result.selectedRobots()).isZero();
        assertThat(result.underpowered()).isFalse();
        assertThat(result.warnings())
                .noneMatch(w -> w.contains("Парк меньше требуемого"))
                .noneMatch(w -> w.contains("Состав сценария пуст"));
    }
}
