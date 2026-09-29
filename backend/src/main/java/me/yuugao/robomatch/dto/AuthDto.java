package me.yuugao.robomatch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * Ответ регистрации и входа: JWT + профиль.
 * <p>
 * Токен живёт ТОЛЬКО на серверной стороне: браузер его не получает
 * напрямую — Next.js BFF кладёт его в httpOnly-cookie и сам подставляет
 * заголовок Authorization при проксировании (docs/architecture.md §5).
 * В Swagger UI токен виден, чтобы тестировать API напрямую.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Ответ регистрации и входа (JWT + профиль)")
public class AuthDto {

    @Schema(description = "JWT (HMAC-SHA256, TTL 24 ч)", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String token;

    @Schema(description = "Профиль пользователя",
            example = "{\"id\": 1, \"login\": \"user\", \"role\": \"user\"}")
    private UserDto user;
}
