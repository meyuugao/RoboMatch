package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Calculation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий расчётов (calculation, data_model.md §10.8). Append-only:
 * сервис только INSERT-ит новые строки, история не перезапывается.
 */
@Repository
public interface CalculationRepository extends JpaRepository<Calculation, Long> {

    /**
 * История расчётов сценария, свежие сверху (индекс V1 без сортировки).
 *
 * @param scenarioId идентификатор сценария
 * @return расчёты сценария (calculated_at по убыванию, затем id по убыванию)
 */
    List<Calculation> findAllByScenarioIdOrderByCalculatedAtDescIdDesc(Long scenarioId);

    /**
 * Последний расчёт сценария (сравнение берёт актуальный результат
 * каждого сценария). Id DESC - детерминизм при равных timestamp.
 *
 * @param scenarioId идентификатор сценария
 * @return последний расчёт или Optional.empty, если расчётов не было
 */
    Optional<Calculation> findFirstByScenarioIdOrderByCalculatedAtDescIdDesc(
            Long scenarioId);
}
