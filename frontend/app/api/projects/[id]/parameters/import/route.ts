import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут импорта параметров: POST /api/projects/{id}/parameters/import
 * -> backend (multipart/form-data). Файл читается formData и
 * переотправляется тем же multipart — Next сам ставит boundary. Лимит —
 * на backend (413); таймаут увеличен относительно JSON-роутов: разбор
 * Excel до 10 МБ может занять секунды.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** Мягкий предел BFF: тело запроса больше этого — 413 до буферизации
 * formData в память Next-процесса (точный лимит применяет backend,
 * но нет смысла читать и переотправлять заведомо большое тело). */
const BFF_MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

type Params = { params: Promise<{ id: string }> };

export async function POST(request: NextRequest, { params }: Params) {
  const { id } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "Некорректный id проекта" }, { status: 400 });
  }
  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > BFF_MAX_UPLOAD_BYTES) {
    return NextResponse.json(
      { error: "Файл слишком большой. Уменьшите размер файла и повторите загрузку." },
      { status: 413 },
    );
  }

  try {
    const form = await request.formData();
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/parameters/import`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        body: form,
        signal: AbortSignal.timeout(30_000),
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
