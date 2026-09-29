package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ProjectParameterValue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий значений параметров проекта (project_parameter_value,
 * data_model.md §10.3).
 * <p>
 * UNIQUE (project_id, object_type_parameter_id) в V1 — на нём построен
 * UPSERT сервиса: findByProjectIdAndObjectTypeParameterId, затем UPDATE
 * существующей строки или INSERT новой; гонка двух одновременных INSERT
 * ловится DataIntegrityViolationException и повторяется как UPDATE
 * (см. ProjectParameterService).
 */
public interface ProjectParameterValueRepository
        extends JpaRepository<ProjectParameterValue, Long> {

    /**
 * Все значения проекта (одним запросом — карта по otpId в сервисе).
 *
 * @param projectId идентификатор проекта
 * @return значения параметров проекта (порядок не гарантирован)
 */
    List<ProjectParameterValue> findAllByProjectId(Long projectId);

    /**
 * Значение одного параметра проекта (основа UPSERT).
 *
 * @param projectId идентификатор проекта
 * @param objectTypeParameterId идентификатор определения параметра
 * @return существующее значение или Optional.empty (нет строки — INSERT)
 */
    Optional<ProjectParameterValue> findByProjectIdAndObjectTypeParameterId(
            Long projectId, Long objectTypeParameterId);

    /**
 * Сброс значения (DELETE /parameters/{parameterId}). @Transactional
 * обязателен: derived-delete исполняет DML и без транзакции падает
 * TransactionRequiredException (сервис вызывает его вне tx —
 * однострочная операция, см. javadoc сервиса).
 *
 * @param projectId идентификатор проекта
 * @param objectTypeParameterId идентификатор определения параметра
 */
    @Transactional
    void deleteByProjectIdAndObjectTypeParameterId(
            Long projectId, Long objectTypeParameterId);
}
