import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут значения параметра: PUT/DELETE /api/projects/{id}/parameters/
 * {parameterId} -> backend. Схема сохранения — per-field PUT:
 * форма отправляет только изменённые параметры, по одному запросу.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string; parameterId: string }> };

async function proxy(
  request: NextRequest,
  id: string,
  parameterId: string,
  method: "PUT" | "DELETE",
) {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(parameterId)) {
    return NextResponse.json(
      { error: "Некорректный идентификатор" },
      { status: 400 },
    );
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
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/parameters/${parameterId}`,
      { method, headers, body, cache: "no-store", signal: AbortSignal.timeout(10_000) },
    );
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

export async function PUT(request: NextRequest, { params }: Params) {
  const { id, parameterId } = await params;
  return proxy(request, id, parameterId, "PUT");
}

export async function DELETE(request: NextRequest, { params }: Params) {
  const { id, parameterId } = await params;
  return proxy(request, id, parameterId, "DELETE");
}
