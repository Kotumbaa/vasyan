import { authenticateRequest, handleAuthRequest } from "./auth";
import { handleCatalogRequest } from "./catalog";
import { baseHeaders, errorResponse, jsonResponse } from "./http";
import { handleLibraryRequest } from "./library";
import { handleLyricsRequest } from "./lyrics";
import { handleStreamProxyRequest } from "./streamProxy";
import type { Env, RequestContext } from "./types";

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const requestId =
      request.headers.get("x-request-id")?.trim() || crypto.randomUUID();

    if (request.method.toUpperCase() === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: baseHeaders(requestId),
      });
    }

    try {
      const url = new URL(request.url);
      if (
        request.method.toUpperCase() === "GET" &&
        (url.pathname === "/" || url.pathname === "/health")
      ) {
        return jsonResponse({ ok: true, service: "liner-worker" }, requestId);
      }

      const user = await authenticateRequest(request, env);
      const reqCtx: RequestContext = {
        request,
        env,
        ctx,
        url,
        requestId,
        user,
        userId: user?.id ?? null,
      };

      const handlers = [
        handleAuthRequest,
        handleLibraryRequest,
        handleCatalogRequest,
        handleLyricsRequest,
        handleStreamProxyRequest,
      ];

      for (const handler of handlers) {
        const res = await handler(reqCtx);
        if (res) return res;
      }

      const method = request.method.toUpperCase();
      if (method === "GET" && url.pathname === "/v1/me/library/artists") {
        return jsonResponse({ items: [], nextCursor: null }, requestId);
      }
      if (method === "GET" && /^\/v1\/tracks\/[^/]+\/ambient$/.test(url.pathname)) {
        return jsonResponse({ ambient: null }, requestId);
      }
      if (method === "POST" && url.pathname === "/v1/presence/heartbeat") {
        return new Response(null, { status: 204, headers: baseHeaders(requestId) });
      }

      return errorResponse(404, "Endpoint not found.", requestId, "NOT_FOUND");
    } catch (err) {
      const message =
        err instanceof Error && err.message ? err.message : "Internal server error.";
      return errorResponse(500, message, requestId, "INTERNAL_ERROR");
    }
  },
};
