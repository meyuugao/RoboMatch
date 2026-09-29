import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роуты 2D-схемы имитации - симметричный путь по
 * simulationId: POST сохраняет SVG (сериализуется клиентом, файл
 * складывается в data/simulations/, ссылка - в kpi_json.exportUrl
 * результата), GET отдаёт сохранённый файл с Content-Disposition
 * attachment. Изоляция - на backend (404).
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string; simulationId: string }> };

export async function GET(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id, simulationId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(simulationId)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/simulations/${simulationId}/export`,
      {
        headers: { Authorization: `Bearer ${token}` },
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (!backendResponse.ok) {
      const body = await backendResponse.text();
      return new NextResponse(body, {
        status: backendResponse.status,
        headers: { "Content-Type": "application/json" },
      });
    }
    const svg = await backendResponse.text();
    return new NextResponse(svg, {
      status: 200,
      headers: {
        "Content-Type": "image/svg+xml",
        "Content-Disposition":
          backendResponse.headers.get("Content-Disposition")
            ?? `attachment; filename=simulation-${simulationId}.svg`,
      },
    });
  } catch {
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 },
    );
  }
}

export async function POST(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id, simulationId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(simulationId)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Некорректное тело запроса" }, { status: 400 });
  }
  const svg = (body as { svg?: unknown } | null)?.svg;
  if (typeof svg !== "string" || svg.trim().length === 0) {
    return NextResponse.json(
      { error: "SVG-разметка схемы обязательна" },
      { status: 400 },
    );
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/simulations/${simulationId}/export`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ svg }),
        cache: "no-store",
        signal: AbortSignal.timeout(15_000),
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
