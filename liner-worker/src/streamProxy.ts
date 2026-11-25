import { baseHeaders, errorResponse, jsonResponse } from "./http";
import type { RequestContext, SubRouterHandler } from "./types";

interface CachedStream {
  url: string;
  mimeType: string;
  userAgent: string;
  source: "youtube" | "soundcloud";
  isHls: boolean;
  expiresAtMs: number;
}

const STREAM_CACHE = new Map<string, CachedStream>();
let cachedSoundCloudClientId: { id: string; expiresAtMs: number } | null = null;

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const SC_API_TIMEOUT_MS = 10_000;
const SC_STREAM_TIMEOUT_MS = 15_000;

function buildStreamCacheKey(
  scTrackId: string | null,
  videoId: string | null,
  artist: string,
  title: string,
): string {
  return `${scTrackId ? `sc:${scTrackId}` : (videoId ?? "")}|${artist}|${title}`.toLowerCase();
}

function normalizeVideoId(raw: string): string | null {
  const clean = raw.trim().replace(/^yt:/, "");
  return /^[a-zA-Z0-9_-]{11}$/.test(clean) ? clean : null;
}

function normalizeSoundCloudTrackId(raw: string): string | null {
  const match = raw.trim().match(/^sc:(\d+)$/i);
  return match?.[1] ?? null;
}

