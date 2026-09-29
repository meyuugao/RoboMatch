import { NextResponse } from "next/server";

/**
 * BFF-роут (Backend-for-Frontend): прокси к Spring Boot.
 *
 * Схема запроса:
 * браузер/страница -> GET /api/solutions?фильтры (Next.js, этот файл)
 * -> GET http://backend:8080/api/solutions?фильтры
 *
 * Все query-параметры (поиск q, фильтры, сортировка, пагинация —
 *) пробрасываются на backend как есть: один источник валидации
 * (белые списки в SolutionService), BFF не интерпретирует фильтры.
 *
 * BACKEND_URL вынесен в переменную окружения (см. .env.example):
 * локально это localhost:8080, в docker-compose — http://backend:8080.
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET(request: Request) {
  try {
    const qs = new URL(request.url).searchParams.toString();
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/solutions${qs ? `?${qs}` : ""}`,
      { cache: "no-store", signal: AbortSignal.timeout(10_000) },
    );

    if (!backendResponse.ok) {
      // backend жив, но ответил ошибкой (400 — невалидный фильтр и т.п.)
      // — пробрасываем статус и тело наружу
      const body = await backendResponse.text();
      return new NextResponse(body, {
        status: backendResponse.status,
        headers: { "Content-Type": "application/json" },
      });
    }

    const data = await backendResponse.json();
    return NextResponse.json(data);
  } catch {
    // backend недоступен (не запущен / сеть) — понятная ошибка для UI
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 },
    );
  }
}
