package me.yuugao.robomatch.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Стабильная обёртка страницы результатов.
 * <p>
 * Spring Data Page&lt;T&gt; сериализуется нестабильно (грабля Boot 4 /
 * Jackson 3: состав полей меняется от версии к версии, вложенные
 * unsorted/empty-объекты ломают клиентов). Поэтому наружу отдаём свою
 * обёртку с фиксированным контрактом — сервис собирает её из Page.
 *
 * @param <T> тип элемента (SolutionSummaryDto и др.)
 */
@Getter
@AllArgsConstructor
@Schema(description = "Страница результатов (обёртка списка)")
public class PageResponse<T> {

    @Schema(description = "Элементы текущей страницы",
            example = "[{\"id\": 42, \"name\": \"Ronavi H1500\"}]")
    private final List<T> content;

    @Schema(description = "Номер страницы, начиная с 0", example = "0")
    private final int page;

    @Schema(description = "Размер страницы", example = "20")
    private final int size;

    @Schema(description = "Всего элементов под фильтр", example = "187")
    private final long totalElements;

    @Schema(description = "Всего страниц", example = "10")
    private final int totalPages;
}
