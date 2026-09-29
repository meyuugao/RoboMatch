package me.yuugao.robomatch.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Сценарий с составом оборудования в ответе GET /scenarios (страница
 * сценариев: состав — из подбора и ручных добавлений).
 *
 * <p>lastCalculatedAt/compositionChanged: дата последнего расчёта
 * и признак «состав изменён с момента расчёта» — сравнение текущего
 * состава со снимком metrics_json последнего расчёта. Бейдж в UI
 * предупреждает, что дашборд экономики показывает устаревший состав.
 *
 * @param id идентификатор сценария
 * @param type тип: base | purchase | raas
 * @param name название
 * @param solutions состав оборудования
 * @param lastCalculatedAt дата последнего расчёта сценария
 * @param compositionChanged состав изменён с момента последнего расчёта
 */
@Schema(description = "Сценарий с составом оборудования")
public record ScenarioDetailsDto(
        @Schema(description = "Идентификатор сценария", example = "2")
        Long id,

        @Schema(description = "Тип: base | purchase | raas", example = "purchase",
                allowableValues = {"base", "purchase", "raas"})
        String type,

        @Schema(description = "Название", example = "Покупка оборудования")
        String name,

        @Schema(description = "Состав оборудования (решения и количество)",
                example = "[{\"solutionId\": 42, \"quantity\": 3}]")
        List<ScenarioSolutionDto> solutions,

        @Schema(description = "Дата последнего расчёта сценария "
                + "(null — не считался)", example = "2026-09-26T10:15:30Z")
        Instant lastCalculatedAt,

        @Schema(description = "Состав изменён с момента последнего расчёта "
                + "(устаревший расчёт на дашборде)", example = "false")
        boolean compositionChanged
) {
}
