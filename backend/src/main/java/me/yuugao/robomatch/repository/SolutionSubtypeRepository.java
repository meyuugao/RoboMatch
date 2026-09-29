package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SolutionSubtype;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий справочника подтипов решений (фильтр + имена в DTO).
 */
@Repository
public interface SolutionSubtypeRepository extends JpaRepository<SolutionSubtype, Long> {

    /**
 * Все записи по возрастанию id (админка: снимок справочника).
 *
 * @return все подтипы решений, упорядоченные по возрастанию id
 */
    java.util.List<SolutionSubtype> findAllByOrderByIdAsc();
}
