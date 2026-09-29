package me.yuugao.robomatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import org.junit.jupiter.api.Test;

import io.jsonwebtoken.JwtException;

/**
 * Unit-тесты JwtService (без Spring-контекста). Секрет — фиксированная
 * строка длиннее 32 байт (требование HMAC-SHA256).
 * <p>
 * Отдельно проверен fail-fast по секрету: короткий/пустой — ошибка старта
 * (защита от «молча слабого» секрета в проде).
 */
class JwtServiceTest {

    private static final String SECRET =
            "unit-test-secret-0123456789-0123456789-0123456789";

    private final JwtService jwtService = new JwtService(SECRET, 24);

    @Test
    void tokenRoundTrip_returnsLogin() {
        String token = jwtService.generateToken("user");

        assertThat(token).isNotBlank();
        assertThat(jwtService.validateTokenAndGetLogin(token)).isEqualTo("user");
    }

    @Test
    void tokensOfDifferentLogins_differ() {
        // Осмысленная проверка вместо «два токена в одну секунду равны»:
        // iat/exp у JWT имеют секундную точность — одинаковые логины в одну
        // секунду дают идентичные токены (норма), разные логины — никогда.
        String adminToken = jwtService.generateToken("admin");
        String userToken = jwtService.generateToken("user");

        assertThat(adminToken).isNotEqualTo(userToken);
        assertThat(jwtService.validateTokenAndGetLogin(adminToken)).isEqualTo("admin");
        assertThat(jwtService.validateTokenAndGetLogin(userToken)).isEqualTo("user");
    }

    @Test
    void expiredToken_isRejected() {
        // ttl = -1 час: expiration в прошлом — детерминированно истёкший токен
        JwtService expired = new JwtService(SECRET, -1);
        String token = expired.generateToken("user");

        assertThatThrownBy(() -> jwtService.validateTokenAndGetLogin(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithOtherSecret_isRejected() {
        JwtService other = new JwtService(
                "another-secret-9876543210-9876543210-9876543210", 24);
        String foreignToken = other.generateToken("user");

        assertThatThrownBy(() -> jwtService.validateTokenAndGetLogin(foreignToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void garbageToken_isRejected() {
        assertThatThrownBy(() -> jwtService.validateTokenAndGetLogin("не-токен"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecret_failsFast() {
        assertThatThrownBy(() -> new JwtService("короткий", 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void blankSecret_failsFast() {
        assertThatThrownBy(() -> new JwtService("", 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void changeMePlaceholderSecret_failsFast() {
        // плейсхолдер из .env.example проходит проверку длины, но публично
        // известен — стартовать на нём нельзя
        assertThatThrownBy(() ->
                new JwtService("change-me-0123456789-0123456789-0123456789", 24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("плейсхолдер");
    }
}
