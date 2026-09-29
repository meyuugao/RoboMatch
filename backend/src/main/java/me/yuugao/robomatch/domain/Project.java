package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Проект» - объект пользователя (склад/аэропорт/медучреждение)
 * с параметрами, сценариями и результатами.
 * <p>
 * Источник правды: docs/data_model.md §10.2 и V1__baseline_schema.sql
 * (таблица project). Миграция не требуется - таблица уже в V1.
 * <p>
 * Важные решения:
 * - ссылки user_id / object_type_id - Long-колонки без @ManyToOne, как у
 * Solution (чтение не нуждается в JOIN-ассоциациях, а ленивые
 * ассоциации - источник скрытых запросов); имена справочников сервис
 * резолвит батчем;
 * - UNIQUE (user_id, name) - имена проектов уникальны в рамках одного
 * пользователя (дубликат - 409 на уровне сервиса, гонка закрыта
 * ловушкой DataIntegrityViolationException);
 * - created_at / updated_at - Spring Data Auditing; updated_at меняется
 * при каждом UPDATE - на нём построена сортировка списка по умолчанию
 * (последние изменённые сверху);
 * - status - enum ProjectStatus через конвертер (нижний регистр в БД);
 * - сущность НИКОГДА не покидает сервисный слой - наружу ProjectDto
 *.
 */
@Entity
@Table(name = "project", uniqueConstraints = @UniqueConstraint(
        name = "project_user_id_name_key", columnNames = {"user_id", "name"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Владелец. FK -> user.id ON DELETE CASCADE (V1).
 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
 * Тип объекта (склад/аэропорт/медучреждение). FK -> object_type.id.
 */
    @Column(name = "object_type_id", nullable = false)
    private Long objectTypeId;

    /**
 * Имя проекта, уникально в рамках пользователя (UNIQUE в V1).
 */
    @Column(nullable = false, length = 128)
    private String name;

    /**
 * Свободное описание. NULL допустим.
 */
    @Column(columnDefinition = "text")
    private String description;

    /**
 * Жизненный цикл: draft/active/archived (assumptions.md §18).
 */
    @Convert(converter = ProjectStatusConverter.class)
    @Column(nullable = false)
    private ProjectStatus status;

    /**
 * Когда создан. Заполняется аудитом Spring Data.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
 * Когда последний раз изменён (любое поле). Меняется аудитом.
 */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
