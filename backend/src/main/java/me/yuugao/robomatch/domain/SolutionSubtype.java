package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Подтип решения» - справочник solution_subtype (data_model.md,
 * Часть 1). Связь с типами - M:N через solution_type_subtype_mapping
 * (сущность ниже): подтип может относиться к нескольким типам.
 */
@Entity
@Table(name = "solution_subtype")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolutionSubtype {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Технический код подтипа (латиница snake_case). UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String code;

    /**
 * Русское название подтипа. UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String name;
}
