package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ObjectTypeParameter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Репозиторий определений параметров типа объекта (object_type_parameter,
 * data_model.md §3.2). Read-only: состав параметров — справочные данные,
 * наполняется seed-ом; их CRUD — через админку.
 */
public interface ObjectTypeParameterRepository
        extends JpaRepository<ObjectTypeParameter, Long> {

    /**
 * Все определения параметров одного типа объекта — основа формы и
 * шаблона импорта. Сортировка groupName asc, id asc: секции «▌»
 * датасета идут алфавитом, внутри секции — порядок seed (стабильный
 * порядок полей формы между запросами).
 *
 * @param objectTypeId идентификатор типа объекта
 * @return определения параметров типа объекта (groupName asc, id asc)
 */
    List<ObjectTypeParameter> findAllByObjectTypeIdOrderByGroupNameAscIdAsc(
            Long objectTypeId);
}
