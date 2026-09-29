package me.yuugao.robomatch.config;

import me.yuugao.robomatch.security.JwtAuthFilter;
import me.yuugao.robomatch.security.JwtService;
import me.yuugao.robomatch.service.UserService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security: роли, пароли, правила путей.
 * <p>
 * Карта доступа:
 * - permitAll — только гостевой минимум (mvp_scope.md, сценарий гостя):
 * регистрация/вход, каталог /api/solutions/**, Swagger/OpenAPI
 * и /error (forward ошибок не должен сам упираться в 403);
 * - /api/admin/** — hasRole('ADMIN') (админка);
 * - прочие /api/** — authenticated;
 * - всё остальное — denyAll: backend не раздаёт ничего, кроме API.
 * <p>
 * Технические решения:
 * - csrf.disable: REST API без cookie-сессий браузера — CSRF неприменим
 * (токен передаётся заголовком Authorization, не cookie);
 * - STATELESS: сервер не хранит сессии, вся аутентификация — в JWT;
 * - BCryptPasswordEncoder(12): встроен в spring-security-crypto,
 * читает $2b$-хеши python-bcrypt из seed;
 * - JwtAuthFilter создаётся через new (НЕ бин) — иначе двойная регистрация
 * ломает OncePerRequestFilter, см. javadoc фильтра;
 * - 401/403 отдаются тем же JSON-контрактом ErrorResponse, что и из
 * контроллеров — JSON пишется вручную, без зависимости от
 * ObjectMapper (Jackson 3 в Boot 4 не даёт бин Jackson 2).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
 * ErrorResponse тем же форматом, что и GlobalExceptionHandler.
 */
    private static void writeJson(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"timestamp\":\"" + Instant.now()
                        + "\",\"status\":" + status.value()
                        + ",\"error\":\"" + status.getReasonPhrase()
                        + "\",\"message\":\"" + message + "\"}");
    }

    /**
 * Кодировщик паролей для хеша при регистрации и сверки при входе.
 *
 * @return BCrypt со cost 12 — читает $2b$-хеши python-bcrypt из seed
 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // cost 12 — ~0.25 на хеш: подбор по словарю дорог, логин не тормозит.
        return new BCryptPasswordEncoder(12);
    }

    /**
 * Зависимости (JwtService, UserService) — ПАРАМЕТРЫ метода, а не поля
 * конструктора: иначе цикл бинов SecurityConfig -> UserService ->
 * PasswordEncoder(@Bean этого же класса) -> SecurityConfig.
 * С параметрами @Bean-метода Spring резолвит их к моменту сборки цепочки.
 *
 * @param http конфигуратор цепочки фильтров
 * @param jwtService проверка JWT-токенов
 * @param userService загрузка principal из БД
 * @return цепочка Spring Security (STATELESS + JwtAuthFilter)
 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   UserService userService) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // гостевой минимум: вход/регистрация, каталог со словарями
                        // фильтров (/api/filters нужен странице /catalog гостю),
                        // демо-расчёт без входа и без сохранения, документация
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/solutions/**",
                                "/api/filters",
                                "/api/demo/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/error")
                        .permitAll()
                        // админские эндпоинты — только админ
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // остальное API — любому аутентифицированному
                        .requestMatchers("/api/**").authenticated()
                        // ничего кроме API backend не отдаёт
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        // 401: нет/просрочен токен
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, HttpStatus.UNAUTHORIZED, "Требуется авторизация"))
                        // 403: токен есть, роли не хватает
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJson(response, HttpStatus.FORBIDDEN, "Недостаточно прав")))
                .addFilterBefore(new JwtAuthFilter(jwtService, userService),
                        UsernamePasswordAuthenticationFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
