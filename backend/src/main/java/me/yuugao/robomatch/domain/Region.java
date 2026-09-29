package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Регион» — справочник region (data_model.md, Часть 1).
 * <p>
 * Английский code + русское name — конвенция справочников
 * (assumptions.md §15). Используется каталогом: regionName в DTO.
 */
@Entity
@Table(name = "region")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Region {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Технический код (english snake_case), UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String code;

    /**
 * Русское название, UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String name;
}
