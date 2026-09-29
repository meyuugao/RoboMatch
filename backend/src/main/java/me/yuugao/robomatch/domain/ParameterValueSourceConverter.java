package me.yuugao.robomatch.domain;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Конвертер источника значения параметра: enum &lt;-&gt; 'manual'/'import'
 * (нижний регистр, контракт CHECK схемы V1). Как ProjectStatusConverter.
 */
@Converter
public class ParameterValueSourceConverter
        implements AttributeConverter<ParameterValueSource, String> {

    /**
 * Enum -> колонка БД: имя константы в нижнем регистре; null передаётся как null.
 *
 * @param attribute значение enum (может быть null)
 * @return строка нижнего регистра для БД или null
 */
    @Override
    public String convertToDatabaseColumn(ParameterValueSource attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    /**
 * Колонка БД (нижний регистр) -> enum; null передаётся как null.
 *
 * @param dbData значение колонки БД (может быть null)
 * @return значение enum или null
 */
    @Override
    public ParameterValueSource convertToEntityAttribute(String dbData) {
        return dbData == null ? null
                : ParameterValueSource.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
