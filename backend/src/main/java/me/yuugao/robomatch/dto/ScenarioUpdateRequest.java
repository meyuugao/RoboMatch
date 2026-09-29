package me.yuugao.robomatch.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Тело PUT /api/projects/{id}/scenarios/{scenarioId} — правка имени и/или
 * состава сценария: передан solutions — состав замещается целиком
 * (валидация: решение существует, количество 1-10000).
 *
 * @param name новое имя сценария
 * @param solutions новый состав (замещает целиком); отсутствует — не меняется
 */
@Schema(description = "Правка сценария: имя и состав оборудования")
public record ScenarioUpdateRequest(
        @Schema(description = "Новое имя сценария", example = "Покупка 2027")
        @Size(max = 128, message = "Название сценария — до 128 символов")
        String name,

        @Schema(description = "Новый состав (замещает целиком); отсутствует "
                + "— состав не меняется",
                example = "[{\"solutionId\": 42, \"quantity\": 3}]")
        @Size(max = 200, message = "Состав сценария — до 200 позиций ")
        @Valid
        List<ScenarioCompositionItemDto> solutions
) {

    /**
 * Строка состава: решение каталога + количество. Причина
 * manualReason обязательна для ручных добавлений.
 *
 * @param solutionId идентификатор решения
 * @param quantity количество единиц
 * @param manualReason причина ручного добавления (при manual=true)
 * @param manual добавлено вручную
 */
    @Schema(description = "Строка состава сценария")
    public record ScenarioCompositionItemDto(
            @Schema(description = "Идентификатор решения", example = "42")
            @NotNull(message = "Укажите решение (solutionId)")
            Long solutionId,

            @Schema(description = "Количество единиц", example = "3",
                    defaultValue = "1")
            @Positive(message = "Количество должно быть положительным")
            @jakarta.validation.constraints.Max(value = 10_000,
                    message = "Количество — до 10 000 единиц")
            Integer quantity,

            @Schema(description = "Причина ручного добавления — "
                    + "обязательна при manual=true (проверяется сервисом, "
                    + ")", example = "Планируем расширение проходов до 3 м")
            @Size(max = 1024, message = "Причина — до 1024 символов")
            String manualReason,

            @Schema(description = "Добавлено вручную (is_manual)",
                    example = "false")
            Boolean manual
    ) {
    }
}
