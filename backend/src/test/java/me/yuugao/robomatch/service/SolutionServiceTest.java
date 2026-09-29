package me.yuugao.robomatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.domain.SolutionTypeSubtypeMapping;
import me.yuugao.robomatch.dto.CatalogQuery;
import me.yuugao.robomatch.dto.FiltersDto;
import me.yuugao.robomatch.dto.SolutionFullDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.mapper.SolutionMapper;
import me.yuugao.robomatch.repository.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Юнит-тесты сервиса каталога на Mockito: валидация параметров
 * (белые списки, диапазоны, границы страницы — все нарушения -> 400),
 * сборка сортировки (COALESCE-сентинелы для nullable-полей), правила
 * сравнения (2-10, дубликаты, 404) и словари фильтров.
 */
@ExtendWith(MockitoExtension.class)
class SolutionServiceTest {

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
    private IndustryRepository industryRepository;
    @Mock
    private ProcessRepository processRepository;
    @Mock
    private SolutionApplicationRepository solutionApplicationRepository;
    @Mock
    private SolutionCharacteristicRepository solutionCharacteristicRepository;
    @Mock
    private CharacteristicTypeRepository characteristicTypeRepository;
    @Mock
    private SolutionCaseRepository solutionCaseRepository;
    @Mock
    private SolutionCaseLinkRepository solutionCaseLinkRepository;
    @Mock
    private SolutionTypeSubtypeMappingRepository solutionTypeSubtypeMappingRepository;
    @Mock
    private me.yuugao.robomatch.repository.ObjectTypeRepository objectTypeRepository;

    // реальный маппер (чистая функция) — @Spy, чтобы @InjectMocks внедрил его в сервис
    @Spy
    private SolutionMapper solutionMapper = new SolutionMapper();

    @InjectMocks
    private SolutionService solutionService;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private Solution solution(Long id) {
        return Solution.builder()
                .id(id).name("Решение " + id).vendorId(1L).productClass("brs")
                .status("operation").priceRub(new BigDecimal("100000"))
                .sourceKind("organizer_catalog")
                .build();
    }

    // --- валидация списка -------------------------------------------------

