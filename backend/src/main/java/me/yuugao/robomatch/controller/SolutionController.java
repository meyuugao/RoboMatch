package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.CatalogQuery;
import me.yuugao.robomatch.dto.PageResponse;
import me.yuugao.robomatch.dto.SolutionFullDto;
import me.yuugao.robomatch.dto.SolutionSummaryDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.NotFoundException;
import me.yuugao.robomatch.service.SolutionService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * REST-контроллер каталога решений (
 * сортировка и сравнение выбранных решений).
 * <p>
 * Роль контроллера — тонкая: принять HTTP (query-параметры списка),
 * собрать CatalogQuery, позвать сервис, вернуть JSON. Формат фильтров —
 * query-параметры URL: ссылки шарятся, состояние кэшируется, это REST.
 * <p>
 * /api/solutions/compare объявлен ДО /{id}: literal-сегмент в Spring
 * PathPattern приоритетнее переменной, но порядок методов фиксирует
 * намерение явно.
 */
@RestController
@RequestMapping("/api/solutions")
@RequiredArgsConstructor
@Tag(name = "Каталог решений", description = "Список с фильтрами, карточка, сравнение (read-only)")
public class SolutionController {

    private final SolutionService solutionService;

    /**
 * Список решений с фильтрами, поиском, сортировкой и пагинацией.
 * GET /api/solutions
 *
 * @param q подстрока названия (регистронезависимая)
 * @param typeId фильтр по типу решения (id из /api/filters)
 * @param subtypeId фильтр по подтипу решения
 * @param industryId фильтр по отрасли применения
 * @param processId фильтр по процессу применения
 * @param status статус: operation | piloting | rnd
 * @param trlMin нижняя граница УГТ (TRL), 1-9
 * @param trlMax верхняя граница УГТ (TRL), 1-9
 * @param priceMin цена от, руб.
 * @param priceMax цена до, руб.
 * @param payloadMin грузоподъёмность от, кг
 * @param sortBy поле сортировки: name | price | trl | payload_kg | created_at
 * @param sortDir направление сортировки: asc | desc
 * @param page номер страницы, начиная с 0
 * @param size размер страницы, 1-100 (по умолчанию 20)
 * @return страница SolutionSummaryDto
 * @throws BadRequestException 400 — невалидные значения фильтров/сортировки/пагинации
 */
    @GetMapping
    @Operation(summary = "Список решений",
            description = "Поиск по подстроке названия (регистронезависимый), фильтры, "
                    + "сортировка, пагинация. Все параметры опциональны. "
                    + "Невалидные значения — 400 с пояснением.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Страница решений"),
            @ApiResponse(responseCode = "400", description = "Невалидные значения параметров")
    })
    public PageResponse<SolutionSummaryDto> search(
            @Parameter(description = "Поиск по названию (подстрока, без регистра)")
            @RequestParam(required = false) String q,
            @Parameter(description = "Тип решения (id из /api/filters)")
            @RequestParam(required = false) Long typeId,
            @Parameter(description = "Подтип решения (id из /api/filters)")
            @RequestParam(required = false) Long subtypeId,
            @Parameter(description = "Отрасль применения (id из /api/filters)")
            @RequestParam(required = false) Long industryId,
            @Parameter(description = "Процесс применения (id из /api/filters)")
            @RequestParam(required = false) Long processId,
            @Parameter(description = "Статус: operation | piloting | rnd")
            @RequestParam(required = false) String status,
            @Parameter(description = "УГТ (TRL) от, 1-9", example = "7")
            @RequestParam(required = false) Integer trlMin,
            @Parameter(description = "УГТ (TRL) до, 1-9", example = "9")
            @RequestParam(required = false) Integer trlMax,
            @Parameter(description = "Цена от, руб.", example = "100000")
            @RequestParam(required = false) BigDecimal priceMin,
            @Parameter(description = "Цена до, руб.", example = "5000000")
            @RequestParam(required = false) BigDecimal priceMax,
            @Parameter(description = "Грузоподъёмность от, кг", example = "500")
            @RequestParam(required = false) BigDecimal payloadMin,
            @Parameter(description = "Сортировка: name | price | trl | payload_kg | created_at")
            @RequestParam(required = false) String sortBy,
            @Parameter(description = "Направление: asc | desc")
            @RequestParam(required = false) String sortDir,
            @Parameter(description = "Страница, начиная с 0", example = "0")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Размер страницы, 1-100 (по умолчанию 20)", example = "20")
            @RequestParam(required = false) Integer size) {
        CatalogQuery query = new CatalogQuery(q, typeId, subtypeId, industryId, processId,
                status, trlMin, trlMax, priceMin, priceMax, payloadMin, sortBy, sortDir,
                page, size);
        return solutionService.search(query);
    }

    /**
 * Сравнение решений: полные карточки 2-10 решений.
 * GET /api/solutions/compare?ids=1,2,3
 *
 * @param ids идентификаторы решений через запятую
 * @return список SolutionFullDto для сопоставления по ТТХ
 * @throws BadRequestException 400 — меньше 2 или больше 10 идентификаторов
 * @throws NotFoundException 404 — среди id есть неизвестный
 */
    @GetMapping("/compare")
    @Operation(summary = "Сравнение решений",
            description = "Полные карточки 2-10 решений для сопоставления по унифицированным "
                    + "характеристикам. Меньше 2 или больше 10 — 400; "
                    + "неизвестный id — 404. Дубликаты id игнорируются.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Карточки решений"),
            @ApiResponse(responseCode = "400", description = "Число решений вне 2-10"),
            @ApiResponse(responseCode = "404", description = "Неизвестный id решения")
    })
    public List<SolutionFullDto> compare(
            @Parameter(description = "Идентификаторы решений через запятую", example = "1,2,3")
            @RequestParam List<Long> ids) {
        return solutionService.compare(ids);
    }

    /**
 * Карточка решения. GET /api/solutions/{id}
 *
 * @param id идентификатор решения
 * @return полная карточка SolutionFullDto (ТТХ, кейсы, применения)
 * @throws NotFoundException 404 — решение не найдено
 */
    @GetMapping("/{id}")
    @Operation(summary = "Карточка решения",
            description = "Полная карточка: имена справочников, ТТХ из EAV с провенансом, "
                    + "кейсы, применения, источник записи. 404, если не найдено.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Карточка решения"),
            @ApiResponse(responseCode = "404", description = "Решение не найдено")
    })
    public ResponseEntity<SolutionFullDto> getSolutionById(
            @Parameter(description = "Идентификатор решения", example = "42")
            @PathVariable Long id) {
        return ResponseEntity.ok(solutionService.getSolutionById(id));
    }
}
