package me.yuugao.robomatch.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Кейс внедрения в карточке решения (solution_case через
 * solution_case_link).
 */
@Getter
@Setter
@AllArgsConstructor
@Builder
@Schema(description = "Кейс внедрения решения")
public class SolutionCaseDto {

    @Schema(description = "Идентификатор кейса", example = "3")
    private Long id;

    @Schema(description = "Название кейса", example = "Автоматизация склада Ozon")
    private String name;

    @Schema(description = "Описание кейса",
            example = "Склад 12 000 кв. м, 40 роботов, запуск за 6 месяцев")
    private String description;

    @Schema(description = "Ссылка на источник",
            example = "https://example.com/case/ozon")
    private String sourceUrl;

    @Schema(description = "Дата актуальности источника", example = "2026-09-26")
    private LocalDate sourceDate;
}
