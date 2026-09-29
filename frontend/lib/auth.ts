import { headers } from "next/headers";
import { redirect } from "next/navigation";
import type { User } from "@/types/user";

/**
 * Сессия на стороне Next.js (BFF-паттерн, architecture.md §5).
 *
 * JWT физически лежит в httpOnly-cookie «robomatch_session»: браузер
 * и JS-код страницы этот токен НЕ видят. Все запросы к backend идут
 * только через BFF-роуты app/api/auth/*, которые подставляют заголовок
 * Authorization: Bearer из cookie.
 *
 * getCurrentUser зовёт СОБСТВЕННЫЙ /api/auth/me (как lib/api.ts зовёт
 * /api/solutions): серверный fetch требует абсолютный URL - берём его
 * из заголовков запроса. Cookie текущего посетителя пробрасывается
 * в подзапрос вручную.
 *
 * Любая ошибка (backend недоступен, нет cookie, 401) => null = гость:
 * страницы не должны падать из-за сессии.
 */

/** Имя cookie сессии (см. роуты app/api/auth). */
export const SESSION_COOKIE = "robomatch_session";

/** Срок жизни cookie сессии, секунды. По умолчанию 86400 = 24 ч (TTL JWT). */
export const SESSION_TTL_SECONDS = Number(
  process.env.SESSION_TTL_SECONDS ?? 86400
);

/**
 * Опции cookie сессии. secure - ТОЛЬКО через явную переменную
 * COOKIE_SECURE (не через NODE_ENV: в docker-контейнере Next.js
 * NODE_ENV=production, а демо работает по http - браузер отклонил бы
 * Secure-cookie и сессия молча не работала бы).
 */
export function sessionCookieOptions(maxAgeSeconds: number) {
  return {
    httpOnly: true as const,
    sameSite: "lax" as const,
    path: "/",
    secure: process.env.COOKIE_SECURE === "true",
    maxAge: maxAgeSeconds,
  };
}

/** Текущий пользователь или null (гость / сессия недействительна). */
export async function getCurrentUser(): Promise<User | null> {
  try {
    const requestHeaders = await headers();
    const appUrl =
      process.env.APP_URL ??
      `${requestHeaders.get("x-forwarded-proto") ?? "http"}://${requestHeaders.get("host")}`;

    const response = await fetch(`${appUrl}/api/auth/me`, {
      headers: { cookie: requestHeaders.get("cookie") ?? "" },
      cache: "no-store",
      // паритет с lib/api.ts: без сигнала зависший /api/auth/me держал бы
      // SSR до транзитивного таймаута
      signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) {
      return null;
    }
    return (await response.json()) as User;
  } catch {
    // backend недоступен и т.п. - считаем гостем, не вешаем страницу
    return null;
  }
}

/**
 * Требовать аутентификацию на защищённой странице: без сессии выполняет
 * server-side redirect на /login (
 * зарегистрированных). Возвращает пользователя для рендера.
 */
export async function requireUser(): Promise<User> {
  const user = await getCurrentUser();
  if (!user) {
    redirect("/login");
  }
  return user;
}

/**
 * Доступ только администратору: гость уходит
 * на вход, обычный пользователь - к своим проектам с сообщением через
 * query-параметр (без утечки данных раздела «Управление»).
 */
export async function requireAdmin(): Promise<User> {
  const user = await requireUser();
  if (user.role !== "admin") {
    redirect("/projects?forbidden=admin");
  }
  return user;
}
