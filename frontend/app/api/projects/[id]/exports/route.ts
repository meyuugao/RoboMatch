import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth";

/**
 * BFF-роут генерации и истории выгрузок.
 *
 * POST - генерация отчёта: таймаут 60 с (PDF может генерироваться дольше
 * других форматов - сборка модели + рендер шрифтов; всё равно далеко за
 * пределами обычных секунд). GET - история выгрузок проекта.
 *
 * Изоляция - на backend (404 чужого проекта), BFF только переносит
 * ответ и сообщение об ошибке (ErrorResponse.message).
 */
export const dynamic = "force-dynamic";

const BACKEND_URL = process.env.BACKEND_URL ?? "http://localhost:8080";

/** Генерация отчёта - с запасом (PDF тяжелее CSV/XLSX). */
const TIMEOUT_MS = 60_000;

type Params = { params: Promise<{ id: string }> };

export async function POST(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  let format: unknown;
  let theme: unknown;
  let schemaTheme: unknown;
  try {
    const body = await request.json();
    format = body?.format;
    theme = body?.theme;
    schemaTheme = body?.schemaTheme;
  } catch {
    return NextResponse.json(
      { error: "Некорректное тело запроса" },
      { status: 400 },
    );
  }
  if (format !== "pdf" && format !== "xlsx" && format !== "csv") {
    return NextResponse.json(
      { error: "Формат отчёта - pdf, xlsx или csv" },
      { status: 400 },
    );
  }
  // тема PDF-отчёта: явный выбор пользователя на странице
  // экспорта (по умолчанию - как в интерфейсе); отсутствует/мусор -
  // backend подставит светлую. Excel/CSV - без темы.
  const reportTheme = theme === "dark" ? "dark" : "light";
  // тема 2D-схемы в отчёте: ui - как сохранена в интерфейсе (по
  // умолчанию), light/dark - сервер перерисовывает схему выбранной
  // темы (светлая схема не попадает в тёмный отчёт и наоборот)
  const schemaThemeValue =
    schemaTheme === "light" || schemaTheme === "dark"
      ? schemaTheme
      : "ui";

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/exports`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          format,
          theme: reportTheme,
          schemaTheme: schemaThemeValue,
        }),
        cache: "no-store",
        signal: AbortSignal.timeout(TIMEOUT_MS),
      },
    );
    const responseBody = await backendResponse.text();
    return new NextResponse(responseBody, {
      status: backendResponse.status,
      headers: { "Content-Type": "application/json" },
    });
  } catch (error) {
    if (error instanceof Error && error.name === "TimeoutError") {
      return NextResponse.json(
        {
          error:
            "Генерация отчёта длится дольше 60 секунд - попробуйте ещё раз.",
        },
        { status: 504 },
      );
    }
    return NextResponse.json(
      { error: "Backend недоступен. Запустите Spring Boot на :8080" },
      { status: 502 },
    );
  }
}

export async function GET(
  request: NextRequest,
  { params }: Params,
): Promise<NextResponse> {
  const { id } = await params;
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ error: "Требуется авторизация" }, { status: 401 });
  }
  if (!/^\d+$/.test(id)) {
    return NextResponse.json({ error: "Некорректный id" }, { status: 400 });
  }

  try {
    const backendResponse = await fetch(
      `${BACKEND_URL}/api/projects/${id}/exports`,
      {
        headers: { Authorization: `Bearer ${token}` },
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
