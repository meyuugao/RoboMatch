package me.yuugao.robomatch.domain;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Конвертер статуса проекта: enum &lt;-&gt; строка БД в нижнем регистре.
 * <p>
 * Как и UserRoleConverter: CHECK схемы V1 допускает только
 * 'draft'/'active'/'archived' — EnumType.STRING сохранил бы «DRAFT»
 * и упал на вставке. Конвертер держит контракт схемы.
 */
@Converter
public class ProjectStatusConverter implements AttributeConverter<ProjectStatus, String> {

    /**
 * Enum -> колонка БД: имя константы в нижнем регистре; null передаётся как null.
 *
 * @param attribute значение enum (может быть null)
 * @return строка нижнего регистра для БД или null
 */
    @Override
    public String convertToDatabaseColumn(ProjectStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    /**
 * Колонка БД (нижний регистр) -> enum; null передаётся как null.
 *
 * @param dbData значение колонки БД (может быть null)
 * @return значение enum или null
 */
    @Override
    public ProjectStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ProjectStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
