import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут запуска имитации.
 * Таймаут 70 с: модель обязана уложиться в 60 с + запас на
 * сеть; UI показывает индикатор и предупреждение при превышении 60 с.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** Модель ≤ 60 с + запас. */
const TIMEOUT_MS = 70_000;

type Params = { params: Promise<{ id: string; scenarioId: string }> };

export async function POST(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id, scenarioId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(scenarioId)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/scenarios/${scenarioId}/simulation/run`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
        cache: "no-store",
        signal: AbortSignal.timeout(TIMEOUT_MS),
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
