package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ParameterType;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Репозиторий справочника типов параметров (parameter_type).
 * <p>
 * Своих запросов нет: списку параметров проекта хватает унаследованного
 * findAllById — сервис грузит определения object_type_parameter одного
 * типа объекта, собирает с них parameter_type_id и одним IN-запросом
 * получает имена/единицы/типы (без N+1).
 */
public interface ParameterTypeRepository extends JpaRepository<ParameterType, Long> {
}
