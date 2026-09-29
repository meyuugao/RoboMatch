import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут ручного добавления решения в сценарий:
 * POST /api/projects/{id}/scenarios/{scenarioId}/solutions -> backend
 * (состав - домен сценариев, полный CRUD в одном месте). Тело
 * (solutionId, manualReason, quantity?) пробрасывается как есть -
 * причина обязательна, валидирует backend (400) и эта форма (модалка
 * не отправит пустую причину).
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

type Params = { params: Promise<{ id: string; scenarioId: string }> };

export async function POST(request: NextRequest, { params }: Params) {
  const { id, scenarioId } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id) || !/^\d+$/.test(scenarioId)) {
    return NextResponse.json(
      { error: "Некорректный идентификатор" },
      { status: 400 },
    );
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/scenarios/${scenarioId}/solutions`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: await request.text(),
        cache: "no-store",
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (backendResponse.status === 201) {
      return new NextResponse(null, { status: 201 });
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
