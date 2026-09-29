import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут точечного изменения состава сценария: PUT — количество
 * единиц решения (тело {quantity} пробрасывается на backend, валидация
 * 1-10000 там и в форме), DELETE — убрать решение из состава. Исторические
 * расчёты не трогаются (append-only, data_model.md §10.8). Сессия — из
 * httpOnly-cookie; ошибки — от backend.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = {
  params: Promise<{ id: string; scenarioId: string; solutionId: string }>;
};

export async function PUT(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id, scenarioId, solutionId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(scenarioId) || !/^\d+$/.test(solutionId)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/scenarios/${scenarioId}/solutions/${solutionId}`,
      {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: await request.text(),
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
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

export async function DELETE(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id, scenarioId, solutionId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(scenarioId) || !/^\d+$/.test(solutionId)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/scenarios/${scenarioId}/solutions/${solutionId}`,
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
