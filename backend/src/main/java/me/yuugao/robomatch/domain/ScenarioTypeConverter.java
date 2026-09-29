package me.yuugao.robomatch.domain;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Конвертер типа сценария: enum &lt;-&gt; 'base'/'purchase'/'raas'
 * (нижний регистр, контракт CHECK схемы V1). Как ProjectStatusConverter.
 */
@Converter
public class ScenarioTypeConverter implements AttributeConverter<ScenarioType, String> {

    /**
 * Enum -> колонка БД: имя константы в нижнем регистре; null передаётся как null.
 *
 * @param attribute значение enum (может быть null)
 * @return строка нижнего регистра для БД или null
 */
    @Override
    public String convertToDatabaseColumn(ScenarioType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    /**
 * Колонка БД (нижний регистр) -> enum; null передаётся как null.
 *
 * @param dbData значение колонки БД (может быть null)
 * @return значение enum или null
 */
    @Override
    public ScenarioType convertToEntityAttribute(String dbData) {
        return dbData == null ? null
                : ScenarioType.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
