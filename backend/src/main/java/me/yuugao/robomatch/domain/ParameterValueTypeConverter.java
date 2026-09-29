package me.yuugao.robomatch.domain;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Конвертер типа значения параметра: enum &lt;-&gt; строка БД в нижнем
 * регистре ('number'/'boolean'/'text').
 * <p>
 * Как ProjectStatusConverter: CHECK схемы V1 допускает только нижний
 * регистр, EnumType.STRING сохранил бы «NUMBER» и упал на вставке.
 * Конвертер держит контракт схемы.
 */
@Converter
public class ParameterValueTypeConverter
        implements AttributeConverter<ParameterValueType, String> {

    /**
 * Enum -> колонка БД: имя константы в нижнем регистре; null передаётся как null.
 *
 * @param attribute значение enum (может быть null)
 * @return строка нижнего регистра для БД или null
 */
    @Override
    public String convertToDatabaseColumn(ParameterValueType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    /**
 * Колонка БД (нижний регистр) -> enum; null передаётся как null.
 *
 * @param dbData значение колонки БД (может быть null)
 * @return значение enum или null
 */
    @Override
    public ParameterValueType convertToEntityAttribute(String dbData) {
        return dbData == null ? null
                : ParameterValueType.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
