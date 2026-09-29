package me.yuugao.robomatch.security;

import me.yuugao.robomatch.dto.UserDto;
import me.yuugao.robomatch.service.UserService;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT-фильтр: читает Authorization: Bearer, проверяет токен, кладёт
 * аутентификацию в SecurityContext.
 * <p>
 * ВАЖНО: класс СОЗНАТЕЛЬНО не @Component и не бин. Если зарегистрировать
 * фильтр бином, Boot добавит его в servlet-цепочку ДО security-цепочки;
 * OncePerRequestFilter пометит запрос «уже отфильтрованным» (общая константа
 * на класс), и экземпляр внутри SecurityFilterChain молча пропустит работу
 * — все защищённые пути начнут отдавать 401. Поэтому SecurityConfig создаёт
 * фильтр через new и вставляет addFilterBefore.
 * <p>
 * Principal — свежий UserDto из БД (UserService.getByLogin), а не только
 * login из токена: смена роли или удаление аккаунта применяются немедленно,
 * без ожидания истечения токена.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserService userService;

    /**
 * Создаёт фильтр с зависимостями (НЕ бин — см. класс, регистрация в SecurityConfig).
 *
 * @param jwtService проверка токенов
 * @param userService свежий principal из БД (роль/удаление — сразу)
 */
    public JwtAuthFilter(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                String login = jwtService.validateTokenAndGetLogin(token);
                UserDto user = userService.getByLogin(login);
                var authorities = List.of(new SimpleGrantedAuthority(
                        "ROLE_" + user.getRole().toUpperCase(Locale.ROOT)));
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (io.jsonwebtoken.JwtException
                     | me.yuugao.robomatch.exception.UnauthorizedException e) {
                // Токен просрочен/подписан другим ключом/аккаунт удалён —
                // запрос остаётся неаутентифицированным: защищённые пути
                // ответят 401 точкой входа SecurityConfig.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
