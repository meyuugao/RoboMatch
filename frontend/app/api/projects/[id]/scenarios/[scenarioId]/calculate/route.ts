import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут расчёта сценария. Таймаут 15 с: расчёт выполняется
 * синхронно до 10 с + запас на сеть; кнопка в UI показывает
 * индикатор выполнения. Сессия - httpOnly-cookie.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** Расчёт ≤ 10 с + запас. */
const TIMEOUT_MS = 15_000;

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
      `${BACKEND_URL}/api/projects/${id}/scenarios/${scenarioId}/calculate`,
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
