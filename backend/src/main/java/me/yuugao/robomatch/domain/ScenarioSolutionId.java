package me.yuugao.robomatch.domain;

import java.io.Serializable;

/**
 * Составной ключ scenario_solution (scenario_id, solution_id) — @IdClass
 * (паттерн SolutionCaseLinkId/TypeSubtypeMappingId, V1: PK без суррогата).
 */
public class ScenarioSolutionId implements Serializable {

    /**
 * Сценарий. FK -> scenario.id; часть составного PK.
 */
    private Long scenarioId;

    /**
 * Решение. FK -> solution.id; часть составного PK.
 */
    private Long solutionId;

    /**
 * Конструктор без аргументов — требуется JPA для @IdClass.
 */
    public ScenarioSolutionId() {
    }

    /**
 * Полный конструктор ключа.
 *
 * @param scenarioId идентификатор сценария
 * @param solutionId идентификатор решения
 */
    public ScenarioSolutionId(Long scenarioId, Long solutionId) {
        this.scenarioId = scenarioId;
        this.solutionId = solutionId;
    }

    /**
 * Равенство по обоим компонентам ключа (контракт @IdClass).
 *
 * @param other объект для сравнения
 * @return true, если оба компонента совпадают
 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScenarioSolutionId id)) {
            return false;
        }
        return scenarioId != null && scenarioId.equals(id.scenarioId)
                && solutionId != null && solutionId.equals(id.solutionId);
    }

    /**
 * Хеш по обоим компонентам — согласован с equals (контракт @IdClass).
 *
 * @return хеш-код ключа
 */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(scenarioId, solutionId);
    }
}
