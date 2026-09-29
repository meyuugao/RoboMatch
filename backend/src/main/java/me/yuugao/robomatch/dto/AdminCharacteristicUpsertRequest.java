package me.yuugao.robomatch.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Upsert значения ТТХ решения (EAV solution_characteristic):
 * PUT /api/admin/solutions/{id}/characteristics/{characteristicTypeId}.
 * <p>
 * Типобезопасность (data_model.md §4): ровно одно значение из
 * valueNumeric/valueText/valueBool/valueDate должно соответствовать
 * data_type типа характеристики — соответствие проверяет сервис и
 * отвечает 400 по-русски.
 * <p>
 * Провенанс: manual (по умолчанию, из админки) или
 * open_source (тогда обязательны sourceUrl + sourceDate, значение
 * считается подтверждённым: isConfirmed=true — уточнение организатора/).
 * Само поле isConfirmed в запросе НЕ принимается: подтверждённость
 * вычисляется из источника и подделке не подлежит.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Upsert значения ТТХ решения (EAV solution_characteristic)")
public class AdminCharacteristicUpsertRequest {

    @Schema(description = "Числовое значение (для типа number)", nullable = true,
            example = "1500.5")
    private BigDecimal valueNumeric;

    @Schema(description = "Текстовое значение (для типа text)", nullable = true,
            example = "Литиевая батарея")
    @Size(max = 512, message = "Текстовое значение: до 512 символов")
    private String valueText;

    @Schema(description = "Булево значение (для типа boolean)", nullable = true,
            example = "true")
    private Boolean valueBool;

    @Schema(description = "Дата (для типа date)", nullable = true, example = "2026-09-01")
    private LocalDate valueDate;

    @Schema(description = "Источник значения: manual | open_source", example = "manual",
            allowableValues = {"manual", "open_source"})
    @Pattern(regexp = "manual|open_source",
            message = "Источник значения: manual или open_source")
    private String sourceKind;

    @Schema(description = "Ссылка на источник http(s) (обязательна для "
            + "open_source)", nullable = true,
            example = "https://example.com/datasheet.pdf")
    @Size(max = 1024, message = "Ссылка на источник: до 1024 символов")
    @jakarta.validation.constraints.Pattern(regexp = "^https?://.+",
            message = "Ссылка на источник: должна начинаться с http:// или https://")
    private String sourceUrl;

    @Schema(description = "Дата актуальности источника (для open_source)",
            nullable = true, example = "2026-09-01")
    private LocalDate sourceDate;
}
