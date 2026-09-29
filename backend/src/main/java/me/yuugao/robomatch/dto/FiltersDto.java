package me.yuugao.robomatch.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Словари для UI - GET /api/filters; objectTypes -
 * типы объектов для формы создания проекта.
 * <p>
 * Отдаётся целиком одним ответом: объём справочников мал (десятки строк),
 * а UI получает всё для зависимых списков (тип → допустимые подтипы
 * через typeSubtypeMapping) без дополнительных запросов.
 */
@Getter
@AllArgsConstructor
@Schema(description = "Словари для фильтров каталога (GET /api/filters)")
public class FiltersDto {

    @Schema(description = "Типы решений",
            example = "[{\"id\": 1, \"code\": \"amr\", \"name\": \"AMR\"}]")
    private final List<DictItemDto> types;

    @Schema(description = "Типы объектов пользователя (склад/аэропорт/"
            + "медучреждение) - выбор при создании проекта",
            example = "[{\"id\": 1, \"code\": \"warehouse\", \"name\": \"Склад\"}]")
    private final List<DictItemDto> objectTypes;

    @Schema(description = "Подтипы решений",
            example = "[{\"id\": 3, \"code\": \"amr_pallet\", \"name\": \"Паллетный AMR\"}]")
    private final List<DictItemDto> subtypes;

    @Schema(description = "Отрасли",
            example = "[{\"id\": 1, \"code\": \"retail\", \"name\": \"Торговля и услуги\"}]")
    private final List<DictItemDto> industries;

    @Schema(description = "Процессы",
            example = "[{\"id\": 2, \"code\": \"intra_logistics\", \"name\": \"Внутрискладская логистика\"}]")
    private final List<DictItemDto> processes;

    @Schema(description = "Статусы решений (CHECK solution.status)",
            example = "[\"operation\", \"piloting\", \"rnd\"]")
    private final List<String> statuses;

    @Schema(description = "Допустимые сочетания тип-подтип (для зависимых списков UI)",
            example = "[{\"typeId\": 1, \"subtypeId\": 3}]")
    private final List<TypeSubtypeLinkDto> typeSubtypeMapping;

    @Schema(description = "Минимальная цена в каталоге, руб.", example = "250000.00")
    private final BigDecimal priceMin;

    @Schema(description = "Максимальная цена в каталоге, руб.", example = "2700000.00")
    private final BigDecimal priceMax;

    @Schema(description = "Минимальный УГТ (TRL) в каталоге", example = "5")
    private final Integer trlMin;

    @Schema(description = "Максимальный УГТ (TRL) в каталоге", example = "9")
    private final Integer trlMax;
}
