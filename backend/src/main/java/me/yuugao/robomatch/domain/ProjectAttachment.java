package me.yuugao.robomatch.domain;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Вложение проекта» — загруженный исходник параметров
 * (Excel/CSV по шаблону) и история загрузок
 * (data_model.md §10.4, таблица project_attachment уже в V1).
 * <p>
 * Важные решения:
 * - file_path — ОТНОСИТЕЛЬНЫЙ путь внутри хранилища
 * ({projectId}/{attachmentId}_{safeName}); абсолютный корень — env
 * UPLOAD_ROOT. Имя файла
 * пользователя НЕ участвует в построении пути напрямую — санитизация
 * в AttachmentStorage (защита от path traversal);
 * - FK project_id — Long-колонка; ON DELETE CASCADE в V1 (физическое
 * удаление файлов при удалении проекта — — делает сервис
 * поверх каскада БД);
 * - file_name хранит исходное имя для показа пользователю.
 */
@Entity
@Table(name = "project_attachment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectAttachment {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Проект-владелец. FK -> project.id ON DELETE CASCADE (V1).
 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
 * Имя файла при загрузке (как его назвал пользователь).
 */
    @Column(name = "file_name", nullable = false, columnDefinition = "text")
    private String fileName;

    /**
 * Относительный путь в хранилище: {projectId}/{id}_{safeName}.
 */
    @Column(name = "file_path", nullable = false, columnDefinition = "text")
    private String filePath;

    /**
 * MIME-тип из загрузки (xlsx / xls / csv — белый список в сервисе).
 */
    @Column(name = "mime_type", columnDefinition = "text")
    private String mimeType;

    /**
 * Размер в байтах (CHECK >= 0 в V1).
 */
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /**
 * Момент загрузки.
 */
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
}
