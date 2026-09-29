package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ScenarioSolution;
import me.yuugao.robomatch.domain.ScenarioSolutionId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий состава сценариев (scenario_solution, data_model.md §10.6).
 * Ручное добавление решений в сравнение и экономика
 * читают состав отсюда.
 */
@Repository
public interface ScenarioSolutionRepository
        extends JpaRepository<ScenarioSolution, ScenarioSolutionId> {

    /**
 * Решение в сценарии (дубликат ручного добавления — 409).
 *
 * @param scenarioId идентификатор сценария
 * @param solutionId идентификатор решения
 * @return строка состава или Optional.empty, если решения нет в сценарии
 */
    Optional<ScenarioSolution> findByScenarioIdAndSolutionId(Long scenarioId,
                                                             Long solutionId);

    /**
 * Состав сценария (экономика: CAPEX = Σ количество × цена).
 *
 * @param scenarioId идентификатор сценария
 * @return позиции состава сценария (порядок не гарантирован)
 */
    List<ScenarioSolution> findAllByScenarioId(Long scenarioId);

    /**
 * Замена состава сценария при правке (PUT /scenarios/{id}).
 *
 * @param scenarioId идентификатор сценария
 */
    @Transactional
    @Modifying
    void deleteAllByScenarioId(Long scenarioId);

    /**
 * Есть ли сценарии с этим решением (запрет удаления, data_model §11).
 *
 * @param solutionId идентификатор решения
 * @return true, если решение входит в какой-либо сценарий
 */
    boolean existsBySolutionId(Long solutionId);
}
