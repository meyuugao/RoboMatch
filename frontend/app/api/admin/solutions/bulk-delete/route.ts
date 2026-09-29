import { NextRequest } from "next/server";
import { proxyJson } from "@/lib/admin-bff";

/**
 * BFF: массовое удаление решений —
 * POST /api/admin/solutions/bulk-delete (тело {ids}).
 */
export const dynamic = "force-dynamic";

export async function POST(request: NextRequest) {
  return proxyJson(request, "POST", "/api/admin/solutions/bulk-delete");
}
