package me.yuugao.robomatch.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Тело PUT /api/projects/{id}/assumptions: карта «код допущения
 * → значение». Значение null или пустая строка - сброс в дефолт; коды вне
 * карты не меняются; reserve_rate и неизвестные коды - 400.
 *
 * @param values карта «код допущения → значение|null» (null - сброс в дефолт)
 */
@Schema(description = "Изменение допущений экономики")
public record AssumptionUpdateRequest(
        @Schema(description = "Карта «код → значение|null» (null - сброс "
                + "в дефолт)", example = "{\"k_load\": \"0.8\", "
                + "\"tariff_rub_kwh\": \"7.5\"}")
        @Size(max = 64, message = "Не более 64 допущений в одном запросе")
        Map<String, String> values
) {
}
