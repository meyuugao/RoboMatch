import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут профиля: читает JWT из httpOnly-cookie и ходит с ним на
 * backend (/api/auth/me, Authorization: Bearer). Наружу — профиль
 * пользователя (для getCurrentUser в lib/auth.ts) или 401.
 *
 * Это единственное место, где токен достаётся из cookie: браузер
 * продолжает его не видеть.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET(request: NextRequest) {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }

  try {
    const backendResponse = await fetch(`${BACKEND_URL}/api/auth/me`, {
      headers: { Authorization: `Bearer ${token}` },
      cache: "no-store",
        signal: AbortSignal.timeout(10_000),
    });

    const data = await backendResponse.json().catch(() => null);
    if (!backendResponse.ok || !data?.login) {
      return NextResponse.json(
        { error: data?.message ?? "Сессия недействительна" },
        { status: 401 }
      );
    }
    return NextResponse.json(data);
  } catch {
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 }
    );
  }
}
