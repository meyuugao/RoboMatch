package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Region;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий справочника регионов (regionName в DTO каталога).
 */
@Repository
public interface RegionRepository extends JpaRepository<Region, Long> {

    /**
 * Все записи по возрастанию id (админка: снимок справочника).
 *
 * @return все регионы, упорядоченные по возрастанию id
 */
    java.util.List<Region> findAllByOrderByIdAsc();
}
