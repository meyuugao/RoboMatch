package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Запрос регистрации: POST /api/auth/register.
 * <p>
 * Минимальные требования (не заданы конкурсными материалами, зафиксированы как допущение
 * команды):
 * - логин: 3–64 символа, латиница/цифры/точка/подчёркивание/дефис;
 * - пароль: 8–72 символа (72 — жёсткое ограничение bcrypt на длину
 * входа алгоритма, больше просто не имеет смысла).
 * <p>
 * Роль в запросе НЕТ намеренно: регистрация всегда создаёт роль user;
 * админ выдаётся только seed-ом демо-аккаунта (повысить себя через
 * API нельзя — requirements/roles.md).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Запрос регистрации (POST /api/auth/register)")
public class RegisterRequest {

    @Schema(description = "Логин: 3-64 символа, латиница, цифры, . _ -", example = "ivanov")
    @NotBlank(message = "Логин обязателен")
    @Size(min = 3, max = 64, message = "Логин: от 3 до 64 символов")
    @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
            message = "Логин: только латинские буквы, цифры и символы . _ -")
    private String login;

    @Schema(description = "Пароль: 8-72 символов", example = "qwerty123")
    @NotBlank(message = "Пароль обязателен")
    @Size(min = 8, max = 72, message = "Пароль: от 8 до 72 символов")
    private String password;
}
