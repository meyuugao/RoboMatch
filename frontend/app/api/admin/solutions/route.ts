import { NextRequest } from "next/server";
import { proxyJson } from "@/lib/admin-bff";

/** BFF: список решений раздела «Управление» + создание (POST) -> backend /api/admin/solutions. */
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const qs = new URL(request.url).searchParams.toString();
  return proxyJson(request, "GET", `/api/admin/solutions${qs ? `?${qs}` : ""}`);
}

export async function POST(request: NextRequest) {
  return proxyJson(request, "POST", "/api/admin/solutions");
}
