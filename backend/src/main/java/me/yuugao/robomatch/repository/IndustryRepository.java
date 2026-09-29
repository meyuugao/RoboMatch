package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Industry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий справочника отраслей (фильтр каталога /api/filters).
 */
@Repository
public interface IndustryRepository extends JpaRepository<Industry, Long> {

    /**
 * Все записи по возрастанию id (админка: снимок справочника).
 *
 * @return все отрасли, упорядоченные по возрастанию id
 */
    java.util.List<Industry> findAllByOrderByIdAsc();
}
