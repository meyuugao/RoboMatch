import { NextRequest } from "next/server";
import { numericId, proxyJson } from "@/lib/admin-bff";

/** BFF: upsert/удаление значения ТТХ решения (EAV). */
export const dynamic = "force-dynamic";

type Params = {
  params: Promise<{ id: string; typeId: string }>;
};

async function resolve(
  rawId: string,
  rawTypeId: string,
): Promise<string | null> {
  const id = numericId(rawId);
  const typeId = numericId(rawTypeId);
  if (id === null || typeId === null) {
    return null;
  }
  return `/api/admin/solutions/${id}/characteristics/${typeId}`;
}

export async function PUT(request: NextRequest, { params }: Params) {
  const { id, typeId } = await params;
  const backend = await resolve(id, typeId);
  return backend
    ? proxyJson(request, "PUT", backend)
    : Response.json({ error: "Некорректный id" }, { status: 400 });
}

export async function DELETE(request: NextRequest, { params }: Params) {
  const { id, typeId } = await params;
  const backend = await resolve(id, typeId);
  return backend
    ? proxyJson(request, "DELETE", backend)
    : Response.json({ error: "Некорректный id" }, { status: 400 });
}
