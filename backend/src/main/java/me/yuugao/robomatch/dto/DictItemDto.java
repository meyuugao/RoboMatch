package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Элемент словаря для UI-фильтров каталога (GET /api/filters).
 * Английский code + русское name - конвенция справочников (assumptions.md §15).
 */
@Getter
@AllArgsConstructor
@Schema(description = "Элемент словаря для фильтров каталога")
public class DictItemDto {

    @Schema(description = "Идентификатор", example = "1")
    private final Long id;

    @Schema(description = "Технический код (english snake_case)", example = "amr")
    private final String code;

    @Schema(description = "Русское название", example = "AMR (автономный мобильный робот)")
    private final String name;
}
