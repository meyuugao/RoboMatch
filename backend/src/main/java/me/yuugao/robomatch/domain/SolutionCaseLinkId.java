package me.yuugao.robomatch.domain;

import java.io.Serializable;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Класс составного ключа solution_case_link (solution_id, case_id) —
 * обязателен для @IdClass: Serializable + equals/hashCode.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SolutionCaseLinkId implements Serializable {

    /**
 * Решение. FK -> solution.id; часть составного PK.
 */
    private Long solutionId;

    /**
 * Кейс. FK -> solution_case.id; часть составного PK.
 */
    private Long caseId;

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
        if (!(o instanceof SolutionCaseLinkId that)) {
            return false;
        }
        return Objects.equals(solutionId, that.solutionId)
                && Objects.equals(caseId, that.caseId);
    }

    /**
 * Хеш по обоим компонентам — согласован с equals (контракт @IdClass).
 *
 * @return хеш-код ключа
 */
    @Override
    public int hashCode() {
        return Objects.hash(solutionId, caseId);
    }
}
