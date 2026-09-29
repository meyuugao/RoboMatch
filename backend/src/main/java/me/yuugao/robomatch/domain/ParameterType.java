package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Справочник «Тип параметра» - имя, единица и тип значения параметра
 * объекта (data_model.md §4; 132 записи из seed, коды - английский
 * snake_case, assumptions.md §20).
 * <p>
 * Справочник Части 1: таблица существует в V1; JPA-сущность нужна
 * сборке метаданных параметров проекта
 * (object_type_parameter ссылается на неё по id, а имена/единицы/
 * типы пользователь видит в форме и шаблоне импорта).
 * Ссылка object_type_parameter.parameter_type_id - Long-колонка без
 * ассоциации @ManyToOne (единообразие с Solution/Project: имена
 * справочников сервис резолвит батчем, ленивые ассоциации - источник
 * скрытых запросов).
 */
@Entity
@Table(name = "parameter_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParameterType {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Технический код (латиница snake_case): total_warehouse_area, has_wms, ...
 */
    @Column(nullable = false, unique = true, length = 128)
    private String code;

    /**
 * Русское название («Общая площадь склада»).
 */
    @Column(nullable = false, columnDefinition = "text")
    private String name;

    /**
 * Единица измерения («кв. м», «шт», «руб.»; NULL для безразмерных).
 */
    @Column(columnDefinition = "text")
    private String unit;

    /**
 * Тип значения: number / boolean / text (CHECK схемы V1).
 */
    @Convert(converter = ParameterValueTypeConverter.class)
    @Column(name = "value_type", nullable = false, columnDefinition = "text")
    private ParameterValueType valueType;
}
