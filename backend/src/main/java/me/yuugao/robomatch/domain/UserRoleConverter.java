package me.yuugao.robomatch.domain;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Конвертер роли: enum &lt;-&gt; строка БД в нижнем регистре.
 * <p>
 * EnumType.STRING сохранял бы имя enum как есть («USER»), а CHECK-ограничение
 * схемы V1 допускает только 'user'/'admin'/'guest' — регистр важен.
 * Конвертер держит контракт схемы, не трогая БД
 * не меняем без миграции).
 */
@Converter
public class UserRoleConverter implements AttributeConverter<UserRole, String> {

    /**
 * Enum -> колонка БД: имя константы в нижнем регистре; null передаётся как null.
 *
 * @param attribute значение enum (может быть null)
 * @return строка нижнего регистра для БД или null
 */
    @Override
    public String convertToDatabaseColumn(UserRole attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    /**
 * Колонка БД (нижний регистр) -> enum; null передаётся как null.
 *
 * @param dbData значение колонки БД (может быть null)
 * @return значение enum или null
 */
    @Override
    public UserRole convertToEntityAttribute(String dbData) {
        return dbData == null ? null : UserRole.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
