package me.yuugao.robomatch.controller;

import me.yuugao.robomatch.dto.AuthDto;
import me.yuugao.robomatch.dto.LoginRequest;
import me.yuugao.robomatch.dto.RegisterRequest;
import me.yuugao.robomatch.dto.UserDto;
import me.yuugao.robomatch.exception.BadRequestException;
import me.yuugao.robomatch.exception.ConflictException;
import me.yuugao.robomatch.exception.UnauthorizedException;
import me.yuugao.robomatch.service.UserService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Аутентификация: регистрация, вход, текущий профиль.
 * <p>
 * Цепочка вызова та же, что у каталога: controller -> service -> repository.
 * Все три эндпоинта открыты в SecurityConfig точечно: register и login -
 * permitAll, /me - authenticated (требует заголовок Authorization: Bearer).
 * <p>
 * Коды ответов:
 * - 201/200 + AuthDto - успех (JWT + профиль);
 * - 400 - невалидный ввод (@Valid, GlobalExceptionHandler);
 * - 401 - неверный логин/пароль или нет/просрочен токен;
 * - 409 - логин уже занят.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Аутентификация", description = "Регистрация, вход и профиль пользователя")
public class UserController {

    private final UserService userService;

    /**
 * Регистрация. POST /api/auth/register
 *
 * @param request логин, пароль, имя пользователя
 * @return 201 + AuthDto (JWT и профиль с ролью user)
 * @throws BadRequestException 400 - невалидный ввод
 * @throws ConflictException 409 - логин уже занят
 */
    @PostMapping("/register")
    @Operation(summary = "Регистрация",
            description = "Создаёт аккаунт с ролью user и выдаёт JWT. "
                    + "409 - логин занят, 400 - невалидный ввод")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Аккаунт создан, выдан JWT"),
            @ApiResponse(responseCode = "400", description = "Невалидный ввод"),
            @ApiResponse(responseCode = "409", description = "Логин занят")
    })
    public ResponseEntity<AuthDto> register(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"login\": \"ivanov\", \"password\": \"qwerty123\"}")))
            RegisterRequest request) {
        AuthDto response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
 * Вход. POST /api/auth/login
 *
 * @param request логин и пароль
 * @return AuthDto с JWT и профилем
 * @throws BadRequestException 400 - невалидный ввод
 * @throws UnauthorizedException 401 - неверный логин или пароль
 */
    @PostMapping("/login")
    @Operation(summary = "Вход",
            description = "Проверяет логин и пароль, выдаёт JWT. "
                    + "401 - неверный логин или пароль")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "JWT выдан"),
            @ApiResponse(responseCode = "400", description = "Невалидный ввод"),
            @ApiResponse(responseCode = "401", description = "Неверный логин или пароль")
    })
    public AuthDto login(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(
                                    value = "{\"login\": \"user\", \"password\": \"user\"}")))
            LoginRequest request) {
        return userService.login(request);
    }

    /**
 * Текущий профиль. GET /api/auth/me (заголовок Authorization: Bearer)
 *
 * @param user профиль из JWT (заполняет JwtAuthFilter из БД)
 * @return UserDto текущего пользователя
 */
    @GetMapping("/me")
    @Operation(summary = "Текущий пользователь",
            description = "Возвращает профиль по Bearer-токену; 401 - без токена")
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Профиль пользователя"),
            @ApiResponse(responseCode = "401", description = "Нет или просрочен Bearer-токен")
    })
    public UserDto me(@AuthenticationPrincipal UserDto user) {
        // Principal уже UserDto: JwtAuthFilter загрузил свежий профиль из БД.
        return user;
    }
}
