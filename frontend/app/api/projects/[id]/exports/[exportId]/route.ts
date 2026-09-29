import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут выгрузки: GET — скачивание файла
 * (бинарный поток с Content-Type и Content-Disposition: attachment из
 * backend; filename* — кириллица имени проекта), DELETE — удаление
 * (файл + строка истории). Изоляция — на backend (404).
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string; exportId: string }> };

export async function GET(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id, exportId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(exportId)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/exports/${exportId}`,
      {
        headers: { Authorization: `Bearer ${token}` },
        cache: "no-store",
        signal: AbortSignal.timeout(30_000),
      },
    );
    if (!backendResponse.ok) {
      const body = await backendResponse.text();
      return new NextResponse(body, {
        status: backendResponse.status,
        headers: { "Content-Type": "application/json" },
      });
    }
    // бинарный поток: заголовки файла переносим как есть
    const file = await backendResponse.arrayBuffer();
    return new NextResponse(file, {
      status: 200,
      headers: {
        "Content-Type":
          backendResponse.headers.get("Content-Type")
            ?? "application/octet-stream",
        "Content-Disposition":
          backendResponse.headers.get("Content-Disposition")
            ?? `attachment; filename="RoboMatch-report-${exportId}.bin"`,
      },
    });
  } catch {
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 },
    );
  }
}

export async function DELETE(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id, exportId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(exportId)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/exports/${exportId}`,
      {
        method: "DELETE",
        headers: { Authorization: `Bearer ${token}` },
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (backendResponse.status === 204) {
      return new NextResponse(null, { status: 204 });
    }
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
