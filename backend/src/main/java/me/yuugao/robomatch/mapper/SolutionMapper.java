package me.yuugao.robomatch.mapper;

import me.yuugao.robomatch.domain.Solution;
import me.yuugao.robomatch.dto.*;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Преобразование сущность -> DTO каталога. Ручной маппер (без MapStruct):
 * каждое поле видно явно.
 * <p>
 * Две формы:
 * - toSummary - элемент списка: базовые поля + имена справочников
 * (CatalogNames). «Голых» vendor_id/type_id в ответе больше нет;
 * - toFull - карточка: summary-поля + провенанс записи + внешние
 * связи (ТТХ EAV, кейсы, применения), которые сервис собрал отдельно.
 * <p>
 * Правило каркаса сохраняется: контроллер НИКОГДА не возвращает
 * domain-сущность, только DTO.
 */
@Component
public class SolutionMapper {

    /**
 * Solution + имена справочников -> SolutionSummaryDto (элемент списка).
 *
 * @param solution сущность каталога (null -> null)
 * @param names имена справочников (vendor/тип/подтип/регион; null допустим)
 * @return DTO элемента списка каталога
 */
    public SolutionSummaryDto toSummary(Solution solution, CatalogNames names) {
        if (solution == null) {
            return null;
        }
        return SolutionSummaryDto.builder()
                .id(solution.getId())
                .name(solution.getName())
                .vendorName(names == null ? null : names.vendorName())
                .productClass(solution.getProductClass())
                .solutionTypeName(names == null ? null : names.solutionTypeName())
                .solutionSubtypeName(names == null ? null : names.solutionSubtypeName())
                .regionName(names == null ? null : names.regionName())
                .status(solution.getStatus())
                .description(solution.getDescription())
                .priceRub(solution.getPriceRub())
                .trl(solution.getTrl() == null ? null : solution.getTrl().intValue())
                .marketPotential(solution.getMarketPotential())
                .payloadKg(solution.getPayloadKg())
                .massKg(solution.getMassKg())
                .lengthMm(solution.getLengthMm())
                .widthMm(solution.getWidthMm())
                .heightMm(solution.getHeightMm())
                .positioningAccuracyMm(solution.getPositioningAccuracyMm())
                .speedMs(solution.getSpeedMs())
                .chargingPowerKw(solution.getChargingPowerKw())
                .noiseLevelDba(solution.getNoiseLevelDba())
                .completenessPct(solution.getCompletenessPct() == null
                        ? null : solution.getCompletenessPct().intValue())
                .sourceKind(solution.getSourceKind())
                .build();
    }

    /**
 * Solution + имена + связи -> SolutionFullDto (карточка и сравнение).
 *
 * @param solution сущность каталога (null -> null)
 * @param names имена справочников (null допустим)
 * @param characteristics ТТХ EAV (собраны сервисом)
 * @param cases кейсы решения
 * @param applications применения решения
 * @return DTO карточки решения
 */
    public SolutionFullDto toFull(Solution solution,
                                  CatalogNames names,
                                  List<SolutionCharacteristicDto> characteristics,
                                  List<SolutionCaseDto> cases,
                                  List<SolutionApplicationDto> applications) {
        if (solution == null) {
            return null;
        }
        return SolutionFullDto.builder()
                .id(solution.getId())
                .externalId(solution.getExternalId())
                .name(solution.getName())
                .vendorName(names == null ? null : names.vendorName())
                .productClass(solution.getProductClass())
                .solutionTypeName(names == null ? null : names.solutionTypeName())
                .solutionSubtypeName(names == null ? null : names.solutionSubtypeName())
                .regionName(names == null ? null : names.regionName())
                .status(solution.getStatus())
                .description(solution.getDescription())
                .priceRub(solution.getPriceRub())
                .trl(solution.getTrl() == null ? null : solution.getTrl().intValue())
                .marketPotential(solution.getMarketPotential())
                .payloadKg(solution.getPayloadKg())
                .massKg(solution.getMassKg())
                .lengthMm(solution.getLengthMm())
                .widthMm(solution.getWidthMm())
                .heightMm(solution.getHeightMm())
                .positioningAccuracyMm(solution.getPositioningAccuracyMm())
                .speedMs(solution.getSpeedMs())
                .chargingPowerKw(solution.getChargingPowerKw())
                .noiseLevelDba(solution.getNoiseLevelDba())
                .completenessPct(solution.getCompletenessPct() == null
                        ? null : solution.getCompletenessPct().intValue())
                .sourceKind(solution.getSourceKind())
                .sourceUrl(solution.getSourceUrl())
                .sourceDate(solution.getSourceDate())
                .characteristics(characteristics)
                .cases(cases)
                .applications(applications)
                .build();
    }
}
