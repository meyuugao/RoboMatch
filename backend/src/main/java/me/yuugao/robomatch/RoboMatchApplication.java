package me.yuugao.robomatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Точка входа приложения RoboMatch (backend).
 *
 * <p>Аннотация @SpringBootApplication включает:
 * - поиск компонентов (@Controller, @Service, @Repository...) в этом пакете и ниже;
 * - автоконфигурацию (DataSource, JPA, Flyway, Security) по содержимому classpath
 * и настройкам из application.yml.
 * <p>
 * При старте Spring Boot:
 * 1) поднимает пул соединений к PostgreSQL (spring.datasource);
 * 2) Flyway применяет миграции из classpath:db/migration (создаёт схему, если пусто);
 * 3) Hibernate проверяет (ddl-auto: validate), что сущности совпадают со схемой;
 * 4) Tomcat начинает слушать порт 8080.
 */
@SpringBootApplication
public class RoboMatchApplication {

    /**
 * Запускает контекст Spring Boot (встроенный Tomcat, миграции, аудит).
 *
 * @param args аргументы командной строки (не используются)
 */
    public static void main(String[] args) {
        SpringApplication.run(RoboMatchApplication.class, args);
    }
}
