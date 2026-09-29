package me.yuugao.robomatch.domain;

import java.io.Serializable;

/**
 * Составной ключ object_type_industry (object_type_id, industry_id) -
 * класс для @IdClass (паттерн SolutionCaseLinkId; PK без суррогата в V1).
 */
public class ObjectTypeIndustryId implements Serializable {

    /**
 * Тип объекта. FK -> object_type.id; часть составного PK.
 */
    private Long objectTypeId;

    /**
 * Отрасль. FK -> industry.id; часть составного PK.
 */
    private Long industryId;

    /**
 * Конструктор без аргументов - требуется JPA для @IdClass.
 */
    public ObjectTypeIndustryId() {
    }

    /**
 * Полный конструктор ключа.
 *
 * @param objectTypeId идентификатор типа объекта
 * @param industryId идентификатор отрасли
 */
    public ObjectTypeIndustryId(Long objectTypeId, Long industryId) {
        this.objectTypeId = objectTypeId;
        this.industryId = industryId;
    }

    /**
 * Равенство по обоим компонентам ключа (контракт @IdClass).
 *
 * @param other объект для сравнения
 * @return true, если оба компонента совпадают
 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ObjectTypeIndustryId id)) {
            return false;
        }
        return objectTypeId != null && objectTypeId.equals(id.objectTypeId)
                && industryId != null && industryId.equals(id.industryId);
    }

    /**
 * Хеш по обоим компонентам - согласован с equals (контракт @IdClass).
 *
 * @return хеш-код ключа
 */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(objectTypeId, industryId);
    }
}
