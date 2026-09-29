package me.yuugao.robomatch.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.lang.Nullable;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Результат имитации» сценария (data_model.md §10.11, таблица
 * simulation_result из V1; миграция не нужна).
 *
 * <p>ЖИЗНЕННЫЙ ЦИКЛ: запуск пишет строку
 * status = running, по завершении расчёта - completed (kpi_json - снимок
 * KPI) или failed. Запуск/пауза/перезапуск/скорость - UI-режимы
 * (mvp_scope.md «Имитация»), в БД фиксируются только статусы исполнения.
 *
 * <p>KPI - снимок переменного состава (data_model.md §12: jsonb для
 * данных, состав которых зависит от типа объекта - здесь склад):
 * достижимость производительности, загрузка, простои, узкие места по
 * зонам, сверка с расчётом экономики, плюс служебное
 * поле exportUrl.
 *
 * <p>ИЗОЛЯЦИЯ: доступ только через родительский проект
 * (join на project по scenario, data_model.md §12).
 */
@Entity
@Table(name = "simulation_result")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimulationResult {

    /**
 * Первичный ключ; bigserial = identity.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Сценарий. FK -> scenario.id ON DELETE CASCADE (V1).
 */
    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    /**
 * Снимок KPI имитации (переменный состав, data_model.md §12).
 */
    @Nullable
    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "kpi_json", columnDefinition = "jsonb")
    private String kpiJson;

    /**
 * Начало выполнения (running).
 */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
 * Окончание (completed/failed); NULL у выполняющегося.
 */
    @Nullable
    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
 * Статус исполнения: running | completed | failed (CHECK V1).
 */
    @Column(name = "status", nullable = false)
    private String status;
}
