package me.yuugao.robomatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.dto.AdminSolutionCreateRequest;
import me.yuugao.robomatch.dto.AdminSolutionUpdateRequest;
import me.yuugao.robomatch.dto.SolutionFullDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.mapper.SolutionMapper;
import me.yuugao.robomatch.repository.*;
import me.yuugao.robomatch.service.SolutionService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Юнит-тесты админского CRUD решений:
 * валидации ссылок на справочники (400), дубликат названия у вендора
 * (409, UNIQUE (vendor_id, name)), провенанс manual,
 * запрет удаления при ссылках из сценариев/подбора (409, §11),
 * фильтр «требуют проверки» и экранирование LIKE.
 */
@ExtendWith(MockitoExtension.class)
class AdminSolutionServiceTest {

    @Mock
    private SolutionRepository solutionRepository;
    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private SolutionTypeRepository solutionTypeRepository;
    @Mock
    private SolutionSubtypeRepository solutionSubtypeRepository;
    @Mock
    private RegionRepository regionRepository;
    @Mock
    private ScenarioSolutionRepository scenarioSolutionRepository;
    @Mock
    private SelectionResultRepository selectionResultRepository;
    @Mock
    private SolutionService solutionService;
    @Mock
    private SolutionMapper solutionMapper;

    @InjectMocks
    private AdminSolutionService service;

    private static AdminSolutionCreateRequest createRequest() {
        return AdminSolutionCreateRequest.builder()
                .name("Робот X1")
                .vendorId(5L)
                .productClass("brs")
                .status("operation")
                .priceRub(new BigDecimal("1000000.00"))
                .trl(8)
                .description("Тестовое описание")
                .payloadKg(new BigDecimal("100"))
                .build();
    }

    private static AdminSolutionUpdateRequest updateRequest() {
        return AdminSolutionUpdateRequest.builder()
                .name("Робот X1")
                .vendorId(5L)
                .productClass("brs")
                .status("operation")
                .priceRub(new BigDecimal("1000000.00"))
                .build();
    }

    @Test
    void createSavesWithManualProvenance() {
        when(vendorRepository.existsById(5L)).thenReturn(true);
        when(solutionRepository.findByVendorIdAndName(5L, "Робот X1"))
                .thenReturn(Optional.empty());
        when(solutionRepository.save(any(Solution.class))).thenAnswer(inv -> {
            Solution s = inv.getArgument(0);
            s.setId(77L);
            return s;
        });
        when(solutionService.getSolutionById(77L)).thenReturn(SolutionFullDto.builder().build());

        service.create(createRequest());

        ArgumentCaptor<Solution> captor = ArgumentCaptor.forClass(Solution.class);
        verify(solutionRepository).save(captor.capture());
        Solution saved = captor.getValue();
        assertThat(saved.getSourceKind()).isEqualTo("manual");
        assertThat(saved.getSourceUrl()).isNull();
        assertThat(saved.getSourceDate()).isEqualTo(LocalDate.now());
        assertThat(saved.getTrl()).isEqualTo((short) 8);
        assertThat(saved.getPayloadKg()).isEqualByComparingTo("100");
        // ручная карточка не относится к строкам организатора
        assertThat(saved.getExternalId()).isNull();
    }

