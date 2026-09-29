package me.yuugao.robomatch.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Применение решения (строка solution_application): отрасль + процесс.
 * Имена резолвятся сервисом из справочников.
 */
@Getter
@Setter
@AllArgsConstructor
@Builder
@Schema(description = "Применение решения (отрасль + процесс)")
public class SolutionApplicationDto {

    @Schema(description = "Идентификатор отрасли", example = "1")
    private Long industryId;

    @Schema(description = "Отрасль", example = "Торговля и услуги")
    private String industryName;

    @Schema(description = "Идентификатор процесса", example = "2")
    private Long processId;

    @Schema(description = "Процесс", example = "Внутрискладская логистика")
    private String processName;

    @Schema(description = "Цена предложения для этой пары отрасль+процесс, руб.",
            example = "2500000.00")
    private BigDecimal offerPriceRub;
}
