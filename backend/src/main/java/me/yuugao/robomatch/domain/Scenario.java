package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Сценарий расчёта» — вариант роботизации для сравнения
 * (data_model.md §10.5, таблица scenario из V1; миграция не нужна).
 * <p>
 *.4: базовый,
 * покупка, RaaS. Не менее трёх сценариев в проекте (по одному каждого
 * типа) — инвариант ПРИЛОЖЕНИЯ, а не БД: SelectionService.run создаёт
 * три сценария при первом запуске подбора; позднее могут быть и несколько
 * purchase с разным составом (data_model.md §10.5).
 * <p>
 * UNIQUE (project_id, name) — имена сценариев уникальны в рамках проекта;
 * гонка двух параллельных bootstrap-ов закрывается перехватом
 * DataIntegrityViolationException в сервисе (409, повтор безопасен).
 */
@Entity
@Table(name = "scenario", uniqueConstraints = @UniqueConstraint(
        name = "scenario_project_id_name_key", columnNames = {"project_id", "name"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Scenario {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Проект. FK -> project.id ON DELETE CASCADE (V1).
 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
 * Тип: base / purchase / raas (ScenarioType через конвертер).
 */
    @Convert(converter = ScenarioTypeConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private ScenarioType type;

    /**
 * Имя сценария (для пользователя).
 */
    @Column(nullable = false, columnDefinition = "text")
    private String name;

    /**
 * Когда создан сценарий. Заполняется аудитом Spring Data.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
 * Когда последний раз изменён. Обновляется аудитом на каждый UPDATE.
 */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
