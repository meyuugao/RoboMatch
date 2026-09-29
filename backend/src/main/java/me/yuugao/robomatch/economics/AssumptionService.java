package me.yuugao.robomatch.economics;

import me.yuugao.robomatch.domain.Project;
import me.yuugao.robomatch.domain.ProjectAssumption;
import me.yuugao.robomatch.dto.AssumptionDto;
import me.yuugao.robomatch.dto.AssumptionUpdateRequest;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.EconomicValidationException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.ProjectAssumptionRepository;
import me.yuugao.robomatch.repository.ProjectRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import lombok.RequiredArgsConstructor;

/**
 * Сервис допущений экономики (
 * допущения; 3.5.8: все допущения доступны пользователю).
 *
 * <p>КАТАЛОГ допущений — код, дефолт, единица, диапазон, провенанс —
 * фиксирован кодом и берётся из docs/assumptions.md §22–23
 * (коэффициенты экономики) и §22-бис (входы расчётного модуля,
 *
 * запрещены; каждый код каталога прослеживается до раздела допущений
 * или до формул economic_model.md §1–2).
 * Дефолты внутри документированных диапазонов — середина диапазона
 * или типовое рыночное значение (кроме зафиксированных организатором
 * или командой значений; отклонения перечислены в §22-бис).
 *
 * <p>ХРАНЕНИЕ: переопределения — project_assumption (V5); отсутствие
 * строки = дефолт (data_model.md §12: дефолты не дублируются).
 * Снимок эффективных значений попадает в каждый расчёт
 * (calculation_assumption, §10.9) — историю не переписываем.
 *
 * <p>ИЗОЛЯЦИЯ: как везде — userId из JWT, чужой проект — 404.
 */
@Service
@RequiredArgsConstructor
public class AssumptionService {

    private static final LocalDate DOC_DATE = LocalDate.of(2026, 9, 17);
    /**
 * Каталог: порядок стабильный (порядок вывода в UI). Значения и
 * диапазоны — assumptions.md §22 (Легенда XLSX + обоснованные
 * командой) и §23 (RaaS).
 */
    private static final Map<String, CatalogItem> CATALOG = buildCatalog();
    private final ProjectRepository projectRepository;
    private final ProjectAssumptionRepository assumptionRepository;

