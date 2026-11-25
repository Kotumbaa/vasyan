import type { ApiErrorBody } from "./types";

export const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
  "Access-Control-Allow-Headers":
    "Content-Type, Authorization, X-Request-Id, X-Client-Version, X-Platform, X-User-Id, X-User-Name, X-Liner-Edge, X-Liner-Signature, X-Liner-Timestamp, X-Liner-Nonce, X-Session-Id, If-None-Match",
  "Access-Control-Expose-Headers": "X-Request-Id, X-Instance, ETag, Date",
  "Access-Control-Max-Age": "86400",
};

export function baseHeaders(
  requestId: string,
  extra?: Record<string, string>,
): Headers {
  const headers = new Headers(CORS_HEADERS);
  headers.set("X-Request-Id", requestId);
  headers.set("X-Instance", "liner-worker");
  if (extra) {
    for (const [key, value] of Object.entries(extra)) {
      headers.set(key, value);
    }
  }
  return headers;
}

export function jsonResponse<T>(
  data: T,
  requestId: string,
  status = 200,
  extraHeaders?: Record<string, string>,
): Response {
  const headers = baseHeaders(requestId, extraHeaders);
  headers.set("Content-Type", "application/json; charset=utf-8");
  return new Response(JSON.stringify(data), { status, headers });
}

export function noContentResponse(
  requestId: string,
  status = 204,
  extraHeaders?: Record<string, string>,
): Response {
  return new Response(null, {
    status,
    headers: baseHeaders(requestId, extraHeaders),
  });
}

export function errorResponse(
  status: number,
  message: string,
  requestId: string,
  code?: string,
): Response {
  const body: ApiErrorBody = {
    message,
    error: message,
    ...(code ? { code } : {}),
    requestId,
    reqId: requestId,
  };
  return jsonResponse(body, requestId, status);
}

export async function parseJsonBody<T>(request: Request): Promise<T | null> {
  try {
    const text = await request.text();
    if (!text.trim()) return null;
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

export async function readJsonObject(
  request: Request,
): Promise<Record<string, unknown> | null> {
  const parsed = await parseJsonBody<unknown>(request);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return null;
  }
  return parsed as Record<string, unknown>;
}

export function readOptionalString(
  obj: Record<string, unknown>,
  key: string,
): { valid: true; value: string | undefined } | { valid: false } {
  const raw = obj[key];
  if (raw === undefined) return { valid: true, value: undefined };
  if (typeof raw === "string") return { valid: true, value: raw };
  return { valid: false };
}

export function readOptionalNullableString(
  obj: Record<string, unknown>,
  key: string,
): { valid: true; value: string | null | undefined } | { valid: false } {
  const raw = obj[key];
  if (raw === undefined) return { valid: true, value: undefined };
  if (raw === null || typeof raw === "string") return { valid: true, value: raw };
  return { valid: false };
}

export function safeDecodeURIComponent(value: string): string | null {
  try {
    return decodeURIComponent(value);
  } catch {
    return null;
  }
}

export function encodeCursor(offset: number): string {
  return btoa(String(offset));
}

export function decodeCursor(cursor: string | null): number {
  if (!cursor) return 0;
  try {
    const parsed = Number.parseInt(atob(cursor), 10);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0;
  } catch {
    return 0;
  }
}
