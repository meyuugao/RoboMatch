package me.yuugao.robomatch.domain;

import java.io.Serializable;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Класс составного ключа solution_type_subtype_mapping
 * (solution_type_id, solution_subtype_id) для @IdClass.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TypeSubtypeMappingId implements Serializable {

    /**
 * Тип решения. FK -> solution_type.id; часть составного PK.
 */
    private Long solutionTypeId;

    /**
 * Подтип решения. FK -> solution_subtype.id; часть составного PK.
 */
    private Long solutionSubtypeId;

    /**
 * Равенство по обоим компонентам ключа (контракт @IdClass).
 *
 * @param o объект для сравнения
 * @return true, если оба компонента совпадают
 */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TypeSubtypeMappingId that)) {
            return false;
        }
        return Objects.equals(solutionTypeId, that.solutionTypeId)
                && Objects.equals(solutionSubtypeId, that.solutionSubtypeId);
    }

    /**
 * Хеш по обоим компонентам - согласован с equals (контракт @IdClass).
 *
 * @return хеш-код ключа
 */
    @Override
    public int hashCode() {
        return Objects.hash(solutionTypeId, solutionSubtypeId);
    }
}
