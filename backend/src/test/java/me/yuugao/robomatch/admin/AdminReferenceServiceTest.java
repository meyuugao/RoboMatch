package me.yuugao.robomatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


import me.yuugao.robomatch.domain.Industry;
import me.yuugao.robomatch.domain.Process;
import me.yuugao.robomatch.domain.Vendor;
import me.yuugao.robomatch.dto.AdminReferenceCreateRequest;
import me.yuugao.robomatch.dto.AdminReferenceDto;
import me.yuugao.robomatch.dto.AdminReferenceUpdateRequest;
import me.yuugao.robomatch.dto.PageResponse;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

/**
 * Юнит-тесты CRUD справочников админки: белый список
 * dictCode (400), семантика vendor (без кода), characteristic_type
 * (group/data_type обязательны), дубликаты кода/имени (409),
 * запрет удаления при FK-ссылках (409, data_model.md §11).
 */
@ExtendWith(MockitoExtension.class)
class AdminReferenceServiceTest {

    @Mock
    private IndustryRepository industryRepository;
    @Mock
    private ProcessRepository processRepository;
    @Mock
    private VendorRepository vendorRepository;
    @Mock
    private RegionRepository regionRepository;
    @Mock
    private SolutionTypeRepository solutionTypeRepository;
    @Mock
    private SolutionSubtypeRepository solutionSubtypeRepository;
    @Mock
    private CharacteristicTypeRepository characteristicTypeRepository;
    @Mock
    private SolutionRepository solutionRepository;
    @Mock
    private SolutionApplicationRepository applicationRepository;
    @Mock
    private ObjectTypeIndustryRepository objectTypeIndustryRepository;
    @Mock
    private SolutionTypeSubtypeMappingRepository mappingRepository;
    @Mock
    private SolutionCharacteristicRepository characteristicRepository;

    @InjectMocks
    private AdminReferenceService service;

    private static AdminReferenceCreateRequest create(String code, String name) {
        return AdminReferenceCreateRequest.builder().code(code).name(name).build();
    }

