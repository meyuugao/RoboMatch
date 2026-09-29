package me.yuugao.robomatch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Полная карточка решения - GET /api/solutions/{id} и /api/solutions/compare.
 * <p>
 * Всё, что нужно страницам «Карточка решения» и «Сравнение» (,
 * 3.3.7, 2.2 шаг 4): базовые поля + имена справочников + полный провенанс
 * источника карточки + ТТХ из EAV (с провенансом и признаком
 * подтверждённости), кейсы и применения (отрасль + процесс).
 */
@Getter
@Setter
@AllArgsConstructor
@Builder
@Schema(description = "Полная карточка решения каталога")
public class SolutionFullDto {

    @Schema(description = "Идентификатор решения", example = "42")
    private Long id;

    @Schema(description = "UUID строки каталога организатора (null для ручных записей)",
            example = "aaaaaaaa-0001-0001-0001-000000000001", nullable = true)
    private UUID externalId;

    @Schema(description = "Название продукта", example = "Ronavi H1500")
    private String name;

    @Schema(description = "Производитель", example = "ООО «Ронави Роботикс»")
    private String vendorName;

    @Schema(description = "Класс продукта", example = "brs",
            allowableValues = {"brs", "bas", "software"})
    private String productClass;

    @Schema(description = "Тип решения", example = "Мобильные роботы")
    private String solutionTypeName;

    @Schema(description = "Подтип решения", example = "Паллетные роботы")
    private String solutionSubtypeName;

    @Schema(description = "Регион", example = "Москва")
    private String regionName;

    @Schema(description = "Статус", example = "operation",
            allowableValues = {"operation", "piloting", "rnd"})
    private String status;

    @Schema(description = "Описание продукта",
            example = "Мобильный складской робот для перевозки паллет")
    private String description;

    @Schema(description = "Цена, руб. с НДС", example = "2700000.00")
    private BigDecimal priceRub;

    @Schema(description = "УГТ (TRL), 1-9", example = "8")
    private Integer trl;

    @Schema(description = "Рыночный потенциал, 2.0-5.0", example = "4.0")
    private BigDecimal marketPotential;

    @Schema(description = "Грузоподъёмность, кг", example = "1500")
    private BigDecimal payloadKg;

    @Schema(description = "Масса, кг", example = "620")
    private BigDecimal massKg;

    @Schema(description = "Длина, мм", example = "1850")
    private BigDecimal lengthMm;

    @Schema(description = "Ширина, мм", example = "800")
    private BigDecimal widthMm;

    @Schema(description = "Высота, мм", example = "2050")
    private BigDecimal heightMm;

    @Schema(description = "Точность позиционирования, мм", example = "10")
    private BigDecimal positioningAccuracyMm;

    @Schema(description = "Скорость, м/с", example = "2.0")
    private BigDecimal speedMs;

    @Schema(description = "Мощность зарядки, кВт", example = "5.5")
    private BigDecimal chargingPowerKw;

    @Schema(description = "Уровень шума, дБА", example = "65")
    private BigDecimal noiseLevelDba;

    @Schema(description = "Заполненность карточки, %", example = "55")
    private Integer completenessPct;

    @Schema(description = "Провенанс записи каталога: тип", example = "organizer_catalog",
            allowableValues = {"organizer_catalog", "open_source", "manual"})
    private String sourceKind;

    @Schema(description = "Провенанс записи каталога: ссылка на источник",
            example = "https://example.com/catalog/h1500")
    private String sourceUrl;

    @Schema(description = "Провенанс записи каталога: дата актуальности",
            example = "2026-09-26")
    private LocalDate sourceDate;

    @Schema(description = "ТТХ решения из EAV (все заполненные значения с провенансом)",
            example = "[{\"typeCode\": \"payload_kg\", \"valueNumeric\": 1500}]")
    private List<SolutionCharacteristicDto> characteristics;

    @Schema(description = "Реализованные кейсы внедрения",
            example = "[{\"id\": 3, \"name\": \"Автоматизация склада Ozon\"}]")
    private List<SolutionCaseDto> cases;

    @Schema(description = "Применения: отрасли и процессы",
            example = "[{\"industryName\": \"Торговля и услуги\", "
                    + "\"processName\": \"Внутрискладская логистика\"}]")
    private List<SolutionApplicationDto> applications;
}
