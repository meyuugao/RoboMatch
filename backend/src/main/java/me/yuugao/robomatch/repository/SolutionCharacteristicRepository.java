package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.SolutionCharacteristic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий значений ТТХ (EAV solution_characteristic).
 * <p>
 * findBySolutionIdIn - батч для карточки и сравнения: все характеристики
 * набора решений одним запросом; метаданные типов - отдельным
 * findAllById по CharacteristicTypeRepository.
 */
@Repository
public interface SolutionCharacteristicRepository
        extends JpaRepository<SolutionCharacteristic, Long> {

    /**
 * Все характеристики одного решения (EAV-строки без метаданных).
 *
 * @param solutionId идентификатор решения
 * @return значения ТТХ решения (порядок не гарантирован)
 */
    List<SolutionCharacteristic> findBySolutionId(Long solutionId);

    /**
 * Батч-загрузка характеристик набора решений (карточка и сравнение без N+1).
 *
 * @param solutionIds коллекция идентификаторов решений
 * @return значения ТТХ всех решений набора
 */
    List<SolutionCharacteristic> findBySolutionIdIn(Collection<Long> solutionIds);

    /**
 * Значения ТТХ этого типа - запрет удаления типа характеристики.
 *
 * @param characteristicTypeId идентификатор типа характеристики
 * @return true, если есть значения этого типа
 */
    boolean existsByCharacteristicTypeId(Long characteristicTypeId);

    /**
 * Точечный upsert-ключ EAV (UNIQUE (solution_id, characteristic_type_id)).
 *
 * @param solutionId идентификатор решения
 * @param characteristicTypeId идентификатор типа характеристики
 * @return существующее значение или Optional.empty (INSERT)
 */
    Optional<SolutionCharacteristic> findBySolutionIdAndCharacteristicTypeId(
            Long solutionId, Long characteristicTypeId);
}
