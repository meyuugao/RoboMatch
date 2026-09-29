package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Отрасль» — справочник industry (data_model.md, Часть 1).
 * <p>
 * 9 значений из catalog_export_v4.csv. Используется фильтром
 * каталога по отрасли через solution_application.
 */
@Entity
@Table(name = "industry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Industry {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Технический код отрасли (латиница snake_case). UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String code;

    /**
 * Русское название отрасли. UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String name;
}
