import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * Общий прокси админских BFF-роутов.
 *
 * Схема: браузер -> app/api/admin/** (этот слой) -> backend /api/admin/**.
 *
 * РОЛЬ: источник правды - backend: /api/admin/** закрыт hasRole('ADMIN')
 * в SecurityConfig; BFF лишь пробрасывает httpOnly-cookie сессии, и 403
 * «Недостаточно прав» проходит наружу как есть. Дополнительно 401 без
 * cookie отдаётся здесь (как у проектов) - не тянем backend без сессии.
 *
 * Таймауты: обычные операции - 10 с (паритет с остальными роутами);
 * импорт каталога (223+ строки, XLSX через POI) - 60 с.
 *
 * 204 без тела: NextResponse с телом на 204 падает и отдаёт 502 -
 * известная грабля (см. projects/[id]/route.ts).
 */

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** Прокси JSON-запроса (GET/POST/PUT/DELETE без файла). */
export async function proxyJson(
  request: NextRequest,
  method: "GET" | "POST" | "PUT" | "DELETE",
  backendPath: string,
  options?: { timeoutMs?: number },
): Promise<NextResponse> {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  try {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
    };
    let body: string | undefined;
    if (method === "POST" || method === "PUT") {
      headers["Content-Type"] = "application/json";
      body = await request.text();
      // пустое тело (refresh) не отправляем как JSON
      if (body === "") {
        body = undefined;
      }
    }
    const backendResponse = await fetch(`${BACKEND_URL}${backendPath}`, {
      method,
      headers,
      body,
      cache: "no-store",
      signal: AbortSignal.timeout(options?.timeoutMs ?? 10_000),
    });
    return relay(backendResponse);
  } catch (error) {
    if (error instanceof Error && error.name === "TimeoutError") {
      return NextResponse.json(
        { error: "Сервис не ответил вовремя. Повторите запрос" },
        { status: 504 },
      );
    }
    return NextResponse.json(
      { error: "Сервис временно недоступен. Повторите попытку позже" },
      { status: 502 },
    );
  }
}

/** Прокси multipart-загрузки файла каталога. */
export async function proxyMultipart(
  request: NextRequest,
  backendPath: string,
  options?: { timeoutMs?: number },
): Promise<NextResponse> {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  try {
    const form = await request.formData();
    const backendResponse = await fetch(`${BACKEND_URL}${backendPath}`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: form,
      signal: AbortSignal.timeout(options?.timeoutMs ?? 60_000),
    });
    return relay(backendResponse);
  } catch (error) {
    if (error instanceof Error && error.name === "TimeoutError") {
      return NextResponse.json(
        { error: "Импорт не успел завершиться - проверьте журнал загрузок" },
        { status: 504 },
      );
    }
    return NextResponse.json(
      { error: "Сервис временно недоступен. Повторите попытку позже" },
      { status: 502 },
    );
  }
}

/** Передать ответ backend как есть (статус + JSON-тело; 204 - без тела). */
async function relay(backendResponse: Response): Promise<NextResponse> {
  if (backendResponse.status === 204) {
    return new NextResponse(null, { status: 204 });
  }
  const body = await backendResponse.text();
  return new NextResponse(body, {
    status: backendResponse.status,
    headers: { "Content-Type": "application/json" },
  });
}

/** Белый список справочников раздела «Управление» (7 шт.). */
const DICT_CODES = new Set([
  "industry",
  "process",
  "vendor",
  "region",
  "solution_type",
  "solution_subtype",
  "characteristic_type",
]);

/** Корректен ли код справочника в path (защита от произвольных путей). */
export function validDictCode(dictCode: string): boolean {
  return DICT_CODES.has(dictCode);
}

/** Проверка числового path-параметра (id). */
export function numericId(value: string): number | null {
  return /^\d+$/.test(value) ? Number(value) : null;
}
