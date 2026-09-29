package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Связь решение-кейс» — solution_case_link (data_model.md §2.6),
 * M:N. Составной PK (solution_id, case_id) — @IdClass.
 * <p>
 * Read-only: связи создаёт seed. Карточка каталога читает
 * кейсы решения через эту таблицу батчем (compare до 10 решений).
 */
@Entity
@Table(name = "solution_case_link")
@IdClass(SolutionCaseLinkId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolutionCaseLink {

    /**
 * Решение. FK -> solution.id ON DELETE CASCADE; часть составного PK.
 */
    @Id
    @Column(name = "solution_id", nullable = false)
    private Long solutionId;

    /**
 * Кейс. FK -> solution_case.id ON DELETE CASCADE; часть составного PK.
 */
    @Id
    @Column(name = "case_id", nullable = false)
    private Long caseId;
}
