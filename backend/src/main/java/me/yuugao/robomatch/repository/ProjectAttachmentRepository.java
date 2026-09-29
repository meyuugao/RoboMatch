package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.ProjectAttachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий вложений проекта (project_attachment, data_model.md §10.4).
 * Выборка всегда через project_id — вложение доступно только через
 * свой проект (сквозное правило изоляции, data_model.md §12).
 */
public interface ProjectAttachmentRepository
        extends JpaRepository<ProjectAttachment, Long> {

    /**
 * Все вложения проекта (история загрузок).
 *
 * @param projectId идентификатор проекта
 * @return вложения проекта, свежие сверху (uploaded_at по убыванию)
 */
    List<ProjectAttachment> findAllByProjectIdOrderByUploadedAtDesc(Long projectId);

    /**
 * Одно вложение проекта (для удаления: и id, и проект должны сойтись).
 *
 * @param projectId идентификатор проекта
 * @param id идентификатор вложения
 * @return вложение или Optional.empty, если не найдено в этом проекте
 */
    Optional<ProjectAttachment> findByProjectIdAndId(Long projectId, Long id);
}
