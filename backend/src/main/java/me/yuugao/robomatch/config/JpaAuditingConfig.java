package me.yuugao.robomatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Включает аудит Spring Data JPA для полей @CreatedDate / @LastModifiedDate
 * в сущности Solution (created_at / updated_at).
 * <p>
 * Без этой конфигурации аннотации аудита молча не работают — частая ошибка.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
