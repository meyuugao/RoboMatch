package me.yuugao.robomatch.economics;

import me.yuugao.robomatch.exception.EconomicValidationException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * ЧИСТЫЙ расчётный движок экономики (economic_model.md разделы 2-4).
 * Никаких зависимостей от Spring/БД: вход — EconomicsInput (уже собранные
 * числа), выход — EconomicsResult. Тестируется напрямую (unit).
 *
 * <p>Терминология парка (§2.1): requiredRobots — рекомендация
 * по пиковой нагрузке (формула); selectedRobots — фактический состав
 * сценария (sum(quantity)); ВСЯ экономика считается по selectedRobots.
 * Недобор (selected &lt; required) — флаг underpowered + предупреждение,
 * не ошибка.
 *
 * <p>Реализованные формулы (economic_model.md):
 * <ul>
 * <li>2.1 requiredRobots = ceil(Peak_demand x (1 + K_reserve) /
 * (P_nominal x K_load x K_availability)) — K_reserve трактуется как
 * РЕЗЕРВНАЯ НАДБАВКА 0.15-0.20 (Легенда XLSX: «с резервом 15-20%»),
 * что эквивалентно делению эффективной производительности на
 * (1 + K_reserve);</li>
 * <li>2.2 N_infra = ceil(N_robots x Ratio_infra);</li>
 * <li>2.3 CAPEX: 6 статей (C_infra...C_training - проценты стоимости
 * оборудования, assumptions.md §22) + C_reserve = Reserve_rate x
 * сумма;</li>
 * <li>2.4 OPEX: C_electricity = selected x P_consumption x Hours_year x
 * Tariff, C_staff = N_staff_rob x Salary_year x K_начислений (в
 * датасете коэффициент хранится как 1.302 - уже с единицей);</li>
 * <li>2.5 dOPEX = OPEX_rob - OPEX_base (полное, с ФОТ);</li>
 * <li>2.6 Effect_gross = dFOT + dOther - dOPEX_без_ФОТ (изменение ФОТ
 * учтено отдельным слагаемым dFOT - без двойного счёта);
 * Amort_year = CAPEX / Lifetime (линейная); Effect_year =
 * Effect_gross - Amort_year;</li>
 * <li>2.7 Payback = capexForEffect / Effect_year при Effect_year &gt; 0 и
 * capexForEffect &gt; 0, иначе null (не окупается / не определена);
 * расчёт ПОСЛЕ вычитания платежей по кредиту;</li>
 * <li>2.8 ROI = Effect_year x Horizon / capexForEffect x 100
 * (Effect_year_t = Effect_year - динамика не задана);</li>
 * <li>2.9 TCO покупки = CAPEX + OPEX x Horizon + Replacements (замена
 * роботов по сроку службы: floor((Horizon-1)/Lifetime) x C_equipment;
 * АКБ уже в C_consumables - assumptions §22); TCO RaaS =
 * CAPEX_raas + Платежи x годы_платежей + (Электроэнергия + Связь +
 * Персонал) x Горизонт (прочий OPEX идёт весь горизонт,
 * платежи - только годы контракта при выкупе);</li>
 * <li>2.10 заёмное финансирование: аннуитет Debt = Loan x r(1+r)^T /
 * ((1+r)^T - 1), r - годовая ставка; вычитается из Effect_year
 * ДО расчёта Payback/ROI; TCO обслуживание долга НЕ
 * включает (финансирование, не владение);</li>
 * <li>2.11 RaaS: fixed/usage/mixed + опциональный выкуп
 * Buyout = Price x max(0, 1 - Contract_years / Lifetime);</li>
 * <li>2.13 результат — таблица сравнения (собирает ComparisonService).</li>
 * </ul>
 *
 * <p>Округление (§4): N_robots и N_infra — вверх (ceil); стоимости — до
 * рублей; Payback — до десятых года; ROI — до десятых процента.
 * Округление — на уровне строк-показателей: каждая статья CAPEX/OPEX,
 * амортизация, платежи RaaS и выкупная стоимость — целые рубли
 * (money, HALF_UP), итоги и зависимые величины считаются из
 * округлённых строк — аддитивная согласованность отображения (сумма
 * показанных статей = показанный итог). Внутри одной
 * строки-показателя промежуточных округлений нет
 * (MathContext.DECIMAL128).
 */
final class EconomicModel {

