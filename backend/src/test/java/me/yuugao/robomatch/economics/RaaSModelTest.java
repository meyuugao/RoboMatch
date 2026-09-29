package me.yuugao.robomatch.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import me.yuugao.robomatch.economics.EconomicModel.*;
import me.yuugao.robomatch.exception.EconomicValidationException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * Unit-тесты модели RaaS (economic_model.md §2.11, assumptions.md §23,
 *, уточнения организатора): фиксированная ставка / плата за
 * использование / смешанная модель, опциональный выкуп по остаточной
 * стоимости и сравнение с покупкой по ЕДИНЫМ метрикам (CAPEX, OPEX,
 * годовой эффект, окупаемость, ROI, TCO).
 */
class RaaSModelTest {

    private static final List<SolutionLine> LINES = List.of(
            new SolutionLine(1, "Робот А", 3, bd("2500000"), bd("6"),
                    bd("1.35")));

    private static EconomicsInput raas(RaasTerms terms) {
        return new EconomicsInput(ScenarioKind.RAAS,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                terms, null, LINES);
    }

    private static EconomicsInput purchase() {
        return new EconomicsInput(ScenarioKind.PURCHASE,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                null, null, LINES);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void fixedModel_paymentAndOpex() {
        // Ставка = 2% × 2 500 000 = 50 000 руб./мес
        // Payment_year = 12 × 50 000 × 3 = 1 800 000
        RaasTerms terms = new RaasTerms("fixed", bd("2.0"), 3, false, null);
        EconomicsResult r = EconomicModel.calculate(raas(terms));
        assertThat(r.raas()).isNotNull();
        assertThat(r.raas().rateMonthRub()).isEqualByComparingTo("50000");
        assertThat(r.raas().paymentYear()).isEqualByComparingTo("1800000");
        // OPEX = платежи + электроэнергия + связь (персонал 0):
        // 1 800 000 + 227 651 + 108 000 = 2 135 651 (без сервисных статей -
        // оборудование у оператора услуги, assumptions §23)
        assertThat(r.opexTotal()).isEqualByComparingTo("2135651");
        assertThat(r.cService()).isEqualByComparingTo("0");
        assertThat(r.cLicenses()).isEqualByComparingTo("0");
    }

    @Test
    void fixedModel_noBuyout_capexZero_paybackUndefined() {
        // Без выкупа CAPEX = 0: окупаемость не определена - сообщение,
        // сравнение по годовому эффекту и TCO (§2.11)
        RaasTerms terms = new RaasTerms("fixed", bd("2.0"), 3, false, null);
        EconomicsResult r = EconomicModel.calculate(raas(terms));
        assertThat(r.capexTotal()).isEqualByComparingTo("0");
        assertThat(r.raas().capexRaas()).isEqualByComparingTo("0");
        assertThat(r.paybackYears()).isNull();
        assertThat(r.roiPct()).isNull();
        assertThat(r.warnings()).anyMatch(w -> w.contains("CAPEX = 0"));
        // Эффект = ΔFOT − (платежи + электроэнергия + связь)
        // = 60 000 000 − 2 135 651 = 57 864 349
        assertThat(r.effectYear()).isEqualByComparingTo("57864349");
        // TCO = OPEX × горизонт (платежи весь срок, продление)
        assertThat(r.tcoRub()).isEqualByComparingTo("10678255");
    }

    @Test
    void usageModel_requiresRate_andPaysPerOperation() {
        // usage без ставки - 400 (список недостающего)
        assertThatThrownBy(() -> EconomicModel.calculate(
                raas(new RaasTerms("usage", bd("2.0"), 3, false, null))))
                .isInstanceOf(EconomicValidationException.class)
                .hasMessageContaining("raas_usage_rate_rub");
        // usage = 10 руб. × 730 000 операций = 7 300 000
        EconomicsResult r = EconomicModel.calculate(raas(
                new RaasTerms("usage", bd("2.0"), 3, false, bd("10"))));
        assertThat(r.raas().paymentYear()).isEqualByComparingTo("7300000");
    }

    @Test
    void mixedModel_sumsFixedAndUsage() {
        // mixed = 12 × 50 000 × 3 + 10 × 730 000 = 1 800 000 + 7 300 000
        EconomicsResult r = EconomicModel.calculate(raas(
                new RaasTerms("mixed", bd("2.0"), 3, false, bd("10"))));
        assertThat(r.raas().paymentYear()).isEqualByComparingTo("9100000");
        assertThatThrownBy(() -> EconomicModel.calculate(raas(
                new RaasTerms("mixed", bd("2.0"), 3, false, null))))
                .isInstanceOf(EconomicValidationException.class)
                .hasMessageContaining("raas_usage_rate_rub");
    }

    @Test
    void buyout_residualValueIntoCapex() {
        // Выкуп: Buyout = 2 500 000 × max(0, 1 − 3/6) = 1 250 000
        // CAPEX_raas = 3 × 1 250 000 = 3 750 000
        // Платежи прекращаются после 3 лет контракта
        RaasTerms terms = new RaasTerms("fixed", bd("2.0"), 3, true, null);
        EconomicsResult r = EconomicModel.calculate(raas(terms));
        assertThat(r.raas().buyoutValue()).isEqualByComparingTo("1250000");
        assertThat(r.raas().capexRaas()).isEqualByComparingTo("3750000");
        assertThat(r.raas().paymentYears()).isEqualTo(3);
        // Амортизация выкупа по остаточному сроку (6 − 3 = 3 года):
        // 3 750 000 / 3 = 1 250 000
        assertThat(r.amortYear()).isEqualByComparingTo("1250000");
        // Окупаемость = CAPEX_raas / эффект
        assertThat(r.paybackYears()).isNotNull();
        // (§2.9): TCO = CAPEX_raas + Платежи × 3 года контракта
        // + (электроэнергия + связь + персонал) × 5 лет горизонта
        // = 3 750 000 + 1 800 000×3 + 335 651×5 = 10 828 255
        assertThat(r.tcoRub()).isEqualByComparingTo("10828255");
    }

    @Test
    void unknownModel_rejected() {
        assertThatThrownBy(() -> EconomicModel.calculate(
                raas(new RaasTerms("crypto", bd("2.0"), 3, false, null))))
                .isInstanceOf(EconomicValidationException.class)
                .hasMessageContaining("Неизвестная модель");
    }

    @Test
    void buyoutWithZeroResidual_paymentsFullHorizon() {
        // Ревью E-03: контракт ≥ срока службы - остаточная доля ≤ 0,
        // выкупа нет (CAPEX_raas = 0) - платежи идут ВЕСЬ горизонт
        // (min(контракт, горизонт) применяется только к реальному выкупу)
        RaasTerms terms = new RaasTerms("fixed", bd("2.0"), 6, true, null);
        EconomicsResult r = EconomicModel.calculate(raas(terms));
        assertThat(r.raas().buyoutValue()).isEqualByComparingTo("0");
        assertThat(r.raas().capexRaas()).isEqualByComparingTo("0");
        assertThat(r.raas().paymentYears()).isEqualTo(5);
        // TCO = 0 + OPEX × 5 (а не × min(6,5) с потерей лет платежей)
        assertThat(r.tcoRub())
                .isEqualByComparingTo(r.opexTotal().multiply(bd("5")));
    }

    @Test
    void usageModel_emptyFleet_zeroPaymentsWithWarning() {
        // пустой состав + плата за использование -
        // операции выполнять некому, платежи обнуляются (не 7.3 млн)
        EconomicsInput input = new EconomicsInput(ScenarioKind.RAAS,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                new RaasTerms("usage", bd("2.0"), 3, false, bd("10")),
                null, List.of());
        EconomicsResult r = EconomicModel.calculate(input);
        assertThat(r.raas().paymentYear()).isEqualByComparingTo("0");
        assertThat(r.warnings()).anyMatch(w -> w.contains("обнулены"));
        // mixed при пустом составе - тоже 0 от usage-компоненты
        EconomicsInput mixed = new EconomicsInput(ScenarioKind.RAAS,
                bd("136.36"), bd("8030"), bd("730000"),
                bd("60000000"), bd("1332000"), bd("1.302"),
                5,
                bd("0.75"), bd("0.15"), bd("1.0"),
                bd("25"), bd("1.35"), bd("7"),
                bd("0.10"), bd("0.35"),
                bd("10"), bd("15"), bd("15"), bd("7.5"), bd("3"),
                bd("10"), bd("5"), bd("36000"), bd("2"), bd("3.5"),
                bd("6"), bd("100"), bd("0"), 0,
                new RaasTerms("mixed", bd("2.0"), 3, false, bd("10")),
                null, List.of());
        assertThat(EconomicModel.calculate(mixed).raas().paymentYear())
                .isEqualByComparingTo("0");
    }

    @Test
    void raasComparableWithPurchase_byUnifiedMetrics() {
        // уточнения организатора: сравнение по единым показателям - все метрики
        // определены (числом или null с интерпретацией) у обоих сценариев
        EconomicsResult purchase = EconomicModel.calculate(purchase());
        EconomicsResult raasFixed = EconomicModel.calculate(
                raas(new RaasTerms("fixed", bd("2.0"), 3, true, null)));
        for (EconomicsResult r : List.of(purchase, raasFixed)) {
            assertThat(r.capexTotal()).isNotNull();
            assertThat(r.opexTotal()).isNotNull();
            assertThat(r.effectYear()).isNotNull();
            assertThat(r.tcoRub()).isNotNull();
            // окупаемость: число или null (с предупреждением/сообщением)
            if (r.paybackYears() == null) {
                assertThat(r.warnings()).isNotEmpty();
            }
        }
        // У покупки CAPEX выше (выкуп дешевле полного парка)
        assertThat(purchase.capexTotal())
                .isGreaterThan(raasFixed.capexTotal());
        // Годовой OPEX RaaS выше (платежи вместо сервисных статей)
        assertThat(raasFixed.opexTotal())
                .isGreaterThan(purchase.opexTotal());
    }
}
