package me.yuugao.robomatch.domain;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Значение параметра проекта» - введённое пользователем
 * значение параметра своего объекта (data_model.md §10.3, таблица
 * project_parameter_value уже в V1 - миграция не нужна;
 * ручной ввод / 3.2.3 импорт).
 * <p>
 * Важные решения (единые с Project/Solution):
 * - FK project_id / object_type_parameter_id - Long-колонки без
 * ассоциаций @ManyToOne; UNIQUE (project_id, object_type_parameter_id)
 * отражён в @Table - один параметр = одно значение (UPSERT в сервисе);
 * - EAV-конвенция CHECK «ровно одно value_* NOT NULL»: заполняется
 * ровно та колонка, которая соответствует parameter_type.value_type;
 * - дефолты НЕ дублируются: строки создаются только для введённых
 * значений, дефолт читается из object_type_parameter (§12
 * data_model.md);
 * - source - enum через конвертер (manual/import, контракт CHECK V1);
 * - updated_at ставится сервисом явно в момент записи (бизнес-факт
 * последнего редактирования; NOT NULL DEFAULT now в схеме -
 * страховка при прямых INSERT мимо приложения);
 * - сущность не покидает сервисный слой - наружу ParameterDto.
 */
@Entity
@Table(name = "project_parameter_value", uniqueConstraints = @UniqueConstraint(
        name = "project_parameter_value_project_id_object_type_parameter_id_key",
        columnNames = {"project_id", "object_type_parameter_id"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectParameterValue {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Проект-владелец. FK -> project.id ON DELETE CASCADE (V1).
 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
 * Определение параметра. FK -> object_type_parameter.id (без CASCADE).
 */
    @Column(name = "object_type_parameter_id", nullable = false)
    private Long objectTypeParameterId;

    /**
 * Значение числового параметра (value_type=number).
 */
    @Column(name = "value_numeric", precision = 16, scale = 4)
    private BigDecimal valueNumeric;

    /**
 * Значение текстового параметра (value_type=text).
 */
    @Column(name = "value_text", columnDefinition = "text")
    private String valueText;

    /**
 * Значение логического параметра (value_type=boolean).
 */
    @Column(name = "value_bool")
    private Boolean valueBool;

    /**
 * Как попало значение: manual (форма) / import (Excel/CSV).
 */
    @Convert(converter = ParameterValueSourceConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private ParameterValueSource source;

    /**
 * Момент последнего изменения значения (ставит сервис).
 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
