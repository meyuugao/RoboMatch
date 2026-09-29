package me.yuugao.robomatch.service;

import me.yuugao.robomatch.domain.User;
import me.yuugao.robomatch.domain.UserRole;
import me.yuugao.robomatch.dto.AuthDto;
import me.yuugao.robomatch.dto.LoginRequest;
import me.yuugao.robomatch.dto.RegisterRequest;
import me.yuugao.robomatch.dto.UserDto;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.UnauthorizedException;
import me.yuugao.robomatch.mapper.UserMapper;
import me.yuugao.robomatch.repository.UserRepository;
import me.yuugao.robomatch.security.JwtService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Бизнес-логика аккаунтов: регистрация, вход, профиль.
 * <p>
 * Правила:
 * - пароль хешируется BCryptPasswordEncoder (cost 12) и нигде не логируется;
 * - сообщение при неверном логине/пароле единое - без подсказки, что именно
 * неверно;
 * - регистрация всегда создаёт роль user (роль admin - только seed);
 * - гонка регистраций страхована UNIQUE-индексом: DataIntegrityViolation
 * переводится в тот же 409, что и обычное «логин занят»;
 * - сущность User наружу не отдаётся - только UserDto (маппер).
 */
@Service
@RequiredArgsConstructor
public class UserService {

    /**
 * Фиктивный bcrypt-хеш (cost 12) для ветки «логин не найден»: сравнение
 * выполняется и там, чтобы время ответа не выдавало существование
 * аккаунта (timing-перечисление логинов).
 * Хеш бессмысленной строки - совпадение невозможно.
 */
    private static final String DUMMY_HASH =
            "$2b$12$MzuMzh5aqA7e8VDh8KmpZuJTzf/hrjuyIcY3hAvhjvGpByOBWVAGS";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("Неверный логин или пароль");
    }

    /**
 * Регистрация: 201 + токен; 409 - логин занят (в том числе гонка).
 * saveAndFlush, чтобы UNIQUE-нарушение всплыло внутри try, а не при
 * коммите транзакции за пределами метода.
 *
 * @param request логин и пароль нового пользователя
 * @return JWT и профиль созданного пользователя
 */
    @Transactional
    public AuthDto register(RegisterRequest request) {
        if (userRepository.findByLogin(request.getLogin()).isPresent()) {
            throw new ConflictException("Логин уже занят");
        }
        User user = User.builder()
                .login(request.getLogin())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.USER)
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Два запроса с одним логином одновременно: оба прошли проверку
            // findByLogin, второй упал в UNIQUE(login) - это тоже 409.
            throw new ConflictException("Логин уже занят");
        }
        return buildAuthResponse(user);
    }

    /**
 * Вход: 200 + токен; 401 - неверный логин или пароль (сообщение единое).
 *
 * @param request логин и пароль
 * @return JWT и профиль пользователя
 */
    @Transactional(readOnly = true)
    public AuthDto login(LoginRequest request) {
        User user = userRepository.findByLogin(request.getLogin()).orElse(null);
        if (user == null) {
            // тот же объём работы bcrypt, что и при существующем логине -
            // время ответа не отличается
            passwordEncoder.matches(request.getPassword(), DUMMY_HASH);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return buildAuthResponse(user);
    }

    /**
 * Свежий профиль по логину (subject JWT). Вызывается на каждый запрос
 * с токеном из JwtAuthFilter - роль и существование аккаунта берутся
 * из БД, а не из токена: отзыв роли/аккаунта применяется сразу.
 *
 * @param login логин (subject JWT)
 * @return профиль пользователя из БД
 */
    @Transactional(readOnly = true)
    public UserDto getByLogin(String login) {
        return userRepository.findByLogin(login)
                .map(userMapper::toDto)
                .orElseThrow(UserService::invalidCredentials);
    }

    private AuthDto buildAuthResponse(User user) {
        return AuthDto.builder()
                .token(jwtService.generateToken(user.getLogin()))
                .user(userMapper.toDto(user))
                .build();
    }
}
