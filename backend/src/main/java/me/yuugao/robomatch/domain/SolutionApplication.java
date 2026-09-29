package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Применение решения» — таблица solution_application
 * (data_model.md §2.2): связь «решение применяется в отрасли X для
 * процесса Y» с опциональной ценой предложения.
 * <p>
 * FK хранятся как Long (как в Solution) — read-only каталогу объекты
 * не нужны, а ленивые ассоциации — источник скрытых запросов.
 * <p>
 * Используется: фильтр каталога по отрасли/процессу (EXISTS в JPQL),
 * раздел «Применение» карточки решения, подбор.
 */
@Entity
@Table(name = "solution_application")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolutionApplication {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Решение. FK -> solution.id ON DELETE CASCADE; тройка (solution, отрасль, процесс) UNIQUE (V1).
 */
    @Column(name = "solution_id", nullable = false)
    private Long solutionId;

    /**
 * Отрасль применения. FK -> industry.id.
 */
    @Column(name = "industry_id", nullable = false)
    private Long industryId;

    /**
 * Процесс применения. FK -> process.id.
 */
    @Column(name = "process_id", nullable = false)
    private Long processId;

    /**
 * Цена предложения для этой пары отрасль+процесс, руб. (может отличаться).
 */
    @Column(name = "offer_price_rub", precision = 15, scale = 2)
    private BigDecimal offerPriceRub;

    /**
 * Когда создана запись. Заполняется аудитом Spring Data.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
