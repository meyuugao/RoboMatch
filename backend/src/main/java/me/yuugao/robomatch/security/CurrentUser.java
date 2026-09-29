package me.yuugao.robomatch.security;

import me.yuugao.robomatch.dto.UserDto;
import me.yuugao.robomatch.exception.UnauthorizedException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Доступ к идентификатору текущего пользователя из любого слоя
 * (изоляция проектов).
 * <p>
 * Вход requireUserId — fail-fast: null-пользователь
 * — это ошибка программирования (эндпоинт обязан быть authenticated в
 * SecurityConfig), и она должна падать громко с 401, а не молчаливой
 * фильтрацией по null user_id, которая вернула бы чужие проекты или
 * пустой список.
 * getUserId остался nullable для будущих гостевых контекстов (демо-расчёт).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
 * id аутентифицированного пользователя или null, если запрос без
 * валидного токена (гость). Только для контекстов, где гость —
 * нормальная ситуация; для изоляции данных использовать
 * {@link #requireUserId}.
 *
 * @return id текущего пользователя или null для гостевого запроса
 */
    public static Long getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof UserDto user
                && user.getId() != null) {
            return user.getId();
        }
        return null;
    }

    /**
 * id аутентифицированного пользователя; без токена — сразу 401.
 * Fail-fast: вызывается сервисами с изоляцией данных (проекты),
 * чтобы ошибка конфигурации путей не приводила к фильтрации по null.
 *
 * @return id текущего пользователя
 * @throws me.yuugao.robomatch.exception.UnauthorizedException запрос без валидного токена
 */
    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new UnauthorizedException("Требуется авторизация");
        }
        return userId;
    }
}

