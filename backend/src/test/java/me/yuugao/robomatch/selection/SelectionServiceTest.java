package me.yuugao.robomatch.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.SelectionResultDto;
import me.yuugao.robomatch.dto.SelectionRunDto;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Unit-тест SelectionService:
 * конвейер по selection_algorithm.md §2–§9 на моках репозиториев.
 * Покрытие: статусы fit/needs_check/excluded, worst-case сравнения,
 * единицы м→мм (строгое «меньше»), провенанс EAV, веса и нормализация
 * §6 (перенормировка при all-NULL критерии, единственный fit),
 * edge cases (пустой каталог, все needs_check), UPSERT и bootstrap.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelectionServiceTest {

    private static final Long USER = 1L;
    private static final Long PROJECT_ID = 5L;
    private static final Long WAREHOUSE_TYPE_ID = 10L;
    private static final Long INDUSTRY_ID = 20L;
    // id метаданных параметров (object_type_parameter) и характеристик
    private static final Long P_PALLET = 51L;
    private static final Long P_FLOOR = 52L;
    private static final Long P_RACK = 53L;
    private static final Long P_MAIN = 54L;
    private static final Long P_CEILING = 55L;
    private static final Long P_ACCURACY = 56L;
    private static final Long P_POWER = 57L;
    private static final Long P_NOISE = 58L;
    private static final Long C_PAYLOAD = 91L;
    private static final Long C_MASS = 92L;
    private static final Long C_LENGTH = 93L;
    private static final Long C_WIDTH = 94L;
    private static final Long C_HEIGHT = 95L;
    private static final Long C_ACCURACY = 96L;
    private static final Long C_CHARGING = 97L;
    private static final Long C_NOISE = 98L;
    /**
 * Коды parameter_type по id определения (справочник для findAllById).
 */
    private static final java.util.Map<Long, String> CODE_BY_ID = new java.util.HashMap<>();
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ObjectTypeRepository objectTypeRepository;
    @Mock
    private ObjectTypeIndustryRepository objectTypeIndustryRepository;
    @Mock
    private ObjectTypeParameterRepository objectTypeParameterRepository;
    @Mock
    private ParameterTypeRepository parameterTypeRepository;
    @Mock
    private ProjectParameterValueRepository valueRepository;
    @Mock
    private SolutionRepository solutionRepository;
    @Mock
    private me.yuugao.robomatch.repository.SolutionTypeRepository solutionTypeRepository;
    @Mock
    private SolutionCharacteristicRepository solutionCharacteristicRepository;
    @Mock
    private CharacteristicTypeRepository characteristicTypeRepository;
    @Mock
    private SolutionCaseLinkRepository solutionCaseLinkRepository;
    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private SelectionResultRepository selectionResultRepository;
    @Mock
    private ScenarioRepository scenarioRepository;
    @Mock
    private ScenarioService scenarioService;
    @InjectMocks
    private SelectionService service;

    private static ParameterType pt(Long id, String code, String name) {
        return ParameterType.builder().id(id).code(code).name(name).unit("ед")
                .valueType(ParameterValueType.NUMBER).build();
    }

    private static ObjectTypeParameter otp(Long id, String code, String name,
                                           String def, String min, String max) {
        CODE_BY_ID.put(id, code);
        return ObjectTypeParameter.builder()
                .id(id).objectTypeId(WAREHOUSE_TYPE_ID).parameterTypeId(id)
                .groupName("Тест").isRequired(false)
                .defaultValueNumeric(new BigDecimal(def))
                .minValue(new BigDecimal(min)).maxValue(new BigDecimal(max))
                .sourceNote("Тест").build();
    }

    private static CharacteristicType ct(Long id, String code) {
        return CharacteristicType.builder().id(id).code(code).name(code)
                .groupCode("specs").dataType("number").unit(null)
                .isFilterable(false).isRequired(true).sortOrder(0).build();
    }

    /**
 * Решение с полным набором ТТХ (колонки) - все проверки проходят.
 */
    private static Solution fullRobot(long id, String name, String price, int trl) {
        return Solution.builder().id(id).name(name).vendorId(1L)
                .productClass("brs").status("operation")
                .priceRub(new BigDecimal(price)).trl((short) trl)
                .payloadKg(new BigDecimal("1500")).massKg(new BigDecimal("800"))
                .lengthMm(new BigDecimal("900")).widthMm(new BigDecimal("700"))
                .heightMm(new BigDecimal("300"))
                .positioningAccuracyMm(new BigDecimal("1"))
                .chargingPowerKw(new BigDecimal("1"))
                .noiseLevelDba(new BigDecimal("60"))
                .build();
    }

    private static Solution withType(Solution solution, Long typeId) {
        solution.setSolutionTypeId(typeId);
        return solution;
    }

    @BeforeEach
    void setUp() {
        Project project = Project.builder().id(PROJECT_ID).userId(USER)
                .objectTypeId(WAREHOUSE_TYPE_ID).name("Склад").build();
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(objectTypeRepository.findById(WAREHOUSE_TYPE_ID)).thenReturn(Optional.of(
                ObjectType.builder().id(WAREHOUSE_TYPE_ID).code("warehouse")
                        .name("Склад").isCalcEnabled(true).build()));
        when(objectTypeIndustryRepository.findByObjectTypeId(WAREHOUSE_TYPE_ID))
                .thenReturn(List.of(ObjectTypeIndustry.builder()
                        .objectTypeId(WAREHOUSE_TYPE_ID).industryId(INDUSTRY_ID)
                        .sourceNote("assumptions.md §1").build()));

        List<ObjectTypeParameter> definitions = List.of(
                otp(P_PALLET, "pallet_unit_weight",
                        "Максимальная масса грузовой единицы (паллет), кг",
                        "1500", "200", "1500"),
                otp(P_FLOOR, "floor_load_max_kg",
                        "Максимальная нагрузка на пол (на точку опоры), кг",
                        "5000", "500", "5000"),
                otp(P_RACK, "rack_aisle_width",
                        "Минимальная ширина рабочих проходов между стеллажами, м",
                        "1.5", "1.5", "4.5"),
                otp(P_MAIN, "main_aisle_width",
                        "Минимальная ширина главных проездов, м",
                        "2.5", "2.5", "6"),
                otp(P_CEILING, "storage_zone_ceiling_height",
                        "Минимальная высота потолков в зоне хранения, м",
                        "5", "5", "16"),
                otp(P_ACCURACY, "required_positioning_accuracy_mm",
                        "Требование к точности позиционирования"
                                + " (в самой требовательной операции), мм",
                        "5", "1", "50"),
                otp(P_POWER, "available_power_capacity",
                        "Минимальная доступная мощность для зарядки роботов, кВт",
                        "100", "100", "3000"),
                otp(P_NOISE, "max_allowed_noise_dba",
                        "Максимально допустимый уровень шума"
                                + " (в самой тихой зоне), дБА",
                        "70", "25", "80"));
        when(objectTypeParameterRepository
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(WAREHOUSE_TYPE_ID))
                .thenReturn(definitions);
        // справочник типов параметров: id определения = id типа, код - из мапы
        when(parameterTypeRepository.findAllById(any())).thenReturn(
                CODE_BY_ID.entrySet().stream()
                        .map(e -> ParameterType.builder().id(e.getKey())
                                .code(e.getValue()).name(e.getValue()).unit("ед")
                                .valueType(ParameterValueType.NUMBER).build())
                        .toList());
        when(valueRepository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of());

        when(characteristicTypeRepository.findAll()).thenReturn(List.of(
                ct(C_PAYLOAD, "payload_kg"), ct(C_MASS, "mass_kg"),
                ct(C_LENGTH, "length_mm"), ct(C_WIDTH, "width_mm"),
                ct(C_HEIGHT, "height_mm"), ct(C_ACCURACY, "positioning_accuracy_mm"),
                ct(C_CHARGING, "charging_power_kw"), ct(C_NOISE, "noise_level_dba")));

        when(solutionCharacteristicRepository.findBySolutionIdIn(any()))
                .thenReturn(List.of());
        when(solutionCaseLinkRepository.findBySolutionIdIn(any())).thenReturn(List.of());
        when(vendorRepository.findAllById(any())).thenReturn(List.of());
        when(selectionResultRepository.findAllByProjectId(PROJECT_ID))
                .thenReturn(List.of());
        when(scenarioRepository.findAllByProjectIdOrderByIdAsc(PROJECT_ID))
                .thenReturn(List.of(Scenario.builder().id(1L).projectId(PROJECT_ID)
                        .type(ScenarioType.PURCHASE)
                        .name("Покупка оборудования").build()));
    }

    private void stubPool(Solution... solutions) {
        when(solutionRepository.findAllByIndustryIds(any()))
                .thenReturn(List.of(solutions));
    }

    // --- статусы (§5) ------------------------------------------------------

    /**
 * У решений есть кейсы (критерий «Кейсы» = 1, §6.1).
 */
    private void stubCases(long... solutionIds) {
        when(solutionCaseLinkRepository.findBySolutionIdIn(any()))
                .thenReturn(java.util.Arrays.stream(solutionIds)
                        .mapToObj(id -> me.yuugao.robomatch.domain.SolutionCaseLink
                                .builder().solutionId(id).caseId(1L).build())
                        .toList());
    }

    @Test
    void run_fullRobot_fit() {
        stubPool(fullRobot(101L, "Робот А", "2700000", 8));
        stubCases(101L);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        assertThat(response.results()).hasSize(1);
        SelectionResultDto view = response.results().get(0);
        assertThat(view.status()).isEqualTo("fit");
        assertThat(view.rank()).isEqualTo(1);
        assertThat(view.missingData()).isNull();
        assertThat(view.criteriaContribution()).isNotNull();
        // полный робот = все критерии оценены, Score = 1.00 (единственный fit)
        assertThat(view.score()).isEqualByComparingTo("1.00");
    }

    @Test
    void run_payloadViolation_excluded() {
        Solution weak = fullRobot(101L, "Слабый робот", "1000000", 9);
        weak.setPayloadKg(new BigDecimal("800"));
        stubPool(weak);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        SelectionResultDto view = response.results().get(0);
        assertThat(view.status()).isEqualTo("excluded");
        assertThat(view.reason()).contains("грузоподъёмность");
        assertThat(view.reason()).contains("1500");
        assertThat(view.rank()).isNull();
        verify(selectionResultRepository).saveAll(anyList());
    }

    @Test
    void run_missingSpec_needsCheck() {
        Solution noNoise = fullRobot(101L, "Без шума", "2700000", 8);
        noNoise.setNoiseLevelDba(null);
        stubPool(noNoise);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        SelectionResultDto view = response.results().get(0);
        assertThat(view.status()).isEqualTo("needs_check");
        assertThat(view.missingData()).isNotNull();
        assertThat(view.missingData()).anyMatch(m -> m.contains("уровень шума"));
        assertThat(view.rank()).isNull();
        assertThat(view.score()).isNull();
    }

    @Test
    void run_missingObjectParam_needsCheck() {
        // убираем параметр «нагрузка на пол» из метаданных типа
        List<ObjectTypeParameter> withoutFloor = new ArrayList<>();
        for (ObjectTypeParameter p : objectTypeParameterRepository
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(WAREHOUSE_TYPE_ID)) {
            if (!p.getId().equals(P_FLOOR)) {
                withoutFloor.add(p);
            }
        }
        when(objectTypeParameterRepository
                .findAllByObjectTypeIdOrderByGroupNameAscIdAsc(WAREHOUSE_TYPE_ID))
                .thenReturn(withoutFloor);
        stubPool(fullRobot(101L, "Робот А", "2700000", 8));

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        SelectionResultDto view = response.results().get(0);
        assertThat(view.status()).isEqualTo("needs_check");
        assertThat(view.missingData())
                .anyMatch(m -> m.contains("нагрузку на пол") || m.contains("не задан"));
    }

    @Test
    void run_unconfirmedSpec_needsCheck() {
        stubPool(fullRobot(101L, "Робот А", "2700000", 8));
        // EAV-строка перекрывает колонку: неподтверждённое значение
        when(solutionCharacteristicRepository.findBySolutionIdIn(any()))
                .thenReturn(List.of(SolutionCharacteristic.builder()
                        .solutionId(101L).characteristicTypeId(C_PAYLOAD)
                        .valueNumeric(new BigDecimal("1600"))
                        .sourceKind("manual").sourceUrl(null)
                        .isConfirmed(false).build()));

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        SelectionResultDto view = response.results().get(0);
        assertThat(view.status()).isEqualTo("needs_check");
        assertThat(view.missingData()).anyMatch(m -> m.contains("не подтверждены"));
    }

    // --- единицы: метры объекта -> миллиметры ТТХ (§4) --------------------

    @Test
    void run_eavOverridesColumn() {
        // EAV подтверждённое значение 1600 перекрывает колонку 1500 - проходит
        stubPool(fullRobot(101L, "Робот А", "2700000", 8));
        when(solutionCharacteristicRepository.findBySolutionIdIn(any()))
                .thenReturn(List.of(SolutionCharacteristic.builder()
                        .solutionId(101L).characteristicTypeId(C_PAYLOAD)
                        .valueNumeric(new BigDecimal("1600"))
                        .sourceKind("open_source").sourceUrl("https://vendor.example")
                        .isConfirmed(true).build()));

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        assertThat(response.results().get(0).status()).isEqualTo("fit");
        assertThat(response.results().get(0).criteriaContribution()).isNotNull();
    }

    // --- ранжирование (§6) -------------------------------------------------

    @Test
    void run_metersConvertedToMm_borderIsStrict() {
        // проход 1.5 м = 1500 мм: длина ровно 1500 НЕ проходит (строго <)
        Solution border = fullRobot(101L, "Ровно в проход", "2700000", 8);
        border.setLengthMm(new BigDecimal("1500"));
        stubPool(border);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        assertThat(response.results().get(0).status()).isEqualTo("excluded");
        assertThat(response.results().get(0).reason()).contains("1500");

        // 1499 мм - проходит
        Solution fits = fullRobot(102L, "Чуть меньше", "2700000", 8);
        fits.setLengthMm(new BigDecimal("1499"));
        stubPool(fits);
        SelectionRunDto second = service.run(USER, PROJECT_ID);
        assertThat(second.results().get(0).status()).isEqualTo("fit");
    }

    @Test
    void run_weightsAndOrder_twoFits() {
        // A: дешевле, точнее, выше TRL -> выше Score
        Solution a = fullRobot(101L, "Робот А", "700000", 9);
        Solution b = fullRobot(102L, "Робот Б", "2700000", 8);
        b.setPositioningAccuracyMm(new BigDecimal("5"));
        stubPool(a, b);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        assertThat(response.results()).hasSize(2);
        SelectionResultDto first = response.results().get(0);
        assertThat(first.solutionId()).isEqualTo(101L);
        assertThat(first.rank()).isEqualTo(1);
        assertThat(response.results().get(1).rank()).isEqualTo(2);
        // вес критерия отображается, вклад = вес x нормированное
        var price = first.criteriaContribution().stream()
                .filter(c -> c.code().equals("price")).findFirst().orElseThrow();
        // у A цена минимальная в выборке -> N=1 -> вклад = вес 0.20
        assertThat(price.normalizedValue()).isEqualByComparingTo("1");
        assertThat(price.contribution()).isEqualByComparingTo("0.200");
        // суммы весов склада = 1.00 (§6.1)
        var weightSum = first.criteriaContribution().stream()
                .map(c -> c.weight()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(weightSum).isEqualByComparingTo("1.00");
    }

    @Test
    void run_allNullCriterion_weightsRenormalized() {
        // УГТ (TRL) не задан ни у одного fit-решения -> критерий исключён из
        // формулы, веса остальных перенормированы к 1.0 (§6.2). Кейсов нет
        // у обоих - но «Кейсы» бинарный и оценён всегда (0), не исключается.
        Solution a = fullRobot(101L, "Робот А", "700000", 9);
        a.setTrl(null);
        Solution b = fullRobot(102L, "Робот Б", "2700000", 8);
        b.setTrl(null);
        stubPool(a, b);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        SelectionResultDto first = response.results().get(0);
        var trl = first.criteriaContribution().stream()
                .filter(c -> c.code().equals("trl")).findFirst().orElseThrow();
        assertThat(trl.evaluated()).isFalse();
        assertThat(trl.contribution()).isEqualByComparingTo("0");
        // вес исключённого критерия в отображении = 0
        assertThat(trl.weight()).isEqualByComparingTo("0");
        // сумма весов = 1 (с точностью округления 4 знаков)
        var weightSum = first.criteriaContribution().stream()
                .map(c -> c.weight()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(weightSum).isCloseTo(BigDecimal.ONE,
                org.assertj.core.api.Assertions.within(new BigDecimal("0.001")));
        // цена: 0.20 / (1 - 0.15) = 0.2353 после перенормировки
        var price = first.criteriaContribution().stream()
                .filter(c -> c.code().equals("price")).findFirst().orElseThrow();
        assertThat(price.weight()).isEqualByComparingTo("0.2353");
        // Score A: все критерии лучшие в выборке -> перенормированная сумма
        // (0.95 / 0.85) = 1.00... вклад цены = 0.2353 x 1 = 0.235
        assertThat(price.contribution()).isEqualByComparingTo("0.235");
    }

    // --- edge cases -------------------------------------------------------

    @Test
    void run_singleFit_scoreIsOne() {
        stubPool(fullRobot(101L, "Единственный", "2700000", 8));
        stubCases(101L);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        // единственный fit: он же лучший по всем критериям -> Score 1.00
        // (selection_algorithm.md §10, вопрос 4)
        assertThat(response.results().get(0).score()).isEqualByComparingTo("1.00");
    }

    @Test
    void run_emptyCatalog_emptyResultsButScenariosCreated() {
        when(solutionRepository.findAllByIndustryIds(any())).thenReturn(List.of());

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        assertThat(response.results()).isEmpty();
        verify(scenarioService).ensureDefaultScenarios(PROJECT_ID);
        verify(selectionResultRepository, never()).saveAll(anyList());
    }

    // --- сохранение (§10.7) -------------------------------------------------

    @Test
    void run_allNeedsCheck_noRanks() {
        Solution s1 = fullRobot(101L, "Без шума 1", "2700000", 8);
        s1.setNoiseLevelDba(null);
        Solution s2 = fullRobot(102L, "Без шума 2", "1500000", 7);
        s2.setNoiseLevelDba(null);
        stubPool(s1, s2);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        assertThat(response.results()).hasSize(2);
        assertThat(response.results())
                .allMatch(r -> r.status().equals("needs_check"));
        assertThat(response.results()).allMatch(r -> r.rank() == null);
        assertThat(response.results()).allMatch(r -> r.score() == null);
        assertThat(response.results())
                .allMatch(r -> r.criteriaContribution() == null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_upsertsAndDeletesStale() {
        stubPool(fullRobot(101L, "Робот А", "2700000", 8),
                fullRobot(102L, "Робот Б", "1500000", 7));
        // прошлый запуск: 101 (обновится) + 999 (покинул пул - удалить)
        SelectionResult stale = SelectionResult.builder().id(77L)
                .projectId(PROJECT_ID).solutionId(999L)
                .status(SelectionStatus.NEEDS_CHECK).reason("старое").build();
        SelectionResult existing = SelectionResult.builder().id(78L)
                .projectId(PROJECT_ID).solutionId(101L)
                .status(SelectionStatus.NEEDS_CHECK).reason("старое").build();
        when(selectionResultRepository.findAllByProjectId(PROJECT_ID))
                .thenReturn(List.of(stale, existing));

        service.run(USER, PROJECT_ID);

        ArgumentCaptor<List<SelectionResult>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(selectionResultRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).allSatisfy(row -> {
            if (row.getSolutionId() == 101L) {
                assertThat(row.getId()).isEqualTo(78L); // обновлена, не новая
                assertThat(row.getStatus()).isEqualTo(SelectionStatus.FIT);
                // 102 дешевле -> выше Score -> ранг 1; 101 - ранг 2
                assertThat(row.getRank()).isEqualTo(2);
            }
            if (row.getSolutionId() == 102L) {
                assertThat(row.getRank()).isEqualTo(1);
            }
        });
        // покинувшая пул пара удалена
        verify(selectionResultRepository).deleteAllById(List.of(77L));
    }

    @Test
    void run_foreignProject_404() {
        assertThatThrownBy(() -> service.run(2L, PROJECT_ID))
                .isInstanceOf(NotFoundException.class);
        verify(solutionRepository, never()).findAllByIndustryIds(any());
    }

    @Test
    void getResults_neverRun_empty() {
        SelectionRunDto response = service.getResults(USER, PROJECT_ID);

        assertThat(response.results()).isEmpty();
        assertThat(response.scenarios()).isNotEmpty();
    }

    @Test
    void getResults_storedStatusesWithFreshExplanations() {
        Solution noNoise = fullRobot(101L, "Без шума", "2700000", 8);
        noNoise.setNoiseLevelDba(null);
        stubPool(noNoise);
        // зафиксированный запуск: статус/причина в БД, объяснения - пересчёт
        when(selectionResultRepository.findAllByProjectId(PROJECT_ID))
                .thenReturn(List.of(SelectionResult.builder().id(1L)
                        .projectId(PROJECT_ID).solutionId(101L)
                        .status(SelectionStatus.NEEDS_CHECK).reason("старая причина")
                        .build()));

        SelectionRunDto response = service.getResults(USER, PROJECT_ID);

        SelectionResultDto view = response.results().get(0);
        // статус/причина - из хранения; объяснения - пересчёт
        assertThat(view.status()).isEqualTo("needs_check");
        assertThat(view.reason()).isEqualTo("старая причина");
        assertThat(view.missingData()).isNotNull(); // свежий missing
    }

    // --- гонки: оба DIVE-пути → 409 ---

    @Test
    void run_responseIncludesScenariosAndVendorNames() {
        stubPool(fullRobot(101L, "Робот А", "2700000", 8));
        when(vendorRepository.findAllById(any())).thenReturn(List.of(
                Vendor.builder().id(1L).name("ООО «Ронави Роботикс»").build()));

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        assertThat(response.scenarios()).hasSize(1);
        assertThat(response.results().get(0).vendorName())
                .isEqualTo("ООО «Ронави Роботикс»");
    }

    @Test
    void run_bootstrapRace_conflict409() {
        stubPool(fullRobot(101L, "Робот А", "2700000", 8));
        // гонка двух первых запусков: оба вставляют сценарии -
        // DataIntegrityViolationException из ensureDefaultScenarios
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "unique (project_id, name)")).when(scenarioService)
                .ensureDefaultScenarios(PROJECT_ID);

        assertThatThrownBy(() -> service.run(USER, PROJECT_ID))
                .isInstanceOf(me.yuugao.robomatch.exception.ConflictException.class)
                .hasMessageContaining("Параллельный запуск");
        verify(selectionResultRepository, never()).saveAll(anyList());
    }

    // --- объяснения: Score только fit, missing и у excluded ---

    @Test
    void run_upsertRace_conflict409() {
        stubPool(fullRobot(101L, "Робот А", "2700000", 8));
        // гонка UPSERT: DataIntegrityViolationException из saveAll
        when(selectionResultRepository.findAllByProjectId(PROJECT_ID))
                .thenReturn(List.of());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "unique (project_id, solution_id)"))
                .when(selectionResultRepository).saveAll(anyList());

        assertThatThrownBy(() -> service.run(USER, PROJECT_ID))
                .isInstanceOf(me.yuugao.robomatch.exception.ConflictException.class)
                .hasMessageContaining("Параллельный запуск");
    }

    @Test
    void run_excluded_hasMissingDataButNoScore() {
        // робот без шума И со слабой грузоподъёмностью: excluded
        // (нарушение), но недостающее «шум» тоже показывается
        Solution weak = fullRobot(101L, "Слабый без шума", "2700000", 8);
        weak.setPayloadKg(new BigDecimal("800"));
        weak.setNoiseLevelDba(null);
        stubPool(weak);

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        SelectionResultDto view = response.results().get(0);
        assertThat(view.status()).isEqualTo("excluded");
        assertThat(view.reason()).contains("грузоподъёмность");
        assertThat(view.score()).isNull();
        assertThat(view.criteriaContribution()).isNull();
        assertThat(view.missingData()).isNotNull();
        assertThat(view.missingData()).anyMatch(m -> m.contains("уровень шума"));
    }

    @Test
    void run_fitResponse_containsSolutionTypeName() {
        stubPool(fullRobot(101L, "Робот А", "2700000", 8));
        //
        when(solutionTypeRepository.findAllById(any())).thenReturn(List.of(
                me.yuugao.robomatch.domain.SolutionType.builder()
                        .id(30L).code("mobile_robots").name("Мобильные роботы")
                        .build()));
        // решению нужен тип
        org.mockito.Mockito.when(solutionRepository.findAllByIndustryIds(any()))
                .thenReturn(List.of(withType(fullRobot(101L, "Робот А", "2700000", 8), 30L)));

        SelectionRunDto response = service.run(USER, PROJECT_ID);

        assertThat(response.results().get(0).solutionTypeName())
                .isEqualTo("Мобильные роботы");
    }
}
