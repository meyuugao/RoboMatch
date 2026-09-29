package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SolutionCaseLink;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Репозиторий связей «решение - кейс» (solution_case_link, M:N).
 * <p>
 * findBySolutionIdIn - батч: пары (solutionId, caseId) набора решений,
 * по которым сервис догружает сами кейсы через findAllById.
 */
@Repository
public interface SolutionCaseLinkRepository extends JpaRepository<SolutionCaseLink, Long> {

    /**
 * Связи одного решения с кейсами (кейсы карточки решения).
 *
 * @param solutionId идентификатор решения
 * @return пары (solutionId, caseId) решения (порядок не гарантирован)
 */
    List<SolutionCaseLink> findBySolutionId(Long solutionId);

    /**
 * Батч-загрузка связей набора решений (карточка и сравнение без N+1).
 *
 * @param solutionIds коллекция идентификаторов решений
 * @return пары (solutionId, caseId) всех решений набора
 */
    List<SolutionCaseLink> findBySolutionIdIn(Collection<Long> solutionIds);
}
