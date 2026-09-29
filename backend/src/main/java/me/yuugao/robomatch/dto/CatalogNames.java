package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Имена справочников для одного решения (значения JOIN-колонок).
 * <p>
 * Запись, а не класс: сервис батч-загружает справочники (4 запроса IN
 * на страницу) и собирает CatalogNames для каждого решения; NULL в
 * CatalogNames = у решения не указан тип/подтип/регион (в каталоге
 * организатора бывают пустые значения — это норма данных, не ошибка).
 *
 * @param vendorName имя производителя (vendor)
 * @param solutionTypeName имя типа решения
 * @param solutionSubtypeName имя подтипа решения
 * @param regionName имя региона
 */
@Schema(description = "Имена справочников одного решения каталога")
public record CatalogNames(
        @Schema(description = "Производитель", example = "ООО «Ронави Роботикс»",
                nullable = true)
        String vendorName,
        @Schema(description = "Тип решения", example = "Мобильные роботы", nullable = true)
        String solutionTypeName,
        @Schema(description = "Подтип решения", example = "Паллетные роботы", nullable = true)
        String solutionSubtypeName,
        @Schema(description = "Регион", example = "Москва", nullable = true)
        String regionName) {

    /**
 * Пустые имена (все NULL) — для решений без справочных значений.
 */
    public static CatalogNames empty() {
        return new CatalogNames(null, null, null, null);
    }
}
