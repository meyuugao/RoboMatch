package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Запрос генерации выгрузки: формат — PDF-отчёт либо
 * расчётные таблицы Excel/CSV. Тело POST /api/projects/{id}/exports.
 *
 * <p>Тема (тёмная тема): цветовая схема PDF-отчёта.
 * «light» — белые страницы (по умолчанию, в т.ч. при отсутствии
 * поля), «dark» — сланцевые страницы со светлым текстом: одинаковый
 * контент в обеих темах, выбор не зависит от темы браузера —
 * пользователь выбирает на странице экспорта. Excel/CSV —
 * машиночитаемые форматы, тема не применяется.
 *
 * @param format формат отчёта: pdf | xlsx | csv
 * @param theme тема PDF-отчёта: light | dark
 * @param schemaTheme тема 2D-схемы имитации в отчёте: ui (как
 * сохранено в интерфейсе, по умолчанию) | light |
 * dark — при отличии сохранённой схемы она
 * перерисовывается сервером в выбранной теме
 */
@Schema(description = "Запрос генерации выгрузки отчёта")
public record ExportCreateRequest(
        @Schema(description = "Формат отчёта: pdf — человекочитаемый "
                + "итоговый отчёт; xlsx — машиночитаемые расчётные "
                + "таблицы; csv — плоская таблица (UTF-8, «;», запятая "
                + "в десятичных дробях)", example = "pdf",
                allowableValues = {"pdf", "xlsx", "csv"})
        @NotBlank(message = "Укажите формат отчёта: pdf, xlsx или csv")
        @Pattern(regexp = "pdf|xlsx|csv",
                message = "Формат отчёта — pdf, xlsx или csv")
        String format,
        @Schema(description = "Тема PDF-отчёта: light — белые страницы "
                + "(по умолчанию), dark — тёмные со светлым текстом; "
                + "на Excel/CSV не влияет", example = "dark",
                allowableValues = {"light", "dark"})
        @Pattern(regexp = "light|dark",
                message = "Тема отчёта — light или dark")
        String theme,
        @Schema(description = "Тема 2D-схемы имитации в отчёте: "
                + "ui — как сохранена в интерфейсе (по умолчанию), "
                + "light — светлая, dark — тёмная; при отличии "
                + "сохранённой схемы от выбранной темы схема "
                + "перерисовывается сервером", example = "dark",
                allowableValues = {"ui", "light", "dark"})
        @Pattern(regexp = "ui|light|dark",
                message = "Тема схемы — ui, light или dark")
        String schemaTheme
) {
}
