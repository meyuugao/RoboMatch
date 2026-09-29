package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на сохранение 2D-схемы имитации ( — экспорт или
 * сохранение визуализаций): сериализованный
 * клиентом SVG текущей схемы. Сервер санитизирует разметку (без
 * скриптов и on*-обработчиков) и сохраняет в хранилище
 * data/simulations/{projectId}/{simulationId}.svg; ссылка — в
 * simulation_result.kpi_json.exportUrl.
 *
 * @param svg SVG-разметка текущей 2D-схемы
 */
@Schema(description = "Сохранение 2D-схемы имитации (SVG)")
public record SimulationExportRequest(
        @Schema(description = "SVG-разметка текущей 2D-схемы (без "
                + "скриптов — сервер санитизирует)", example = "<svg ...>")
        @NotBlank(message = "SVG-разметка обязательна")
        @Size(max = 2000000,
                message = "Схема слишком большая — до 2 000 000 символов")
        String svg
) {
}
