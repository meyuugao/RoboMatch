package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Тип характеристики» - справочник characteristic_type
 * (data_model.md §2.3): метаданные EAV-модели ТТХ.
 * <p>
 * Именно через метаданные (а не хардкод полей) описывается состав
 * характеристик - принцип
 * пишет значения в solution_characteristic со ссылкой на этот справочник.
 */
@Entity
@Table(name = "characteristic_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CharacteristicType {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Технический код характеристики (латиница snake_case: payload_kg, ...). UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String code;

    /**
 * Человекочитаемое название (для UI карточки).
 */
    @Column(nullable = false)
    private String name;

    /**
 * identification | technical | infrastructure | economic | applicability | data_quality.
 */
    @Column(name = "group_code", nullable = false, length = 32)
    private String groupCode;

    /**
 * number | text | boolean | date.
 */
    @Column(name = "data_type", nullable = false, length = 16)
    private String dataType;

    /**
 * Единица измерения (кг, мм, кВт...) или NULL для безразмерных.
 */
    @Column(length = 32)
    private String unit;

    /**
 * Участвует ли характеристика в фильтрах каталога (true у 9 зеркальных ТТХ).
 */
    @Column(name = "is_filterable", nullable = false)
    private Boolean isFilterable;

    /**
 * Обязательность дозаполнения ТТХ карточки (true у 8 ключевых ТТХ, уточнения организатора).
 */
    @Column(name = "is_required", nullable = false)
    private Boolean isRequired;

    /**
 * Порядок вывода в UI (внутри группы).
 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
