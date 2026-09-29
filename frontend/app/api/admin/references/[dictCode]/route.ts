import { NextRequest } from "next/server";
import { proxyJson, validDictCode } from "@/lib/admin-bff";

/** BFF: список записей справочника + создание (POST). */
export const dynamic = "force-dynamic";

type Params = { params: Promise<{ dictCode: string }> };

function badDict(): Response {
  return Response.json(
    { error: "Неизвестный справочник" },
    { status: 400 },
  );
}

export async function GET(request: NextRequest, { params }: Params) {
  const { dictCode } = await params;
  if (!validDictCode(dictCode)) {
    return badDict();
  }
  const qs = new URL(request.url).searchParams.toString();
  return proxyJson(
    request,
    "GET",
    `/api/admin/references/${dictCode}${qs ? `?${qs}` : ""}`,
  );
}

export async function POST(request: NextRequest, { params }: Params) {
  const { dictCode } = await params;
  if (!validDictCode(dictCode)) {
    return badDict();
  }
  return proxyJson(request, "POST", `/api/admin/references/${dictCode}`);
}
