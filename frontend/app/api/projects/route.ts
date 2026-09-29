import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут проектов:
 * браузер/страница -> /api/projects (Next.js, этот файл)
 * -> http://backend:8080/api/projects
 *
 * GET — список (query page/size пробрасываются как есть; сортировка
 * фиксирована на backend: updated_at DESC), POST — создание. Оба
 * требуют сессию: JWT достаётся из httpOnly-cookie и уходит на backend
 * заголовком Authorization (как /api/auth/me) — браузер токен не видит.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** Заголовки для backend-запроса с сессией. */
function backendHeaders(token: string, contentType?: string) {
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
  if (contentType) {
    headers["Content-Type"] = contentType;
  }
  return headers;
}

export async function GET(request: NextRequest) {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }

  try {
    const qs = new URL(request.url).searchParams.toString();
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects${qs ? `?${qs}` : ""}`,
      {
        headers: backendHeaders(token),
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
      },
    );

    const body = await backendResponse.text();
    return new NextResponse(body, {
      status: backendResponse.status,
      headers: { "Content-Type": "application/json" },
    });
  } catch {
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 },
    );
  }
}

export async function POST(request: NextRequest) {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }

  try {
    const body = await request.text();
    const backendResponse = await fetch(`${BACKEND_URL}/api/projects`, {
      method: "POST",
      headers: backendHeaders(token, "application/json"),
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });

    const responseBody = await backendResponse.text();
    return new NextResponse(responseBody, {
      status: backendResponse.status,
      headers: { "Content-Type": "application/json" },
    });
  } catch {
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 },
    );
  }
}
