package me.yuugao.robomatch.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Описание гостевого демо-расчёта: доступные типы объектов, параметры
 * демо-набора и состав демо (для страницы демо-расчёта без входа).
 *
 * @param objectTypeName название типа объекта демо-расчёта
 * @param availableTypes типы объектов с признаком доступности демо-набора
 * @param parameters параметры демо-набора для показа (заголовок,
 * значение, единица)
 * @param composition демо-состав (решение, вендор, количество)
 */
@Schema(description = "Описание гостевого демо-расчёта")
public record DemoDescriptorDto(
        @Schema(description = "Название типа объекта демо-расчёта",
                example = "Склад")
        String objectTypeName,

        @Schema(description = "Типы объектов с признаком доступности демо",
                example = "[{\"code\": \"warehouse\", \"name\": \"Склад\", "
                        + "\"available\": true}]")
        List<DemoObjectTypeDto> availableTypes,

        @Schema(description = "Параметры демо-набора данных",
                example = "[{\"title\": \"Объём приёмки\", \"value\": "
                        + "\"1000\", \"unit\": \"поддон/сут\"}]")
        List<DemoParameterDto> parameters,

        @Schema(description = "Демонстрационный состав решений",
                example = "[{\"solutionName\": \"Ronavi H1500 "
                        + "(грузоподъемность до 1 500 кг)\", "
                        + "\"vendorName\": \"ООО \\\"Ронави Роботикс\\\"\", "
                        + "\"quantity\": 3}]")
        List<DemoCompositionDto> composition
) {

    /**
 * Тип объекта с признаком доступности демо-набора.
 *
 * @param code код типа объекта
 * @param name название
 * @param available доступен ли демо-расчёт
 */
    @Schema(description = "Тип объекта для демо-расчёта")
    public record DemoObjectTypeDto(
            @Schema(description = "Код типа объекта", example = "warehouse")
            String code,

            @Schema(description = "Название типа объекта", example = "Склад")
            String name,

            @Schema(description = "Доступен ли демо-расчёт для типа",
                    example = "true")
            boolean available
    ) {
    }

    /**
 * Параметр демо-набора для показа на странице.
 *
 * @param title человекочитаемое название
 * @param value значение (строкой, как в наборе)
 * @param unit единица измерения (пустая - безразмерный)
 */
    @Schema(description = "Параметр демо-набора данных")
    public record DemoParameterDto(
            @Schema(description = "Название параметра",
                    example = "Объём приёмки")
            String title,

            @Schema(description = "Значение из демо-набора", example = "1000")
            String value,

            @Schema(description = "Единица измерения", example = "поддон/сут")
            String unit
    ) {
    }

    /**
 * Строка демо-состава.
 *
 * @param solutionName название решения каталога
 * @param vendorName вендор
 * @param quantity количество, ед.
 */
    @Schema(description = "Решение демонстрационного состава")
    public record DemoCompositionDto(
            @Schema(description = "Название решения",
                    example = "Ronavi H1500 (грузоподъемность до 1 500 кг)")
            String solutionName,

            @Schema(description = "Вендор", example = "ООО \"Ронави Роботикс\"")
            String vendorName,

            @Schema(description = "Количество, ед.", example = "3")
            int quantity
    ) {
    }
}
