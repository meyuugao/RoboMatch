package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Кейс» — справочник solution_case (data_model.md §2.5):
 * реализованный проект внедрения. Связь с решениями M:N через
 * solution_case_link (сущность ниже).
 * <p>
 * Кейсы — критерий ранжирования в подборе (selection_algorithm.md §6.1,
 * «Бинарный» тип нормализации) и раздел карточки каталога.
 */
@Entity
@Table(name = "solution_case")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolutionCase {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Уникальный текст кейса. columnDefinition="text": кейсы организатора —
 * абзацы внедрения (до тысяч символов); varchar(255) по умолчанию
 * обрезался бы в H2-тестах (в PostgreSQL колонка и так text, V1).
 */
    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String name;

    /**
 * Описание кейса: что внедрено и результат.
 */
    @Column(columnDefinition = "text")
    private String description;

    /**
 * Ссылка на источник кейса.
 */
    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    /**
 * Дата актуальности источника.
 */
    @Column(name = "source_date")
    private LocalDate sourceDate;

    /**
 * Когда создана запись. Заполняется аудитом Spring Data.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
 * Когда последний раз изменена запись. Обновляется аудитом на каждый UPDATE.
 */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
