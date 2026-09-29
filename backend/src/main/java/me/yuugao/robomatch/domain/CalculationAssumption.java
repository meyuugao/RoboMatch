package me.yuugao.robomatch.domain;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Допущение расчёта» - снимок допущений конкретного расчёта
 * (data_model.md §10.9, таблица calculation_assumption из V1).
 * Формат «что / значение / источник / влияние».
 *
 * <p>Заполняется ПРИ КАЖДОМ расчёте (append-only calculation → снимок
 * сюда): пользователь изменил допущение → новый расчёт с новым снимком,
 * старые снимки не меняются (воспроизведение).
 */
@Entity
@Table(name = "calculation_assumption", uniqueConstraints = @UniqueConstraint(
        name = "calculation_assumption_calculation_id_name_key",
        columnNames = {"calculation_id", "name"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalculationAssumption {

    /**
 * Первичный ключ; bigserial в PostgreSQL = identity-колонка.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Расчёт-владелец снимка. FK -> calculation.id ON DELETE CASCADE.
 */
    @Column(name = "calculation_id", nullable = false)
    private Long calculationId;

    /**
 * Код допущения (k_load, tariff_rub_kwh, ...; каталог - AssumptionService).
 */
    @Column(nullable = false, columnDefinition = "text")
    private String name;

    /**
 * Значение (строковое представление, как в §10.9).
 */
    @Column(nullable = false, columnDefinition = "text")
    private String value;

    /**
 * Единица измерения.
 */
    @Column(columnDefinition = "text")
    private String unit;

    /**
 * Провенанс: organizer_catalog | open_source | manual.
 */
    @Column(name = "source_kind", nullable = false, columnDefinition = "text")
    @Builder.Default
    private String sourceKind = "manual";

    /**
 * Ссылка на источник допущения.
 */
    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    /**
 * Дата актуальности источника.
 */
    @Column(name = "source_date")
    private LocalDate sourceDate;

    /**
 * Влияние на результат (уточнения организатора).
 */
    @Column(name = "impact_note", columnDefinition = "text")
    private String impactNote;
}
