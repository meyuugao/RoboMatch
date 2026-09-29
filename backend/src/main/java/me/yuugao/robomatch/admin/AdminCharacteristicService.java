package me.yuugao.robomatch.admin;

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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * EAV-значения ТТХ решения (solution_characteristic) - upsert/delete
 * из админки.
 * <p>
 * Типобезопасность (data_model.md §4): значение обязано соответствовать
 * data_type типа характеристики - ровно одно value_* не NULL и именно
 * того типа; иначе 400 по-русски.
 * <p>
 * Провенанс: manual (ввод из админки, is_confirmed=false) или
 * open_source (обязательны source_url + source_date, is_confirmed=true
 * - уточнение организатора: данные из открытых источников считаются подтверждёнными).
 * <p>
 * ЗЕРКАЛЬНЫЕ КОЛОНКИ: 9 технических ТТХ продублированы колонками в
 * solution (data_model.md §5.8 «запись идёт в оба места») - upsert
 * синхронизирует зеркало значением, удаление - обнуляет (порт
 * solution_characteristic_repo.py; NULL чужих значений не затирает).
 */
@Service
public class AdminCharacteristicService {

    /**
 * Код типа -> сеттер зеркальной колонки solution (9 ТТХ, §5.8).
 * Package-private: переиспользуется импортом каталога (CatalogImporter)
 * для синхронизации зеркал колонок ТТХ расширенного формата.
 */
    static final Map<String, BiConsumer<Solution, BigDecimal>> MIRROR_SETTERS =
            Map.of(
                    "payload_kg", (Solution s, BigDecimal v) -> s.setPayloadKg(v),
                    "mass_kg", (Solution s, BigDecimal v) -> s.setMassKg(v),
                    "length_mm", (Solution s, BigDecimal v) -> s.setLengthMm(v),
                    "width_mm", (Solution s, BigDecimal v) -> s.setWidthMm(v),
                    "height_mm", (Solution s, BigDecimal v) -> s.setHeightMm(v),
                    "positioning_accuracy_mm",
                    (Solution s, BigDecimal v) -> s.setPositioningAccuracyMm(v),
                    "speed_m_s", (Solution s, BigDecimal v) -> s.setSpeedMs(v),
                    "charging_power_kw",
                    (Solution s, BigDecimal v) -> s.setChargingPowerKw(v),
                    "noise_level_dba", (Solution s, BigDecimal v) -> s.setNoiseLevelDba(v));

    private final SolutionRepository solutionRepository;
    private final CharacteristicTypeRepository characteristicTypeRepository;
    private final SolutionCharacteristicRepository characteristicRepository;

    /**
 * Конструктор с зависимостями (Spring DI).
 *
 * @param solutionRepository решений каталога (зеркала ТТХ)
 * @param characteristicTypeRepository типов ТТХ
 * @param characteristicRepository значений ТТХ (EAV)
 */
    public AdminCharacteristicService(SolutionRepository solutionRepository,
                                      CharacteristicTypeRepository characteristicTypeRepository,
                                      SolutionCharacteristicRepository characteristicRepository) {
        this.solutionRepository = solutionRepository;
        this.characteristicTypeRepository = characteristicTypeRepository;
        this.characteristicRepository = characteristicRepository;
    }

    /**
 * Значение соответствует data_type типа; ровно одно value_*.
 */
    private static void validateValueMatchesType(CharacteristicType type,
                                                 AdminCharacteristicUpsertRequest request) {
        int filled = countNonNull(request.getValueNumeric(), request.getValueText(),
                request.getValueBool(), request.getValueDate());
        if (filled == 0) {
            throw new BadRequestException("Передайте значение: тип характеристики «"
                    + type.getName() + "» ожидает "
                    + dataTypeLabel(type.getDataType()));
        }
        if (filled > 1) {
            throw new BadRequestException("Передано несколько значений - тип «"
                    + type.getName() + "» ожидает одно значение ("
                    + dataTypeLabel(type.getDataType()) + ")");
        }
        boolean matches = switch (type.getDataType()) {
            case "number" -> request.getValueNumeric() != null;
            case "text" -> request.getValueText() != null;
            case "boolean" -> request.getValueBool() != null;
            case "date" -> request.getValueDate() != null;
            default -> false;
        };
        if (!matches) {
            throw new BadRequestException("Тип характеристики «" + type.getName()
                    + "» ожидает " + dataTypeLabel(type.getDataType())
                    + " - передано значение другого типа");
        }
    }

    private static String dataTypeLabel(String dataType) {
        return switch (dataType) {
            case "number" -> "число (valueNumeric)";
            case "text" -> "текст (valueText)";
            case "boolean" -> "да/нет (valueBool)";
            case "date" -> "дату (valueDate)";
            default -> "значение типа " + dataType;
        };
    }

