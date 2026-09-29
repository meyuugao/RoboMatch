package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Пользователь» — аккаунт платформы.
 * <p>
 * Источник правды: docs/data_model.md §10.1 и V1__baseline_schema.sql
 * (таблица «user»: id, login UNIQUE, password_hash, role, created_at).
 * Миграция не требуется — таблица уже в V1.
 * <p>
 * Важные решения:
 * - имя таблицы «user» — зарезервированное слово PostgreSQL, поэтому
 * в кавычках; Hibernate подставляет его как есть.
 * - role — enum UserRole через конвертер (нижний регистр в БД).
 * - created_at заполняет Spring Data Auditing (@CreatedDate), как у Solution.
 * - сущность НИКОГДА не покидает сервисный слой — наружу уходит UserDto
 *.
 */
@Entity
@Table(name = "\"user\"")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Логин, уникальный (UNIQUE в V1) — основа для subject в JWT.
 */
    @Column(nullable = false, unique = true, length = 64)
    private String login;

    /**
 * Хеш пароля (bcrypt, cost 12). Открытый пароль не хранится и не логируется.
 */
    @Column(name = "password_hash", nullable = false, columnDefinition = "text")
    private String passwordHash;

    /**
 * Роль: guest / user / admin (CHECK в V1, конвертер — UserRoleConverter).
 */
    @Convert(converter = UserRoleConverter.class)
    @Column(nullable = false)
    private UserRole role;

    /**
 * Когда создан аккаунт. Заполняется аудитом Spring Data.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
