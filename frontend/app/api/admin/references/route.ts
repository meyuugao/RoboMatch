import { NextRequest } from "next/server";
import { proxyJson } from "@/lib/admin-bff";

/** BFF: счётчики записей всех справочников (плитки страницы). */
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  return proxyJson(request, "GET", "/api/admin/references");
}
