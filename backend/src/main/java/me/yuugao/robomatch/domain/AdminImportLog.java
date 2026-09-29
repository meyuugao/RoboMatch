package me.yuugao.robomatch.domain;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «История импорта каталога» (миграция V7):
 * одна строка на каждую загрузку файла организатора.
 * <p>
 * Жизненный цикл: INSERT со status='running' на старте импорта ->
 * UPDATE на 'completed' (summary_json со счётчиками) или 'failed'.
 * Наличие строки 'running' = in-flight импорт (повторный запуск
 * отклоняется 409 - CatalogImporter).
 * <p>
 * summary_json хранит сериализованный CatalogImportSummaryDto; колонка
 * jsonb (PostgreSQL), в сущности - готовый JSON-текст.
 */
// без @EntityListeners - аудируемых полей нет
// (created_at/updated_at задаёт код/БД), слушатель был инертным
@Entity
@Table(name = "admin_import_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminImportLog {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Имя файла, как его назвал администратор при загрузке.
 */
    @Column(name = "file_name", nullable = false)
    private String fileName;

    /**
 * Абсолютный путь сохранённой копии в data/admin-imports/{id}.{ext}.
 */
    @Column(name = "file_path", nullable = false)
    private String filePath;

    /**
 * Размер файла в байтах (снимок на момент загрузки).
 */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /**
 * Когда импорт начат.
 */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /**
 * Когда импорт завершён (успех или ошибка); NULL у running.
 */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /**
 * running | completed | failed (CHECK в V7).
 */
    @Column(nullable = false, length = 16)
    private String status;

    /**
 * Кто загрузил: FK -> user.id (демо: admin).
 */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    /**
 * Счётчики импорта (CatalogImportSummaryDto в JSON); NULL у failed.
 */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "summary_json", columnDefinition = "jsonb")
    private String summaryJson;
}
