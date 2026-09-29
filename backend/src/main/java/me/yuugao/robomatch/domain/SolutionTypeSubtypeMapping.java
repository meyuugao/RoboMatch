package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Допустимые сочетания тип-подтип» — solution_type_subtype_mapping
 * (data_model.md §2.7): M:N, какой подтип уместен для какого типа.
 * <p>
 * Используется /api/filters: UI фильтрует список подтипов
 * по выбранному типу, не предлагая бессмысленных пар.
 */
@Entity
@Table(name = "solution_type_subtype_mapping")
@IdClass(TypeSubtypeMappingId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolutionTypeSubtypeMapping {

    /**
 * Тип решения. FK -> solution_type.id ON DELETE CASCADE; часть составного PK.
 */
    @Id
    @Column(name = "solution_type_id", nullable = false)
    private Long solutionTypeId;

    /**
 * Подтип решения. FK -> solution_subtype.id ON DELETE CASCADE; часть составного PK.
 */
    @Id
    @Column(name = "solution_subtype_id", nullable = false)
    private Long solutionSubtypeId;

    /**
 * Комментарий об источнике сопоставления.
 */
    @Column(name = "source_note", columnDefinition = "text")
    private String sourceNote;
}
