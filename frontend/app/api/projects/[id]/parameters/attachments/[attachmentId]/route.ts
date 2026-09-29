import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут удаления вложения: DELETE /api/projects/{id}/parameters/
 * attachments/{attachmentId} -> backend (файл + строка БД). 204 без тела.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string; attachmentId: string }> };

export async function DELETE(request: NextRequest, { params }: Params) {
  const { id, attachmentId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(attachmentId)) {
    return NextResponse.json(
      { error: "Некорректный идентификатор" },
      { status: 400 },
    );
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/parameters/attachments/${attachmentId}`,
      {
        method: "DELETE",
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(10_000),
      },
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
