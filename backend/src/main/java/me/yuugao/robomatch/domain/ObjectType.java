package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Тип объекта» - справочник Части 1 (склад/аэропорт/
 * медучреждение, data_model.md §4).
 * <p>
 * Существует в V1 (наполняется seed-ом из XLSX организатора);
 * JPA-сущность нужна связи project.object_type_id
 * (выбор типа объекта при создании
 * проекта, user-flow.md шаг 1) и списку типов в /api/filters.
 * <p>
 * is_calc_enabled - гейт расчёта/имитации по типу объекта (MVP: только
 * склад): отдаётся в карточке проекта и используется гейтом
 * расчёта/имитации.
 */
@Entity
@Table(name = "object_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ObjectType {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Технический код: warehouse / airport / hospital (assumptions.md §15).
 */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /**
 * Русское название («Склад», «Аэропорт», «Медицинское учреждение»).
 */
    @Column(nullable = false)
    private String name;

    /**
 * Разрешён ли расчёт экономики для этого типа объекта (MVP: склад).
 */
    @Column(name = "is_calc_enabled", nullable = false)
    private Boolean isCalcEnabled;

    /**
 * Паспорт данных (откуда параметры: seed организатора и т.п.).
 */
    @Column(name = "data_source_note", columnDefinition = "text")
    private String dataSourceNote;
}
