package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Scenario;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Репозиторий сценариев (data_model.md §10.5). Срез «Подбор»: bootstrap
 * трёх сценариев (base/purchase/raas) при первом запуске подбора.
 */
@Repository
public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    /**
 * Сценарии проекта в стабильном порядке (id — порядок создания).
 *
 * @param projectId идентификатор проекта
 * @return сценарии проекта, упорядоченные по возрастанию id
 */
    List<Scenario> findAllByProjectIdOrderByIdAsc(Long projectId);

    /**
 * Есть ли у проекта сценарии (bootstrap только при первом запуске).
 *
 * @param projectId идентификатор проекта
 * @return true, если у проекта есть хоть один сценарий
 */
    boolean existsByProjectId(Long projectId);
}
