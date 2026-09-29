package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Параметры запроса списка каталога (GET /api/solutions),.
 * <p>
 * Контроллер собирает запись из @RequestParam, сервис валидирует
 * (белые списки sortBy/sortDir/status, диапазоны) и строит Pageable.
 * Все поля опциональны; null = фильтр не действует.
 *
 * @param q поиск по названию/производителю (подстрока)
 * @param typeId фильтр по типу решения (id справочника)
 * @param subtypeId фильтр по подтипу решения (id справочника)
 * @param industryId фильтр по отрасли (id справочника)
 * @param processId фильтр по процессу (id справочника)
 * @param status фильтр по статусу решения
 * @param trlMin нижняя граница УГТ (TRL), 1-9
 * @param trlMax верхняя граница УГТ (TRL), 1-9
 * @param priceMin нижняя граница цены, руб. (>= 0)
 * @param priceMax верхняя граница цены, руб. (>= 0)
 * @param payloadMin нижняя граница грузоподъёмности, кг (>= 0)
 * @param sortBy поле сортировки (name по умолчанию)
 * @param sortDir направление сортировки (asc по умолчанию)
 * @param page номер страницы, начиная с 0 (0 по умолчанию)
 * @param size размер страницы, 1-100 (20 по умолчанию)
 */
@Schema(description = "Параметры запроса списка каталога (GET /api/solutions)")
public record CatalogQuery(
        @Schema(description = "Поиск по названию/производителю (подстрока)",
                example = "Ronavi", nullable = true)
        String q,
        @Schema(description = "Тип решения (id справочника)", example = "1", nullable = true)
        Long typeId,
        @Schema(description = "Подтип решения (id справочника)", example = "3", nullable = true)
        Long subtypeId,
        @Schema(description = "Отрасль (id справочника)", example = "1", nullable = true)
        Long industryId,
        @Schema(description = "Процесс (id справочника)", example = "2", nullable = true)
        Long processId,
        @Schema(description = "Статус решения: operation | piloting | rnd", example = "operation",
                allowableValues = {"operation", "piloting", "rnd"}, nullable = true)
        String status,
        @Schema(description = "Нижняя граница УГТ (TRL), 1-9", example = "7", nullable = true)
        Integer trlMin,
        @Schema(description = "Верхняя граница УГТ (TRL), 1-9", example = "9", nullable = true)
        Integer trlMax,
        @Schema(description = "Нижняя граница цены, руб. (>= 0)", example = "1000000",
                nullable = true)
        BigDecimal priceMin,
        @Schema(description = "Верхняя граница цены, руб. (>= 0)", example = "5000000",
                nullable = true)
        BigDecimal priceMax,
        @Schema(description = "Нижняя граница грузоподъёмности, кг (>= 0)", example = "500",
                nullable = true)
        BigDecimal payloadMin,
        @Schema(description = "Поле сортировки", example = "price",
                allowableValues = {"name", "price", "trl", "payload_kg", "created_at"},
                nullable = true)
        String sortBy,
        @Schema(description = "Направление сортировки", example = "desc",
                allowableValues = {"asc", "desc"}, nullable = true)
        String sortDir,
        @Schema(description = "Номер страницы, начиная с 0", example = "0", nullable = true)
        Integer page,
        @Schema(description = "Размер страницы, 1-100", example = "20", nullable = true)
        Integer size) {
}
