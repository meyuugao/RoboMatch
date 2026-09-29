package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Выгрузка» - сгенерированный отчёт проекта (data_model.md
 * §10.12, таблица export из V1; миграция не нужна).
 *
 * <p>СОСТАВ ОТЧЁТА: параметры объекта, выбранные решения, состав
 * оборудования, расчёт экономики, ограничения, источники данных, дата
 * расчёта - всё берётся из последних расчётов сценариев (append-only,
 * §10.8) и текущих данных проекта; отчёт ГОТОВЫЕ метрики читает и не
 * пересчитывает (источник правды формул - economic_model.md).
 *
 * <p>ПОМЕТКА: каждый отчёт содержит «предварительная оценка,
 * требует верификации при обследовании объекта» - на титуле и в конце.
 *
 * <p>ХРАНЕНИЕ: файл - ExportStorage (data/exports/{projectId}/
 * {exportId}.{ext}); в БД - ОТНОСИТЕЛЬНЫЙ путь file_path и история
 * выгрузок. Удаление проекта - каскад строк схемой V1 + физическое
 * удаление каталога хранилищем.
 *
 * <p>ИЗОЛЯЦИЯ: доступ только через родительский проект
 * (data_model.md §12).
 */
@Entity
@Table(name = "export")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Export {

    /**
 * Первичный ключ; bigserial = identity.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Проект. FK -> project.id ON DELETE CASCADE (V1).
 */
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /**
 * Формат: pdf | xlsx | csv (CHECK V1).
 */
    @Column(nullable = false, columnDefinition = "text")
    private String format;

    /**
 * Относительный путь файла в хранилище ({projectId}/{id}.{ext}).
 */
    @Column(name = "file_path", nullable = false, columnDefinition = "text")
    private String filePath;

    /**
 * Момент генерации.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
 * Кто выгрузил. FK -> user.id (V1, без каскада).
 */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;
}
