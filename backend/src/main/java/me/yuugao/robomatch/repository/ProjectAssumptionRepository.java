package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ProjectAssumption;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Репозиторий текущих переопределений допущений проекта
 * (project_assumption, миграция V5).
 */
@Repository
public interface ProjectAssumptionRepository
        extends JpaRepository<ProjectAssumption, Long> {

    /**
 * Все переопределения проекта (карта name -> value в сервисе).
 *
 * @param projectId идентификатор проекта
 * @return переопределения допущений проекта (порядок не гарантирован)
 */
    List<ProjectAssumption> findAllByProjectId(Long projectId);
}
