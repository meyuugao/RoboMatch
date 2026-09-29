package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Solution;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий решений.
 * <p>
 * search(...) - единый JPQL-запрос списка каталога:
 * - все скалярные фильтры - паттерн «:param IS NULL OR ...» (не задан
 * параметр - условие не действует);
 * - отрасль/процесс - EXISTS по solution_application: проверяется ОДНА
 * строка применения (та же пара отрасль+процесс), а не два независимых
 * условия - семантика «применяется в отрасли X для процесса Y»;
 * - поиск q - LOWER(name) LIKE с ESCAPE: регистронезависимый поиск по
 * подстроке, вилдкарды %/_ в запросе пользователя экранируются
 * сервисом (иначе q="%" возвращал весь каталог).
 * Портируемо между PostgreSQL (прод) и H2 (интеграционные тесты).
 * План запроса: поскольку фильтруется LOWER(name), работает
 * функциональный GIN-индекс idx_solution_name_lower_trgm
 * по выражению lower(name) (миграция V3, gin_trgm_ops) - индекс по
 * «голому» name из V1 под это выражение не подходит;
 * - Pageable приносит сортировку; для nullable-колонок (trl, payload_kg)
 * сервис формирует JpaSort c COALESCE-сентинелом (NULL всегда в конец,
 * одинаково в PostgreSQL и H2) + вторичный ключ s.id.
 * <p>
 * findPriceTrlRanges - min/max цены и УГТ для словаря фильтров (/api/filters).
 */
@Repository
public interface SolutionRepository extends JpaRepository<Solution, Long> {

    /**
 * Пул решений по отраслям (шаг 2 подбора, selection_algorithm.md §3):
 * решения, у которых есть solution_application для одной из отраслей
 * объекта. DISTINCT - у решения может быть несколько применений
 * в одной отрасли (разные процессы).
 *
 * @param industryIds идентификаторы отраслей объекта
 * @return решения с применением хотя бы в одной из отраслей (без дублей)
 */
    @Query("""
            SELECT DISTINCT s FROM Solution s
            WHERE s.id IN (SELECT sa.solutionId FROM SolutionApplication sa
                           WHERE sa.industryId IN :industryIds)
            """)
    List<Solution> findAllByIndustryIds(@Param("industryIds") Collection<Long> industryIds);

    /**
 * Полнотекстовый (по подстроке) поиск с фильтрами и пагинацией.
 * Все параметры опциональны (null - фильтр не действует).
 * <p>
 * CAST(:q AS string): у q нет типизированного контекста сравнения
 * (только IS NULL и CONCAT), и Hibernate 7 биндит его как bytea -
 * PostgreSQL отвечает «function lower(bytea) does not exist»
 * (поймано e2e на живом PG; H2 молчаливо пропускал). Явный cast
 * даёт параметру тип text в любом диалекте.
 * <p>
 * ESCAPE '\' - символ экранирования LIKE; сервис экранирует
 * %, _ и сам backslash в значении q (SolutionService.escapeLike).
 *
 * @param q подстрока названия (null - фильтр не действует)
 * @param typeId идентификатор типа (null - фильтр не действует)
 * @param subtypeId идентификатор подтипа (null - фильтр не действует)
 * @param industryId идентификатор отрасли (null - фильтр не действует)
 * @param processId идентификатор процесса (null - фильтр не действует)
 * @param status статус решения (null - фильтр не действует)
 * @param trlMin минимальный УГТ (null - фильтр не действует)
 * @param trlMax максимальный УГТ (null - фильтр не действует)
 * @param priceMin минимальная цена, руб. (null - фильтр не действует)
 * @param priceMax максимальная цена, руб. (null - фильтр не действует)
 * @param payloadMin минимальная грузоподъёмность, кг (null - фильтр не действует)
 * @param pageable параметры страницы и сортировки
 * @return страница решений каталога по заданным фильтрам
 */
    @Query("""
            SELECT s FROM Solution s
            WHERE (CAST(:q AS string) IS NULL
                        OR LOWER(s.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\')
              AND (:typeId IS NULL OR s.solutionTypeId = :typeId)
              AND (:subtypeId IS NULL OR s.solutionSubtypeId = :subtypeId)
              AND (:status IS NULL OR s.status = :status)
              AND (:trlMin IS NULL OR s.trl >= :trlMin)
              AND (:trlMax IS NULL OR s.trl <= :trlMax)
              AND (:priceMin IS NULL OR s.priceRub >= :priceMin)
              AND (:priceMax IS NULL OR s.priceRub <= :priceMax)
              AND (:payloadMin IS NULL OR s.payloadKg >= :payloadMin)
              AND (
                    (:industryId IS NULL AND :processId IS NULL)
                    OR EXISTS (
                        SELECT 1 FROM SolutionApplication sa
                        WHERE sa.solutionId = s.id
                          AND (:industryId IS NULL OR sa.industryId = :industryId)
                          AND (:processId IS NULL OR sa.processId = :processId)
                    )
                  )
            """)
    Page<Solution> search(@Param("q") String q,
                          @Param("typeId") Long typeId,
                          @Param("subtypeId") Long subtypeId,
                          @Param("industryId") Long industryId,
                          @Param("processId") Long processId,
                          @Param("status") String status,
                          @Param("trlMin") Integer trlMin,
                          @Param("trlMax") Integer trlMax,
                          @Param("priceMin") BigDecimal priceMin,
                          @Param("priceMax") BigDecimal priceMax,
                          @Param("payloadMin") BigDecimal payloadMin,
                          Pageable pageable);

