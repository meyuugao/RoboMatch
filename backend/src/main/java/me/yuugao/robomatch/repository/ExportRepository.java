package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.Export;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий выгрузок (export, data_model.md §10.12). История проекта
 * - свежие сверху; одиночная выгрузка - с изоляцией через join на
 * проект владельца (§12: доступ только через родительский проект).
 */
@Repository
public interface ExportRepository extends JpaRepository<Export, Long> {

    /**
 * История выгрузок проекта, свежие сверху (индекс V1 без сортировки).
 *
 * @param projectId идентификатор проекта
 * @return выгрузки проекта (created_at по убыванию, затем id по убыванию)
 */
    List<Export> findAllByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    /**
 * Выгрузка по id с проверкой владельца проекта (изоляция 404).
 *
 * @param exportId идентификатор выгрузки
 * @param projectId идентификатор проекта
 * @param userId владелец проекта
 * @return выгрузка или Optional.empty, если не найдена/чужая
 */
    @Query("select e from Export e join Project p on p.id = e.projectId "
            + "where e.id = :exportId and e.projectId = :projectId "
            + "and p.userId = :userId")
    Optional<Export> findByIdAndProjectIdAndUserId(@Param("exportId") Long exportId,
                                                   @Param("projectId") Long projectId,
                                                   @Param("userId") Long userId);
}
