package me.yuugao.robomatch.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ответ POST /selection/run и GET /selection: сценарии проекта и полный
 * список результатов с объяснениями. Одинаковая форма
 * для запуска и чтения — UI обновляется одним ответом.
 *
 * @param scenarios сценарии проекта (создаются первым запуском подбора)
 * @param results результаты подбора с объяснениями
 */
@Schema(description = "Подбор: сценарии проекта и результаты с объяснениями")
public record SelectionRunDto(
        @Schema(description = "Сценарии проекта (создаются первым запуском "
                + "подбора: base, purchase, raas)",
                example = "[{\"id\": 1, \"type\": \"base\"}]")
        List<ScenarioDto> scenarios,

        @Schema(description = "Результаты подбора (все применимые решения "
                + "с=status/reason/rank/score/вкладом/недостающими данными)",
                example = "[{\"solutionId\": 42, \"status\": \"fit\"}]")
        List<SelectionResultDto> results
) {
}
