package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ManualAdjustment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Репозиторий ручных корректировок (manual_adjustment, data_model.md
 * §10.10). История вмешательств — append-only.
 */
@Repository
public interface ManualAdjustmentRepository
        extends JpaRepository<ManualAdjustment, Long> {

    /**
 * Все корректировки исходного расчёта (порядок применения).
 *
 * @param calculationId идентификатор исходного расчёта
 * @return корректировки расчёта в порядке применения (id по возрастанию)
 */
    List<ManualAdjustment> findAllByCalculationIdOrderByIdAsc(Long calculationId);
}
