package me.yuugao.robomatch.mapper;

import me.yuugao.robomatch.domain.User;
import me.yuugao.robomatch.domain.UserRole;
import me.yuugao.robomatch.dto.UserDto;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Преобразование User (БД) -> UserDto (API). Ручной маппер, как
 * SolutionMapper — каждое поле видно явно. password_hash наружу не
 * уходит ни при каких обстоятельствах.
 */
@Component
public class UserMapper {

    /**
 * User (БД) -> UserDto (API). Роль — строкой в нижнем регистре, как в БД.
 *
 * @param user сущность пользователя (null -> null; без password_hash)
 * @return DTO пользователя для API
 */
    public UserDto toDto(User user) {
        if (user == null) {
            return null;
        }
        UserRole role = user.getRole();
        return UserDto.builder()
                .id(user.getId())
                .login(user.getLogin())
                .role(role == null ? null : role.name().toLowerCase(Locale.ROOT))
                .createdAt(user.getCreatedAt())
                .build();
    }
}
