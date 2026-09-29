package me.yuugao.robomatch.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Решение» — каталожная карточка продукта.
 * <p>
 * Источник правды: docs/data_model.md §2.1 и scripts/seed/schema.psql (таблица solution).
 * Поля — один в один со схемой БД, ничего сверх не выдумано:
 * 28 колонок, включая 9 материализованных ТТХ (payload_kg...noise_level_dba).
 * <p>
 * Важные решения:
 * - @Table(name = "solution"): имя таблицы в snake_case, как в PostgreSQL.
 * - @Column(name = "..."): имена колонок тоже snake_case — так schema.psql и DDL
 * не расходятся с JPA (Hibernate по умолчанию ищет camelCase).
 * - Ссылки (vendor_id, solution_type_id...) храним как Long, а не как @ManyToOne:
 * для read-only каталога JOIN'ы не нужны, а ленивые ассоциации — источник
 * скрытых запросов (N+1). Когда понадобятся объекты — заведём отдельные сущности.
 * - created_at / updated_at заполняет Spring Data Auditing (@CreatedDate/@LastModifiedDate),
 * включённый в JpaAuditingConfig.
 * - ddl-auto = validate: Hibernate только СВЕРЯЕТ сущность со схемой, схемой владеет Flyway.
 */
@Entity
@Table(name = "solution")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Solution {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * UUID строки каталога организатора (CSV) — защита от дублей при повторном импорте.
 */
    @Column(name = "external_id", unique = true)
    private UUID externalId;

    /**
 * Название продукта. NOT NULL.
 */
    @Column(nullable = false)
    private String name;

    /**
 * FK -> vendor.id. NOT NULL: у решения всегда есть производитель.
 */
    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    /**
 * Класс продукта: brs (робототехника) | bas (беспилотная авиация) | software.
 */
    @Column(name = "product_class", nullable = false, length = 16)
    private String productClass;

    /**
 * FK -> solution_type.id. NULL допустим (в CSV бывают пустые типы).
 */
    @Column(name = "solution_type_id")
    private Long solutionTypeId;

    /**
 * FK -> solution_subtype.id. NULL допустим.
 */
    @Column(name = "solution_subtype_id")
    private Long solutionSubtypeId;

    /**
 * FK -> region.id. NULL допустим.
 */
    @Column(name = "region_id")
    private Long regionId;

    /**
 * Статус: operation | piloting | rnd.
 */
    @Column(nullable = false, length = 16)
    private String status;

    /**
 * Описание продукта.
 */
    @Column(columnDefinition = "text")
    private String description;

    /**
 * Цена, руб. с НДС. numeric(15,2), NOT NULL.
 */
    @Column(name = "price_rub", nullable = false, precision = 15, scale = 2)
    private BigDecimal priceRub;

    /**
 * УГТ (TRL): 1..9. smallint в БД -> Short в Java (Integer бы не прошёл валидацию).
 */
    @Column(precision = 5)
    private Short trl;

    /**
 * Рыночный потенциал: 2.0..5.0.
 */
    @Column(name = "market_potential", precision = 3, scale = 1)
    private BigDecimal marketPotential;

    // --- 9 ТТХ: материализованная проекция EAV (solution_characteristic) ---

    /**
 * Грузоподъёмность, кг.
 */
    @Column(name = "payload_kg", precision = 12, scale = 3)
    private BigDecimal payloadKg;

    /**
 * Масса робота, кг.
 */
    @Column(name = "mass_kg", precision = 12, scale = 3)
    private BigDecimal massKg;

    /**
 * Длина, мм.
 */
    @Column(name = "length_mm", precision = 12, scale = 3)
    private BigDecimal lengthMm;

    /**
 * Ширина, мм.
 */
    @Column(name = "width_mm", precision = 12, scale = 3)
    private BigDecimal widthMm;

    /**
 * Высота, мм.
 */
    @Column(name = "height_mm", precision = 12, scale = 3)
    private BigDecimal heightMm;

    /**
 * Точность позиционирования, мм.
 */
    @Column(name = "positioning_accuracy_mm", precision = 12, scale = 3)
    private BigDecimal positioningAccuracyMm;

    /**
 * Скорость, м/с.
 */
    @Column(name = "speed_m_s", precision = 12, scale = 3)
    private BigDecimal speedMs;

    /**
 * Мощность зарядки, кВт.
 */
    @Column(name = "charging_power_kw", precision = 12, scale = 3)
    private BigDecimal chargingPowerKw;

    /**
 * Уровень шума, дБА.
 */
    @Column(name = "noise_level_dba", precision = 12, scale = 3)
    private BigDecimal noiseLevelDba;

    /**
 * Заполненность карточки, %: 0..100. smallint -> Short.
 */
    @Column(name = "completeness_pct", precision = 5)
    private Short completenessPct;

    /**
 * Происхождение записи: organizer_catalog | open_source | manual.
 */
    @Column(name = "source_kind", nullable = false, length = 32)
    private String sourceKind;

    /**
 * Ссылка на источник данных.
 */
    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    /**
 * Дата актуальности источника.
 */
    @Column(name = "source_date")
    private LocalDate sourceDate;

    /**
 * Когда создана запись. timestamptz. Заполняется аудитом Spring Data.
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
