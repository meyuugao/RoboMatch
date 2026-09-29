package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Ручная корректировка» (data_model.md §10.10, таблица
 * manual_adjustment из V1;
 * доступны для ручной корректировки с фиксацией изменения).
 *
 * <p>Фиксация «что / было / стало / почему / кто / когда»: metric_name,
 * original_value, new_value, reason, author_user_id, created_at.
 * Корректировка порождает НОВЫЙ расчёт (append-only, §10.8); эта строка
 * хранит сам факт вмешательства и привязана к ИСХОДНОМУ расчёту.
 */
@Entity
@Table(name = "manual_adjustment")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualAdjustment {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Скорректированный расчёт. FK -> calculation.id ON DELETE CASCADE.
 */
    @Column(name = "calculation_id", nullable = false)
    private Long calculationId;

    /**
 * Какая метрика скорректирована (код из CalculationMetrics).
 */
    @Column(name = "metric_name", nullable = false, columnDefinition = "text")
    private String metricName;

    /**
 * Значение до корректировки (null - метрика не была рассчитана).
 */
    @Column(name = "original_value", precision = 18, scale = 4)
    private BigDecimal originalValue;

    /**
 * Значение после (обязательно).
 */
    @Column(name = "new_value", precision = 18, scale = 4, nullable = false)
    private BigDecimal newValue;

    /**
 * Причина - обязательна.
 */
    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    /**
 * Автор - из JWT (изоляция: авторство фиксируется сервером).
 */
    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    /**
 * Когда внесена корректировка. Заполняется аудитом Spring Data.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
