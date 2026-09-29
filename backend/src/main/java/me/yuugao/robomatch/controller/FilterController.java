package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.FiltersDto;
import me.yuugao.robomatch.service.SolutionService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * REST-контроллер словарей фильтров каталога.
 * <p>
 * Отдельный контроллер (не метод в SolutionController), потому что базовый
 * путь другой: /api/filters. Эндпоинт гостевой — каталог доступен без
 * авторизации (SecurityConfig), поэтому permitAll включает /api/filters.
 */
@RestController
@RequestMapping("/api/filters")
@RequiredArgsConstructor
@Tag(name = "Справочники каталога", description = "Словари и диапазоны для панели фильтров")
public class FilterController {

    private final SolutionService solutionService;

    /**
 * Словари фильтров одним ответом. GET /api/filters
 *
 * @return FiltersDto — типы/подтипы с сочетаниями, отрасли,
 * процессы, статусы, фактические диапазоны цены и УГТ
 */
    @GetMapping
    @Operation(summary = "Словари фильтров каталога",
            description = "Типы и подтипы решений (с допустимыми сочетаниями), отрасли, "
                    + "процессы, статусы, фактические диапазоны цены и УГТ.")
    @ApiResponses(
            @ApiResponse(responseCode = "200", description = "Словари фильтров"))
    public FiltersDto getFilters() {
        return solutionService.getFilters();
    }
}
