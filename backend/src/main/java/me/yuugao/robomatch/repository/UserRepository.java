package me.yuugao.robomatch.repository;

import me.yuugao.robomatch.domain.User;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Репозиторий пользователей (Spring Data JPA).
 * <p>
 * Метод один - поиск по логину: логин является естественным ключом
 * (UNIQUE в V1) и subject'ом JWT. Регистрация проверяет занятость логина
 * через findByLogin, а гонку двух одновременных регистраций страхует
 * UNIQUE-индекс БД (DataIntegrityViolationException -> 409 в сервисе).
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
 * Пользователь по логину; Optional.empty - если логина нет.
 *
 * @param login логин (естественный ключ, UNIQUE в V1)
 * @return пользователь или Optional.empty, если логин не занят
 */
    Optional<User> findByLogin(String login);
}
