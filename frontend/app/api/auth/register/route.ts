import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE, SESSION_TTL_SECONDS, sessionCookieOptions } from "@/lib/auth";

/**
 * BFF-роут регистрации: браузер -> POST /api/auth/register (здесь)
 * -> backend. Успех (201) сразу открывает сессию: JWT в httpOnly-cookie,
 * наружу - только профиль. 409 (логин занят) и 400 (невалидный ввод)
 * пробрасываются с русским сообщением.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();

    const backendResponse = await fetch(`${BACKEND_URL}/api/auth/register`, {
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

    // 201 - как в контракте backend; наружу - только профиль
    const response = NextResponse.json({ user: data.user }, { status: 201 });
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
