package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Состав оборудования сценария» - решения, входящие в сценарий,
 * с количеством (data_model.md §10.6, таблица scenario_solution из V1).
 * <p>
 * Сюда попадает и результат подбора, и ручные добавления:
 * is_manual=true + заполненный manual_reason - «с предупреждением и
 * причиной»; заполняемость причины контролирует сервис
 * (ManualAddRequest.manualReason @NotBlank).
 * <p>
 * PK (scenario_id, solution_id) - @IdClass; FK на solution без CASCADE:
 * удаление решения из каталога не должно молча опустошать сценарии.
 */
@Entity
@Table(name = "scenario_solution")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(ScenarioSolutionId.class)
public class ScenarioSolution {

    /**
 * Сценарий. FK -> scenario.id ON DELETE CASCADE (V1).
 */
    @Id
    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    /**
 * Решение каталога. FK -> solution.id (без каскада, V1).
 */
    @Id
    @Column(name = "solution_id", nullable = false)
    private Long solutionId;

    /**
 * Требуемое количество единиц, минимум 1 (CHECK V1).
 */
    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    /**
 * Добавлено вручную пользователем.
 */
    @Column(name = "is_manual", nullable = false)
    @Builder.Default
    private Boolean isManual = false;

    /**
 * Причина ручного добавления - обязательна при is_manual=true.
 */
    @Column(name = "manual_reason", columnDefinition = "text")
    private String manualReason;

    /**
 * Когда позиция добавлена в сценарий. Заполняется аудитом Spring Data.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
