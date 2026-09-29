package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.CharacteristicType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий метаданных характеристик (characteristic_type).
 */
@Repository
public interface CharacteristicTypeRepository extends JpaRepository<CharacteristicType, Long> {

    /**
 * Все записи по возрастанию id (админка: снимок справочника).
 *
 * @return все типы характеристик, упорядоченные по возрастанию id
 */
    java.util.List<CharacteristicType> findAllByOrderByIdAsc();
}
