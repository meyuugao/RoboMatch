package me.yuugao.robomatch.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Генерация и проверка JWT (HMAC-SHA256, jjwt 0.13).
 * <p>
 * Токен — доступ-only, без refresh: TTL 24 часа, после истечения нужно
 * войти заново (refresh-токены — вне границ MVP).
 * Токен живёт только на серверной стороне: браузер его не видит —
 * Next.js BFF держит его в httpOnly-cookie (architecture.md §5).
 * <p>
 * Fail-fast по секрету: JWT_SECRET передаётся ТОЛЬКО через переменную
 * окружения (docker-compose требует её наличия); пустой или короче
 * 32 байт секрет — ошибка старта с понятным сообщением, дефолтного
 * значения нет (защита от «молча слабого» секрета в проде).
 */
@Service
public class JwtService {

    /**
 * HMAC-SHA256 требует ключ не короче 256 бит = 32 байта.
 */
    public static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration ttl;

    /**
 * Создаёт сервис: проверяет секрет fail-fast и строит ключ HMAC.
 *
 * @param secret значение app.jwt.secret (только через переменную окружения);
 * пустой, короче 32 байт или плейсхолдер — ошибка старта
 * @param ttlHours время жизни токена в часах (app.jwt.ttl-hours, по умолчанию 24)
 */
    public JwtService(
            @Value("${app.jwt.secret:}") String secret,
            @Value("${app.jwt.ttl-hours:24}") long ttlHours) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secret == null || secret.isBlank() || secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET не задан или короче " + MIN_SECRET_BYTES + " байт. "
                            + "Сгенерируйте секрет и передайте через переменную окружения: "
                            + "openssl rand -base64 48");
        }
        if (secret.startsWith("change-me")) {
            // .env.example содержит плейсхолдер «change-me-…», проходящий проверку длины:
            // скопированный без правки, он стал бы публично известным секретом
            // — отказываемся стартовать на нём
            throw new IllegalStateException(
                    "JWT_SECRET содержит плейсхолдер из .env.example. "
                            + "Сгенерируйте собственный секрет: openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.ttl = Duration.ofHours(ttlHours);
    }

    /**
 * Токен для логина: subject=login, iat=сейчас, exp=сейчас+TTL.
 *
 * @param login логин пользователя (subject токена)
 * @return подписанный JWT (HMAC-SHA256)
 */
    public String generateToken(String login) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(login)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
 * Проверка подписи и срока; возвращает login (subject).
 * Любая проблема (подпись, срок, формат) — io.jsonwebtoken.JwtException,
 * вызывающий код (JwtAuthFilter) трактует её как «нет аутентификации».
 *
 * @param token JWT из заголовка Authorization (без префикса Bearer)
 * @return login из subject валидного токена
 */
    public String validateTokenAndGetLogin(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }
}
