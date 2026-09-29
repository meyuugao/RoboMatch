package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SolutionType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий справочника типов решений (фильтр + имена в DTO).
 */
@Repository
public interface SolutionTypeRepository extends JpaRepository<SolutionType, Long> {

    /**
 * Все записи по возрастанию id (админка: снимок справочника).
 *
 * @return все типы решений, упорядоченные по возрастанию id
 */
    java.util.List<SolutionType> findAllByOrderByIdAsc();
}
