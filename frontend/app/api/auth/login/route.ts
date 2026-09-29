import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE, SESSION_TTL_SECONDS, sessionCookieOptions } from "@/lib/auth";

/**
 * BFF-роут входа: браузер -> POST /api/auth/login (здесь) -> backend.
 *
 * Схема: берём {login, password}, проверяем на Spring Boot, полученный
 * JWT кладём в httpOnly-cookie (БРАУЗЕР токен не получает) и возвращаем
 * наружу только профиль пользователя. Дальше каждый запрос к BFF несёт
 * cookie, а BFF добавляет Authorization: Bearer при проксировании.
 *
 * Ошибки backend (401 — неверный логин/пароль, 400) пробрасываются
 * наружу как есть — с русским сообщением из ErrorResponse.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function POST(request: NextRequest) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json(
      { error: "Некорректный формат тела запроса — ожидается JSON." },
      { status: 400 },
    );
  }

  try {
    const backendResponse = await fetch(`${BACKEND_URL}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      cache: "no-store",
        signal: AbortSignal.timeout(10_000),
    });

    const data = await backendResponse.json().catch(() => null);
    if (!backendResponse.ok || !data?.token) {
      return NextResponse.json(
        {
          error:
            data?.message ?? data?.error ?? `Backend ответил ${backendResponse.status}`,
        },
        { status: backendResponse.ok ? 502 : backendResponse.status }
      );
    }

    // наружу — только профиль; токен остаётся на сервере (cookie)
    const response = NextResponse.json({ user: data.user });
    response.cookies.set(
      SESSION_COOKIE,
      data.token,
      sessionCookieOptions(SESSION_TTL_SECONDS)
    );
    return response;
  } catch {
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 }
    );
  }
}
