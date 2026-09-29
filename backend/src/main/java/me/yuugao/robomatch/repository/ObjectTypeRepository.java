package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ObjectType;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Репозиторий справочника типов объектов (Часть 1). Read-only:
 * наполнение — seed из XLSX организатора, CRUD — админка.
 */
public interface ObjectTypeRepository extends JpaRepository<ObjectType, Long> {

    /**
 * Тип объекта по коду (warehouse/airport/hospital).
 *
 * @param code технический код типа объекта
 * @return тип объекта или Optional.empty, если код неизвестен
 */
    Optional<ObjectType> findByCode(String code);
}