async function getSoundCloudClientId(): Promise<string | null> {
  if (cachedSoundCloudClientId && cachedSoundCloudClientId.expiresAtMs > Date.now()) {
    return cachedSoundCloudClientId.id;
  }
  try {
    const htmlRes = await fetch("https://soundcloud.com/", {
      headers: {
        "User-Agent": BROWSER_UA,
        Accept: "text/html,application/xhtml+xml",
      },
      signal: AbortSignal.timeout(SC_API_TIMEOUT_MS),
    });
    if (!htmlRes.ok) return null;
    const html = await htmlRes.text();
    const scriptMatches = Array.from(
      html.matchAll(/src="((?:https:)?\/\/a-v2\.sndcdn\.com\/assets\/[^"]+\.js)"/g),
    ).map((m) => (m[1].startsWith("//") ? `https:${m[1]}` : m[1]));

    for (const scriptUrl of scriptMatches.reverse()) {
      const jsRes = await fetch(scriptUrl, {
        headers: { "User-Agent": BROWSER_UA },
        signal: AbortSignal.timeout(SC_API_TIMEOUT_MS),
      });
      if (!jsRes.ok) continue;
      const js = await jsRes.text();
      const match = js.match(/client_id\s*[:=]\s*["']([a-zA-Z0-9]{20,40})["']/);
      if (match?.[1]) {
        cachedSoundCloudClientId = {
          id: match[1],
          expiresAtMs: Date.now() + 6 * 60 * 60 * 1000,
        };
        return match[1];
      }
    }
  } catch {
    return null;
  }
  return null;
}

interface ScTranscoding {
  url?: string;
  snipped?: boolean;
  preset?: string;
  format?: { protocol?: string; mime_type?: string };
}

function rankTranscoding(t: ScTranscoding): number {
  const proto = t.format?.protocol ?? "";
  const mime = t.format?.mime_type ?? "";
  const isMpeg = mime.startsWith("audio/mpeg");
  if (proto === "progressive") return isMpeg ? 0 : 1;
  if (proto === "hls") {
    if (isMpeg) return 2;
    if (mime.includes("mp4") || mime.includes("aac")) return 3;
    if (mime.includes("ogg") || mime.includes("opus")) return 4;
    return 5;
  }
  return 99;
}

function isPlayableTranscoding(t: ScTranscoding): boolean {
  if (!t.url || t.snipped) return false;
  const proto = t.format?.protocol ?? "";
  const mime = t.format?.mime_type ?? "";
  if (proto === "progressive") return true;
  // Skip encrypted / FairPlay HLS
  if (proto === "hls" && !mime.includes("encrypted") && !mime.includes("mpegurl")) {
    return true;
  }
  return false;
}

async function resolveSoundCloudOnEdge(
  query: string,
  diag: string[],
): Promise<CachedStream | null> {
  const cleanQuery = query.trim();
  if (!cleanQuery) {
    diag.push("sc:empty-query");
    return null;
  }
  const clientId = await getSoundCloudClientId();
  if (!clientId) {
    diag.push("sc:no-client-id");
    return null;
  }

  try {
    const searchUrl =
      `https://api-v2.soundcloud.com/search/tracks?q=${encodeURIComponent(cleanQuery)}` +
      `&client_id=${encodeURIComponent(clientId)}&limit=10`;
    const searchRes = await fetch(searchUrl, {
      headers: { "User-Agent": BROWSER_UA, Accept: "application/json" },
      signal: AbortSignal.timeout(SC_API_TIMEOUT_MS),
    });
    if (!searchRes.ok) {
      diag.push(`sc:search-http=${searchRes.status}`);
      return null;
    }

    const data = (await searchRes.json()) as {
      collection?: Array<{
        title?: string;
        duration?: number;
        full_duration?: number;
        policy?: string;
        streamable?: boolean;
        media?: { transcodings?: ScTranscoding[] };
      }>;
    };

    const tracks = data.collection ?? [];
    if (tracks.length === 0) {
      diag.push("sc:0-results");
      return null;
    }

    for (const item of tracks) {
      if (item.policy === "BLOCK" || item.policy === "SNIP") continue;
      if (item.streamable === false) continue;
      if (
        item.duration &&
        item.full_duration &&
        item.duration < item.full_duration - 5_000
      ) {
        continue;
      }

      const candidates = (item.media?.transcodings ?? [])
        .filter(isPlayableTranscoding)
        .sort((a, b) => rankTranscoding(a) - rankTranscoding(b));

      for (const transcoding of candidates) {
        if (!transcoding.url) continue;
        const sep = transcoding.url.includes("?") ? "&" : "?";
        const resolveRes = await fetch(
          `${transcoding.url}${sep}client_id=${encodeURIComponent(clientId)}`,
          {
            headers: { "User-Agent": BROWSER_UA, Accept: "application/json" },
            signal: AbortSignal.timeout(SC_API_TIMEOUT_MS),
          },
        );
        if (!resolveRes.ok) continue;
        const resolved = (await resolveRes.json()) as { url?: string };
        if (!resolved.url) continue;

        const isHls = transcoding.format?.protocol === "hls";
        const mimeType = isHls
          ? "application/vnd.apple.mpegurl"
          : transcoding.format?.mime_type?.split(";")[0]?.trim() || "audio/mpeg";

        return {
          url: resolved.url,
          mimeType,
          userAgent: BROWSER_UA,
          source: "soundcloud",
          isHls,
          expiresAtMs: Date.now() + 15 * 60 * 1000,
        };
      }
    }
    diag.push(`sc:no-playable-in-${tracks.length}`);
  } catch (e) {
    diag.push(`sc:err=${e instanceof Error ? e.message : String(e)}`);
    return null;
  }
  return null;
}

async function resolveSoundCloudTrackById(
  scTrackId: string,
  diag: string[],
): Promise<CachedStream | null> {
  const clientId = await getSoundCloudClientId();
  if (!clientId) {
    diag.push("sc:no-client-id");
    return null;
  }
  try {
    const res = await fetch(
      `https://api-v2.soundcloud.com/tracks/${encodeURIComponent(scTrackId)}?client_id=${encodeURIComponent(clientId)}`,
      {
        headers: { "User-Agent": BROWSER_UA, Accept: "application/json" },
        signal: AbortSignal.timeout(SC_API_TIMEOUT_MS),
      },
    );
    if (!res.ok) {
      diag.push(`sc:track-http=${res.status}`);
      return null;
    }
    const item = (await res.json()) as {
      policy?: string;
      streamable?: boolean;
      media?: { transcodings?: ScTranscoding[] };
    };
    const candidates = (item.media?.transcodings ?? [])
      .filter(isPlayableTranscoding)
      .sort((a, b) => rankTranscoding(a) - rankTranscoding(b));

    for (const transcoding of candidates) {
      if (!transcoding.url) continue;
      const sep = transcoding.url.includes("?") ? "&" : "?";
      const resolveRes = await fetch(
        `${transcoding.url}${sep}client_id=${encodeURIComponent(clientId)}`,
        {
          headers: { "User-Agent": BROWSER_UA, Accept: "application/json" },
          signal: AbortSignal.timeout(SC_API_TIMEOUT_MS),
        },
      );
      if (!resolveRes.ok) continue;
      const resolved = (await resolveRes.json()) as { url?: string };
      if (!resolved.url) continue;
      const isHls = transcoding.format?.protocol === "hls";
      return {
        url: resolved.url,
        mimeType: isHls
          ? "application/vnd.apple.mpegurl"
          : transcoding.format?.mime_type?.split(";")[0]?.trim() || "audio/mpeg",
        userAgent: BROWSER_UA,
        source: "soundcloud",
        isHls,
        expiresAtMs: Date.now() + 15 * 60 * 1000,
      };
    }
    diag.push("sc:id-no-playable");
  } catch (e) {
    diag.push(`sc:id-err=${e instanceof Error ? e.message : String(e)}`);
  }
  return null;
}

async function resolveStreamForRelay(
  rawId: string,
  title: string,
  artist: string,
  diag: string[],
): Promise<CachedStream | null> {
  const scTrackId = normalizeSoundCloudTrackId(rawId);
  const videoId = normalizeVideoId(rawId);
  const cacheKey = buildStreamCacheKey(scTrackId, videoId, artist, title);
  const cached = STREAM_CACHE.get(cacheKey);
  if (cached && cached.expiresAtMs > Date.now()) {
    return cached;
  }

  let resolved: CachedStream | null = null;
  if (scTrackId) {
    resolved = await resolveSoundCloudTrackById(scTrackId, diag);
  }
  if (!resolved) {
    const query = [artist, title].filter(Boolean).join(" ").trim();
    if (query) {
      resolved = await resolveSoundCloudOnEdge(query, diag);
    }
  }

  if (resolved) {
    if (STREAM_CACHE.size > 250) {
      const firstKey = STREAM_CACHE.keys().next().value;
      if (firstKey) STREAM_CACHE.delete(firstKey);
    }
    STREAM_CACHE.set(cacheKey, resolved);
  }
  return resolved;
}

function rewriteHlsPlaylist(playlistText: string, workerOrigin: string): string {
  const proxyLine = (rawUrl: string): string => {
    const trimmed = rawUrl.trim();
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
      return trimmed;
    }
    return `${workerOrigin}/v1/stream/seg?u=${encodeURIComponent(trimmed)}`;
  };

  return playlistText
    .split(/\r?\n/)
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) return line;
      if (trimmed.startsWith("#")) {
        // Rewrite URI="..." inside #EXT-X-MAP or #EXT-X-KEY if present
        return line.replace(/URI="([^"]+)"/g, (_m, uri: string) => {
          return `URI="${proxyLine(uri)}"`;
        });
      }
      return proxyLine(trimmed);
    })
    .join("\n");
}

