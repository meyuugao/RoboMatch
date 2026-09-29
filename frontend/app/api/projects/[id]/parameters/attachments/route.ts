import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут вложений проекта: GET /api/projects/{id}/parameters/
 * attachments -> backend. История загрузок (Excel/CSV).
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string }> };

export async function GET(request: NextRequest, { params }: Params) {
  const { id } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "Некорректный id проекта" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/parameters/attachments`,
      {
        headers: { Authorization: `Bearer ${token}` },
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
