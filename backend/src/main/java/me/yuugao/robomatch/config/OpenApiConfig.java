package me.yuugao.robomatch.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;

/**
 * Конфигурация OpenAPI (springdoc-openapi).
 * <p>
 * Что даёт:
 * - /v3/api-docs - машиночитаемая OpenAPI-спецификация (JSON);
 * - /swagger-ui.html - интерактивная документация в браузере.
 * <p>
 * Это закрывает требования и 3.8.5: «OpenAPI-спецификация;
 * все реализованные API документированы» (mvp_scope.md, Архитектура).
 *
 * <p>Аннотация @SecurityScheme объявляет схему авторизации «bearer-jwt»:
 * когда JWT реализован, в Swagger UI появляется кнопка Authorize -
 * можно тестировать защищённые эндпоинты прямо из документации.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "RoboMatch API",
                version = "0.1.0",
                description = "REST API платформы предынвестиционной оценки "
                        + "роботизации: каталог решений (список с фильтрами "
                        + "и поиском, карточка, сравнение), параметры объекта, "
                        + "подбор, экономика, имитация, экспорт отчётов. "
                        + "Аутентификация - JWT (кнопка Authorize)."
        )
)
@SecurityScheme(
        name = "bearer-jwt",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiConfig {
    // Декларативная конфигурация: аннотации выше - вся настройка.
}
