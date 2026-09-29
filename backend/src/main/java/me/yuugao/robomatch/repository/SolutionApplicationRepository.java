package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SolutionApplication;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Репозиторий применений решений (solution_application).
 * <p>
 * findBySolutionIdIn — батч для карточки и сравнения (до 10 решений
 * одним запросом, без N+1). Фильтр списка по отрасли/процессу ходит
 * через EXISTS-подзапрос в SolutionRepository.search.
 */
@Repository
public interface SolutionApplicationRepository
        extends JpaRepository<SolutionApplication, Long> {

    /**
 * Применения одного решения (раздел «Применение» карточки решения).
 *
 * @param solutionId идентификатор решения
 * @return строки применений решения (порядок не гарантирован)
 */
    List<SolutionApplication> findBySolutionId(Long solutionId);

    /**
 * Батч-загрузка применений набора решений (карточка и сравнение без N+1).
 *
 * @param solutionIds коллекция идентификаторов решений
 * @return строки применений всех решений набора
 */
    List<SolutionApplication> findBySolutionIdIn(Collection<Long> solutionIds);

    /**
 * Ссылки применений на отрасль — запрет удаления industry (FK).
 *
 * @param industryId идентификатор отрасли
 * @return true, если есть применения с этой отраслью
 */
    boolean existsByIndustryId(Long industryId);

    /**
 * Ссылки применений на процесс — запрет удаления process (FK).
 *
 * @param processId идентификатор процесса
 * @return true, если есть применения с этим процессом
 */
    boolean existsByProcessId(Long processId);
}
