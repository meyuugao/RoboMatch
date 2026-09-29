package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Маппинг тип объекта → отрасли» — object_type_industry
 * (data_model.md §3.1, таблица из V1; 7 связей из seed, assumptions.md
 * §1). Срез «Подбор» начинает использовать: шаг 1 алгоритма — определить
 * отрасли объекта (selection_algorithm.md §2).
 * <p>
 * PK (object_type_id, industry_id) — @IdClass (паттерн
 * SolutionCaseLinkId); таблица read-only для приложения (наполняет seed).
 */
@Entity
@Table(name = "object_type_industry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(ObjectTypeIndustryId.class)
public class ObjectTypeIndustry {

    /**
 * Тип объекта (склад/аэропорт/медучреждение). FK -> object_type.id.
 */
    @Id
    @Column(name = "object_type_id", nullable = false)
    private Long objectTypeId;

    /**
 * Отрасль каталога. FK -> industry.id.
 */
    @Id
    @Column(name = "industry_id", nullable = false)
    private Long industryId;

    /**
 * Обоснование связи (assumptions.md §1). NOT NULL в V1.
 */
    @Column(name = "source_note", nullable = false, columnDefinition = "text")
    private String sourceNote;
}
