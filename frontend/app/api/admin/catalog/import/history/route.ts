import { NextRequest } from "next/server";
import { proxyJson } from "@/lib/admin-bff";

/** BFF: история импортов каталога (V7 admin_import_log). */
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const qs = new URL(request.url).searchParams.toString();
  return proxyJson(
    request,
    "GET",
    `/api/admin/catalog/import/history${qs ? `?${qs}` : ""}`,
  );
}
