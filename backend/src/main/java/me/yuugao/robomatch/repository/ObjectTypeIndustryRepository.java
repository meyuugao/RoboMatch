package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ObjectTypeIndustry;
import me.yuugao.robomatch.domain.ObjectTypeIndustryId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Репозиторий маппинга «тип объекта → отрасли» (object_type_industry,
 * data_model.md §3.1). Шаг 1 подбора: отрасли объекта
 * (selection_algorithm.md §2).
 */
@Repository
public interface ObjectTypeIndustryRepository
        extends JpaRepository<ObjectTypeIndustry, ObjectTypeIndustryId> {

    /**
 * Отрасли типа объекта (шаг 1 подбора).
 *
 * @param objectTypeId идентификатор типа объекта
 * @return связи «тип объекта → отрасль» (порядок не гарантирован)
 */
    List<ObjectTypeIndustry> findByObjectTypeId(Long objectTypeId);

    /**
 * Ссылка «тип объекта x отрасль» - запрет удаления industry (FK).
 *
 * @param industryId идентификатор отрасли
 * @return true, если на отрасль есть связи из типов объектов
 */
    boolean existsByIndustryId(Long industryId);
}
