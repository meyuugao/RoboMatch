package me.yuugao.robomatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import me.yuugao.robomatch.domain.CharacteristicType;
import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.domain.SolutionCharacteristic;
import me.yuugao.robomatch.dto.AdminCharacteristicUpsertRequest;
import me.yuugao.robomatch.dto.SolutionCharacteristicDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.repository.CharacteristicTypeRepository;
import me.yuugao.robomatch.repository.SolutionCharacteristicRepository;
import me.yuugao.robomatch.repository.SolutionRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Юнит-тесты EAV-ТТХ решений:
 * типобезопасность значения (data_type, data_model.md §4), провенанс
 * manual/open_source (is_confirmed), синхронизация 9 зеркальных колонок
 * solution (§5.8), удаление со сбросом зеркала.
 */
@ExtendWith(MockitoExtension.class)
class AdminCharacteristicServiceTest {

    @Mock
    private SolutionRepository solutionRepository;
    @Mock
    private CharacteristicTypeRepository characteristicTypeRepository;
    @Mock
    private SolutionCharacteristicRepository characteristicRepository;

    @InjectMocks
    private AdminCharacteristicService service;

    private static Solution solution(Long id) {
        Solution s = Solution.builder().build();
        s.setId(id);
        return s;
    }

    private static CharacteristicType type(Long id, String code, String name,
                                           String dataType) {
        CharacteristicType t = CharacteristicType.builder()
                .code(code).name(name).groupCode("technical").dataType(dataType)
                .build();
        t.setId(id);
        return t;
    }

    @Test
    void upsertSolutionNotFound() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(42L, 1L,
                AdminCharacteristicUpsertRequest.builder()
                        .valueNumeric(BigDecimal.TEN).build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Решение с id=42");
    }

