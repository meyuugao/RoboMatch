package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.CalculationAssumption;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Репозиторий снимков допущений расчёта (calculation_assumption,
 * data_model.md §10.9). Пишется пачкой при каждом расчёте.
 */
@Repository
public interface CalculationAssumptionRepository
        extends JpaRepository<CalculationAssumption, Long> {

    /**
 * Снимок конкретного расчёта (детальный просмотр).
 *
 * @param calculationId идентификатор расчёта
 * @return допущения расчёта в порядке записи (id по возрастанию)
 */
    List<CalculationAssumption> findAllByCalculationIdOrderByIdAsc(Long calculationId);
}
