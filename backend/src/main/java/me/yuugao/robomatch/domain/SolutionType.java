package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Тип решения» - справочник solution_type (data_model.md, Часть 1).
 * <p>
 * Классификатор типов роботизированных решений (уточнения организатора–8.8:
 * мобильные роботы, AMR, FMR, робот-штабелёр и т.д.). solution_type_id в
 * карточке решения может быть NULL (в CSV организатора бывают пустые типы).
 */
@Entity
@Table(name = "solution_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolutionType {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Технический код типа (латиница snake_case). UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String code;

    /**
 * Русское название типа. UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String name;
}
