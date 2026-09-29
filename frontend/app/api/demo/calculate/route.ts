import { NextResponse } from "next/server";

/**
 * BFF-роут гостевого демо-расчёта:
 * POST /api/demo/calculate -> backend. Гостевой - без сессии; расчёт
 * выполняется в памяти и не сохраняется.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function POST(request: Request) {
  try {
    // Тело необязательно: без него backend считает демо-набор «Склад»
    const raw = await request.text();
    const init: RequestInit = {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      signal: AbortSignal.timeout(15_000),
    };
    if (raw) {
      init.body = raw;
    }
    const backendResponse =
        await fetch(`${BACKEND_URL}/api/demo/calculate`, init);

    if (!backendResponse.ok) {
      const body = await backendResponse.text();
      return new NextResponse(body, {
        status: backendResponse.status,
        headers: { "Content-Type": "application/json" },
      });
    }

    return NextResponse.json(await backendResponse.json());
  } catch {
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 },
    );
  }
}
