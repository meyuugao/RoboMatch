import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут допущений экономики: чтение (дефолты каталога +
 * переопределения пользователя) и изменение. Сессия — httpOnly-cookie.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string }> };

async function proxy(
  request: NextRequest,
  id: string,
  method: "GET" | "PUT",
): Promise<NextResponse> {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "Некорректный id проекта" }, { status: 400 });
  }
  let body: string | undefined;
  if (method === "PUT") {
    body = await request.text();
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/assumptions`,
      {
        method,
        headers: {
          Authorization: `Bearer ${token}`,
          ...(method === "PUT" ? { "Content-Type": "application/json" } : {}),
        },
        ...(body !== undefined ? { body } : {}),
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

export async function PUT(request: NextRequest, { params }: Params) {
  const { id } = await params;
  return proxy(request, id, "PUT");
}
