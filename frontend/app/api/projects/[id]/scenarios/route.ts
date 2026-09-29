import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут сценариев: список с составом
 * оборудования и создание (конкретный сценарий или восстановление
 * недостающих). Сессия — из httpOnly-cookie; статусы и тексты ошибок —
 * от backend (изоляция 404 / валидация 400 / конфликты 409 — его зона).
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string }> };

async function proxy(
  request: NextRequest,
  id: string,
  method: "GET" | "POST",
): Promise<NextResponse> {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "Некорректный id проекта" }, { status: 400 });
  }
  let body: string | undefined;
  if (method === "POST") {
    body = await request.text();
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/scenarios`,
      {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
        },
        ...(body !== undefined && body.length > 0 ? { body } : {}),
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
      },
    );
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

export async function GET(request: NextRequest, { params }: Params) {
  const { id } = await params;
  return proxy(request, id, "GET");
}

export async function POST(request: NextRequest, { params }: Params) {
  const { id } = await params;
  return proxy(request, id, "POST");
}
