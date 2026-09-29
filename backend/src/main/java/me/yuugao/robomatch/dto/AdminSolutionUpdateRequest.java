package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.*;

/**
 * Правка решения каталога: PUT /api/admin/solutions/{id}.
 * <p>
 * PUT-семантика: передаются все редактируемые поля карточки; отсутствующее
 * nullable-поле (тип, регион, описание, ТТХ) очищается — форма редактирования
 * присылает полное состояние. Уникальность (vendor_id, name) и провенанс
 * (source_kind='manual' для ручных правок) — на сервисе.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Правка решения каталога (PUT /api/admin/solutions/{id})")
public class AdminSolutionUpdateRequest {

    @Schema(description = "Название продукта, 1-256 символов",
            example = "Мобильный робот складской X1")
    @NotBlank(message = "Название решения обязательно")
    @Size(min = 1, max = 256, message = "Название решения: до 256 символов")
    private String name;

    @Schema(description = "Производитель (id справочника vendor)", example = "12")
    @NotNull(message = "Производитель (vendor) обязателен")
    private Long vendorId;

    @Schema(description = "Класс продукта: brs | bas | software", example = "brs",
            allowableValues = {"brs", "bas", "software"})
    @NotNull(message = "Класс продукта обязателен")
    @Pattern(regexp = "brs|bas|software",
            message = "Класс продукта: brs, bas или software")
    private String productClass;

    @Schema(description = "Тип решения (id справочника)", nullable = true, example = "3")
    private Long solutionTypeId;

    @Schema(description = "Подтип решения (id справочника)", nullable = true, example = "7")
    private Long solutionSubtypeId;

    @Schema(description = "Регион (id справочника)", nullable = true, example = "1")
    private Long regionId;

    @Schema(description = "Статус: operation | piloting | rnd", example = "operation",
            allowableValues = {"operation", "piloting", "rnd"})
    @NotNull(message = "Статус обязателен")
    @Pattern(regexp = "operation|piloting|rnd",
            message = "Статус: operation, piloting или rnd")
    private String status;

    @Schema(description = "Описание продукта", nullable = true,
            example = "Мобильный складской робот для перевозки паллет")
    private String description;

    @Schema(description = "Цена, руб. с НДС (>= 0)", example = "2700000.00")
    @NotNull(message = "Цена обязательна")
    @DecimalMin(value = "0", message = "Цена не может быть отрицательной")
    private BigDecimal priceRub;

    @Schema(description = "УГТ (TRL), 1-9", nullable = true, example = "8")
    @Min(value = 1, message = "УГТ: от 1 до 9")
    @Max(value = 9, message = "УГТ: от 1 до 9")
    private Integer trl;

    @Schema(description = "Рыночный потенциал, 2.0-5.0", nullable = true, example = "4.0")
    @DecimalMin(value = "2", message = "Рыночный потенциал: от 2 до 5")
    @jakarta.validation.constraints.DecimalMax(value = "5",
            message = "Рыночный потенциал: от 2 до 5")
    private BigDecimal marketPotential;

    @Schema(description = "Грузоподъёмность, кг (>= 0)", nullable = true, example = "1500")
    @DecimalMin(value = "0", message = "Грузоподъёмность не может быть отрицательной")
    private BigDecimal payloadKg;

    @Schema(description = "Масса робота, кг (>= 0)", nullable = true, example = "620")
    @DecimalMin(value = "0", message = "Масса не может быть отрицательной")
    private BigDecimal massKg;

    @Schema(description = "Длина, мм (>= 0)", nullable = true, example = "1850")
    @DecimalMin(value = "0", message = "Длина не может быть отрицательной")
    private BigDecimal lengthMm;

    @Schema(description = "Ширина, мм (>= 0)", nullable = true, example = "800")
    @DecimalMin(value = "0", message = "Ширина не может быть отрицательной")
    private BigDecimal widthMm;

    @Schema(description = "Высота, мм (>= 0)", nullable = true, example = "2050")
    @DecimalMin(value = "0", message = "Высота не может быть отрицательной")
    private BigDecimal heightMm;

    @Schema(description = "Точность позиционирования, мм (>= 0)", nullable = true,
            example = "10")
    @DecimalMin(value = "0", message = "Точность позиционирования не может быть отрицательной")
    private BigDecimal positioningAccuracyMm;

    @Schema(description = "Скорость, м/с (>= 0)", nullable = true, example = "2.0")
    @DecimalMin(value = "0", message = "Скорость не может быть отрицательной")
    private BigDecimal speedMs;

    @Schema(description = "Мощность зарядки, кВт (>= 0)", nullable = true, example = "5.5")
    @DecimalMin(value = "0", message = "Мощность зарядки не может быть отрицательной")
    private BigDecimal chargingPowerKw;

    @Schema(description = "Уровень шума, дБА (>= 0)", nullable = true, example = "65")
    @DecimalMin(value = "0", message = "Уровень шума не может быть отрицательным")
    private BigDecimal noiseLevelDba;
}
