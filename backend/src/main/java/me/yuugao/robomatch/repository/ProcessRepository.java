package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Process;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий справочника процессов (фильтр каталога /api/filters).
 */
@Repository
public interface ProcessRepository extends JpaRepository<Process, Long> {

    /**
 * Все записи по возрастанию id (админка: снимок справочника).
 *
 * @return все процессы, упорядоченные по возрастанию id
 */
    java.util.List<Process> findAllByOrderByIdAsc();
}
