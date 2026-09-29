import { NextResponse } from "next/server";
import { SESSION_COOKIE, sessionCookieOptions } from "@/lib/auth";

/**
 * BFF-роут выхода: удаляет cookie сессии (maxAge=0).
 *
 * JWT - доступ-only без refresh: серверной «сессии» на backend нет,
 * поэтому достаточно стереть токен на стороне BFF. Сам токен перестаёт
 * действовать не позже TTL (24 ч).
 */
export const dynamic = "force-dynamic";

export async function POST() {
  const response = NextResponse.json({ ok: true });
  response.cookies.set(SESSION_COOKIE, "", sessionCookieOptions(0));
  return response;
}
