package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * Запрос входа: POST /api/auth/login.
 * <p>
 * Длины не проверяются намеренно: на входе проверяются только логин/пароль
 * из БД, а сообщение об ошибке единое («Неверный логин или пароль») —
 * не подсказываем, что именно неверно.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Запрос входа (POST /api/auth/login)")
public class LoginRequest {

    @Schema(description = "Логин", example = "user")
    @NotBlank(message = "Логин обязателен")
    private String login;

    @Schema(description = "Пароль", example = "useruser")
    @NotBlank(message = "Пароль обязателен")
    private String password;
}