    private static Map<String, CatalogItem> buildCatalog() {
        Map<String, CatalogItem> m = new LinkedHashMap<>();
        // --- организатор (Легенда Датасеты_хакатон.xlsx, §22) ----------
        m.put("k_load", new CatalogItem("k_load",
                "Коэффициент загрузки робота (K_load)", "0.75", "доля",
                bd("0.70"), bd("0.85"), "number", true, "organizer_catalog",
                "Типовой KPI AMR 70-85%: учитывает зарядку, простои, "
                        + "ожидание (Легенда XLSX). Влияет на рекомендуемое "
                        + "количество роботов."));
        m.put("k_reserve", new CatalogItem("k_reserve",
                "Резервная надбавка на пиковую нагрузку (K_reserve)", "0.15",
                "доля", bd("0.15"), bd("0.20"), "number", true,
                "organizer_catalog",
                "«Робот рассчитывается на пиковую нагрузку с резервом 15-20%» "
                        + "(Легенда XLSX): потребность умножается на "
                        + "(1+K_reserve)."));
        m.put("reserve_rate", new CatalogItem("reserve_rate",
                "Резерв CAPEX (Reserve_rate)", "0.10", "доля", bd("0"),
                bd("0.10"), "number", false, "organizer_catalog",
                "«CAPEX включает: ... + резерв 10%» (Легенда XLSX) — "
                        + "фикс организатора, не редактируется."));
        // --- команда (§22, изменяемые пользователем) -------------------
        m.put("tariff_rub_kwh", new CatalogItem("tariff_rub_kwh",
                "Тариф на электроэнергию", "7.0", "руб./кВт·ч", bd("6"),
                bd("8"), "number", true, "manual",
                "Средневзвешенный тариф промышленных потребителей РФ "
                        + "(6-8 руб./кВт·ч, с НДС). Влияет на C_electricity."));
        m.put("ratio_infra", new CatalogItem("ratio_infra",
                "Зарядных станций на робота (Ratio_infra)", "0.35", "шт./робот",
                bd("0.2"), bd("0.5"), "number", true, "manual",
                "Типовые спецификации AMR: 1 станция на 2-5 роботов. "
                        + "Определяет N_infra."));
        m.put("k_availability", new CatalogItem("k_availability",
                "Коэффициент доступности (K_availability)", "1.0", "доля",
                bd("0"), bd("1"), "number", true, "manual",
                "Данных о доступности в ТТХ нет; простои уже учтены "
                        + "в K_load. Нейтральное значение 1.0 — снизьте "
                        + "при известных простоях сверх K_load."));
        m.put("robot_nominal_productivity_per_hour",
                new CatalogItem("robot_nominal_productivity_per_hour",
                        "Номинальная производительность робота (P_nominal)",
                        null, "операций/час", bd("1"), null, "number", true,
                        "manual",
                        "В каталоге производительность — текст, числового "
                                + "значения нет. Задайте числом для расчёта "
                                + "рекомендуемого количества парка "
                                + "(производительность)."));
        m.put("robot_power_consumption_kw",
                new CatalogItem("robot_power_consumption_kw",
                        "Потребляемая мощность робота (P_consumption)", null,
                        "кВт", bd("0"), null, "number", true, "manual",
                        "Если не задана — консервативно берётся мощность "
                                + "зарядки из ТТХ состава (верхняя оценка "
                                + "потребления); при отсутствии и её — "
                                + "электроэнергия считается нулём с "
                                + "предупреждением."));
        m.put("amortization_years", new CatalogItem("amortization_years",
                "Срок службы для амортизации (по умолчанию)", "6", "лет",
                bd("5"), bd("7"), "number", true, "manual",
                "5-7 лет линейной амортизации робота. При наличии "
                        + "срока службы в ТТХ решений берётся "
                        + "средневзвешенный по составу (уточнение организатора)."));
        m.put("c_infra_pct", new CatalogItem("c_infra_pct",
                "Инфраструктура — доля стоимости оборудования", "10", "%",
                bd("5"), bd("15"), "number", true, "manual",
                "Зарядные станции, разметка, СКУД-интеграция."));
        m.put("c_software_pct", new CatalogItem("c_software_pct",
                "ПО — доля стоимости оборудования", "15", "%", bd("10"),
                bd("20"), "number", true, "manual",
                "ПО флит-менеджмента, WMS-модуль."));
        m.put("c_integration_pct", new CatalogItem("c_integration_pct",
                "Интеграция — доля стоимости оборудования", "15", "%",
                bd("10"), bd("25"), "number", true, "manual",
                "Интеграция с WMS/ERP (уточнение организатора «базовая интеграция»)."));
        m.put("c_commissioning_pct", new CatalogItem("c_commissioning_pct",
                "Пусконаладка — доля стоимости оборудования", "7.5", "%",
                bd("5"), bd("10"), "number", true, "manual",
                "уточнение организатора: пусконаладка в цену изделия не входит."));
        m.put("c_training_pct", new CatalogItem("c_training_pct",
                "Обучение — доля стоимости оборудования", "3", "%", bd("2"),
                bd("5"), "number", true, "manual",
                "Обучение персонала — статья CAPEX."));
        m.put("c_service_pct", new CatalogItem("c_service_pct",
                "Сервис — доля стоимости оборудования в год", "10", "%",
                bd("8"), bd("12"), "number", true, "manual",
                "Сервисные контракты вендоров."));
        m.put("c_licenses_pct", new CatalogItem("c_licenses_pct",
                "Лицензии ПО — доля стоимости оборудования в год", "5", "%",
                bd("3"), bd("7"), "number", true, "manual", "Лицензии ПО."));
        m.put("c_communication_rub_year",
                new CatalogItem("c_communication_rub_year",
                        "Связь — на робота в год", "36000", "руб./год",
                        bd("12000"), bd("60000"), "number", true, "manual",
                        "Связь/SIM промышленного тарифа (12-60 тыс. "
                                + "руб./год на робота)."));
        m.put("c_consumables_pct", new CatalogItem("c_consumables_pct",
                "Расходники — доля стоимости оборудования в год", "2", "%",
                bd("1"), bd("3"), "number", true, "manual",
                "Включая замену АКБ раз в 3-5 лет (Легенда XLSX)."));
        m.put("c_repair_pct", new CatalogItem("c_repair_pct",
                "Ремонт вне сервиса — доля стоимости оборудования в год",
                "3.5", "%", bd("2"), bd("5"), "number", true, "manual",
                "Ремонт вне сервисного контракта."));
        m.put("equipment_cost_factor_pct",
                new CatalogItem("equipment_cost_factor_pct",
                        "Фактор стоимости оборудования", "100", "%",
                        bd("50"), bd("200"), "number", true, "manual",
                        "100% — цены каталога; скорректируйте при известной "
                                + " скидке/наценке (влияют CAPEX, OPEX, TCO)."));
        m.put("other_annual_effect_rub",
                new CatalogItem("other_annual_effect_rub",
                        "Прочие измеримые годовые эффекты (dOther)", "0",
                        "руб./год", null, null, "number", true, "manual",
                        "Предотвращённые потери, дополнительный доход. "
                                + "0 — не задано."));
        m.put("exploitation_staff_count",
                new CatalogItem("exploitation_staff_count",
                        "Персонал эксплуатации роботизированного сценария "
                                + "(N_staff_rob)", "0", "чел", bd("0"), null,
                        "number", true, "manual",
                        "0 — целевые группы (отборщики, операторы погрузчиков) "
                                + "замещаются роботами полностью. Скорректируйте "
                                + "при своём составе эксплуатации."));
        // --- RaaS (§23) -------------------------------------------------
        m.put("raas_payment_model", new CatalogItem("raas_payment_model",
                "Модель платежей RaaS", "fixed", "fixed|usage|mixed", null,
                null, "enum", true, "manual",
                "Фиксированная ставка — минимальная детерминированная модель; "
                        + "usage/mixed требуют ставку за операцию."));
        m.put("raas_rate_month_pct", new CatalogItem("raas_rate_month_pct",
                "Ставка RaaS — доля цены робота в месяц", "2.0", "%", bd("1.5"),
                bd("3"), "number", true, "manual",
                "1.5-3% цены робота в месяц; при 5 млн руб. — "
                        + "75-150 тыс. руб./мес за робота."));
        m.put("raas_contract_years", new CatalogItem("raas_contract_years",
                "Срок контракта RaaS", "3", "лет", bd("1"), bd("10"),
                "number", true, "manual",
                "3 года (36 месяцев) — горизонт окупаемости парка оператора, "
                        + "сопоставимость с покупкой по TCO."));
        m.put("raas_buyout", new CatalogItem("raas_buyout",
                "Выкуп оборудования в конце контракта", "false", "да/нет",
                null, null, "boolean", true, "manual",
                "Опционально, по остаточной стоимости (линейная амортизация, "
                        + "уточнение организатора): Buyout = Цена x max(0, 1 - срок "
                        + "контракта / срок службы)."));
        m.put("raas_usage_rate_rub", new CatalogItem("raas_usage_rate_rub",
                "Ставка за операцию (модели usage/mixed)", null,
                "руб./операция", bd("0"), null, "number", true, "manual",
                "Обязательна для моделей «плата за использование» и "
                        + "«смешанная»; для fixed не используется."));
        // --- заёмное финансирование (уточнения организатора, §2.10) ------------
        m.put("loan_amount_rub", new CatalogItem("loan_amount_rub",
                "Заёмные средства (сумма кредита)", "0", "руб.", bd("0"),
                null, "number", true, "manual",
                "Базовое финансирование — собственные средства (уточнение организатора). "
                        + "Сумма > 0 включает аннуитетные платежи."));
        m.put("loan_rate_pct", new CatalogItem("loan_rate_pct",
                "Ставка по заёмным средствам", null, "% год.", bd("0.1"),
                null, "number", true, "manual",
                "Обязательна при сумме кредита > 0 (уточнение организатора: тип и ставку "
                        + "указывает команда, фиксируется в допущениях)."));
        m.put("loan_term_years", new CatalogItem("loan_term_years",
                "Срок кредита", null, "лет", bd("1"), bd("15"), "number",
                true, "manual",
                "Обязателен при сумме кредита > 0 (аннуитет с годовыми "
                        + "платежами)."));
        // --- имитация 2D + KPI (§22-бис) -------------------------------
        // Используются ТОЛЬКО моделью имитации (SimulationModel); в
        // формулы экономики не входят, но попадают в снимок допущений
        // расчёта (§10.9) как входы проекта —
        // коэффициенты прослеживаются до assumptions.md.
        m.put("sim_avg_route_length_m", new CatalogItem(
                "sim_avg_route_length_m",
                "Имитация: средняя длина маршрута за перемещение", "60",
                "м", bd("10"), bd("500"), "number", true, "manual",
                "Средняя длина пути робота между зонами склада за одно "
                        + "перемещение (приёмка → хранение, хранение → "
                        + "отбор → отгрузка). Типовая геометрия склада "
                        + "10-20 тыс. м² (200×100 м): док → среднее "
                        + "паллетоместо ~ 60 м. Уточните по плану "
                        + "объекта — влияет на время цикла и "
                        + "достижимость производительности."));
        m.put("sim_avg_robot_speed_m_s", new CatalogItem(
                "sim_avg_robot_speed_m_s",
                "Имитация: скорость робота (фолбэк без ТТХ)", "1.5",
                "м/с", bd("0.5"), bd("3.0"), "number", true, "manual",
                "Применяется, только если у решения в составе нет ТТХ "
                        + "«скорость» (speed_m_s). Типовые AMR "
                        + "1,0-2,0 м/с; при наличии ТТХ берётся "
                        + "средневзвешенная скорость состава."));
        m.put("sim_pick_drop_sec", new CatalogItem("sim_pick_drop_sec",
                "Имитация: время погрузки и разгрузки паллеты", "40",
                "с", bd("10"), bd("120"), "number", true, "manual",
                "Суммарное время взятия и оставления паллеты за операцию "
                        + "(по 20 с на точку). Типовые AMR-погрузчики: "
                        + "подъём/опускание вил 15-30 с на операцию; "
                        + "влияет на время цикла и узкие места зон."));
        return Map.copyOf(m);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
 * Текущие допущения проекта: дефолты каталога + переопределения.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @return эффективные значения всех допущений
 */
    @Transactional(readOnly = true)
    public List<AssumptionDto> list(Long userId, Long projectId) {
        requireOwnedProject(userId, projectId);
        Map<String, String> overrides = overrides(projectId);
        List<AssumptionDto> views = new ArrayList<>();
        for (CatalogItem item : CATALOG.values()) {
            String effective = overrides.getOrDefault(item.name(),
                    item.defaultValue());
            views.add(new AssumptionDto(item.name(), item.title(),
                    effective, item.defaultValue(), item.unit(), item.kind(),
                    item.min(), item.max(), item.editable(),
                    item.sourceKind(), item.impactNote()));
        }
        return views;
    }

    /**
 * Изменить допущения: тело — карта «код → значение|null».
 * ЧАСТИЧНОЕ обновление: коды вне карты не меняются; явный null (или
 * пустая строка) — сброс кода в дефолт (строка переопределения
 * удаляется). «Полная замена» — только если карта покрывает все коды.
 *
 * @param userId владелец (изоляция)
 * @param projectId идентификатор проекта
 * @param request карта код → значение|null (сброс в дефолт)
 * @return эффективные значения всех допущений после обновления
 */
    @Transactional
    public List<AssumptionDto> update(Long userId, Long projectId,
                                      AssumptionUpdateRequest request) {
        requireOwnedProject(userId, projectId);
        Map<String, String> overrides = overrides(projectId);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry
                : request.values() == null ? Map.<String, String>of()
                .entrySet() : request.values().entrySet()) {
            CatalogItem item = CATALOG.get(entry.getKey());
            if (item == null) {
                problems.add("Неизвестное допущение: " + entry.getKey());
                continue;
            }
            if (!item.editable()) {
                problems.add("Допущение " + entry.getKey()
                        + " зафиксировано организатором и не изменяется");
                continue;
            }
            String value = entry.getValue() == null ? null : entry.getValue().trim();
            if (value == null || value.isEmpty()) {
                overrides.remove(item.name());
                continue;
            }
            if (value.length() > 64) {
                problems.add("Допущение " + item.name()
                        + ": значение — до 64 символов");
                continue;
            }
            String problem = validateValue(item, value);
            if (problem != null) {
                problems.add(problem);
                continue;
            }
            overrides.put(item.name(), value);
        }
        if (!problems.isEmpty()) {
            throw new BadRequestException(String.join("; ", problems));
        }
        // существующие строки — одним запросом в память (обновление
        // частичное: вне карты не трогаем, null в карте — сброс)
        Map<String, ProjectAssumption> existing = new LinkedHashMap<>();
        for (ProjectAssumption row
                : assumptionRepository.findAllByProjectId(projectId)) {
            existing.put(row.getName(), row);
        }
        // устаревшие переопределения (сброшенные в дефолт) удаляются
        List<ProjectAssumption> toDelete = existing.values().stream()
                .filter(row -> !overrides.containsKey(row.getName()))
                .toList();
        if (!toDelete.isEmpty()) {
            assumptionRepository.deleteAll(toDelete);
        }
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            ProjectAssumption row = existing.get(entry.getKey());
            try {
                if (row == null) {
                    row = ProjectAssumption.builder()
                            .projectId(projectId).name(entry.getKey()).build();
                }
                row.setValue(entry.getValue());
                assumptionRepository.save(row);
            } catch (DataIntegrityViolationException ex) {
                // Гонка двух параллельных PUT: UNIQUE(project_id, name) —
                // повтор идентичен повторному запросу
                throw new ConflictException("Допущения параллельно изменены "
                        + "другим запросом — повторите ещё раз");
            }
        }
        return list(userId, projectId);
    }

    /**
 * Эффективное числовое значение допущения (дефолт или override).
 */
    BigDecimal effectiveNumeric(Long projectId, String name) {
        String raw = effectiveRaw(projectId, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new BigDecimal(raw.replace(',', '.'));
    }

    /**
 * Эффективное строковое значение (enum/boolean тоже строкой).
 */
    String effectiveRaw(Long projectId, String name) {
        Map<String, String> overrides = overrides(projectId);
        String value = overrides.getOrDefault(name,
                CATALOG.get(name) == null ? null
                        : CATALOG.get(name).defaultValue());
        return value == null || value.isBlank() ? null : value;
    }

    /**
 * Снимок значений допущений ТОЛЬКО из каталога (без переопределений
 * проекта) — для гостевого демо-расчёта, где проекта нет. Только
 * чтение, обращений к БД не выполняет.
 *
 * @return снимок значений каталога допущений
 */
    public EffectiveAssumptions catalogDefaultsSnapshot() {
        Map<String, String> effective = new LinkedHashMap<>();
        for (CatalogItem item : CATALOG.values()) {
            String value = item.defaultValue();
            if (value != null && !value.isBlank()) {
                effective.put(item.name(), value);
            }
        }
        return new EffectiveAssumptions(effective);
    }

    /**
 * Снимок ЭФФЕКТИВНЫХ значений всех допущений проекта ОДНИМ
 * запросом (без ~50 одинаковых SELECT и без torn read
 * при параллельном PUT). Неизменяемый внутри расчёта.
 *
 * @param projectId идентификатор проекта
 * @return снимок эффективных значений допущений
 */
    public EffectiveAssumptions effectiveSnapshot(Long projectId) {
        Map<String, String> overrides = overrides(projectId);
        Map<String, String> effective = new LinkedHashMap<>();
        for (CatalogItem item : CATALOG.values()) {
            String value = overrides.getOrDefault(item.name(),
                    item.defaultValue());
            if (value != null && !value.isBlank()) {
                effective.put(item.name(), value);
            }
        }
        return new EffectiveAssumptions(effective);
    }

    /**
 * Снимок всех допущений для расчёта.
 */
    List<CatalogItem> catalogItems() {
        return List.copyOf(CATALOG.values());
    }

    CatalogItem catalogItem(String name) {
        return CATALOG.get(name);
    }

    private String validateValue(CatalogItem item, String value) {
        switch (item.kind()) {
            case "number" -> {
                BigDecimal parsed;
                try {
                    parsed = new BigDecimal(value.replace(',', '.'));
                } catch (NumberFormatException ex) {
                    return "Допущение " + item.name() + ": ожидается число, "
                            + "получено «" + value + "»";
                }
                if (parsed.signum() < 0) {
                    return "Допущение " + item.name()
                            + ": отрицательные значения не допускаются";
                }
                if (item.min() != null && parsed.compareTo(item.min()) < 0) {
                    return "Допущение " + item.name() + ": минимум "
                            + item.min().toPlainString();
                }
                if (item.max() != null && parsed.compareTo(item.max()) > 0) {
                    return "Допущение " + item.name() + ": максимум "
                            + item.max().toPlainString();
                }
            }
            case "enum" -> {
                if (!"fixed".equals(value) && !"usage".equals(value)
                        && !"mixed".equals(value)) {
                    return "Допущение " + item.name()
                            + ": допустимо fixed | usage | mixed";
                }
            }
            case "boolean" -> {
                if (!"true".equalsIgnoreCase(value)
                        && !"false".equalsIgnoreCase(value)) {
                    return "Допущение " + item.name()
                            + ": допустимо true | false";
                }
            }
            default -> {
                return "Допущение " + item.name() + ": неподдерживаемый тип";
            }
        }
        return null;
    }

    private Map<String, String> overrides(Long projectId) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ProjectAssumption row
                : assumptionRepository.findAllByProjectId(projectId)) {
            map.put(row.getName(), row.getValue());
        }
        return map;
    }

    private void requireOwnedProject(Long userId, Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new NotFoundException("Проект не найден");
        }
    }

    /**
 * Запись каталога допущений (источник — assumptions.md §22-23).
 */
    record CatalogItem(String name, String title, String defaultValue,
                       String unit, BigDecimal min, BigDecimal max,
                       String kind, boolean editable, String sourceKind,
                       String impactNote) {
    }

    /**
 * Эффективные значения допущений без обращений к БД.
 *
 * @param values карта код → эффективное значение (строкой)
 */
    public record EffectiveAssumptions(Map<String, String> values) {

        /**
 * Строковое значение (enum/boolean тоже строкой), null — не задано.
 *
 * @param name код допущения
 * @return значение строкой или null
 */
        public String raw(String name) {
            return values.get(name);
        }

        /**
 * Числовое значение, null — не задано.
 *
 * @param name код допущения
 * @return числовое значение или null
 */
        public BigDecimal numeric(String name) {
            String raw = values.get(name);
            return raw == null ? null
                    : new BigDecimal(raw.replace(',', '.'));
        }

        /**
 * Обязательное числовое (400 со списком, §6).
 *
 * @param name код допущения
 * @return числовое значение (обязательно заданное)
 * @throws me.yuugao.robomatch.exception.EconomicValidationException допущение не задано
 */
        public BigDecimal require(String name) {
            BigDecimal value = numeric(name);
            if (value == null) {
                throw new EconomicValidationException(List.of(
                        "Не задано обязательное допущение «" + name
                                + "»."));
            }
            return value;
        }
    }
}
