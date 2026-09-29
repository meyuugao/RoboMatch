package me.yuugao.robomatch.domain;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Конвертер статуса подбора: enum &lt;-&gt; 'fit'/'needs_check'/'excluded'
 * (нижний регистр, контракт CHECK схемы V1). Как ProjectStatusConverter.
 */
@Converter
public class SelectionStatusConverter implements AttributeConverter<SelectionStatus, String> {

    /**
 * Enum -> колонка БД: имя константы в нижнем регистре; null передаётся как null.
 *
 * @param attribute значение enum (может быть null)
 * @return строка нижнего регистра для БД или null
 */
    @Override
    public String convertToDatabaseColumn(SelectionStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    /**
 * Колонка БД (нижний регистр) -> enum; null передаётся как null.
 *
 * @param dbData значение колонки БД (может быть null)
 * @return значение enum или null
 */
    @Override
    public SelectionStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null
                : SelectionStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
