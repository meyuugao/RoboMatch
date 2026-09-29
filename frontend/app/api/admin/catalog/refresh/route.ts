import { NextRequest } from "next/server";
import { proxyJson } from "@/lib/admin-bff";

/**
 * BFF: обновление каталога по запросу администратора —
 * повторный импорт последнего загруженного файла.
 */
export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  return proxyJson(request, "POST", "/api/admin/catalog/refresh", {
    timeoutMs: 60_000,
  });
}
