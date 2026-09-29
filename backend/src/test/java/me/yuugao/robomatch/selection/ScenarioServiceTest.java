package me.yuugao.robomatch.selection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


import me.yuugao.robomatch.domain.*;
import me.yuugao.robomatch.dto.ManualAddRequest;
import me.yuugao.robomatch.dto.ScenarioSolutionDto;
import me.yuugao.robomatch.dto.ScenarioUpdateRequest;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Unit-тест ScenarioService (2026-09-23):
 * инвариант «base не содержит решений» (добавление и замена состава -
 * 400), точечное изменение состава (DELETE строки / PUT количества,
 * 204/200/400/404), изоляция (чужой проект/сценарий - 404).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScenarioServiceTest {

    private static final Long USER = 7L;
    private static final Long PROJECT_ID = 50L;
    private static final Long BASE_ID = 101L;
    private static final Long PURCHASE_ID = 102L;
    private static final Long SOLUTION_ID = 42L;
    private static final Long VENDOR_ID = 9L;

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ScenarioRepository scenarioRepository;
    @Mock
    private ScenarioSolutionRepository scenarioSolutionRepository;
    @Mock
    private SolutionRepository solutionRepository;
    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private CalculationRepository calculationRepository;

    @InjectMocks
    private ScenarioService service;

    @BeforeEach
    void seedFixtures() {
        Project project = Project.builder().id(PROJECT_ID).userId(USER)
                .objectTypeId(1L).name("Склад").build();
        when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(project));
        when(projectRepository.findById(999L)).thenReturn(Optional.empty());
        when(scenarioRepository.findById(BASE_ID)).thenReturn(Optional.of(
                Scenario.builder().id(BASE_ID).projectId(PROJECT_ID)
                        .type(ScenarioType.BASE)
                        .name("Текущий процесс без роботизации").build()));
        when(scenarioRepository.findById(PURCHASE_ID)).thenReturn(Optional.of(
                Scenario.builder().id(PURCHASE_ID).projectId(PROJECT_ID)
                        .type(ScenarioType.PURCHASE)
                        .name("Покупка оборудования").build()));
    }

    // ------------------------------------------------------------------
    // Инвариант: base не содержит решений (data_model.md §10.5)
    // ------------------------------------------------------------------

    @Test
    void addSolutionManually_toBase_rejected400() {
        // Ручное добавление в base - 400 с понятным сообщением
        assertThatThrownBy(() -> service.addSolutionManually(USER, PROJECT_ID,
                BASE_ID, new ManualAddRequest(SOLUTION_ID, "Причина", 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Базовый сценарий не содержит решений");
        // сохранения не произошло
        verify(scenarioSolutionRepository, never()).save(any());
    }

    @Test
    void addSolutionManually_toPurchase_ok() {
        Solution solution = Solution.builder().id(SOLUTION_ID)
                .name("Ronavi H1500").vendorId(VENDOR_ID)
                .priceRub(new BigDecimal("2700000")).build();
        when(solutionRepository.findById(SOLUTION_ID))
                .thenReturn(Optional.of(solution));
        when(scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(PURCHASE_ID, SOLUTION_ID))
                .thenReturn(Optional.empty());
        when(scenarioSolutionRepository.save(any())).thenAnswer(inv ->
                inv.getArgument(0));
        assertThatCode(() -> service.addSolutionManually(USER, PROJECT_ID,
                PURCHASE_ID, new ManualAddRequest(SOLUTION_ID, "Пилот", 2)))
                .doesNotThrowAnyException();
        verify(scenarioSolutionRepository).save(any(ScenarioSolution.class));
    }

    @Test
    void updateComposition_nonEmptyInBase_rejected400() {
        // Замена состава PUT-ом: непустой список в base - 400;
        // пустой список в base допустим (никаких изменений не несёт)
        ScenarioUpdateRequest.ScenarioCompositionItemDto item =
                new ScenarioUpdateRequest.ScenarioCompositionItemDto(SOLUTION_ID, 1,
                        "Причина", true);
        assertThatThrownBy(() -> service.update(USER, PROJECT_ID, BASE_ID,
                new ScenarioUpdateRequest(null, List.of(item))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Базовый сценарий не содержит решений");
        verify(scenarioSolutionRepository, never())
                .deleteAllByScenarioId(any());
    }

    // ------------------------------------------------------------------
    // Точечное изменение состава (DELETE / PUT quantity)
    // ------------------------------------------------------------------

    @Test
    void removeSolution_deletesOnlyRow() {
        ScenarioSolution row = ScenarioSolution.builder()
                .scenarioId(PURCHASE_ID).solutionId(SOLUTION_ID).quantity(2)
                .isManual(false).build();
        when(scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(PURCHASE_ID, SOLUTION_ID))
                .thenReturn(Optional.of(row));
        assertThatCode(() -> service.removeSolution(USER, PROJECT_ID,
                PURCHASE_ID, SOLUTION_ID)).doesNotThrowAnyException();
        // удалена ТОЛЬКО строка состава; сценарий и расчёты не тронуты
        verify(scenarioSolutionRepository).delete(row);
        verify(scenarioSolutionRepository, never())
                .deleteAllByScenarioId(any());
        verify(scenarioRepository, never()).delete(any());
    }

    @Test
    void removeSolution_notInComposition_404() {
        when(scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(PURCHASE_ID, SOLUTION_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.removeSolution(USER, PROJECT_ID,
                PURCHASE_ID, SOLUTION_ID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateQuantity_ok_returnsUpdatedView() {
        ScenarioSolution row = ScenarioSolution.builder()
                .scenarioId(PURCHASE_ID).solutionId(SOLUTION_ID).quantity(1)
                .isManual(false).build();
        when(scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(PURCHASE_ID, SOLUTION_ID))
                .thenReturn(Optional.of(row));
        when(solutionRepository.findById(SOLUTION_ID)).thenReturn(Optional.of(
                Solution.builder().id(SOLUTION_ID).name("Ronavi H1500")
                        .vendorId(VENDOR_ID)
                        .priceRub(new BigDecimal("2700000")).build()));
        when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.of(
                Vendor.builder().id(VENDOR_ID).name("Ронави").build()));
        ScenarioSolutionDto view = service.updateQuantity(USER, PROJECT_ID,
                PURCHASE_ID, SOLUTION_ID, 3);
        assertThat(row.getQuantity()).isEqualTo(3);
        assertThat(view.quantity()).isEqualTo(3);
        assertThat(view.sumRub()).isEqualByComparingTo("8100000");
    }

    @Test
    void updateQuantity_zero_rejected400() {
        assertThatThrownBy(() -> service.updateQuantity(USER, PROJECT_ID,
                PURCHASE_ID, SOLUTION_ID, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Количество");
        assertThatThrownBy(() -> service.updateQuantity(USER, PROJECT_ID,
                PURCHASE_ID, SOLUTION_ID, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateQuantity(USER, PROJECT_ID,
                PURCHASE_ID, SOLUTION_ID, 10_001))
                .isInstanceOf(BadRequestException.class);
        verify(scenarioSolutionRepository, never()).save(any());
    }

    @Test
    void updateQuantity_notInComposition_404() {
        when(scenarioSolutionRepository
                .findByScenarioIdAndSolutionId(PURCHASE_ID, SOLUTION_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateQuantity(USER, PROJECT_ID,
                PURCHASE_ID, SOLUTION_ID, 2))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // Изоляция: чужой проект - 404, не 403
    // ------------------------------------------------------------------

    @Test
    void foreignProject_404_onAllNewPaths() {
        assertThatThrownBy(() -> service.addSolutionManually(USER, 999L,
                PURCHASE_ID, new ManualAddRequest(SOLUTION_ID, "x", 1)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.removeSolution(USER, 999L,
                PURCHASE_ID, SOLUTION_ID))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateQuantity(USER, 999L,
                PURCHASE_ID, SOLUTION_ID, 2))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // Список сценариев: бейдж «состав изменён с момента расчёта»
    // ------------------------------------------------------------------

    @Test
    void listWithSolutions_marksCompositionChanged() {
        when(scenarioRepository.findAllByProjectIdOrderByIdAsc(PROJECT_ID))
                .thenReturn(List.of(
                        Scenario.builder().id(PURCHASE_ID)
                                .projectId(PROJECT_ID)
                                .type(ScenarioType.PURCHASE)
                                .name("Покупка").build()));
        when(scenarioSolutionRepository.findAllByScenarioId(PURCHASE_ID))
                .thenReturn(List.of(ScenarioSolution.builder()
                        .scenarioId(PURCHASE_ID).solutionId(SOLUTION_ID)
                        .quantity(2).isManual(false).build()));
        when(solutionRepository.findAllById(List.of(SOLUTION_ID)))
                .thenReturn(List.of(Solution.builder().id(SOLUTION_ID)
                        .name("Ronavi H1500").vendorId(VENDOR_ID)
                        .priceRub(new BigDecimal("2700000")).build()));
        when(vendorRepository.findAllById(List.of(VENDOR_ID)))
                .thenReturn(List.of(Vendor.builder().id(VENDOR_ID)
                        .name("Ронави").build()));
        // последний расчёт: в снимке 1 робот, сейчас 2 - состав изменён
        Calculation latest = Calculation.builder().id(1L)
                .scenarioId(PURCHASE_ID).versionData("x")
                .versionModel("economic-model-1.0")
                .calculatedAt(Instant.parse("2026-09-23T10:00:00Z"))
                .metricsJson("{\"composition\":[{\"solutionId\":"
                        + SOLUTION_ID + ",\"quantity\":1}]}")
                .build();
        when(calculationRepository
                .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                        PURCHASE_ID))
                .thenReturn(Optional.of(latest));
        var views = service.listWithSolutions(USER, PROJECT_ID);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).compositionChanged()).isTrue();
        assertThat(views.get(0).lastCalculatedAt()).isEqualTo(
                latest.getCalculatedAt());
    }

    @Test
    void listWithSolutions_noCalculation_noBadge() {
        when(scenarioRepository.findAllByProjectIdOrderByIdAsc(PROJECT_ID))
                .thenReturn(List.of(Scenario.builder().id(PURCHASE_ID)
                        .projectId(PROJECT_ID).type(ScenarioType.PURCHASE)
                        .name("Покупка").build()));
        when(scenarioSolutionRepository.findAllByScenarioId(PURCHASE_ID))
                .thenReturn(List.of());
        when(calculationRepository
                .findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
                        PURCHASE_ID))
                .thenReturn(Optional.empty());
        var views = service.listWithSolutions(USER, PROJECT_ID);
        assertThat(views.get(0).compositionChanged()).isFalse();
        assertThat(views.get(0).lastCalculatedAt()).isNull();
    }
}
