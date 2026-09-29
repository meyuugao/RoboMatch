package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Карточка-элемент списка каталога (списочный эндпоинт GET /api/solutions).
 * <p>
 * Отличается от полной карточки (SolutionFullDto): нет externalId,
 * источников и связанных сущностей — списку они не нужны, а вес ответа
 * при 187 решениях имеет значение. Имена справочников (vendorName и др.)
 * резолвятся сервисом батч-загрузкой — «голых» id в UI больше нет
 *.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Решение каталога (строка списка)")
public class SolutionSummaryDto {

    @Schema(description = "Идентификатор решения", example = "42")
    private Long id;

    @Schema(description = "Название продукта", example = "Ronavi H1500")
    private String name;

    @Schema(description = "Производитель", example = "ООО «Ронави Роботикс»")
    private String vendorName;

    @Schema(description = "Класс продукта", example = "brs", allowableValues = {"brs", "bas", "software"})
    private String productClass;

    @Schema(description = "Тип решения (null, если не указан в каталоге)",
            example = "Мобильные роботы", nullable = true)
    private String solutionTypeName;

    @Schema(description = "Подтип решения (null, если не указан)",
            example = "Паллетные роботы", nullable = true)
    private String solutionSubtypeName;

    @Schema(description = "Регион (null, если не указан)", example = "Москва",
            nullable = true)
    private String regionName;

    @Schema(description = "Статус", example = "operation", allowableValues = {"operation", "piloting", "rnd"})
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

    @Schema(description = "Происхождение записи", example = "organizer_catalog",
            allowableValues = {"organizer_catalog", "open_source", "manual"})
    private String sourceKind;
}
