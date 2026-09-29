package me.yuugao.robomatch.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Процесс» - справочник process (data_model.md, Часть 1).
 * <p>
 * Бизнес-процессы, к которым применимы решения («Внутрискладская
 * логистика» и др.); связь «решение - процесс» - через
 * solution_application (отрасль + процесс).
 * <p>
 * ВАЖНО: имя класса Process намеренно совпадает с таблицей; ссылка
 * на java.lang.Process исключена явными импортами этой сущности.
 */
@Entity
@Table(name = "process")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Process {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Технический код процесса (латиница snake_case). UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String code;

    /**
 * Русское название процесса. UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String name;

    /**
 * Активный ли процесс (справочник может содержать архивные записи).
 */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
}