    private static int countNonNull(Object... values) {
        int count = 0;
        for (Object v : values) {
            if (v != null) {
                count++;
            }
        }
        return count;
    }

    private static SolutionCharacteristicDto toDto(SolutionCharacteristic value,
                                                   CharacteristicType type) {
        return SolutionCharacteristicDto.builder()
                .solutionId(value.getSolutionId())
                .typeCode(type.getCode())
                .typeName(type.getName())
                .groupCode(type.getGroupCode())
                .unit(type.getUnit())
                .dataType(type.getDataType())
                .valueNumeric(value.getValueNumeric())
                .valueText(value.getValueText())
                .valueBool(value.getValueBool())
                .valueDate(value.getValueDate())
                .sourceKind(value.getSourceKind())
                .sourceUrl(value.getSourceUrl())
                .sourceDate(value.getSourceDate())
                .isConfirmed(value.getIsConfirmed())
                .build();
    }

    /**
 * Upsert значения ТТХ решения (200) с провенансом и зеркалом.
 *
 * @param solutionId идентификатор решения
 * @param characteristicTypeId идентификатор типа ТТХ
 * @param request значение (по data_type) + провенанс
 * @return сохранённое значение ТТХ с провенансом
 */
    @Transactional
    public SolutionCharacteristicDto upsert(Long solutionId, Long characteristicTypeId,
                                            AdminCharacteristicUpsertRequest request) {
        Solution solution = solutionRepository.findById(solutionId)
                .orElseThrow(() -> new NotFoundException(
                        "Решение с id=" + solutionId + " не найдено"));
        CharacteristicType type = characteristicTypeRepository
                .findById(characteristicTypeId)
                .orElseThrow(() -> new NotFoundException(
                        "Тип характеристики с id=" + characteristicTypeId
                                + " не найден"));

        validateValueMatchesType(type, request);

        String sourceKind = request.getSourceKind() == null
                ? "manual" : request.getSourceKind();
        String sourceUrl = AdminTextUtil.normText(request.getSourceUrl());
        LocalDate sourceDate = request.getSourceDate();
        boolean confirmed;
        if ("open_source".equals(sourceKind)) {
            if (sourceUrl == null || sourceDate == null) {
                throw new BadRequestException("Для источника «открытые источники» "
                        + "обязательны ссылка (sourceUrl) и дата актуальности "
                        + "(sourceDate)");
            }
            confirmed = true;
        } else {
            confirmed = false; // ручной ввод не подтверждён источником
        }

        SolutionCharacteristic value = characteristicRepository
                .findBySolutionIdAndCharacteristicTypeId(solutionId,
                        characteristicTypeId)
                .orElseGet(() -> SolutionCharacteristic.builder()
                        .solutionId(solutionId)
                        .characteristicTypeId(characteristicTypeId)
                        .build());
        value.setValueNumeric(request.getValueNumeric());
        value.setValueText(AdminTextUtil.normText(request.getValueText()));
        value.setValueBool(request.getValueBool());
        value.setValueDate(request.getValueDate());
        value.setSourceKind(sourceKind);
        value.setSourceUrl(sourceUrl);
        value.setSourceDate(sourceDate);
        value.setIsConfirmed(confirmed);
        value = characteristicRepository.save(value);

        // зеркало: только числовые ТТХ из девяти материализованных (§5.8)
        BiConsumer<Solution, BigDecimal> mirror = MIRROR_SETTERS.get(type.getCode());
        if (mirror != null && request.getValueNumeric() != null) {
            mirror.accept(solution, request.getValueNumeric());
            solutionRepository.save(solution);
        }
        return toDto(value, type);
    }

    /**
 * Удаление значения ТТХ (204); зеркальная колонка обнуляется.
 *
 * @param solutionId идентификатор решения
 * @param characteristicTypeId идентификатор типа ТТХ
 */
    @Transactional
    public void delete(Long solutionId, Long characteristicTypeId) {
        Solution solution = solutionRepository.findById(solutionId)
                .orElseThrow(() -> new NotFoundException(
                        "Решение с id=" + solutionId + " не найдено"));
        CharacteristicType type = characteristicTypeRepository
                .findById(characteristicTypeId)
                .orElseThrow(() -> new NotFoundException(
                        "Тип характеристики с id=" + characteristicTypeId
                                + " не найден"));
        SolutionCharacteristic value = characteristicRepository
                .findBySolutionIdAndCharacteristicTypeId(solutionId,
                        characteristicTypeId)
                .orElseThrow(() -> new NotFoundException(
                        "У решения id=" + solutionId + " нет значения характеристики «"
                                + type.getName() + "»"));
        characteristicRepository.delete(value);
        BiConsumer<Solution, BigDecimal> mirror = MIRROR_SETTERS.get(type.getCode());
        if (mirror != null) {
            mirror.accept(solution, null);
            solutionRepository.save(solution);
        }
    }
}
