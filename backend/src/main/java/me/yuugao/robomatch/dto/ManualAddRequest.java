package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/**
 * Тело POST /api/projects/{id}/scenarios/{scenarioId}/solutions —
 * ручное добавление решения в сценарий (
 * в автоматическую подборку, с отображением предупреждения»). Причина
 * manualReason ОБЯЗАТЕЛЬНА
 * предупреждением и причиной; заполняемость контролирует приложение,
 * data_model.md §10.6).
 *
 * @param solutionId идентификатор решения каталога
 * @param manualReason причина ручного добавления (обязательна)
 * @param quantity количество единиц решения (по умолчанию 1)
 */
@Schema(description = "Ручное добавление решения в сценарий")
public record ManualAddRequest(
        @Schema(description = "Идентификатор решения каталога", example = "42")
        @NotNull(message = "Укажите решение (solutionId)")
        Long solutionId,

        @Schema(description = "Причина ручного добавления (обязательна; для "
                + "исключённых решений показывается причина исключения)",
                example = "Планируем расширение проходов до 3 м")
        @NotBlank(message = "Укажите причину ручного добавления — она попадёт в отчёт")
        @Size(max = 1024, message = "Причина — до 1024 символов")
        String manualReason,

        @Schema(description = "Количество единиц решения в сценарии "
                + "(по умолчанию 1)", example = "2", defaultValue = "1")
        @Positive(message = "Количество должно быть положительным")
        @Max(value = 10_000, message = "Количество — до 10 000 единиц "
                + "(бизнес-лимит; экономика рассчитывается по ценам каталога)")
        Integer quantity
) {
}
