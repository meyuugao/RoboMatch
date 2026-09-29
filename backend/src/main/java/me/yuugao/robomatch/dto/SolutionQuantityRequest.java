package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Тело PUT /api/projects/{id}/scenarios/{scenarioId}/solutions/{solutionId} -
 * изменение количества единиц решения в составе сценария (точечная
 * правка строки scenario_solution без замены состава целиком).
 *
 * @param quantity новое количество единиц (1-10000)
 */
@Schema(description = "Изменение количества решения в составе сценария")
public record SolutionQuantityRequest(
        @Schema(description = "Новое количество единиц (1-10000, "
                + "CHECK scenario_solution.quantity)", example = "3",
                defaultValue = "1")
        @NotNull(message = "Укажите количество (quantity)")
        @Positive(message = "Количество должно быть положительным")
        @Max(value = 10_000, message = "Количество - до 10 000 единиц "
                + "(бизнес-лимит; экономика умножает на цены)")
        Integer quantity
) {
}