    /**
 * Диапазоны фактических цен и УГТ для UI-фильтров (min/max по каталогу).
 *
 * @return min/max цены (руб.) и УГТ по всем решениям каталога
 */
    @Query("""
            SELECT MIN(s.priceRub) AS minPrice, MAX(s.priceRub) AS maxPrice,
                   MIN(s.trl) AS minTrl, MAX(s.trl) AS maxTrl
            FROM Solution s
            """)
    CatalogRanges findPriceTrlRanges();

    /**
 * Поиск по UUID строки каталога организатора (дедуп при импорте).
 *
 * @param externalId UUID строки каталога
 * @return решение или Optional.empty, если UUID неизвестен
 */
    Optional<Solution> findByExternalId(UUID externalId);

    // --- Срез «Админка» --------------------------------------------------

    /**
 * Дубль названия у того же вендора - UNIQUE (vendor_id, name).
 *
 * @param vendorId идентификатор производителя
 * @param name название решения
 * @return существующее решение или Optional.empty
 */
    Optional<Solution> findByVendorIdAndName(Long vendorId, String name);

    /**
 * Ссылки из карточек решений (FK vendor_id) - для запрета удаления.
 *
 * @param vendorId идентификатор производителя
 * @return true, если есть карточки с этим производителем
 */
    boolean existsByVendorId(Long vendorId);

    /**
 * Ссылки из карточек решений (FK solution_type_id).
 *
 * @param solutionTypeId идентификатор типа решения
 * @return true, если есть карточки с этим типом
 */
    boolean existsBySolutionTypeId(Long solutionTypeId);

    /**
 * Ссылки из карточек решений (FK solution_subtype_id).
 *
 * @param solutionSubtypeId идентификатор подтипа решения
 * @return true, если есть карточки с этим подтипом
 */
    boolean existsBySolutionSubtypeId(Long solutionSubtypeId);

    /**
 * Ссылки из карточек решений (FK region_id).
 *
 * @param regionId идентификатор региона
 * @return true, если есть карточки с этим регионом
 */
    boolean existsByRegionId(Long regionId);

    /**
 * Список для админки: поиск по названию + фильтр «требует проверки»
 * (source_kind = 'manual' - внесено вручную и не подтверждено
 * источником). CAST - как в search: Hibernate 7 биндит
 * q как bytea без типизированного контекста.
 *
 * @param q подстрока названия (null - фильтр не действует)
 * @param sourceKind происхождение записи (null - фильтр не действует)
 * @param pageable параметры страницы и сортировки
 * @return страница решений каталога для админки
 */
    @Query("""
            SELECT s FROM Solution s
            WHERE (CAST(:q AS string) IS NULL
                        OR LOWER(s.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\')
              AND (:sourceKind IS NULL OR s.sourceKind = :sourceKind)
            """)
    Page<Solution> adminSearch(@Param("q") String q,
                               @Param("sourceKind") String sourceKind,
                               Pageable pageable);

    /**
 * Проекция диапазонов для /api/filters (типы повторяют колонки: trl - smallint/Short).
 */
    interface CatalogRanges {

        /**
 * Минимальная цена по каталогу, руб.
 *
 * @return минимальная цена либо null при пустом каталоге
 */
        BigDecimal getMinPrice();

        /**
 * Максимальная цена по каталогу, руб.
 *
 * @return максимальная цена либо null при пустом каталоге
 */
        BigDecimal getMaxPrice();

        /**
 * Минимальный УГТ по каталогу (1..9).
 *
 * @return минимальный УГТ либо null при пустом каталоге
 */
        Short getMinTrl();

        /**
 * Максимальный УГТ по каталогу (1..9).
 *
 * @return максимальный УГТ либо null при пустом каталоге
 */
        Short getMaxTrl();
    }
}