    /**
 * Версия расчётной модели (economic_model.md §5): меняется при изменении формул.
 */
    static final String MODEL_VERSION = "economic-model-1.0";

    /**
 * Шаги чувствительности (§2.12): -20/-10/0/+10/+20.
 */
    static final List<BigDecimal> SENSITIVITY_STEPS =
            List.of(bd("-20"), bd("-10"), bd("0"), bd("10"), bd("20"));

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = bd("100");

    private EconomicModel() {
    }

    static EconomicsResult calculate(EconomicsInput in) {
        List<String> warnings = new ArrayList<>();
        int selectedRobots = in.lines().stream()
                .mapToInt(SolutionLine::quantity).sum();

        // --- 2.1: требуемое количество (рекомендация по пиковой нагрузке) --
        // requiredRobots — рекомендация; selectedRobots — факт;
        // вся экономика ниже считается по selectedRobots.
        Integer required = null;
        if (in.pNominalPerHour() != null
                && in.pNominalPerHour().signum() > 0
                && in.peakDemandPerHour() != null) {
            BigDecimal effective = in.pNominalPerHour()
                    .multiply(in.kLoad(), MC)
                    .multiply(in.kAvailability(), MC);
            if (effective.signum() > 0) {
                BigDecimal need = in.peakDemandPerHour()
                        .multiply(bd(1).add(in.kReserve(), MC), MC)
                        .divide(effective, MC);
                required = need.setScale(0, RoundingMode.CEILING)
                        .intValueExact();
            }
        }
        // Недобор парка — мягкий флаг (не исключение):
        // (жёсткий запрет) применяется к подбору, не к экономике.
        // Условие 3: предупреждение видно в UI и попадёт в PDF.
        // Базовый сценарий не содержит решений ПО ОПРЕДЕЛЕНИЮ
        // (инвариант base, data_model.md §10.5) — проверки
        // парка и пустого состава к нему не применяются, предупреждения
        // не добавляются (раньше «Парк меньше требуемого (0 < N)» и
        // «Состав пуст» попадали в API/compare базовой колонки).
        boolean base = in.kind() == ScenarioKind.BASE;
        boolean underpowered = required != null
                && selectedRobots < required;
        // Превышение парка — нейтральный флаг
        // (не ошибка, в warnings НЕ добавляется): UI показывает плашку
        // «парк превышает рекомендуемый — возможна переплата»
        boolean overpowered = required != null && selectedRobots > required;
        if (underpowered && !base) {
            warnings.add("Парк меньше требуемого: состав сценария ("
                    + selectedRobots + " ед.) меньше требуемого по пиковой "
                    + "нагрузке (" + required + " ед.) — "
                    + "расчёт не отражает достижение заявленной "
                    + "производительности.");
        }

        // --- 2.2: вспомогательное оборудование ---------------------------
        int nInfra = BigDecimal.valueOf(selectedRobots)
                .multiply(in.ratioInfra(), MC)
                .setScale(0, RoundingMode.CEILING).intValueExact();

        // --- срок службы: ТТХ решений, иначе допущение -------------------
        BigDecimal lifetime = weightedLifetime(in);
        if (lifetime.signum() <= 0) {
            lifetime = in.amortizationYears();
        }
        if (lifetime == null || lifetime.signum() <= 0) {
            throw new EconomicValidationException(List.of(
                    "Срок службы оборудования равен нулю — амортизация "
                            + "неопределима. Задайте срок службы "
                            + "в ТТХ решений или допущение «amortization_years»."));
        }

        // --- средняя цена робота (с фактором стоимости) ------------------
        BigDecimal factor = percentFactor(in.equipmentCostFactorPct());
        BigDecimal equipmentSum = in.lines().stream()
                .map(l -> l.priceRub().multiply(BigDecimal.valueOf(l.quantity()), MC)
                        .multiply(factor, MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal capexEquipment = money(equipmentSum);
        BigDecimal avgPrice = selectedRobots > 0
                ? equipmentSum.divide(BigDecimal.valueOf(selectedRobots), MC)
                : BigDecimal.ZERO;

        if (selectedRobots == 0 && !base) {
            warnings.add("Состав сценария пуст: selectedRobots = 0, "
                    + "CAPEX = 0. Добавьте решения в сценарий "
                    + "(подбор или вручную). ΔFOT обнулён: замещения "
                    + "персонала без роботов нет.");
        }

        // --- 2.4: базовый OPEX = ФОТ целевых групп (контур роботизации) --
        BigDecimal opexBase = money(in.fotBaseYear());

        if (in.kind() == ScenarioKind.BASE) {
            // Базовый сценарий: изменений нет, эффект/окупаемость неприменимы
            return new EconomicsResult(0, 0, null, false, false,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    opexBase, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    in.fotBaseYear(), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, opexBase, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    null, null,
                    money(opexBase.multiply(BigDecimal.valueOf(in.horizonYears()), MC)),
                    BigDecimal.ZERO, BigDecimal.ZERO, null, null, warnings);
        }

        // --- 2.3: CAPEX покупки (проценты — от стоимости оборудования). ---
        // Для RaaS статьи не применяются: CAPEX_raas — только выкупная
        // стоимость (assumptions §23), считается в блоке 2.11 ниже.
        BigDecimal cInfra = BigDecimal.ZERO;
        BigDecimal cSoftware = BigDecimal.ZERO;
        BigDecimal cIntegration = BigDecimal.ZERO;
        BigDecimal cCommissioning = BigDecimal.ZERO;
        BigDecimal cTraining = BigDecimal.ZERO;
        BigDecimal cReserve = BigDecimal.ZERO;
        BigDecimal capex = BigDecimal.ZERO;

        // --- 2.4: OPEX ---------------------------------------------------
        BigDecimal consumption = in.powerConsumptionKw();
        if (consumption == null || consumption.signum() < 0) {
            consumption = BigDecimal.ZERO;
        }
        BigDecimal cElectricity = money(BigDecimal.valueOf(selectedRobots)
                .multiply(consumption, MC)
                .multiply(in.hoursYear(), MC)
                .multiply(in.tariffRubPerKwh(), MC));
        BigDecimal cCommunication = money(BigDecimal.valueOf(selectedRobots)
                .multiply(in.cCommunicationRubPerRobotYear(), MC));

        // ФОТ роботизированного сценария: персонал эксплуатации (§2.4)
        BigDecimal cStaff = money(BigDecimal.valueOf(in.exploitationStaffCount())
                .multiply(in.salaryYearYear(), MC)
                .multiply(in.payrollRate(), MC));

        BigDecimal fotBase = in.fotBaseYear();
        BigDecimal deltaFot = fotBase.subtract(cStaff, MC);
        // Пустой состав роботизированного
        // сценария — замещения персонала нет, ΔFOT обнуляется (раньше
        // полный FOT_base попадал в эффект без единого робота: получался
        // Effect_gross = +203 112 000).
        // П.2.6: ΔFOT учитывается только при selectedRobots > 0;
        // nonPayrollOpex при пустом составе = 0 → Effect_gross = ΔOther,
        // амортизация = 0 → Effect_year = Effect_gross
        if (selectedRobots == 0) {
            deltaFot = BigDecimal.ZERO;
        }
        BigDecimal deltaOther = in.otherAnnualEffectRub();

        // --- 2.11 (ранний расчёт условий RaaS): нужен до CAPEX, т.к. ---
        // CAPEX сценария RaaS — выкупная стоимость, а не полный CAPEX
        // покупки (assumptions §23: CAPEX при RaaS — минимальный или 0)
        RaasResult raas = null;
        if (in.kind() == ScenarioKind.RAAS) {
            // --- 2.11: RaaS ---------------------------------------------
            RaasTerms r = in.raas();
            // Точная месячная ставка (БЕЗ промежуточного округления — §4:
            // округление только на выходе: округление ставки
            // до рублей до x12xN давало сдвиг платежа до 6xN руб./год);
            // до рублей округляется ТОЛЬКО отображаемое значение
            BigDecimal rateMonthExact = avgPrice
                    .multiply(r.rateMonthPct(), MC).divide(HUNDRED, MC);
            BigDecimal rateMonth = EconomicModel.money(rateMonthExact);
            BigDecimal paymentYear = BigDecimal.ZERO;
            switch (r.paymentModel()) {
                // Платежи за ВЫБРАННЫЙ состав: selectedRobots,
                // не требуемое количество
                case "fixed" -> paymentYear = rateMonthExact
                        .multiply(BigDecimal.valueOf(12), MC)
                        .multiply(BigDecimal.valueOf(selectedRobots), MC);
                case "usage" -> {
                    if (r.usageRateRubPerOperation() == null) {
                        throw new EconomicValidationException(List.of(
                                "Для модели платежей «плата за использование» "
                                        + "задайте допущение raas_usage_rate_rub "
                                        + "(руб./операция)."));
                    }
                    // Пустой состав: операции выполнять некому — платежи
                    // за использование обнуляются
                    paymentYear = selectedRobots == 0 ? BigDecimal.ZERO
                            : r.usageRateRubPerOperation()
                            .multiply(in.operationsYear(), MC);
                    if (selectedRobots == 0) {
                        warnings.add("Состав сценария пуст — платежи RaaS "
                                + "за использование обнулены (некому "
                                + "выполнять операции).");
                    }
                }
                case "mixed" -> {
                    if (r.usageRateRubPerOperation() == null) {
                        throw new EconomicValidationException(List.of(
                                "Для смешанной модели платежей задайте допущение "
                                        + "raas_usage_rate_rub (руб./операция)."));
                    }
                    paymentYear = rateMonthExact
                            .multiply(BigDecimal.valueOf(12), MC)
                            .multiply(BigDecimal.valueOf(selectedRobots), MC)
                            .add(selectedRobots == 0 ? BigDecimal.ZERO
                                    : r.usageRateRubPerOperation()
                                    .multiply(in.operationsYear(), MC));
                }
                default -> throw new EconomicValidationException(List.of(
                        "Неизвестная модель платежей RaaS: " + r.paymentModel()));
            }
            // Выкуп по остаточной стоимости (линейная амортизация, уточнение организатора)
            BigDecimal residualShare = bd(1)
                    .subtract(BigDecimal.valueOf(r.contractYears())
                            .divide(lifetime, MC), MC);
            BigDecimal buyoutValue = BigDecimal.ZERO;
            if (r.buyout() && residualShare.signum() > 0) {
                buyoutValue = money(avgPrice.multiply(residualShare, MC));
            }
            BigDecimal capexRaas = buyoutValue.signum() > 0
                    ? money(buyoutValue.multiply(
                    BigDecimal.valueOf(selectedRobots), MC))
                    : BigDecimal.ZERO;
            // Платежи идут годы min(контракт, горизонт) ТОЛЬКО при
            // реальном выкупе (capexRaas > 0): остаточная доля <= 0 —
            // выкупа нет, платежи весь горизонт
            int paymentYears = r.buyout() && capexRaas.signum() > 0
                    ? Math.min(r.contractYears(), in.horizonYears())
                    : in.horizonYears();
            raas = new RaasResult(r.paymentModel(), rateMonth,
                    money(paymentYear), buyoutValue, capexRaas, paymentYears);
        }

        boolean isRaas = raas != null;
        // CAPEX: покупка — 6 статей + резерв (§2.3); RaaS — выкуп (2.11)
        if (!isRaas) {
            cInfra = money(pct(capexEquipment, in.cInfraPct()));
            cSoftware = money(pct(capexEquipment, in.cSoftwarePct()));
            cIntegration = money(pct(capexEquipment, in.cIntegrationPct()));
            cCommissioning = money(pct(capexEquipment, in.cCommissioningPct()));
            cTraining = money(pct(capexEquipment, in.cTrainingPct()));
            cReserve = money(capexEquipment.add(cInfra).add(cSoftware)
                    .add(cIntegration).add(cCommissioning).add(cTraining)
                    .multiply(in.reserveRate(), MC));
            capex = capexEquipment.add(cInfra).add(cSoftware)
                    .add(cIntegration).add(cCommissioning).add(cTraining)
                    .add(cReserve);
        } else {
            capex = raas.capexRaas();
        }
        // Статьи OPEX: RaaS — платежи вместо сервиса/лицензий/расходников/
        // ремонта (оборудование у оператора услуги, assumptions §23)
        BigDecimal cService = isRaas ? BigDecimal.ZERO
                : money(pct(capexEquipment, in.cServicePct()));
        BigDecimal cLicenses = isRaas ? BigDecimal.ZERO
                : money(pct(capexEquipment, in.cLicensesPct()));
        BigDecimal cConsumables = isRaas ? BigDecimal.ZERO
                : money(pct(capexEquipment, in.cConsumablesPct()));
        BigDecimal cRepair = isRaas ? BigDecimal.ZERO
                : money(pct(capexEquipment, in.cRepairPct()));
        BigDecimal paymentYear = isRaas ? raas.paymentYear() : BigDecimal.ZERO;
        BigDecimal paymentYears = isRaas
                ? BigDecimal.valueOf(raas.paymentYears()) : BigDecimal.ZERO;

        BigDecimal opex = paymentYear.add(cService).add(cLicenses)
                .add(cElectricity).add(cCommunication).add(cConsumables)
                .add(cRepair).add(cStaff);
        // Полное изменение OPEX (с ФОТ) — показатель таблицы сравнения
        BigDecimal opexDelta = opex.subtract(opexBase, MC);

        // --- 2.6: эффект (dFOT отдельно; dOPEX в эффекте — БЕЗ ФОТ) -----
        BigDecimal nonPayrollOpex = paymentYear.add(cService).add(cLicenses)
                .add(cElectricity).add(cCommunication).add(cConsumables)
                .add(cRepair);
        BigDecimal effectGross = deltaFot.add(deltaOther, MC)
                .subtract(nonPayrollOpex, MC);

        // Амортизация: покупка — CAPEX/Lifetime; RaaS — выкупная часть
        // по остаточному сроку службы (Lifetime - контракт).
        BigDecimal amort;
        if (isRaas) {
            BigDecimal remaining = lifetime.subtract(
                    BigDecimal.valueOf(in.raas().contractYears()), MC);
            amort = raas.capexRaas().signum() > 0 && remaining.signum() > 0
                    ? money(raas.capexRaas().divide(remaining, MC)) : BigDecimal.ZERO;
        } else {
            amort = money(capex.divide(lifetime, MC));
        }
        BigDecimal capexForEffect = isRaas ? raas.capexRaas() : capex;
        BigDecimal effectYearBeforeDebt = money(effectGross.subtract(amort, MC));

        // --- 2.10: заёмное финансирование — ДО Payback/ROI ---------
        // Аннуитет вычитается из Effect_year сразу после амортизации;
        // окупаемость и ROI ниже считаются от эффекта ПОСЛЕ обслуживания
        // долга. TCO обслуживание долга НЕ включает (финансирование,
        // не владение — §2.10).
        LoanResult loanResult = null;
        BigDecimal effectYear = effectYearBeforeDebt;
        if (in.loan() != null && in.loan().amountRub() != null
                && in.loan().amountRub().signum() > 0) {
            BigDecimal rate = in.loan().ratePctYear().divide(HUNDRED, MC);
            int term = in.loan().termYears();
            BigDecimal pow = bd(1).add(rate, MC).pow(term, MC);
            BigDecimal debt = in.loan().amountRub()
                    .multiply(rate.multiply(pow, MC), MC)
                    .divide(pow.subtract(bd(1), MC), MC);
            effectYear = money(effectYearBeforeDebt.subtract(debt, MC));
            loanResult = new LoanResult(money(debt),
                    effectYearBeforeDebt, effectYear);
        }

        // --- 2.7: окупаемость (только при Effect > 0 и CAPEX > 0) --------
        // Считается по effectYear ПОСЛЕ вычитания платежей по кредиту
        BigDecimal payback = null;
        if (capexForEffect.signum() > 0 && effectYear.signum() > 0) {
            payback = capexForEffect.divide(effectYear, MC)
                    .setScale(1, RoundingMode.HALF_UP);
            // Cap: колонка numeric(10,2), экстремальные значения —
            // «не окупается в разумный срок»
            if (payback.compareTo(bd(1_000_000)) > 0) {
                warnings.add("Срок окупаемости превышает 1 млн лет — "
                        + "сценарий не окупается в разумный срок.");
                payback = null;
            }
        } else if (effectYear.signum() <= 0) {
            warnings.add("Годовой эффект не положителен — окупаемость "
                    + "не рассчитывается: сценарий не окупается "
                    + "на текущих допущениях.");
        } else if (capexForEffect.signum() == 0) {
            warnings.add("CAPEX = 0 (RaaS без выкупа / пустой состав) — "
                    + "окупаемость не определена; сравнивайте по годовому "
                    + "эффекту и TCO.");
        }

        // --- 2.8: ROI за горизонт (после обслуживания долга) --------
        BigDecimal roi = null;
        if (capexForEffect.signum() > 0) {
            BigDecimal rawRoi = effectYear
                    .multiply(BigDecimal.valueOf(in.horizonYears()), MC)
                    .divide(capexForEffect, MC).multiply(HUNDRED, MC);
            roi = rawRoi.setScale(1, RoundingMode.HALF_UP);
            // Cap по образцу Payback: предельное |ROI| 1 млрд % —
            // вне представимого диапазона numeric(12,2) и здравого смысла
            // (раньше переполняла numeric(7,2) — 500/409)
            if (rawRoi.abs().compareTo(BigDecimal.valueOf(1_000_000_000)) > 0) {
                warnings.add("ROI вне представимого диапазона (свыше "
                        + "1 млрд %) — эффект непропорционально малым "
                        + "вложениям; проверьте состав и цены.");
                roi = null;
            }
        }

        // --- 2.9: TCO с заменами по сроку службы --------------------------
        BigDecimal replacements = BigDecimal.ZERO;
        if (!isRaas && lifetime.signum() > 0 && in.horizonYears() > 1) {
            // floor((Horizon-1)/Lifetime) по ВЕЩЕСТВЕННОМУ сроку службы
            // (обрезание Lifetime до целого меняло число замен
            // при дробном средневзвешенном сроке); срок < 1 года — формула
            // §2.9 дословно (замены каждые Lifetime лет, без исключения)
            int count = BigDecimal.valueOf(in.horizonYears() - 1)
                    .divide(lifetime, 6, RoundingMode.DOWN)
                    .setScale(0, RoundingMode.DOWN).intValueExact();
            replacements = money(capexEquipment
                    .multiply(BigDecimal.valueOf(Math.max(0, count)), MC))
                    .max(BigDecimal.ZERO);
        }
        // TCO RaaS: платежи идут только годы контракта
        // (при выкупе — min(контракт, горизонт)), а прочий OPEX
        // (электроэнергия + связь + персонал) — ВЕСЬ горизонт, выкуп —
        // единоразово в capexForEffect. Без выкупа формула вырождается
        // в (платежи + прочий OPEX) x горизонт = opex x горизонт.
        BigDecimal opexNonPayment = cElectricity.add(cCommunication)
                .add(cStaff);
        BigDecimal tco = isRaas
                ? money(capexForEffect
                .add(paymentYear.multiply(paymentYears, MC), MC)
                .add(opexNonPayment.multiply(
                        BigDecimal.valueOf(in.horizonYears()), MC), MC))
                : money(capex.add(opex
                        .multiply(BigDecimal.valueOf(in.horizonYears()), MC), MC)
                .add(replacements, MC));

        return new EconomicsResult(selectedRobots, nInfra, required,
                underpowered, overpowered,
                money(capex), capexEquipment, cInfra, cSoftware, cIntegration,
                cCommissioning, cTraining, cReserve,
                money(opex), cService, cLicenses, cElectricity, cCommunication,
                cConsumables, cRepair, cStaff,
                money(fotBase), cStaff, money(deltaFot), deltaOther,
                opexBase, money(opexDelta), money(effectGross), amort,
                effectYear, payback, roi, tco, replacements, avgPrice,
                raas, loanResult, warnings);
    }

    /**
 * Средневзвешенный по количеству срок службы состава (null — не задан).
 */
    private static BigDecimal weightedLifetime(EconomicsInput in) {
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal acc = BigDecimal.ZERO;
        for (SolutionLine line : in.lines()) {
            if (line.lifetimeYears() != null && line.lifetimeYears().signum() > 0) {
                acc = acc.add(line.lifetimeYears()
                        .multiply(BigDecimal.valueOf(line.quantity()), MC), MC);
                weight = weight.add(BigDecimal.valueOf(line.quantity()), MC);
            }
        }
        return weight.signum() > 0 ? acc.divide(weight, MC) : BigDecimal.ZERO;
    }

    /**
 * x% от базы: base x pct / 100.
 */
    private static BigDecimal pct(BigDecimal base, BigDecimal pct) {
        return base.multiply(pct, MC).divide(HUNDRED, MC);
    }

    /**
 * Множитель из процентов: 100 -> 1.
 */
    private static BigDecimal percentFactor(BigDecimal pct) {
        return pct.divide(HUNDRED, MC);
    }

    /**
 * Стоимость — до рублей (§4).
 */
    static BigDecimal money(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    /**
 * Тип сценария (domain.ScenarioType без зависимости JPA в движке).
 */
    enum ScenarioKind {
        /**
 * Текущий процесс без роботизации (ΔOPEX/TCO — к нему).
 */
        BASE,
        /**
 * Покупка оборудования (CAPEX + OPEX роботов).
 */
        PURCHASE,
        /**
 * Роботы как услуга (economic_model.md §2.11).
 */
        RAAS
    }

    // ==================================================================
    // Основной расчёт
    // ==================================================================

    /**
 * Статья состава сценария.
 */
    record SolutionLine(long solutionId, String name, int quantity,
                        BigDecimal priceRub, BigDecimal lifetimeYears,
                        BigDecimal chargingPowerKw) {
    }

    // ==================================================================
    // Вспомогательные операции
    // ==================================================================

    /**
 * Полный вход расчёта (все значения эффективные: параметры/дефолты/допущения).
 */
    record EconomicsInput(ScenarioKind kind,
                          BigDecimal peakDemandPerHour, BigDecimal hoursYear,
                          BigDecimal operationsYear,
                          BigDecimal fotBaseYear, BigDecimal salaryYearYear,
                          BigDecimal payrollRate,
                          int horizonYears,
                          BigDecimal kLoad, BigDecimal kReserve, BigDecimal kAvailability,
                          BigDecimal pNominalPerHour, BigDecimal powerConsumptionKw,
                          BigDecimal tariffRubPerKwh,
                          BigDecimal reserveRate, BigDecimal ratioInfra,
                          BigDecimal cInfraPct, BigDecimal cSoftwarePct,
                          BigDecimal cIntegrationPct, BigDecimal cCommissioningPct,
                          BigDecimal cTrainingPct,
                          BigDecimal cServicePct, BigDecimal cLicensesPct,
                          BigDecimal cCommunicationRubPerRobotYear,
                          BigDecimal cConsumablesPct, BigDecimal cRepairPct,
                          BigDecimal amortizationYears,
                          BigDecimal equipmentCostFactorPct,
                          BigDecimal otherAnnualEffectRub,
                          int exploitationStaffCount,
                          RaasTerms raas,
                          LoanTerms loan,
                          List<SolutionLine> lines) {
    }

    /**
 * Условия RaaS (economic_model.md §2.11, assumptions.md §23).
 */
    record RaasTerms(String paymentModel, BigDecimal rateMonthPct,
                     int contractYears, boolean buyout,
                     BigDecimal usageRateRubPerOperation) {
    }

    /**
 * Заёмное финансирование (economic_model.md §2.10, уточнения организатора).
 */
    record LoanTerms(BigDecimal amountRub, BigDecimal ratePctYear,
                     Integer termYears) {
    }

    /**
 * Итог расчёта: все показатели + разбивки для metrics_json.
 */
    record EconomicsResult(int selectedRobots, int nInfra,
                           Integer requiredRobots, boolean underpowered,
                           boolean overpowered,
                           BigDecimal capexTotal, BigDecimal cEquipment,
                           BigDecimal cInfra, BigDecimal cSoftware,
                           BigDecimal cIntegration, BigDecimal cCommissioning,
                           BigDecimal cTraining, BigDecimal cReserve,
                           BigDecimal opexTotal, BigDecimal cService,
                           BigDecimal cLicenses, BigDecimal cElectricity,
                           BigDecimal cCommunication, BigDecimal cConsumables,
                           BigDecimal cRepair, BigDecimal cStaff,
                           BigDecimal fotBase, BigDecimal fotRob,
                           BigDecimal deltaFot, BigDecimal deltaOther,
                           BigDecimal opexBase, BigDecimal opexDelta,
                           BigDecimal effectGross, BigDecimal amortYear,
                           BigDecimal effectYear,
                           BigDecimal paybackYears, BigDecimal roiPct,
                           BigDecimal tcoRub, BigDecimal replacementsRub,
                           BigDecimal avgRobotPrice,
                           RaasResult raas, LoanResult loan,
                           List<String> warnings) {
    }

    /**
 * Блок RaaS (заполняется только для сценария raas).
 */
    record RaasResult(String paymentModel, BigDecimal rateMonthRub,
                      BigDecimal paymentYear, BigDecimal buyoutValue,
                      BigDecimal capexRaas, int paymentYears) {
    }

    /**
 * Блок заёмного финансирования (заполняется при Loan &gt; 0).
 */
    record LoanResult(BigDecimal debtPaymentYear,
                      BigDecimal effectYearBeforeDebt,
                      BigDecimal effectYearAfterDebt) {
    }
}
