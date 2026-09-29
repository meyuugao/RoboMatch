import { NextResponse } from "next/server";

/**
 * BFF-роут словарей фильтров каталога:
 * GET /api/filters -> backend. Гостевой (каталог доступен без входа).
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET() {
  try {
    const backendResponse = await fetch(`${BACKEND_URL}/api/filters`, {
      cache: "no-store",
        signal: AbortSignal.timeout(10_000),
    });

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
