package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SelectionResult;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Репозиторий результатов подбора (selection_result, data_model.md §10.7).
 * UPSERT перезапуска — по UNIQUE (project_id, solution_id): существующие
 * пары обновляются, новые вставляются, несостоявшиеся удаляются
 * (deleteAllById по id несостоявшихся; пустой пул — deleteAllByProjectId).
 */
@Repository
public interface SelectionResultRepository extends JpaRepository<SelectionResult, Long> {

    /**
 * Результаты проекта (без JOIN: имена решений сервис резолвит батчем).
 *
 * @param projectId идентификатор проекта
 * @return результаты последнего запуска подбора (порядок не гарантирован)
 */
    List<SelectionResult> findAllByProjectId(Long projectId);

    /**
 * Полная очистка (пул подбора опустел).
 *
 * @param projectId идентификатор проекта
 */
    void deleteAllByProjectId(Long projectId);

    /**
 * Есть ли результаты подбора по этому решению (запрет удаления).
 *
 * @param solutionId идентификатор решения
 * @return true, если решение фигурирует в чьих-либо результатах подбора
 */
    boolean existsBySolutionId(Long solutionId);
}
