package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SolutionTypeSubtypeMapping;
import me.yuugao.robomatch.domain.TypeSubtypeMappingId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий допустимых сочетаний тип-подтип (для /api/filters).
 */
@Repository
public interface SolutionTypeSubtypeMappingRepository
        extends JpaRepository<SolutionTypeSubtypeMapping, TypeSubtypeMappingId> {

    /**
 * Пары «тип-подтип» — запрет удаления типа/подтипа со ссылками.
 *
 * @param solutionTypeId идентификатор типа решения
 * @return true, если тип участвует в допустимых сочетаниях
 */
    boolean existsBySolutionTypeId(Long solutionTypeId);

    /**
 * Пары «тип-подтип» со стороны подтипа.
 *
 * @param solutionSubtypeId идентификатор подтипа решения
 * @return true, если подтип участвует в допустимых сочетаниях
 */
    boolean existsBySolutionSubtypeId(Long solutionSubtypeId);
}