    @Test
    void unknownDictCodeRejected() {
        assertThatThrownBy(() -> service.create("unknown_dict", create("x", "X")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Неизвестный справочник");
        assertThatThrownBy(() -> service.list("unknown_dict", null, 0, 10))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.delete("unknown_dict", 1L))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void vendorHasNoCode() {
        AdminReferenceCreateRequest request = create("some_code", "ООО Ромбот");
        assertThatThrownBy(() -> service.create("vendor", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("не использует код");
    }

    @Test
    void codePatternEnforcedByDto() {
        // Регекс проверяется Bean Validation на DTO (контроллер); сервис
        // дополнительно нормализует: пустой код -> 400
        AdminReferenceCreateRequest request = AdminReferenceCreateRequest.builder()
                .code("  ").name("Склад").build();
        assertThatThrownBy(() -> service.create("industry", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Код записи обязателен");
    }

    @Test
    void createDuplicateNameConflicts() {
        when(industryRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                Industry.builder().code("logistics").name("Логистика").build()));

        assertThatThrownBy(() -> service.create("industry", create("new_code",
                "Логистика")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже есть");
        verify(industryRepository, never()).save(any(Industry.class));
    }

    @Test
    void createDuplicateCodeConflicts() {
        when(industryRepository.findAllByOrderByIdAsc()).thenReturn(List.of(
                Industry.builder().code("logistics").name("Логистика").build()));

        assertThatThrownBy(() -> service.create("industry", create("logistics",
                "Другая отрасль")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Код «logistics» уже занят");
    }

    @Test
    void createIndustrySavesCodeAndName() {
        when(industryRepository.findAllByOrderByIdAsc()).thenReturn(List.of());
        when(industryRepository.save(any(Industry.class))).thenAnswer(inv -> {
            Industry i = inv.getArgument(0);
            i.setId(1L);
            return i;
        });

        AdminReferenceDto view = service.create("industry", create("medicine",
                "Медицина"));

        assertThat(view.getCode()).isEqualTo("medicine");
        assertThat(view.getName()).isEqualTo("Медицина");
        verify(industryRepository).save(any(Industry.class));
    }

    @Test
    void createProcessIsActiveByDefault() {
        when(processRepository.findAllByOrderByIdAsc()).thenReturn(List.of());
        when(processRepository.save(any(Process.class))).thenAnswer(inv -> {
            Process p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        AdminReferenceDto view = service.create("process", create("packing",
                "Упаковка"));

        assertThat(view.getIsActive()).isTrue();
    }

    @Test
    void characteristicTypeRequiresGroupAndDataType() {
        when(characteristicTypeRepository.findAllByOrderByIdAsc())
                .thenReturn(List.of());

        AdminReferenceCreateRequest noGroup = create("new_char", "Новая ТТХ");
        noGroup.setDataType("number");
        assertThatThrownBy(() -> service.create("characteristic_type", noGroup))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Группа");

        AdminReferenceCreateRequest badType = create("new_char", "Новая ТТХ");
        badType.setGroupCode("technical");
        badType.setDataType("json");
        assertThatThrownBy(() -> service.create("characteristic_type", badType))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Тип значения");
    }

    @Test
    void updateMissingEntryNotFound() {
        // 404 до проверок дублей: несуществующая запись
        when(industryRepository.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.update("industry", 7L,
                AdminReferenceUpdateRequest.builder().code("x").name("X").build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateMissingEntryWithOccupiedCodeStillNotFound() {
        // 404 важнее 409: PUT несуществующего id — дубликаты не проверяются
        when(industryRepository.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.update("industry", 7L,
                AdminReferenceUpdateRequest.builder()
                        .code("logistics").name("Логистика").build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("не найдена");
    }

    @Test
    void deleteIndustryBlockedByApplications() {
        Industry industry = Industry.builder().code("logistics").name("Логистика")
                .build();
        industry.setId(3L);
        when(industryRepository.findById(3L)).thenReturn(Optional.of(industry));
        when(applicationRepository.existsByIndustryId(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete("industry", 3L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("применениях решений");
        verify(industryRepository, never()).delete(any(Industry.class));
    }

    @Test
    void deleteIndustryBlockedByObjectTypes() {
        Industry industry = Industry.builder().code("logistics").name("Логистика")
                .build();
        industry.setId(3L);
        when(industryRepository.findById(3L)).thenReturn(Optional.of(industry));
        when(applicationRepository.existsByIndustryId(3L)).thenReturn(false);
        when(objectTypeIndustryRepository.existsByIndustryId(3L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete("industry", 3L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("типами объектов");
    }

    @Test
    void deleteVendorBlockedBySolutions() {
        Vendor vendor = Vendor.builder().name("ООО Ромбот").build();
        vendor.setId(8L);
        when(vendorRepository.findById(8L)).thenReturn(Optional.of(vendor));
        when(solutionRepository.existsByVendorId(8L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete("vendor", 8L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("есть решения в каталоге");
    }

    @Test
    void deleteSolutionTypeBlockedByTypeSubtypePairs() {
        me.yuugao.robomatch.domain.SolutionType type =
                me.yuugao.robomatch.domain.SolutionType.builder()
                        .code("amr").name("Мобильные роботы").build();
        type.setId(4L);
        when(solutionTypeRepository.findById(4L)).thenReturn(Optional.of(type));
        when(solutionRepository.existsBySolutionTypeId(4L)).thenReturn(false);
        when(mappingRepository.existsBySolutionTypeId(4L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete("solution_type", 4L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("тип-подтип");
    }

    @Test
    void listPagesAndFilters() {
        Industry first = Industry.builder().code("logistics").name("Логистика")
                .build();
        first.setId(1L);
        Industry second = Industry.builder().code("medicine").name("Медицина")
                .build();
        second.setId(2L);
        when(industryRepository.findAllByOrderByIdAsc())
                .thenReturn(List.of(first, second));

        PageResponse<AdminReferenceDto> page1 = service.list("industry", null, 0, 1);
        assertThat(page1.getContent()).hasSize(1);
        assertThat(page1.getTotalElements()).isEqualTo(2);
        assertThat(page1.getTotalPages()).isEqualTo(2);

        PageResponse<AdminReferenceDto> search = service.list("industry", "мед",
                0, 50);
        assertThat(search.getContent()).hasSize(1);
        assertThat(search.getContent().get(0).getCode()).isEqualTo("medicine");
    }

    @Test
    void countsIncludeAllDicts() {
        when(industryRepository.count()).thenReturn(9L);
        when(processRepository.count()).thenReturn(95L);
        when(vendorRepository.count()).thenReturn(103L);
        when(regionRepository.count()).thenReturn(24L);
        when(solutionTypeRepository.count()).thenReturn(11L);
        when(solutionSubtypeRepository.count()).thenReturn(71L);
        when(characteristicTypeRepository.count()).thenReturn(132L);

        var counts = service.counts();

        assertThat(counts).containsEntry("industry", 9L)
                .containsEntry("process", 95L)
                .containsEntry("vendor", 103L)
                .containsEntry("characteristic_type", 132L);
    }
}
