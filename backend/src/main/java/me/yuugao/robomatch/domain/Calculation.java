package me.yuugao.robomatch.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

/**
 * Сущность «Расчёт экономики» сценария (data_model.md §10.8, таблица
 * calculation из V1; миграция не нужна).
 *
 * <p>APPEND-ONLY: каждый расчёт — НОВАЯ строка, старые не
 * меняются; воспроизведение = чтение старой строки и её снимков
 * (calculation_assumption). Скорректированное значение — тоже новый
 * расчёт (§10.10), исходный остаётся в истории.
 *
 * <p>Метрики-колонки — показатели таблицы сравнения;
 * детальная разбивка (статьи CAPEX/OPEX, состав, чувствительность) —
 * в metrics_json (снимок переменного состава, data_model.md §12).
 * Округление (economic_model.md §4): стоимости — до рублей, Payback —
 * до десятых года, ROI — до десятых процента; точные значения — в
 * metrics_json.
 */
@Entity
@Table(name = "calculation")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Calculation {

    /**
 * Первичный ключ; bigserial = identity.
 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
 * Сценарий. FK -> scenario.id ON DELETE CASCADE (V1).
 */
    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    /**
 * Версия исходных данных: SHA-256 снимка входов (первые 12 hex).
 */
    @Column(name = "version_data", nullable = false, columnDefinition = "text")
    private String versionData;

    /**
 * Версия расчётной модели (номер формул economic_model.md).
 */
    @Column(name = "version_model", nullable = false, columnDefinition = "text")
    private String versionModel;

    /**
 * Дата и время расчёта.
 */
    @CreatedDate
    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    /**
 * CAPEX, руб. (округлён до рублей).
 */
    @Column(name = "total_capex", precision = 18, scale = 2)
    @Nullable
    private BigDecimal totalCapex;

    /**
 * Годовой OPEX, руб.
 */
    @Column(name = "total_opex", precision = 18, scale = 2)
    @Nullable
    private BigDecimal totalOpex;

    /**
 * Изменение OPEX к базовому сценарию, руб. (отрицательное — экономия).
 */
    @Column(name = "opex_delta_rub", precision = 18, scale = 2)
    @Nullable
    private BigDecimal opexDeltaRub;

    /**
 * Чистый годовой экономический эффект, руб.
 */
    @Column(name = "effect_year", precision = 18, scale = 2)
    @Nullable
    private BigDecimal effectYear;

    /**
 * Простой срок окупаемости, лет (null — не окупается / не определён).
 */
    @Column(name = "payback_years", precision = 10, scale = 2)
    @Nullable
    private BigDecimal paybackYears;

    /**
 * ROI за горизонт, %.
 */
    @Column(name = "roi_pct", precision = 12, scale = 2)
    @Nullable
    private BigDecimal roiPct;

    /**
 * TCO на горизонте, руб.
 */
    @Column(name = "tco_rub", precision = 18, scale = 2)
    @Nullable
    private BigDecimal tcoRub;

    /**
 * Снимок переменного состава: статьи, состав, чувствительность.
 */
    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "metrics_json", columnDefinition = "jsonb")
    @Nullable
    private String metricsJson;
}
