package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Текущее допущение проекта» — переопределение пользователем
 * глобального дефолта.
 *
 * <p>Дефолты НЕ дублируются (data_model.md §12): отсутствие строки =
 * дефолт из каталога допущений (assumptions.md §22–23, фиксировано в
 * AssumptionService). В расчёт эффективное значение попадает снимком
 * (calculation_assumption), провенанс допущения постоянен и хранится
 * в каталоге, а не здесь.
 */
@Entity
@Table(name = "project_assumption", uniqueConstraints = @UniqueConstraint(
        name = "project_assumption_project_id_name_key",
        columnNames = {"project_id", "name"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectAssumption {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Проект. FK -> project.id ON DELETE CASCADE (V5).
 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
 * Код допущения (k_load, tariff_rub_kwh, ...).
 */
    @Column(nullable = false, columnDefinition = "text")
    private String name;

    /**
 * Значение (строковое представление).
 */
    @Column(nullable = false, columnDefinition = "text")
    private String value;

    /**
 * Когда допущение последний раз изменено. Обновляется аудитом на каждый UPDATE.
 */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
