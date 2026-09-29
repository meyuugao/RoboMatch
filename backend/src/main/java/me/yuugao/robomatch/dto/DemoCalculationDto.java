package me.yuugao.robomatch.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Результат гостевого демо-расчёта: та же таблица сравнения трёх сценариев
 * (base/purchase/raas), что и в проекте, но расчёт выполнен в памяти на
 * константах демо-набора и НЕ сохраняется в БД.
 *
 * @param demo признак демо-расчёта (не сохраняется)
 * @param objectTypeName название типа объекта
 * @param comparison таблица сравнения сценариев
 */
@Schema(description = "Гостевой демо-расчёт (не сохраняется)")
public record DemoCalculationDto(
        @Schema(description = "Признак демо-расчёта: выполняется в памяти "
                + "и не сохраняется", example = "true")
        @JsonProperty("demo")
        boolean demo,

        @Schema(description = "Название типа объекта", example = "Склад")
        String objectTypeName,

        @Schema(description = "Сравнение сценариев демо-расчёта: base / "
                + "purchase / raas",
                example = "{\"scenarios\": [], \"horizonYears\": 5, "
                        + "\"versionModel\": \"economic-model-1.0\"}")
        ComparisonDto comparison
) {
}