function isAllowedSegmentUrl(rawUrl: string): boolean {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== "https:") return false;
    const host = parsed.hostname.toLowerCase();
    return (
      host === "sndcdn.com" ||
      host.endsWith(".sndcdn.com") ||
      host === "soundcloud.cloud" ||
      host.endsWith(".soundcloud.cloud") ||
      host === "soundcloud.com" ||
      host.endsWith(".soundcloud.com")
    );
  } catch {
    return false;
  }
}

export const handleStreamProxyRequest: SubRouterHandler = async (
  ctx: RequestContext,
) => {
  const method = ctx.request.method.toUpperCase();
  const path = ctx.url.pathname;

  // 1. Segment proxy for SoundCloud HLS segments
  if ((method === "GET" || method === "HEAD") && path === "/v1/stream/seg") {
    const targetUrl = ctx.url.searchParams.get("u") ?? "";
    if (!isAllowedSegmentUrl(targetUrl)) {
      return errorResponse(400, "Invalid segment URL.", ctx.requestId, "BAD_REQUEST");
    }
    const upstreamHeaders = new Headers({ "User-Agent": BROWSER_UA });
    const rangeHeader = ctx.request.headers.get("range");
    if (rangeHeader) upstreamHeaders.set("Range", rangeHeader);

    const segRes = await fetch(targetUrl, {
      method,
      headers: upstreamHeaders,
      signal: AbortSignal.timeout(SC_STREAM_TIMEOUT_MS),
    });
    if (!segRes.ok && segRes.status !== 206) {
      return errorResponse(502, `Segment HTTP ${segRes.status}`, ctx.requestId, "UPSTREAM_ERROR");
    }
    const headers = baseHeaders(ctx.requestId);
    headers.set("Content-Type", segRes.headers.get("content-type") || "audio/mpeg");
    headers.set("Cache-Control", "public, max-age=300");
    const cl = segRes.headers.get("content-length");
    if (cl) headers.set("Content-Length", cl);
    const cr = segRes.headers.get("content-range");
    if (cr) headers.set("Content-Range", cr);
    return new Response(method === "HEAD" ? null : segRes.body, {
      status: segRes.status,
      headers,
    });
  }

  // 2. Metadata resolution endpoint: tells Android whether relay stream is HLS or progressive
  if (method === "GET" && path === "/v1/stream/resolve") {
    const rawVideoId = ctx.url.searchParams.get("videoId") ?? "";
    const title = ctx.url.searchParams.get("title") ?? "";
    const artist = ctx.url.searchParams.get("artist") ?? "";
    const scTrackId = normalizeSoundCloudTrackId(rawVideoId);
    const videoId = normalizeVideoId(rawVideoId);

    if (!videoId && !scTrackId && !title.trim()) {
      return errorResponse(400, "Missing videoId or title.", ctx.requestId, "BAD_REQUEST");
    }

    const diag: string[] = [];
    const stream = await resolveStreamForRelay(rawVideoId, title, artist, diag);
    if (!stream) {
      return errorResponse(
        502,
        `Unable to resolve relay stream (${diag.join("; ")}).`,
        ctx.requestId,
        "STREAM_UNAVAILABLE",
      );
    }

    const playPath = stream.isHls ? "/v1/stream/play.m3u8" : "/v1/stream/play";
    const params = new URLSearchParams();
    if (scTrackId) params.set("videoId", `sc:${scTrackId}`);
    else if (videoId) params.set("videoId", videoId);
    if (title) params.set("title", title);
    if (artist) params.set("artist", artist);

    return jsonResponse(
      {
        url: `${ctx.url.origin}${playPath}?${params.toString()}`,
        mimeType: stream.mimeType,
        source: stream.source,
        isHls: stream.isHls,
      },
      ctx.requestId,
    );
  }

  // 3. Audio stream playback endpoint (progressive MP3 or rewritten HLS .m3u8)
  if (
    (method === "GET" || method === "HEAD") &&
    (path === "/v1/stream/play" || path === "/v1/stream/play.m3u8")
  ) {
    const rawVideoId = ctx.url.searchParams.get("videoId") ?? "";
    const title = ctx.url.searchParams.get("title") ?? "";
    const artist = ctx.url.searchParams.get("artist") ?? "";
    const scTrackId = normalizeSoundCloudTrackId(rawVideoId);
    const videoId = normalizeVideoId(rawVideoId);

    if (!videoId && !scTrackId && !title.trim()) {
      return errorResponse(400, "Missing videoId or title.", ctx.requestId, "BAD_REQUEST");
    }

    const diag: string[] = [];
    const stream = await resolveStreamForRelay(rawVideoId, title, artist, diag);
    if (!stream) {
      return errorResponse(
        502,
        `Unable to resolve playable audio stream on relay (${diag.join("; ")}).`,
        ctx.requestId,
        "STREAM_UNAVAILABLE",
      );
    }

    if (stream.isHls) {
      const m3u8Res = await fetch(stream.url, {
        headers: { "User-Agent": stream.userAgent },
        signal: AbortSignal.timeout(SC_API_TIMEOUT_MS),
      });
      if (!m3u8Res.ok) {
        STREAM_CACHE.delete(buildStreamCacheKey(scTrackId, videoId, artist, title));
        return errorResponse(502, `HLS playlist HTTP ${m3u8Res.status}`, ctx.requestId, "UPSTREAM_ERROR");
      }
      const playlistText = await m3u8Res.text();
      const rewritten = rewriteHlsPlaylist(playlistText, ctx.url.origin);
      const headers = baseHeaders(ctx.requestId);
      headers.set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
      headers.set("X-Stream-Source", stream.source);
      return new Response(method === "HEAD" ? null : rewritten, {
        status: 200,
        headers,
      });
    }

    const upstreamHeaders = new Headers({ "User-Agent": stream.userAgent });
    const rangeHeader = ctx.request.headers.get("range");
    if (rangeHeader) upstreamHeaders.set("Range", rangeHeader);

    const upstreamRes = await fetch(stream.url, {
      method,
      headers: upstreamHeaders,
      signal: AbortSignal.timeout(SC_STREAM_TIMEOUT_MS),
    });
    if (!upstreamRes.ok && upstreamRes.status !== 206) {
      STREAM_CACHE.delete(buildStreamCacheKey(scTrackId, videoId, artist, title));
      return errorResponse(
        502,
        `Upstream audio returned HTTP ${upstreamRes.status}.`,
        ctx.requestId,
        "UPSTREAM_ERROR",
      );
    }

    const headers = baseHeaders(ctx.requestId);
    headers.set(
      "Content-Type",
      upstreamRes.headers.get("content-type") || stream.mimeType,
    );
    headers.set("Accept-Ranges", "bytes");
    headers.set("X-Stream-Source", stream.source);
    const cl = upstreamRes.headers.get("content-length");
    if (cl) headers.set("Content-Length", cl);
    const cr = upstreamRes.headers.get("content-range");
    if (cr) headers.set("Content-Range", cr);

    return new Response(method === "HEAD" ? null : upstreamRes.body, {
      status: upstreamRes.status,
      headers,
    });
  }

  return null;
};
