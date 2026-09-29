package me.yuugao.robomatch.simulation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Чистый движок KPI-модели имитации склада
 *.
 * Без Spring и БД - тестируется unit-ом, как {@code EconomicModel}.
 *
 * <p>МОДЕЛЬ (детерминированная, аналитическая - «снимок» KPI;):
 * <ul>
 * <li>операция = паллета-перемещение (как в экономике, §1.1
 * economic_model.md: Operations_year = (приёмка + отгрузка) ×
 * рабочие дни);</li>
 * <li>два маршрута: ПРИЁМКА → ХРАНЕНИЕ (inbound) и
 * ХРАНЕНИЕ → ОТБОР → ОТГРУЗКА (outbound, через зону комплектации -
 * «типовые зоны»);</li>
 * <li>время цикла: погрузка + перемещение + разгрузка;
 * inbound: pickDrop + route/speed; outbound: 2 × pickDrop +
 * 2 × route/speed (две пересадки паллеты: хранение → отбор →
 * отгрузка);</li>
 * <li>загрузка роботов - доля робот-времени парка с полезной работой
 * при пиковой нагрузке; простои - остаток (зарядка и ожидание -
 * semantics K_load, Легенда XLSX);</li>
 * <li>узкие места - доля робот-времени по зонам (приёмка, хранение,
 * отбор, отгрузка): каждая точка операции занимает pickDrop / 2
 * робот-времени; зона с максимумом - узкое место;</li>
 * <li>достижимость заявленной производительности:
 * фактическая производительность робота в модели
 * (3600 / cycle × K_availability) против номинала
 * robot_nominal_productivity_per_hour из расчёта экономики.</li>
 * </ul>
 *
 * <p>ВСЕ КОЭФФИЦИЕНТЫ - из допущений (
 * запрещены): скорость - из ТТХ состава (speed_m_s, средневзвешенно)
 * с фолбэком на допущение sim_avg_robot_speed_m_s; средняя длина
 * маршрута - sim_avg_route_length_m; время погрузки-разгрузки -
 * sim_pick_drop_sec (assumptions.md §22-бис). K_load, K_availability,
 * P_nominal, Ratio_infra - допущения экономики (§22-бис),
 * переиспользуются.
 *
 * <p>ВЕРСИЯ МОДЕЛИ: {@link #MODEL_VERSION} - меняется при изменении
 * расчёта (фиксируется в kpi_json, воспроизведение).
 */
public final class SimulationModel {

    /**
 * Версия KPI-модели (в kpi_json каждого результата).
 */
    public static final String MODEL_VERSION = "simulation-model-1.0";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal SEC_PER_HOUR = BigDecimal.valueOf(3600);
    /**
 * Технический порог расхождения с вкладом экономики (округления).
 */
    private static final BigDecimal DIVERGENCE_EPSILON
            = BigDecimal.valueOf(0.01);
    private SimulationModel() {
    }

    /**
 * Расчёт KPI по составу сценария и параметрам склада.
 *
 * @param in вход модели (эффективные значения параметров/ТТХ/допущений)
 * @return итог модели - 6 KPI + разбивки для kpi_json
 * @throws IllegalArgumentException пустой состав (0 роботов) или
 * нулевые знаменатели - на уровне сервиса фиксируется строкой
 * результата со статусом failed и понятной причиной
 * (economic_model.md §6); входы, проверяемые ДО расчёта
 * (параметры склада, состав, base-сценарий), отклоняются
 * сервисом как 400
 */
    public static SimulationKpi calculate(SimulationInput in) {
        if (in == null) {
            throw new IllegalArgumentException("Вход модели не задан");
        }
        if (in.selectedRobots() <= 0) {
            throw new IllegalArgumentException(
                    "Состав сценария пуст - добавьте решения и повторите "
                            + "запуск имитации");
        }
        if (in.kAvailability() != null && in.kAvailability().signum() <= 0) {
            // каталог допущений допускает 0 -
            // понятная ошибка вместо ArithmeticException «/ by zero»
            throw new IllegalArgumentException(
                    "Коэффициент доступности (k_availability) должен быть"
                            + " больше 0 - проверьте допущение");
        }
        if (in.speedMs() == null || in.speedMs().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Скорость робота не задана (ТТХ speed_m_s или допущение "
                            + "sim_avg_robot_speed_m_s)");
        }
        if (in.routeLengthM() == null || in.routeLengthM().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Средняя длина маршрута не задана "
                            + "(sim_avg_route_length_m)");
        }
        if (in.pickDropSec() == null || in.pickDropSec().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Время погрузки-разгрузки не задано (sim_pick_drop_sec)");
        }
        if (in.hoursPerDay() == null || in.hoursPerDay().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Режим работы даёт 0 часов в сутки - проверьте сменность "
                            + "и продолжительность смены");
        }
        if (in.inboundPerDay() == null || in.outboundPerDay() == null
                || in.inboundPerDay().add(in.outboundPerDay()).signum() <= 0) {
            throw new IllegalArgumentException(
                    "Суточный объём операций равен нулю - проверьте приёмку "
                            + "и отгрузку (паллет/сутки)");
        }
        List<String> warnings = new ArrayList<>();

        // --- пиковые потоки по маршрутам, оп/час (§1.1 экономики) ----
        BigDecimal peakFactor = in.peakFactor() == null
                ? BigDecimal.ONE : in.peakFactor();
        BigDecimal inboundPeak = in.inboundPerDay()
                .divide(in.hoursPerDay(), 6, RoundingMode.HALF_UP)
                .multiply(peakFactor, MC);
        BigDecimal outboundPeak = in.outboundPerDay()
                .divide(in.hoursPerDay(), 6, RoundingMode.HALF_UP)
                .multiply(peakFactor, MC);
        BigDecimal peakDemand = in.peakDemandPerHour() != null
                ? in.peakDemandPerHour()
                : inboundPeak.add(outboundPeak);

        // --- время цикла
        BigDecimal speed = in.speedMs();
        BigDecimal route = in.routeLengthM();
        BigDecimal travelSec = route.divide(speed, 6, RoundingMode.HALF_UP);
        BigDecimal cycleIn = in.pickDropSec().add(travelSec);
        BigDecimal cycleOut = in.pickDropSec().multiply(BigDecimal.valueOf(2))
                .add(travelSec.multiply(BigDecimal.valueOf(2)));
        // средний цикл - по долям суточных объёмов (inbound/outbound)
        BigDecimal dailyTotal = in.inboundPerDay().add(in.outboundPerDay());
        BigDecimal inShare = in.inboundPerDay().divide(dailyTotal, 6,
                RoundingMode.HALF_UP);
        BigDecimal outShare = BigDecimal.ONE.subtract(inShare);
        BigDecimal avgCycle = cycleIn.multiply(inShare, MC)
                .add(cycleOut.multiply(outShare, MC));

        // --- производительность ----------------------------------------
        // мощность робота при непрерывной работе
        BigDecimal capacityPerRobot = SEC_PER_HOUR.divide(avgCycle, 6,
                RoundingMode.HALF_UP);
        BigDecimal kAvailability = in.kAvailability() == null
                ? BigDecimal.ONE : in.kAvailability();
        // фактическая на робота с учётом доступности (K_load экономики
        // НЕ подставляем - модель считает независимо и сверяется)
        BigDecimal actualPerRobot = capacityPerRobot.multiply(kAvailability,
                MC);
        BigDecimal capacityFleet = actualPerRobot.multiply(
                BigDecimal.valueOf(in.selectedRobots()), MC);
        BigDecimal actualThroughput = peakDemand.min(capacityFleet)
                .setScale(1, RoundingMode.HALF_UP);

        // --- загрузка и простои парка ----------------------------------
        // требуемое робот-время на покрытие пика, робото-часов в час
        BigDecimal requiredRobotHours = peakDemand.multiply(avgCycle, MC)
                .divide(SEC_PER_HOUR, 6, RoundingMode.HALF_UP);
        BigDecimal availableRobotHours = BigDecimal
                .valueOf(in.selectedRobots()).multiply(kAvailability, MC);
        BigDecimal utilization = BigDecimal.ONE.min(
                requiredRobotHours.divide(availableRobotHours, 6,
                        RoundingMode.HALF_UP));
        BigDecimal utilizationPct = utilization.multiply(BigDecimal
                .valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        BigDecimal idlePct = BigDecimal.valueOf(100)
                .subtract(utilizationPct);

        // --- узкие места: робот-время по зонам -------------------------
        // точка операции (взял/оставил паллету) - pickDrop/2 робот-времени
        BigDecimal half = in.pickDropSec().divide(BigDecimal.valueOf(2),
                6, RoundingMode.HALF_UP);
        BigDecimal hoursPerOp = BigDecimal.ONE
                .divide(SEC_PER_HOUR, 10, RoundingMode.HALF_UP);
        // приёмка: взятие inbound-паллеты
        BigDecimal receiving = inboundPeak.multiply(half)
                .multiply(hoursPerOp, MC);
        // хранение: оставление inbound + взятие outbound
        BigDecimal storage = inboundPeak.add(outboundPeak).multiply(half)
                .multiply(hoursPerOp, MC);
        // отбор: оставление + взятие outbound (комплектация)
        BigDecimal picking = outboundPeak.multiply(in.pickDropSec())
                .multiply(hoursPerOp, MC);
        // отгрузка: оставление outbound-паллеты
        BigDecimal shipping = outboundPeak.multiply(half)
                .multiply(hoursPerOp, MC);
        BigDecimal shareBase = BigDecimal.valueOf(100)
                .divide(availableRobotHours, 6, RoundingMode.HALF_UP);
        List<ZoneLoad> zones = List.of(
                new ZoneLoad("receiving", "Приёмка",
                        inboundPeak.setScale(1, RoundingMode.HALF_UP),
                        pct(receiving.multiply(shareBase, MC))),
                new ZoneLoad("storage", "Хранение",
                        inboundPeak.add(outboundPeak)
                                .setScale(1, RoundingMode.HALF_UP),
                        pct(storage.multiply(shareBase, MC))),
                new ZoneLoad("picking", "Отбор (комплектация)",
                        outboundPeak.multiply(BigDecimal.valueOf(2))
                                .setScale(1, RoundingMode.HALF_UP),
                        pct(picking.multiply(shareBase, MC))),
                new ZoneLoad("shipping", "Отгрузка",
                        outboundPeak.setScale(1, RoundingMode.HALF_UP),
                        pct(shipping.multiply(shareBase, MC))));
        ZoneLoad bottleneck = zones.stream()
                .max(Comparator.comparing(ZoneLoad::robotTimeSharePct))
                .orElseThrow();

        // --- 6 KPI: заявленная, достижимость, сверка с экономикой ------
        BigDecimal declared = null;
        BigDecimal effectivePerRobot = null;
        BigDecimal achievability = null;
        if (in.pNominalPerHour() != null
                && in.pNominalPerHour().signum() > 0) {
            declared = in.pNominalPerHour().multiply(
                            BigDecimal.valueOf(in.selectedRobots()), MC)
                    .setScale(1, RoundingMode.HALF_UP);
            // достижимость - к ТЕКУЩЕМУ P_nominal (KPI живого запуска)
            achievability = actualPerRobot.divide(in.pNominalPerHour(), 6,
                            RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP);
            // P_effective для сверки - из СНИМКА последнего расчёта
            // экономики («заложенная в расчёт» = допущения снимка,
            // не текущие); без расчёта сравнивать не с чем - предупреждение
            // «экономика не рассчитана» добавляет сервис
            BigDecimal crossPnom = in.econPNominalPerHour() != null
                    && in.econPNominalPerHour().signum() > 0
                    ? in.econPNominalPerHour() : in.pNominalPerHour();
            BigDecimal crossKLoad = in.econKLoad() != null ? in.econKLoad()
                    : (in.kLoad() == null ? BigDecimal.ONE : in.kLoad());
            BigDecimal crossKAvail = in.econKAvailability() != null
                    ? in.econKAvailability() : kAvailability;
            effectivePerRobot = crossPnom.multiply(crossKLoad, MC)
                    .multiply(crossKAvail, MC);
            //
            // производительность - предупреждение при расхождении
            BigDecimal rel = actualPerRobot.subtract(effectivePerRobot, MC)
                    .abs().divide(effectivePerRobot, 6, RoundingMode.HALF_UP);
            if (in.econPNominalPerHour() != null
                    && rel.compareTo(DIVERGENCE_EPSILON) > 0) {
                warnings.add(String.format(
                        "Производительность робота в модели (%s оп/ч) "
                                + "расходится с заложенной в расчёт экономики "
                                + "P_effective = P_nominal × K_load × "
                                + "K_availability (%s оп/ч): отклонение %s%% "
                                + "- проверьте длину маршрута, скорость и "
                                + "время погрузки в допущениях.",
                        actualPerRobot.setScale(1, RoundingMode.HALF_UP)
                                .toPlainString(),
                        effectivePerRobot.setScale(1, RoundingMode.HALF_UP)
                                .toPlainString(),
                        rel.multiply(BigDecimal.valueOf(100))
                                .setScale(1, RoundingMode.HALF_UP)
                                .toPlainString()));
            }
            if (achievability.compareTo(BigDecimal.valueOf(100)) < 0) {
                warnings.add(String.format(
                        "Заявленная производительность недостижима в модели: "
                                + "робот способен на %s%% от номинала "
                                + "%s оп/ч - узкие места: время цикла %s с "
                                + "(маршрут %s м, скорость %s м/с, "
                                + "погрузка-разгрузка %s с).",
                        achievability.toPlainString(),
                        in.pNominalPerHour().toPlainString(),
                        avgCycle.setScale(1, RoundingMode.HALF_UP)
                                .toPlainString(),
                        route.toPlainString(), speed.toPlainString(),
                        in.pickDropSec().toPlainString()));
            }
        } else {
            warnings.add("P_nominal (robot_nominal_productivity_per_hour) "
                    + "не задан - заявленная производительность и "
                    + "достижимость не рассчитаны (задайте допущение).");
        }
        if (peakDemand.compareTo(capacityFleet) > 0) {
            warnings.add(String.format(
                    "Пиковая нагрузка %s оп/ч превышает мощность парка "
                            + "%s оп/ч - дефицит %s оп/ч; узкое место: %s.",
                    peakDemand.setScale(1, RoundingMode.HALF_UP)
                            .toPlainString(),
                    capacityFleet.setScale(1, RoundingMode.HALF_UP)
                            .toPlainString(),
                    peakDemand.subtract(capacityFleet, MC)
                            .setScale(1, RoundingMode.HALF_UP)
                            .toPlainString(),
                    bottleneck.name()));
        }

        // --- статусы для 2D-схемы (доли робот-времени) -----------------
        // доля перемещения в среднем цикле (inbound - 1 ходка, outbound - 2)
        BigDecimal travelPerOp = travelSec.multiply(inShare, MC)
                .add(travelSec.multiply(BigDecimal.valueOf(2), MC)
                        .multiply(outShare, MC));
        BigDecimal moveShare = travelPerOp.divide(avgCycle, 6,
                RoundingMode.HALF_UP);
        BigDecimal movingPct = utilization.multiply(moveShare, MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
        BigDecimal handlingPct = utilization.multiply(BigDecimal.ONE
                        .subtract(moveShare), MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);

        // --- справочно: суточный пробег робота -------------------------
        BigDecimal opsPerDayPerRobot = dailyTotal.divide(
                BigDecimal.valueOf(in.selectedRobots()), 6,
                RoundingMode.HALF_UP);
        BigDecimal dailyTravelKm = opsPerDayPerRobot.multiply(travelPerOp, MC)
                .multiply(speed).divide(BigDecimal.valueOf(1000), 2,
                        RoundingMode.HALF_UP);

        // --- зарядные станции: §2.2 экономики -------------------------
        BigDecimal ratioInfra = in.ratioInfra() == null
                ? BigDecimal.ZERO : in.ratioInfra();
        int chargingStations = ratioInfra.multiply(
                        BigDecimal.valueOf(in.selectedRobots()), MC)
                .setScale(0, RoundingMode.CEILING).intValueExact();

        return new SimulationKpi(declared, actualThroughput,
                utilizationPct, idlePct, zones, achievability,
                in.speedMs(), capacityPerRobot
                .setScale(1, RoundingMode.HALF_UP),
                effectivePerRobot == null ? null : effectivePerRobot
                        .setScale(1, RoundingMode.HALF_UP),
                peakDemand.setScale(1, RoundingMode.HALF_UP),
                avgCycle.setScale(1, RoundingMode.HALF_UP),
                cycleIn.setScale(1, RoundingMode.HALF_UP),
                cycleOut.setScale(1, RoundingMode.HALF_UP),
                dailyTravelKm, chargingStations,
                movingPct, handlingPct, warnings, MODEL_VERSION);
    }

    /**
 * Средневзвешенная по количеству скорость состава. Строки БЕЗ ТТХ
 * «скорость» получают допущение sim_avg_robot_speed_m_s (фолбэк
 * на строку - assumptions §22-бис: «фолбэк, если у решения в составе
 * нет ТТХ»; средневзвешенная считается по всему парку).
 *
 * @param lines строки состава сценария
 * @param fallback допущение sim_avg_robot_speed_m_s; null - строки
 * без ТТХ выпадают из среднего
 * @return средневзвешенная скорость парка, м/с; null - парк пуст
 * или нет ни ТТХ, ни фолбэка
 */
    public static BigDecimal fleetSpeedOf(List<RobotLine> lines,
                                          BigDecimal fallback) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (RobotLine line : lines) {
            BigDecimal speed = line.speedMs() != null
                    ? line.speedMs() : fallback;
            if (speed == null) {
                continue;
            }
            totalQty = totalQty.add(BigDecimal.valueOf(line.quantity()));
            weighted = weighted.add(speed.multiply(
                    BigDecimal.valueOf(line.quantity()), MC));
        }
        if (totalQty.signum() == 0) {
            return null;
        }
        return weighted.divide(totalQty, 3, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    /**
 * Зона склада с загрузкой (узкие места).
 *
 * @param code код зоны (receiving/storage/picking/shipping)
 * @param name человекочитаемое название зоны
 * @param flowPerHour пиковый поток зоны, оп/час
 * @param robotTimeSharePct требуемое робот-время зоны к парку, %: при
 * перегрузке (пик > мощности парка) может
 * превышать 100% - это и есть сигнал дефицита;
 * utilization роботов при этом капится на 100%
 */
    public record ZoneLoad(String code, String name, BigDecimal flowPerHour,
                           BigDecimal robotTimeSharePct) {
    }

    // ==================================================================
    // Расчёт
    // ==================================================================

    /**
 * Строка состава (для схемы и средневзвешенной скорости).
 *
 * @param solutionId идентификатор решения каталога
 * @param name название решения
 * @param quantity количество, шт.
 * @param speedMs скорость по ТТХ (speed_m_s), м/с; null - ТТХ нет
 */
    public record RobotLine(long solutionId, String name, int quantity,
                            BigDecimal speedMs) {
    }

    /**
 * Полный вход модели (эффективные значения: параметры/ТТХ/допущения).
 * econ* - СНИМОК допущений последнего расчёта экономики :
 * P_nominal/k_load/k_availability из metrics_json расчёта; null -
 * расчёта нет или допущения в его снимке отсутствовали.
 *
 * @param selectedRobots роботов в составе сценария
 * @param peakDemandPerHour пиковая потребность, оп/час; null -
 * считается из суточных объёмов
 * @param inboundPerDay приёмка, паллет/сутки
 * @param outboundPerDay отгрузка, паллет/сутки
 * @param hoursPerDay рабочих часов в сутках (сменность)
 * @param peakFactor коэффициент пика (§1.1 экономики)
 * @param pNominalPerHour номинал робота
 * (robot_nominal_productivity_per_hour);
 * null - достижимость не рассчитана
 * @param kLoad K_load (допущение экономики)
 * @param kAvailability K_availability (допущение экономики)
 * @param ratioInfra Ratio_infra (§2.2 экономики: зарядные
 * станции)
 * @param speedMs скорость робота, м/с (средневзвешенно по
 * ТТХ состава, иначе - допущение
 * sim_avg_robot_speed_m_s)
 * @param routeLengthM средняя длина маршрута, м
 * (sim_avg_route_length_m)
 * @param pickDropSec время погрузки-разгрузки, с
 * (sim_pick_drop_sec)
 * @param lines состав сценария (для схемы и скорости)
 * @param econPNominalPerHour P_nominal из снимка последнего расчёта
 * экономики; null - расчёта нет
 * @param econKLoad K_load из снимка расчёта; null - нет
 * @param econKAvailability K_availability из снимка расчёта; null - нет
 */
    public record SimulationInput(int selectedRobots,
                                  BigDecimal peakDemandPerHour,
                                  BigDecimal inboundPerDay,
                                  BigDecimal outboundPerDay,
                                  BigDecimal hoursPerDay,
                                  BigDecimal peakFactor,
                                  BigDecimal pNominalPerHour,
                                  BigDecimal kLoad, BigDecimal kAvailability,
                                  BigDecimal ratioInfra,
                                  BigDecimal speedMs,
                                  BigDecimal routeLengthM,
                                  BigDecimal pickDropSec,
                                  List<RobotLine> lines,
                                  BigDecimal econPNominalPerHour,
                                  BigDecimal econKLoad,
                                  BigDecimal econKAvailability) {
    }

    /**
 * Итог модели - 6 KPI + разбивки для kpi_json.
 *
 * @param declaredThroughputPerHour 1. Заявленная производительность
 * парка, оп/час (P_nominal × роботов -
 * «из сценария»); null - P_nominal не задан
 * (достижимость не рассчитана)
 * @param actualThroughputPerHour 2. Фактическая производительность
 * модели, оп/час: min(пиковая нагрузка,
 * мощность парка)
 * @param utilizationPct 3. Загрузка роботов, % робот-времени
 * с полезной работой
 * @param idlePct 4. Простои, % (остаток: зарядка,
 * ожидание - K_load)
 * @param zones 5. Узкие места: загрузка зон (максимум -
 * узкое место)
 * @param achievabilityPct 6. Достижимость заявленной
 * производительности, % (фактическая на
 * робота) / (P_nominal) × 100
 * @param fleetSpeedMs средневзвешенная скорость состава, м/с
 * @param capacityPerRobotPerHour мощность одного робота при
 * непрерывной работе, оп/час
 * @param effectivePerRobotPerHour заложенная в экономику
 * производительность робота (P_effective =
 * P_nominal × K_load × K_availability, §2.1)
 * @param peakDemandPerHour пиковая потребность, оп/час (справочно)
 * @param avgCycleTimeSec среднее время цикла операции, с
 * @param inboundCycleTimeSec время цикла приёмки (приёмка →
 * хранение), с
 * @param outboundCycleTimeSec время цикла отгрузки (хранение → отбор →
 * отгрузка), с
 * @param dailyTravelKmPerRobot суточный пробег робота, км (справочно)
 * @param chargingStations зарядные станции (§2.2 экономики:
 * ceil(N × ratio_infra))
 * @param movingPct статусные доли для 2D-схемы: в пути, %
 * (простои = 100 − moving − handling; зарядка
 * отображается на схеме внутри простоев -
 * статусы у станций)
 * @param handlingPct статусные доли для 2D-схемы:
 * погрузка/разгрузка, %
 * @param warnings предупреждения модели
 * @param version версия модели
 */
    public record SimulationKpi(
            // --- 6 KPI ---------
            BigDecimal declaredThroughputPerHour,
            BigDecimal actualThroughputPerHour,
            BigDecimal utilizationPct,
            BigDecimal idlePct,
            List<ZoneLoad> zones,
            BigDecimal achievabilityPct,
            // --- разбивки (kpi_json, §12 data_model) ------------------
            BigDecimal fleetSpeedMs,
            BigDecimal capacityPerRobotPerHour,
            BigDecimal effectivePerRobotPerHour,
            BigDecimal peakDemandPerHour,
            BigDecimal avgCycleTimeSec,
            BigDecimal inboundCycleTimeSec,
            BigDecimal outboundCycleTimeSec,
            BigDecimal dailyTravelKmPerRobot,
            int chargingStations,
            BigDecimal movingPct,
            BigDecimal handlingPct,
            List<String> warnings,
            String version) {
    }
}
