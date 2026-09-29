package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.AdminImportLog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий истории импортов каталога (V7).
 * <p>
 * findFirstByStatusOrderByStartedAtDesc — источник для «обновить каталог
 * по запросу»: POST /api/admin/catalog/refresh повторяет
 * импорт последнего файла.
 */
@Repository
public interface AdminImportRepository extends JpaRepository<AdminImportLog, Long> {

    /**
 * In-flight импорт (единица на всё приложение); пусто — можно запускать.
 *
 * @param status статус импорта (running)
 * @return последний импорт с этим статусом или Optional.empty
 */
    Optional<AdminImportLog> findFirstByStatusOrderByStartedAtDesc(String status);

    /**
 * Последний (по времени старта) импорт любого статуса — для refresh.
 *
 * @return последний импорт или Optional.empty, если импортов не было
 */
    Optional<AdminImportLog> findFirstByOrderByStartedAtDesc();

    /**
 * История с пагинацией (свежие сверху).
 *
 * @param pageable параметры страницы
 * @return записи истории импортов, свежие сверху (started_at по убыванию)
 */
    List<AdminImportLog> findAllByOrderByStartedAtDesc(Pageable pageable);
}
