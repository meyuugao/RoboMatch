package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SimulationResult;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий результатов имитации (data_model.md §10.11).
 *
 * <p>ИЗОЛЯЦИЯ: все выборки по проекту -
 * с join на scenario и project и фильтром по пользователю; чужие
 * строки не выбираются вовсе (404, не 403).
 */
public interface SimulationResultRepository
        extends JpaRepository<SimulationResult, Long> {

    /**
 * Последний результат сценария (любого статуса), свежий сверху.
 *
 * @param scenarioId идентификатор сценария
 * @return последний запуск или Optional.empty, если запусков не было
 */
    Optional<SimulationResult> findFirstByScenarioIdOrderByIdDesc(
            Long scenarioId);

    /**
 * Выполняющийся прямо сейчас запуск сценария (гонка повторного
 * запуска - 409; зависший running отсекается по startedAt извне).
 *
 * @param scenarioId идентификатор сценария
 * @param status статус запуска (running)
 * @return последний запуск с этим статусом или Optional.empty
 */
    Optional<SimulationResult> findFirstByScenarioIdAndStatusOrderByIdDesc(
            Long scenarioId, String status);

    /**
 * История имитаций сценария, свежие сверху.
 *
 * @param scenarioId идентификатор сценария
 * @return запуски сценария, упорядоченные по убыванию id
 */
    List<SimulationResult> findAllByScenarioIdOrderByIdDesc(Long scenarioId);

    /**
 * История имитаций ПРОЕКТА с изоляцией по пользователю: имитации
 * доступны только через родительский проект (§12).
 *
 * @param projectId идентификатор проекта
 * @param userId владелец проекта
 * @return имитации проекта, упорядоченные по убыванию id
 */
    @Query("""
            select sim from SimulationResult sim
            join Scenario sc on sc.id = sim.scenarioId
            join Project p on p.id = sc.projectId
            where p.id = :projectId and p.userId = :userId
            order by sim.id desc
            """)
    List<SimulationResult> findAllByProjectIdAndUserIdOrderByIdDesc(
            @Param("projectId") Long projectId,
            @Param("userId") Long userId);

    /**
 * Результат по id С проверкой владельца проекта (изоляция, 404).
 *
 * @param simulationId идентификатор результата имитации
 * @param projectId идентификатор проекта
 * @param userId владелец проекта
 * @return результат или Optional.empty, если не найден/чужой
 */
    @Query("""
            select sim from SimulationResult sim
            join Scenario sc on sc.id = sim.scenarioId
            join Project p on p.id = sc.projectId
            where sim.id = :simulationId and p.id = :projectId
              and p.userId = :userId
            """)
    Optional<SimulationResult> findByIdAndProjectIdAndUserId(
            @Param("simulationId") Long simulationId,
            @Param("projectId") Long projectId,
            @Param("userId") Long userId);
}
