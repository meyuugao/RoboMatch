package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.DemoCalculationDto;
import me.yuugao.robomatch.dto.DemoCalculateRequest;
import me.yuugao.robomatch.dto.DemoDescriptorDto;
import me.yuugao.robomatch.economics.DemoCalculationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Гостевой демо-расчёт: описание демо-набора и расчёт трёх сценариев без
 * входа и БЕЗ сохранения (роль «гость»). Оба эндпоинта открыты без
 * аутентификации (SecurityConfig: /api/demo/** в permitAll) — гостевой
 * минимум рядом с каталогом; запись в БД не выполняется в принципе.
 */
@Tag(name = "Гостевой демо-расчёт",
        description = "Знакомство с платформой без регистрации: описание "
                + "демо-набора данных и сравнение трёх сценариев "
                + "(текущий процесс / покупка / роботы как услуга) "
                + "в памяти, без сохранения")
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoCalculationService demoCalculationService;

    public DemoController(DemoCalculationService demoCalculationService) {
        this.demoCalculationService = demoCalculationService;
    }

    /**
 * Описание демо-расчёта: типы объектов, параметры демо-набора,
 * демонстрационный состав.
 *
 * @return дескриптор демо-расчёта
 */
    @GetMapping
    @Operation(summary = "Описание демо-расчёта",
            description = "Типы объектов с признаком доступности демо, "
                    + "параметры демо-набора данных «Склад» "
                    + "и демонстрационный состав решений из каталога.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Дескриптор демо-расчёта")
    })
    public ResponseEntity<DemoDescriptorDto> descriptor() {
        return ResponseEntity.ok(demoCalculationService.descriptor());
    }

    /**
 * Демо-расчёт в памяти: сравнение трёх сценариев на константах
 * демо-набора. Ничего не сохраняет: ни проекта, ни расчёта.
 *
 * @param request выбранный тип объекта (необязательно, по умолчанию
 * «Склад»)
 * @return сравнение сценариев с признаком демо
 */
    @PostMapping("/calculate")
    @Operation(summary = "Выполнить демо-расчёт",
            description = "Сравнение трёх сценариев (текущий процесс / "
                    + "покупка оборудования / роботы как услуга) на "
                    + "константах демо-набора «Склад». Расчёт выполняется "
                    + "в памяти и не сохраняется.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Сравнение сценариев демо-расчёта"),
            @ApiResponse(responseCode = "400",
                    description = "Тип объекта без демо-набора данных"),
            @ApiResponse(responseCode = "404",
                    description = "Тип объекта не найден")
    })
    public ResponseEntity<DemoCalculationDto> calculate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Выбранный тип объекта (необязательно)",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"objectTypeCode\": \"warehouse\"}")))
            @RequestBody(required = false) DemoCalculateRequest request) {
        String objectTypeCode = request == null
                ? null : request.objectTypeCode();
        return ResponseEntity.ok(
                demoCalculationService.calculate(objectTypeCode));
    }
}
