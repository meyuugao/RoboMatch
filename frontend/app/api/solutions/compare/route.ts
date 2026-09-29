import { NextResponse } from "next/server";

/**
 * BFF-роут сравнения: GET /api/solutions/compare?ids=1,2,3 -> backend.
 * Статический сегмент compare приоритетнее динамического [id] в App
 * Router, роуты не конфликтуют.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET(request: Request) {
  const qs = new URL(request.url).searchParams.toString();
  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/solutions/compare${qs ? `?${qs}` : ""}`,
      { cache: "no-store", signal: AbortSignal.timeout(10_000) },
    );

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
