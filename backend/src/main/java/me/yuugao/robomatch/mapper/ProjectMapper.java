package me.yuugao.robomatch.mapper;

import me.yuugao.robomatch.domain.ObjectType;
import me.yuugao.robomatch.domain.Project;
import me.yuugao.robomatch.dto.ProjectFullDto;
import me.yuugao.robomatch.dto.ProjectSummaryDto;

import org.springframework.stereotype.Component;

/**
 * Преобразование сущность -&gt; DTO проекта. Ручной маппер (как
 * SolutionMapper): каждое поле видно явно, domain-сущности наружу
 * не уходят.
 */
@Component
public class ProjectMapper {

    /**
 * Project + имя типа объекта -> элемент списка.
 *
 * @param project сущность проекта (null -> null)
 * @param objectType тип объекта проекта (null допустим)
 * @return DTO списка проектов
 */
    public ProjectSummaryDto toSummary(Project project, ObjectType objectType) {
        if (project == null) {
            return null;
        }
        return ProjectSummaryDto.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .objectTypeName(objectType == null ? null : objectType.getName())
                .status(project.getStatus() == null ? null
                        : project.getStatus().name().toLowerCase(java.util.Locale.ROOT))
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    /**
 * Project + тип объекта -> карточка (с кодом типа и гейтом расчёта).
 *
 * @param project сущность проекта (null -> null)
 * @param objectType тип объекта (имя/код/гейт расчёта; null допустим)
 * @return DTO карточки проекта
 */
    public ProjectFullDto toFull(Project project, ObjectType objectType) {
        if (project == null) {
            return null;
        }
        return ProjectFullDto.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .objectTypeId(project.getObjectTypeId())
                .objectTypeName(objectType == null ? null : objectType.getName())
                .objectTypeCode(objectType == null ? null : objectType.getCode())
                .objectTypeIsCalcEnabled(objectType == null ? null
                        : objectType.getIsCalcEnabled())
                .status(project.getStatus() == null ? null
                        : project.getStatus().name().toLowerCase(java.util.Locale.ROOT))
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
