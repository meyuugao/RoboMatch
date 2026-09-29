package me.yuugao.robomatch.security;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Тесты PasswordEncoder (BCrypt, cost 12 — как бин в SecurityConfig).
 * <p>
 * Ключевой сценарий — совместимость с seed: демо-аккаунты создаёт
 * python-скрипт bcrypt'ом с префиксом $2b$; Spring BCryptPasswordEncoder
 * обязан читать такие хеши.
 */
class PasswordEncoderTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Test
    void encodeAndMatch_roundTrip() {
        String hash = encoder.encode("qwerty123");

        assertThat(hash).startsWith("$2");
        assertThat(encoder.matches("qwerty123", hash)).isTrue();
    }

    @Test
    void wrongPassword_doesNotMatch() {
        String hash = encoder.encode("qwerty123");

        assertThat(encoder.matches("qwerty124", hash)).isFalse();
    }

    @Test
    void hashDoesNotContainPassword() {
        String hash = encoder.encode("qwerty123");

        assertThat(hash).doesNotContain("qwerty123");
    }

    @Test
    void pythonBcryptHash_isReadable() {
        // Хеш пароля «admin», сгенерированный python-bcrypt 4.x:
        // bcrypt.hashpw(b'admin', bcrypt.gensalt(rounds=12, prefix=b'2b'))
        // — ровно так seed создаёт демо-аккаунты (repositories/users_repo.py).
        String pythonHash = "$2b$12$crKG8WJe/9g94Wj.iAZzcO99YYV0CuyNDJwwTo3vZUVwJ1blGpsDe";

        assertThat(encoder.matches("admin", pythonHash)).isTrue();
        assertThat(encoder.matches("user", pythonHash)).isFalse();
    }
}