    @Test
    void upsertTypeNotFound() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(solution(42L)));
        when(characteristicTypeRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(42L, 9L,
                AdminCharacteristicUpsertRequest.builder()
                        .valueNumeric(BigDecimal.TEN).build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Тип характеристики с id=9");
    }

    @Test
    void upsertRejectsWrongValueType() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(solution(42L)));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));

        // число ожидается, прислали текст
        assertThatThrownBy(() -> service.upsert(42L, 1L,
                AdminCharacteristicUpsertRequest.builder().valueText("много").build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ожидает число");
    }

    @Test
    void upsertRejectsMultipleValues() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(solution(42L)));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));

        assertThatThrownBy(() -> service.upsert(42L, 1L,
                AdminCharacteristicUpsertRequest.builder()
                        .valueNumeric(BigDecimal.TEN).valueBool(true).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("несколько значений");
    }

    @Test
    void upsertRejectsEmptyValue() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(solution(42L)));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));

        assertThatThrownBy(() -> service.upsert(42L, 1L,
                AdminCharacteristicUpsertRequest.builder().build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Передайте значение");
    }

    @Test
    void upsertManualIsUnconfirmedAndSyncsMirror() {
        Solution card = solution(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));
        when(characteristicRepository.findBySolutionIdAndCharacteristicTypeId(42L, 1L))
                .thenReturn(Optional.empty());
        when(characteristicRepository.save(any(SolutionCharacteristic.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SolutionCharacteristicDto dto = service.upsert(42L, 1L,
                AdminCharacteristicUpsertRequest.builder()
                        .valueNumeric(new BigDecimal("1500.5")).build());

        assertThat(dto.getSourceKind()).isEqualTo("manual");
        assertThat(dto.getIsConfirmed()).isFalse();
        // зеркало: материализованная колонка solution синхронизирована (§5.8)
        assertThat(card.getPayloadKg()).isEqualByComparingTo("1500.5");
        verify(solutionRepository).save(card);
    }

    @Test
    void upsertOpenSourceRequiresUrlAndDate() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(solution(42L)));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));

        assertThatThrownBy(() -> service.upsert(42L, 1L,
                AdminCharacteristicUpsertRequest.builder()
                        .valueNumeric(BigDecimal.TEN).sourceKind("open_source")
                        .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ссылка");
    }

    @Test
    void upsertOpenSourceIsConfirmed() {
        Solution card = solution(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));
        when(characteristicRepository.findBySolutionIdAndCharacteristicTypeId(42L, 1L))
                .thenReturn(Optional.empty());
        when(characteristicRepository.save(any(SolutionCharacteristic.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SolutionCharacteristicDto dto = service.upsert(42L, 1L,
                AdminCharacteristicUpsertRequest.builder()
                        .valueNumeric(BigDecimal.TEN)
                        .sourceKind("open_source")
                        .sourceUrl("https://example.com/datasheet")
                        .sourceDate(java.time.LocalDate.of(2026, 9, 1))
                        .build());

        assertThat(dto.getIsConfirmed()).isTrue();
        assertThat(dto.getSourceUrl()).isEqualTo("https://example.com/datasheet");
    }

    @Test
    void upsertNonMirrorTypeDoesNotTouchSolution() {
        Solution card = solution(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        // battery_type - текстовая характеристика вне девяти зеркальных
        when(characteristicTypeRepository.findById(5L))
                .thenReturn(Optional.of(type(5L, "battery_type", "Тип батареи",
                        "text")));
        when(characteristicRepository.findBySolutionIdAndCharacteristicTypeId(42L, 5L))
                .thenReturn(Optional.empty());
        when(characteristicRepository.save(any(SolutionCharacteristic.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.upsert(42L, 5L, AdminCharacteristicUpsertRequest.builder()
                .valueText("Li-ion").build());

        verify(solutionRepository, org.mockito.Mockito.never()).save(any());
        assertThat(card.getPayloadKg()).isNull();
    }

    @Test
    void deleteMissingValueNotFound() {
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(solution(42L)));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));
        when(characteristicRepository.findBySolutionIdAndCharacteristicTypeId(42L, 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(42L, 1L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("нет значения характеристики");
    }

    @Test
    void deleteClearsMirrorColumn() {
        Solution card = solution(42L);
        card.setPayloadKg(new BigDecimal("1500"));
        SolutionCharacteristic value = SolutionCharacteristic.builder()
                .solutionId(42L).characteristicTypeId(1L)
                .valueNumeric(new BigDecimal("1500")).build();
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));
        when(characteristicRepository.findBySolutionIdAndCharacteristicTypeId(42L, 1L))
                .thenReturn(Optional.of(value));

        service.delete(42L, 1L);

        verify(characteristicRepository).delete(value);
        assertThat(card.getPayloadKg()).isNull();
        verify(solutionRepository).save(card);
    }

    @Test
    void upsertUpdatesExistingValue() {
        Solution card = solution(42L);
        when(solutionRepository.findById(42L)).thenReturn(Optional.of(card));
        when(characteristicTypeRepository.findById(1L))
                .thenReturn(Optional.of(type(1L, "payload_kg", "Грузоподъёмность",
                        "number")));
        SolutionCharacteristic existing = SolutionCharacteristic.builder()
                .solutionId(42L).characteristicTypeId(1L)
                .valueNumeric(new BigDecimal("100"))
                .sourceKind("open_source").isConfirmed(true).build();
        existing.setId(55L);
        when(characteristicRepository.findBySolutionIdAndCharacteristicTypeId(42L, 1L))
                .thenReturn(Optional.of(existing));
        when(characteristicRepository.save(any(SolutionCharacteristic.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SolutionCharacteristicDto dto = service.upsert(42L, 1L,
                AdminCharacteristicUpsertRequest.builder()
                        .valueNumeric(new BigDecimal("200")).build());

        // ручная правка перезаписывает провенанс: manual + не подтверждено
        assertThat(dto.getValueNumeric()).isEqualByComparingTo("200");
        assertThat(dto.getSourceKind()).isEqualTo("manual");
        assertThat(dto.getIsConfirmed()).isFalse();
        ArgumentCaptor<SolutionCharacteristic> captor =
                ArgumentCaptor.forClass(SolutionCharacteristic.class);
        verify(characteristicRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(55L);
    }
}
