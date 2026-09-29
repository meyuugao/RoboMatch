import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут шаблона импорта: GET /api/projects/{id}/parameters/template
 * ?format=xlsx|csv -> backend. Бинарный ответ проксируется потоком с
 * сохранением Content-Type и Content-Disposition (скачивание файла).
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

  const format = request.nextUrl.searchParams.get("format");
  if (format !== null && !["xlsx", "csv"].includes(format)) {
    return NextResponse.json(
      { error: "Формат шаблона - xlsx или csv." },
      { status: 400 },
    );
  }

  try {
    const query = format ? `?format=${encodeURIComponent(format)}` : "";
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/parameters/template${query}`,
      {
        headers: { Authorization: `Bearer ${token}` },
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (backendResponse.status === 204) {
      return new NextResponse(null, { status: 204 });
    }
    if (!backendResponse.ok) {
      const responseBody = await backendResponse.text();
      return new NextResponse(responseBody, {
        status: backendResponse.status,
        headers: { "Content-Type": "application/json" },
      });
    }
    return new NextResponse(backendResponse.body, {
      status: backendResponse.status,
      headers: {
        "Content-Type":
          backendResponse.headers.get("Content-Type") ?? "application/octet-stream",
        ...(backendResponse.headers.get("Content-Disposition")
          ? { "Content-Disposition": backendResponse.headers.get("Content-Disposition")! }
          : {}),
      },
    });
  } catch {
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 },
    );
  }
}