    @Test
    void search_invalidSortBy_throws400() {
        CatalogQuery query = new CatalogQuery(null, null, null, null, null, null, null, null,
                null, null, null, "hack", "asc", 0, 20);

        assertThatThrownBy(() -> solutionService.search(query))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("sortBy");
        verify(solutionRepository, never()).search(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void search_invalidSortDir_throws400() {
        CatalogQuery query = new CatalogQuery(null, null, null, null, null, null, null, null,
                null, null, null, "price", "random", 0, 20);

        assertThatThrownBy(() -> solutionService.search(query))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("sortDir");
    }

    @Test
    void search_invalidStatus_throws400() {
        CatalogQuery query = new CatalogQuery(null, null, null, null, null, "unknown", null,
                null, null, null, null, null, null, 0, 20);

        assertThatThrownBy(() -> solutionService.search(query))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("status");
    }

    @Test
    void search_trlRangeInverted_throws400() {
        CatalogQuery query = new CatalogQuery(null, null, null, null, null, null, 8, 7,
                null, null, null, null, null, 0, 20);

        assertThatThrownBy(() -> solutionService.search(query))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("trlMin");
    }

    @Test
    void search_negativePage_throws400() {
        CatalogQuery query = new CatalogQuery(null, null, null, null, null, null, null, null,
                null, null, null, null, null, -1, 20);

        assertThatThrownBy(() -> solutionService.search(query))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("page");
    }

    @Test
    void search_sizeOverLimit_throws400() {
        CatalogQuery query = new CatalogQuery(null, null, null, null, null, null, null, null,
                null, null, null, null, null, 0, 101);

        assertThatThrownBy(() -> solutionService.search(query))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("size");
    }

    @Test
    void search_defaults_areNameAscPage0Size20() {
        CatalogQuery query = new CatalogQuery("   ", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        when(solutionRepository.search(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(solution(1L)), Pageable.ofSize(20), 1));
        when(vendorRepository.findAllById(anySet())).thenReturn(List.of());

        solutionService.search(query);

        verify(solutionRepository).search(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        // пустой q (пробелы) -> null: фильтр не действует
        verify(solutionRepository).search(eq(null), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class));
        // дефолтная сортировка — по названию, по возрастанию
        assertThat(pageable.getSort().getOrderFor("s.name")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("s.name").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void search_payloadDesc_usesCoalesceSentinel() {
        CatalogQuery query = new CatalogQuery(null, null, null, null, null, null, null, null,
                null, null, null, "payload_kg", "desc", 2, 50);
        when(solutionRepository.search(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(50), 0));

        solutionService.search(query);

        verify(solutionRepository).search(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().iterator().next();
        // desc: NULL -> -1 (в конец); asc было бы +1e9
        assertThat(order.getProperty()).isEqualTo("COALESCE(s.payloadKg, -1)");
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    // --- сравнение ---------------------------------------------------------

    @Test
    void compare_singleId_throws400() {
        assertThatThrownBy(() -> solutionService.compare(List.of(1L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("минимум");
    }

    @Test
    void compare_empty_throws400() {
        assertThatThrownBy(() -> solutionService.compare(List.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void compare_moreThanTen_throws400() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);

        assertThatThrownBy(() -> solutionService.compare(ids))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("не более");
    }

    @Test
    void compare_elevenRawIds_tenUnique_allowed() {
        // лимит считается по уникальным id: дубликаты не занимают слот
        // (11 значений, из них уникальных 10)
        List<Long> raw = List.of(1L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        List<Solution> found = new java.util.ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            found.add(solution(id));
        }
        when(solutionRepository.findAllById(anyCollection())).thenReturn(found);
        when(vendorRepository.findAllById(anySet())).thenReturn(List.of());
        when(solutionCharacteristicRepository.findBySolutionIdIn(anyCollection()))
                .thenReturn(List.of());
        when(solutionCaseLinkRepository.findBySolutionIdIn(anyCollection()))
                .thenReturn(List.of());
        when(solutionApplicationRepository.findBySolutionIdIn(anyCollection()))
                .thenReturn(List.of());

        List<SolutionFullDto> result = solutionService.compare(raw);

        assertThat(result).hasSize(10);
    }

    @Test
    void search_escapesLikeWildcards() {
        // q с вилдкардами % и _ экранируется перед передачей в репозиторий
        CatalogQuery query = new CatalogQuery("AMR 100%данные_", null, null, null, null,
                null, null, null, null, null, null, null, null, 0, 20);
        when(solutionRepository.search(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0));

        solutionService.search(query);

        verify(solutionRepository).search(eq("AMR 100\\%данные\\_"), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void compare_duplicatesCollapse_toDistinct() {
        when(solutionRepository.findAllById(anyCollection()))
                .thenReturn(List.of(solution(1L), solution(2L)));
        when(vendorRepository.findAllById(anySet())).thenReturn(List.of());
        when(solutionCharacteristicRepository.findBySolutionIdIn(anyCollection()))
                .thenReturn(List.of());
        when(solutionCaseLinkRepository.findBySolutionIdIn(anyCollection()))
                .thenReturn(List.of());
        when(solutionApplicationRepository.findBySolutionIdIn(anyCollection()))
                .thenReturn(List.of());

        List<SolutionFullDto> result = solutionService.compare(List.of(1L, 1L, 2L));

        assertThat(result).hasSize(2);
        // батч-запросы сделаны один раз на весь набор, без N+1
        verify(solutionCharacteristicRepository).findBySolutionIdIn(anyCollection());
        verify(solutionApplicationRepository).findBySolutionIdIn(anyCollection());
    }

    @Test
    void compare_unknownId_throws404() {
        when(solutionRepository.findAllById(anyCollection()))
                .thenReturn(List.of(solution(1L)));

        assertThatThrownBy(() -> solutionService.compare(List.of(1L, 999L)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("999");
    }

    // --- карточка и словари --------------------------------------------------

    @Test
    void getSolutionById_unknown_throws404() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> solutionService.getSolutionById(42L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("42");
    }

    @Test
    void getFilters_buildsResponse() {
        when(solutionTypeRepository.findAll()).thenReturn(List.of(
                me.yuugao.robomatch.domain.SolutionType.builder()
                        .id(1L).code("amr").name("AMR").build()));
        when(objectTypeRepository.findAll()).thenReturn(List.of(
                me.yuugao.robomatch.domain.ObjectType.builder()
                        .id(1L).code("warehouse").name("Склад")
                        .isCalcEnabled(true).build()));
        when(solutionSubtypeRepository.findAll()).thenReturn(List.of());
        when(industryRepository.findAll()).thenReturn(List.of());
        when(processRepository.findAll()).thenReturn(List.of());
        when(solutionTypeSubtypeMappingRepository.findAll()).thenReturn(List.of(
                SolutionTypeSubtypeMapping.builder()
                        .solutionTypeId(1L).solutionSubtypeId(3L).build()));
        when(solutionRepository.findPriceTrlRanges()).thenReturn(new SolutionRepository.CatalogRanges() {
            @Override
            public BigDecimal getMinPrice() {
                return new BigDecimal("500000");
            }

            @Override
            public BigDecimal getMaxPrice() {
                return new BigDecimal("3200000");
            }

            @Override
            public Short getMinTrl() {
                return (short) 5;
            }

            @Override
            public Short getMaxTrl() {
                return (short) 9;
            }
        });

        FiltersDto filters = solutionService.getFilters();

        assertThat(filters.getTypes()).hasSize(1);
        assertThat(filters.getTypes().get(0).getName()).isEqualTo("AMR");
        assertThat(filters.getObjectTypes()).hasSize(1);
        assertThat(filters.getObjectTypes().get(0).getName()).isEqualTo("Склад");
        assertThat(filters.getStatuses()).containsExactly("operation", "piloting", "rnd");
        assertThat(filters.getTypeSubtypeMapping()).hasSize(1);
        assertThat(filters.getPriceMin()).isEqualByComparingTo("500000");
        assertThat(filters.getTrlMax()).isEqualTo(9);
    }
}
