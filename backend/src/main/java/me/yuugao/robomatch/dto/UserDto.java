package me.yuugao.robomatch.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Профиль пользователя в ответах API. Наружу НЕ отдаются ни password_hash,
 * ни JPA-сущность - только эти 4 поля
 * не отдавать;).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Профиль пользователя")
public class UserDto {

    @Schema(description = "Идентификатор пользователя", example = "1")
    private Long id;

    @Schema(description = "Логин", example = "user")
    private String login;

    @Schema(description = "Роль: guest | user | admin", example = "user",
            allowableValues = {"guest", "user", "admin"})
    private String role;

    @Schema(description = "Когда создан аккаунт (ISO-8601)",
            example = "2026-09-26T10:15:30Z")
    private Instant createdAt;
}
