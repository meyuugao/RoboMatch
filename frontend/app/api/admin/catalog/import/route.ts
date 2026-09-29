import { NextRequest } from "next/server";
import { proxyMultipart } from "@/lib/admin-bff";

/**
 * BFF: загрузка таблицы каталога (multipart CSV/XLSX,
 *). Таймаут 60 с: разбор 223+ строк и XLSX через POI дольше
 * обычного JSON-обмена.
 */
export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  return proxyMultipart(request, "/api/admin/catalog/import", {
    timeoutMs: 60_000,
  });
}
