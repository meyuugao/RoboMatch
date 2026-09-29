import { NextRequest } from "next/server";
import { numericId, proxyJson, validDictCode } from "@/lib/admin-bff";

/** BFF: правка/удаление записи справочника. */
export const dynamic = "force-dynamic";

type Params = { params: Promise<{ dictCode: string; id: string }> };

function badDict(): Response {
  return Response.json(
    { error: "Неизвестный справочник" },
    { status: 400 },
  );
}

export async function PUT(request: NextRequest, { params }: Params) {
  const { dictCode, id } = await params;
  if (!validDictCode(dictCode)) {
    return badDict();
  }
  const numeric = numericId(id);
  return numeric === null
    ? Response.json({ error: "Некорректный id записи" }, { status: 400 })
    : proxyJson(request, "PUT", `/api/admin/references/${dictCode}/${numeric}`);
}

export async function DELETE(request: NextRequest, { params }: Params) {
  const { dictCode, id } = await params;
  if (!validDictCode(dictCode)) {
    return badDict();
  }
  const numeric = numericId(id);
  return numeric === null
    ? Response.json({ error: "Некорректный id записи" }, { status: 400 })
    : proxyJson(
        request,
        "DELETE",
        `/api/admin/references/${dictCode}/${numeric}`,
      );
}
