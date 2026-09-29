import { NextRequest } from "next/server";
import { proxyJson, validDictCode } from "@/lib/admin-bff";

/**
 * BFF: массовое удаление записей справочника -
 * POST /api/admin/references/{dictCode}/bulk-delete (тело {ids}).
 */
export const dynamic = "force-dynamic";

type Params = { params: Promise<{ dictCode: string }> };

export async function POST(request: NextRequest, { params }: Params) {
  const { dictCode } = await params;
  if (!validDictCode(dictCode)) {
    return Response.json(
      { error: "Неизвестный справочник" },
      { status: 400 },
    );
  }
  return proxyJson(
    request,
    "POST",
    `/api/admin/references/${dictCode}/bulk-delete`,
  );
}
