package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Производитель» - справочник vendor (data_model.md, Часть 1).
 * <p>
 * Нужна каталогу: имя производителя в карточке и списке решений
 * вместо «голого» vendor_id. Read-only: записи создаёт seed, CRUD - админка.
 */
@Entity
@Table(name = "vendor")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vendor {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Название производителя. UNIQUE.
 */
    @Column(nullable = false, unique = true)
    private String name;

    /**
 * Когда создана запись. Заполняется аудитом Spring Data.
 */
    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
