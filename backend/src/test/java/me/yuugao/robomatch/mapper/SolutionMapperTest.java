package me.yuugao.robomatch.mapper;

import static org.assertj.core.api.Assertions.assertThat;


import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.dto.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Юнит-тесты маппера каталога: summary с именами справочников
 * и полная карточка со связями. Сущность JPA наружу не отдаётся -
 * проверяем состав DTO.
 */
class SolutionMapperTest {

    private final SolutionMapper mapper = new SolutionMapper();

    private Solution.SolutionBuilder baseSolution() {
        return Solution.builder()
                .id(7L)
                .externalId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .name("Ronavi H1500")
                .vendorId(3L)
                .productClass("brs")
                .solutionTypeId(5L)
                .solutionSubtypeId(6L)
                .regionId(2L)
                .status("operation")
                .description("Автономный погрузчик")
                .priceRub(new BigDecimal("2700000.00"))
                .trl((short) 8)
                .marketPotential(new BigDecimal("4.5"))
                .payloadKg(new BigDecimal("1500"))
                .massKg(new BigDecimal("1044"))
                .completenessPct((short) 55)
                .sourceKind("organizer_catalog")
                .sourceUrl("catalog_export_v4.csv")
                .sourceDate(LocalDate.of(2026, 9, 17));
    }

    @Test
    void toSummary_mapsFieldsAndNames() {
        Solution solution = baseSolution().build();
        CatalogNames names = new CatalogNames("Ронави Роботикс", "AMR", "Погрузчик", "Москва");

        SolutionSummaryDto dto = mapper.toSummary(solution, names);

        assertThat(dto.getId()).isEqualTo(7L);
        assertThat(dto.getName()).isEqualTo("Ronavi H1500");
        assertThat(dto.getVendorName()).isEqualTo("Ронави Роботикс");
        assertThat(dto.getSolutionTypeName()).isEqualTo("AMR");
        assertThat(dto.getSolutionSubtypeName()).isEqualTo("Погрузчик");
        assertThat(dto.getRegionName()).isEqualTo("Москва");
        assertThat(dto.getStatus()).isEqualTo("operation");
        assertThat(dto.getPriceRub()).isEqualByComparingTo("2700000");
        assertThat(dto.getTrl()).isEqualTo(8);
        assertThat(dto.getPayloadKg()).isEqualByComparingTo("1500");
        assertThat(dto.getCompletenessPct()).isEqualTo(55);
        assertThat(dto.getSourceKind()).isEqualTo("organizer_catalog");
    }

    @Test
    void toSummary_nullNames_giveNullNames() {
        // у решения могут быть незаполненные type/subtype/region - это норма данных
        SolutionSummaryDto dto = mapper.toSummary(baseSolution().build(), CatalogNames.empty());

        assertThat(dto.getVendorName()).isNull();
        assertThat(dto.getSolutionTypeName()).isNull();
        assertThat(dto.getRegionName()).isNull();
        assertThat(dto.getName()).isEqualTo("Ronavi H1500");
    }

    @Test
    void toFull_mapsAllSections() {
        Solution solution = baseSolution().build();
        CatalogNames names = new CatalogNames("Ронави Роботикс", "AMR", "Погрузчик", "Москва");
        List<SolutionCharacteristicDto> characteristics = List.of(
                SolutionCharacteristicDto.builder()
                        .typeCode("payload_kg").typeName("Грузоподъёмность")
                        .unit("кг").valueNumeric(new BigDecimal("1500"))
                        .isConfirmed(true).build());
        List<SolutionCaseDto> cases = List.of(
                SolutionCaseDto.builder().id(1L).name("Кейс склада X").build());
        List<SolutionApplicationDto> applications = List.of(
                SolutionApplicationDto.builder()
                        .industryName("Торговля и услуги")
                        .processName("Внутрискладская логистика").build());

        SolutionFullDto dto = mapper.toFull(solution, names, characteristics, cases, applications);

        assertThat(dto.getExternalId()).isNotNull();
        assertThat(dto.getVendorName()).isEqualTo("Ронави Роботикс");
        assertThat(dto.getSourceUrl()).isEqualTo("catalog_export_v4.csv");
        assertThat(dto.getSourceDate()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(dto.getCharacteristics()).hasSize(1);
        assertThat(dto.getCharacteristics().get(0).getIsConfirmed()).isTrue();
        assertThat(dto.getCases()).extracting(SolutionCaseDto::getName)
                .containsExactly("Кейс склада X");
        assertThat(dto.getApplications()).extracting(SolutionApplicationDto::getProcessName)
                .containsExactly("Внутрискладская логистика");
    }

    @Test
    void toSummary_nullSolution_returnsNull() {
        assertThat(mapper.toSummary(null, CatalogNames.empty())).isNull();
        assertThat(mapper.toFull(null, CatalogNames.empty(), List.of(), List.of(), List.of()))
                .isNull();
    }
}
