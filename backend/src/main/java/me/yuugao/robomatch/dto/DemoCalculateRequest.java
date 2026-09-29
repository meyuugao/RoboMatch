package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Запрос гостевого демо-расчёта: выбранный тип объекта. Тело необязательное
 * - без него считается демо-набор «Склад» (единственный расчётный тип).
 *
 * @param objectTypeCode код типа объекта (warehouse | airport | clinic)
 */
@Schema(description = "Запрос гостевого демо-расчёта")
public record DemoCalculateRequest(
        @Schema(description = "Код типа объекта демо-расчёта; по умолчанию "
                + "«Склад» - единственный тип с демо-набором данных",
                example = "warehouse")
        String objectTypeCode
) {
}
