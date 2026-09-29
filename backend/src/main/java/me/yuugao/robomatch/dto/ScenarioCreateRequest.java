package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Тело POST /api/projects/{id}/scenarios — создание сценария. Пустое тело
 * (или без type) — восстановить недостающие сценарии по умолчанию
 * (base/purchase/raas, имена как при bootstrap подбора).
 *
 * @param type тип сценария; отсутствует — создать все недостающие
 * @param name название (по умолчанию — по типу сценария)
 */
@Schema(description = "Создание сценария расчёта")
public record ScenarioCreateRequest(
        @Schema(description = "Тип сценария; отсутствует — создать все "
                + "недостающие сценарии по умолчанию",
                example = "purchase", allowableValues = {"base", "purchase",
                "raas"})
        @Pattern(regexp = "base|purchase|raas",
                message = "Тип сценария: base, purchase или raas")
        String type,

        @Schema(description = "Название (по умолчанию — по типу сценария)",
                example = "Покупка с расширенным парком")
        @Size(max = 128, message = "Название сценария — до 128 символов")
        String name
) {
}
