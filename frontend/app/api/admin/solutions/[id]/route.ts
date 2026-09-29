import { NextRequest } from "next/server";
import { numericId, proxyJson } from "@/lib/admin-bff";

/**
 * BFF: карточка решения — публичный гостевой read
 * /api/solutions/{id}; правка/удаление — /api/admin/solutions/{id}
 * (read-дубликат под /api/admin убран: карточка одна на все роли).
 */
export const dynamic = "force-dynamic";

type Params = { params: Promise<{ id: string }> };

async function readPath(raw: string): Promise<string | null> {
  const id = numericId(raw);
  return id === null ? null : `/api/solutions/${id}`;
}

async function writePath(raw: string): Promise<string | null> {
  const id = numericId(raw);
  return id === null ? null : `/api/admin/solutions/${id}`;
}

export async function GET(request: NextRequest, { params }: Params) {
  const { id } = await params;
  const backend = await readPath(id);
  return backend
    ? proxyJson(request, "GET", backend)
    : invalidId();
}

export async function PUT(request: NextRequest, { params }: Params) {
  const { id } = await params;
  const backend = await writePath(id);
  return backend
    ? proxyJson(request, "PUT", backend)
    : invalidId();
}

export async function DELETE(request: NextRequest, { params }: Params) {
  const { id } = await params;
  const backend = await writePath(id);
  return backend
    ? proxyJson(request, "DELETE", backend)
    : invalidId();
}

function invalidId() {
  return Response.json({ error: "Некорректный id решения" }, { status: 400 });
}
