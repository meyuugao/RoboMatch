package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Сценарий расчёта в ответе подбора (data_model.md §10.5): три создаёт
 * первый запуск подбора (base/purchase/raas), пользователь добавляет в
 * них решения.
 *
 * @param id идентификатор сценария
 * @param type тип: base | purchase | raas
 * @param name название сценария
 */
@Schema(description = "Сценарий расчёта проекта")
public record ScenarioDto(
        @Schema(description = "Идентификатор сценария", example = "1")
        Long id,

        @Schema(description = "Тип: base - текущий процесс, purchase - "
                + "покупка, raas - роботы как услуга", example = "purchase",
                allowableValues = {"base", "purchase", "raas"})
        String type,

        @Schema(description = "Название сценария", example = "Покупка оборудования")
        String name
) {
}
