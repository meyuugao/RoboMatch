package me.yuugao.robomatch.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Параметр проекта с метаданными и текущим значением - элемент ответа
 * GET /api/projects/{id}/parameters.
 * <p>
 * Содержит всё, что нужно форме: тип поля,
 * единицу, обязательность, диапазон, значение по умолчанию, источник
 * норматива, группу для секций и текущее значение с датой изменения.
 * id - это object_type_parameter.id (на него ссылается PUT/DELETE
 * значения и колонки шаблона импорта).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Параметр проекта с текущим значением")
public class ParameterDto {

    @Schema(description = "Идентификатор определения параметра (object_type_parameter.id)",
            example = "1")
    private Long id;

    @Schema(description = "Код параметра (латиница snake_case; он же заголовок колонки "
            + "шаблона импорта)", example = "total_warehouse_area")
    private String code;

    @Schema(description = "Название параметра", example = "Общая площадь склада")
    private String name;

    @Schema(description = "Единица измерения", example = "кв. м", nullable = true)
    private String unit;

    @Schema(description = "Группа параметра (секция формы)", example = "Общие")
    private String groupName;

    @Schema(description = "Тип значения поля формы",
            example = "number", allowableValues = {"number", "boolean", "text"})
    private String valueType;

    @Schema(description = "Обязательный параметр (без него расчёт не запустится)",
            example = "true")
    private Boolean isRequired;

    @Schema(description = "Фиксированный параметр-константа: "
            + "не редактируется - значение задаётся системой и равно defaultValue "
            + "(коэффициент начислений на ФОТ, рабочих дней в году, наличие WMS)",
            example = "false")
    private Boolean isFixed;

    @Schema(description = "Производный параметр: значение рассчитывается "
            + "автоматически из источника (derivedFromName) и не редактируется",
            example = "false")
    private Boolean isDerived;

    @Schema(description = "Имя параметра-источника для производного (null - параметр "
            + "не производный)", nullable = true, example = "total_warehouse_area")
    private String derivedFromName;

    @Schema(description = "Значение по умолчанию (показывается в подсказке поля)",
            nullable = true, example = "{\"kind\": \"number\", \"value\": 5000}")
    private TypedValueDto defaultValue;

    @Schema(description = "Минимум допустимого диапазона (для числовых)",
            example = "10", nullable = true)
    private BigDecimal minValue;

    @Schema(description = "Максимум допустимого диапазона (для числовых)",
            example = "1000000", nullable = true)
    private BigDecimal maxValue;

    @Schema(description = "Источник норматива",
            example = "демо-набор данных «Склад»", nullable = true)
    private String sourceNote;

    @Schema(description = "Текущее значение проекта (null - ещё не введено)", nullable = true,
            example = "{\"kind\": \"number\", \"value\": 5000}")
    private TypedValueDto currentValue;

    @Schema(description = "Когда значение последний раз изменено", nullable = true,
            example = "2026-09-26T10:15:30Z")
    private Instant updatedAt;
}
