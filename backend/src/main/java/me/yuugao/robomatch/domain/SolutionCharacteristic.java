package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Характеристика решения» — EAV-таблица solution_characteristic
 * (data_model.md §2.4): значение ТТХ + обязательный провенанс
 * (source_kind, source_url, source_date, is_confirmed —).
 * <p>
 * Ровно одно из value_* заполнено — гарантирует CHECK в БД.
 * Записи создаёт seed (дозаполнение ТТХ); у 9 основных ТТХ
 * есть зеркальные колонки в solution (§2.3, «зеркальные ТТХ-колонки») — читать
 * карточке удобнее зеркала, EAV отдаёт полный состав ТТХ.
 */
@Entity
@Table(name = "solution_characteristic")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolutionCharacteristic {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Решение. FK -> solution.id ON DELETE CASCADE.
 */
    @Column(name = "solution_id", nullable = false)
    private Long solutionId;

    /**
 * Тип характеристики (метаданные EAV). FK -> characteristic_type.id; пара UNIQUE (V1).
 */
    @Column(name = "characteristic_type_id", nullable = false)
    private Long characteristicTypeId;

    /**
 * Значение числовой характеристики (data_type=number).
 */
    @Column(name = "value_numeric", precision = 14, scale = 4)
    private BigDecimal valueNumeric;

    /**
 * Значение текстовой характеристики (data_type=text).
 */
    @Column(name = "value_text")
    private String valueText;

    /**
 * Значение логической характеристики (data_type=boolean).
 */
    @Column(name = "value_bool")
    private Boolean valueBool;

    /**
 * Значение характеристики-даты (data_type=date).
 */
    @Column(name = "value_date")
    private LocalDate valueDate;

    /**
 * organizer_catalog | open_source | manual.
 */
    @Column(name = "source_kind", nullable = false, length = 32)
    private String sourceKind;

    /**
 * Ссылка на источник значения.
 */
    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    /**
 * Дата актуальности источника.
 */
    @Column(name = "source_date")
    private LocalDate sourceDate;

    /**
 * Признак подтверждённости характеристики.
 */
    @Column(name = "is_confirmed", nullable = false)
    private Boolean isConfirmed;

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
