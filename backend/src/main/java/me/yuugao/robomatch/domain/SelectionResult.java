package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Результат подбора» - итог сравнения решения с обязательными
 * ТТХ и ограничениями объекта (data_model.md §10.7, таблица
 * selection_result из V1).
 * <p>
 * UNIQUE (project_id, solution_id) - на нём построен UPSERT перезапуска
 * подбора: повторный run обновляет статусы/причины/ранги, несостоявшиеся
 * пары удаляются (инвариант §10.7). rank - только у fit (место в
 * объяснимом ранжировании, 1 - лучший); Score и вклад критериев НЕ
 * хранятся - снимки переменного состава считаются на лету (data_model.md
 * §12, алгоритм - selection_algorithm.md §6).
 */
@Entity
@Table(name = "selection_result", uniqueConstraints = @UniqueConstraint(
        name = "selection_result_project_id_solution_id_key",
        columnNames = {"project_id", "solution_id"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SelectionResult {

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
 * Решение. FK -> solution.id (без каскада, V1).
 */
    @Column(name = "solution_id", nullable = false)
    private Long solutionId;

    /**
 * fit / needs_check / excluded (SelectionStatus через конвертер).
 */
    @Convert(converter = SelectionStatusConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private SelectionStatus status;

    /**
 * Объяснение: нарушенные ТТХ (excluded) / недостающие данные (needs_check).
 */
    @Column(columnDefinition = "text")
    private String reason;

    /**
 * Место в ранжировании fit-решений (1 - лучший); NULL для остальных.
 */
    @Column
    private Integer rank;

    /**
 * Момент записи результата (установлен последним запуском подбора). Заполняется аудитом.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