    @Test
    void createDuplicateNameSameVendorConflicts() {
        when(vendorRepository.existsById(5L)).thenReturn(true);
        Solution existing = Solution.builder().build();
        existing.setId(9L);
        when(solutionRepository.findByVendorIdAndName(5L, "Робот X1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже есть");
        verify(solutionRepository, never()).save(any(Solution.class));
    }

    @Test
    void createUnknownVendorRejected() {
        when(vendorRepository.existsById(5L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(createRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Производитель с id=5");
    }

    @Test
    void createUnknownTypeRejected() {
        when(vendorRepository.existsById(5L)).thenReturn(true);
        when(solutionTypeRepository.existsById(3L)).thenReturn(false);
        AdminSolutionCreateRequest request = createRequest();
        request.setSolutionTypeId(3L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Тип решения с id=3");
    }

    @Test
    void createUnknownSubtypeRejected() {
        when(vendorRepository.existsById(5L)).thenReturn(true);
        when(solutionSubtypeRepository.existsById(7L)).thenReturn(false);
        AdminSolutionCreateRequest request = createRequest();
        request.setSolutionSubtypeId(7L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Подтип решения с id=7");
    }

    @Test
    void createUnknownRegionRejected() {
        when(vendorRepository.existsById(5L)).thenReturn(true);
        when(regionRepository.existsById(11L)).thenReturn(false);
        AdminSolutionCreateRequest request = createRequest();
        request.setRegionId(11L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Регион с id=11");
    }

    @Test
    void updateMissingSolutionNotFound() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(42L, updateRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateDuplicateNameOfOtherSolutionConflicts() {
        when(solutionRepository.findById(42L))
                .thenReturn(Optional.of(Solution.builder().build()));
        when(vendorRepository.existsById(5L)).thenReturn(true);
        Solution other = Solution.builder().build();
        other.setId(9L);
        when(solutionRepository.findByVendorIdAndName(5L, "Робот X1"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update(42L, updateRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже есть");
    }

    @Test
    void updateRelinksProvenanceToManual() {
        Solution organizerCard = Solution.builder()
                .sourceKind("organizer_catalog")
                .sourceUrl("catalog_export_v4.csv")
                .build();
        organizerCard.setId(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(organizerCard));
        when(vendorRepository.existsById(5L)).thenReturn(true);
        when(solutionRepository.findByVendorIdAndName(5L, "Робот X1"))
                .thenReturn(Optional.empty());
        when(solutionService.getSolutionById(42L)).thenReturn(SolutionFullDto.builder().build());

        service.update(42L, updateRequest());

        // правка руками меняет провенанс карточки: источник - уже не
        // дословная таблица организатора
        assertThat(organizerCard.getSourceKind()).isEqualTo("manual");
        assertThat(organizerCard.getSourceUrl()).isNull();
        assertThat(organizerCard.getSourceDate()).isEqualTo(LocalDate.now());
        verify(solutionRepository).save(organizerCard);
    }

    @Test
    void updateSameSolutionNameAllowed() {
        Solution card = Solution.builder().name("Робот X1").build();
        card.setId(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(vendorRepository.existsById(5L)).thenReturn(true);
        when(solutionRepository.findByVendorIdAndName(5L, "Робот X1"))
                .thenReturn(Optional.of(card));
        when(solutionService.getSolutionById(42L)).thenReturn(SolutionFullDto.builder().build());

        service.update(42L, updateRequest());

        verify(solutionRepository).save(card);
    }

    @Test
    void updateClearsOptionalFieldsByPutSemantics() {
        Solution card = Solution.builder()
                .description("Старое описание")
                .solutionTypeId(3L)
                .build();
        card.setId(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(vendorRepository.existsById(5L)).thenReturn(true);
        when(solutionRepository.findByVendorIdAndName(5L, "Робот X1"))
                .thenReturn(Optional.empty());
        when(solutionService.getSolutionById(42L)).thenReturn(SolutionFullDto.builder().build());

        service.update(42L, updateRequest());

        // PUT: отсутствующие nullable-поля очищаются (полное состояние)
        assertThat(card.getDescription()).isNull();
        assertThat(card.getSolutionTypeId()).isNull();
    }

    @Test
    void deleteMissingSolutionNotFound() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(42L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteBlockedByScenarioSolution() {
        Solution card = Solution.builder().name("Робот X1").build();
        card.setId(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(scenarioSolutionRepository.existsBySolutionId(42L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(42L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("сценарии");
        verify(solutionRepository, never()).delete(any(Solution.class));
    }

    @Test
    void deleteBlockedBySelectionResult() {
        Solution card = Solution.builder().name("Робот X1").build();
        card.setId(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(scenarioSolutionRepository.existsBySolutionId(42L)).thenReturn(false);
        when(selectionResultRepository.existsBySolutionId(42L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(42L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("подбора");
        verify(solutionRepository, never()).delete(any(Solution.class));
    }

    @Test
    void deleteWithoutReferencesDeletes() {
        Solution card = Solution.builder().name("Робот X1").build();
        card.setId(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(scenarioSolutionRepository.existsBySolutionId(42L)).thenReturn(false);
        when(selectionResultRepository.existsBySolutionId(42L)).thenReturn(false);

        service.delete(42L);

        verify(solutionRepository).delete(card);
    }

    @Test
    void listNeedsCheckFiltersByManualSourceKind() {
        when(solutionRepository.adminSearch(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<Solution>(List.of()));

        service.list(null, true, 0, 20);

        verify(solutionRepository).adminSearch(any(), eq("manual"), any(Pageable.class));
    }

    @Test
    void listEscapesLikeWildcards() {
        when(solutionRepository.adminSearch(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<Solution>(List.of()));

        service.list("100%", false, 0, 20);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(solutionRepository).adminSearch(captor.capture(), any(),
                any(Pageable.class));
        assertThat(captor.getValue()).isEqualTo("100\\%");
    }
}
