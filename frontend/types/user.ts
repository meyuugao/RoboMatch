/**
 * TypeScript-типы пользователя - зеркало backend-DTO
 * (UserDto.java, AuthDto.java). Числа приходят как number,
 * даты - как строки (JSON не имеет типа Date).
 */
export type UserRole = "guest" | "user" | "admin";

export interface User {
  id: number;
  login: string;
  role: UserRole;
  createdAt: string;
}

/**
 * Человекочитаемые подписи ролей (интерфейс - русский,.
 */
export const USER_ROLE_LABELS: Record<UserRole, string> = {
  guest: "Гость",
  user: "Пользователь",
  admin: "Администратор",
};
