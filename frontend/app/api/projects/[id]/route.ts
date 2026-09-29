import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут одного проекта: GET/PUT/DELETE /api/projects/{id} -> backend.
 * Валидация id (число) - здесь; 404 (несуществующий/чужой), валидация
 * тела и изоляция - на стороне backend. Сессия - из httpOnly-cookie.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string }> };

async function proxy(
  request: NextRequest,
  id: string,
  method: "GET" | "PUT" | "DELETE",
) {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "Некорректный id проекта" }, { status: 400 });
  }

  try {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
    };
    let body: string | undefined;
    if (method === "PUT") {
      headers["Content-Type"] = "application/json";
      body = await request.text();
    }
    const backendResponse = await fetch(`${BACKEND_URL}/api/projects/${id}`, {
      method,
      headers,
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(10_000),
    });

    // 204 (DELETE) не должен иметь тела - NextResponse с телом на 204
    // падает и превращался в 502 (backend удалял, а BFF ошибался)
    if (backendResponse.status === 204) {
      return new NextResponse(null, { status: 204 });
    }

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

export async function PUT(request: NextRequest, { params }: Params) {
  const { id } = await params;
  return proxy(request, id, "PUT");
}

export async function DELETE(request: NextRequest, { params }: Params) {
  const { id } = await params;
  return proxy(request, id, "DELETE");
}
