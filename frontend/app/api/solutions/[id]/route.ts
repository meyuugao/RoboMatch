import { NextResponse } from "next/server";

/**
 * BFF-роут карточки решения: GET /api/solutions/{id} -> backend.
 * Валидация id (число) - здесь, остальное (404 и состав карточки) -
 * на стороне backend.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
) {
  const { id } = await params;
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "Некорректный id решения" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(`${BACKEND_URL}/api/solutions/${id}`, {
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
