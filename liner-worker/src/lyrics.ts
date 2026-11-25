import { baseHeaders, errorResponse, jsonResponse, safeDecodeURIComponent } from "./http";
import type {
  LyricsCandidate,
  LyricsQuality,
  LyricsSearchResult,
  LyricsStreamEvent,
  RequestContext,
  SubRouterHandler,
  TrackArtist,
} from "./types";

interface LrclibRecord {
  id?: number;
  trackName?: string;
  artistName?: string;
  albumName?: string;
  duration?: number;
  instrumental?: boolean;
  plainLyrics?: string | null;
  syncedLyrics?: string | null;
}

const LRCLIB_BASE = "https://lrclib.net/api";
const LRCLIB_USER_AGENT = "Liner-Worker/1.0";
const REQUEST_TIMEOUT_MS = 10_000;

function normalizeText(value: string): string {
  return value
    .toLowerCase()
    .replace(/[()[\]\-–—_"'!?.,:;/\\|#]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function buildQuality(
  record: LrclibRecord,
  queryTitle: string,
  queryArtist: string,
  queryDurationMs?: number | null,
): LyricsQuality {
  const hasSynced = Boolean(record.syncedLyrics?.trim());
  const recTitle = normalizeText(record.trackName ?? "");
  const qTitle = normalizeText(queryTitle);
  const recArtist = normalizeText(record.artistName ?? "");
  const qArtist = normalizeText(queryArtist);

  let match = 0.7;
  const reasons: string[] = [];
  if (recTitle && qTitle && (recTitle === qTitle || recTitle.includes(qTitle) || qTitle.includes(recTitle))) {
    match += 0.15;
    reasons.push("title_match");
  }
  if (recArtist && qArtist && (recArtist === qArtist || recArtist.includes(qArtist) || qArtist.includes(recArtist))) {
    match += 0.15;
    reasons.push("artist_match");
  }

  const recordDurationMs =
    typeof record.duration === "number" && record.duration > 0
      ? Math.round(record.duration * 1000)
      : undefined;
  if (queryDurationMs && recordDurationMs) {
    const diff = Math.abs(queryDurationMs - recordDurationMs);
    if (diff <= 3000) {
      match = Math.min(1, match + 0.05);
      reasons.push("duration_exact");
    } else if (diff > 15_000) {
      match = Math.max(0.3, match - 0.25);
      reasons.push("duration_mismatch");
    }
  }

  const sync = hasSynced ? 1.0 : 0.35;
  const content = 0.95;
  const source = 0.9;
  const total = Number(
    (match * 0.4 + sync * 0.3 + content * 0.15 + source * 0.15).toFixed(3),
  );

  return {
    total,
    match: Number(match.toFixed(3)),
    content,
    sync,
    source,
    reasons,
  };
}

function toCandidate(
  record: LrclibRecord,
  queryTitle: string,
  queryArtist: string,
  queryDurationMs?: number | null,
): LyricsCandidate | null {
  const synced = record.syncedLyrics?.trim();
  const plain = record.plainLyrics?.trim();
  if (!synced && !plain) return null;

  const isSynced = Boolean(synced);
  const content = (synced || plain)!;
  const durationMs =
    typeof record.duration === "number" && record.duration > 0
      ? Math.round(record.duration * 1000)
      : queryDurationMs ?? undefined;

  return {
    matchedTrack: {
      title: record.trackName ?? queryTitle,
      artists: record.artistName ? [record.artistName] : [queryArtist],
      durationMs: durationMs ?? null,
    },
    lyrics: {
      format: isSynced ? "lrc" : "plain",
      content,
    },
    syncHint: isSynced ? "line_synced" : "plain",
    provider: "lrclib",
    contentKind: isSynced ? "line_synced" : "plain",
    syncLevel: isSynced ? "line_level" : "plain",
    topPriority: true,
    quality: buildQuality(record, queryTitle, queryArtist, queryDurationMs),
  };
}

async function fetchLrclibJson<T>(pathAndQuery: string): Promise<{ status: number; data: T | null }> {
  const res = await fetch(`${LRCLIB_BASE}${pathAndQuery}`, {
    headers: {
      Accept: "application/json",
      "User-Agent": LRCLIB_USER_AGENT,
    },
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (res.status === 404) {
    return { status: 404, data: null };
  }
  if (!res.ok) {
    throw new Error(`LRCLIB failed with HTTP ${res.status}`);
  }
  const data = (await res.json()) as T;
  return { status: res.status, data };
}

async function lookupLrclib(
  title: string,
  artist: string,
  durationMs?: number | null,
): Promise<{ candidate: LyricsCandidate | null; instrumental: boolean }> {
  const params = new URLSearchParams({
    track_name: title,
    artist_name: artist,
  });
  if (durationMs && durationMs > 0) {
    params.set("duration", String(Math.round(durationMs / 1000)));
  }

  try {
    const exact = await fetchLrclibJson<LrclibRecord>(`/get?${params.toString()}`);
    if (exact.data) {
      if (exact.data.instrumental) {
        return { candidate: null, instrumental: true };
      }
      const candidate = toCandidate(exact.data, title, artist, durationMs);
      if (candidate) return { candidate, instrumental: false };
    }
  } catch {
    // Fall through to search endpoint
  }

  const searchParams = new URLSearchParams({
    track_name: title,
    artist_name: artist,
  });
  const search = await fetchLrclibJson<LrclibRecord[]>(
    `/search?${searchParams.toString()}`,
  );
  const records = Array.isArray(search.data) ? search.data : [];
  if (records.length === 0) {
    return { candidate: null, instrumental: false };
  }

  const candidates = records
    .map((rec) => ({
      rec,
      candidate: toCandidate(rec, title, artist, durationMs),
    }))
    .filter(
      (entry): entry is { rec: LrclibRecord; candidate: LyricsCandidate } =>
        entry.candidate !== null,
    )
    .sort((a, b) => b.candidate.quality.total - a.candidate.quality.total);

  if (candidates.length > 0) {
    return { candidate: candidates[0]!.candidate, instrumental: false };
  }

  const instrumental = records.some((rec) => rec.instrumental === true);
  return { candidate: null, instrumental };
}

async function resolveTrackMetadata(
  trackId: string,
): Promise<{ title: string; artists: TrackArtist[]; durationMs: number } | null> {
  try {
    const res = await fetch("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json",
        "x-youtube-client-name": "67",
        "x-youtube-client-version": "1.20260911.08.00",
        "x-origin": "https://music.youtube.com",
      },
      body: JSON.stringify({
        videoId: trackId,
        context: {
          client: {
            clientName: "WEB_REMIX",
            clientVersion: "1.20260911.08.00",
            hl: "en",
            gl: "US",
          },
        },
      }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (!res.ok) return null;
    const data = (await res.json()) as Record<string, unknown>;
    const contents = data?.contents as Record<string, unknown> | undefined;
    const single = contents?.singleColumnMusicWatchNextResultsRenderer as Record<string, unknown> | undefined;
    const tabbed = single?.tabbedRenderer as Record<string, unknown> | undefined;
    const watchNext = tabbed?.watchNextTabbedResultsRenderer as Record<string, unknown> | undefined;
    const tabs = Array.isArray(watchNext?.tabs) ? watchNext.tabs : [];

    for (const tab of tabs) {
      const queue = (tab as Record<string, unknown>)?.tabRenderer as Record<string, unknown> | undefined;
      const content = queue?.content as Record<string, unknown> | undefined;
      const musicQueue = content?.musicQueueRenderer as Record<string, unknown> | undefined;
      const panel = (musicQueue?.content as Record<string, unknown> | undefined)
        ?.playlistPanelRenderer as Record<string, unknown> | undefined;
      const items = Array.isArray(panel?.contents) ? panel.contents : [];
      for (const item of items) {
        const renderer = (item as Record<string, unknown>)?.playlistPanelVideoRenderer as
          | Record<string, unknown>
          | undefined;
        if (!renderer) continue;
        if (renderer.selected === true || renderer.videoId === trackId) {
          const titleRuns = (renderer.title as Record<string, unknown> | undefined)?.runs;
          const title = Array.isArray(titleRuns)
            ? titleRuns
                .map((r) => (r as Record<string, unknown>)?.text)
                .filter((t): t is string => typeof t === "string")
                .join("")
                .trim()
            : "";
          const byline = (renderer.longBylineText ?? renderer.shortBylineText) as
            | Record<string, unknown>
            | undefined;
          const bylineRuns = Array.isArray(byline?.runs) ? byline.runs : [];
          const artistText = bylineRuns
            .map((r) => (r as Record<string, unknown>)?.text)
            .find((t): t is string => typeof t === "string" && t.trim().length > 0 && t.trim() !== "\u2022");
          if (title) {
            return {
              title,
              artists: [{ id: "unknown", name: artistText?.trim() || "Unknown Artist" }],
              durationMs: 0,
            };
          }
        }
      }
    }
    return null;
  } catch {
    return null;
  }
}

async function handleLyricsSearch(reqCtx: RequestContext): Promise<Response> {
  const title = reqCtx.url.searchParams.get("title")?.trim() ?? "";
  const artist = reqCtx.url.searchParams.get("artist")?.trim() ?? "";
  const durationParam = reqCtx.url.searchParams.get("durationMs");
  const durationMs = durationParam ? Number.parseInt(durationParam, 10) : null;

  if (!title || !artist) {
    return errorResponse(
      400,
      "Query parameters 'title' and 'artist' are required.",
      reqCtx.requestId,
      "BAD_REQUEST",
    );
  }

  try {
    const { candidate } = await lookupLrclib(
      title,
      artist,
      Number.isFinite(durationMs) ? durationMs : null,
    );
    if (!candidate) {
      return errorResponse(
        404,
        "Lyrics not found.",
        reqCtx.requestId,
        "LYRICS_NOT_FOUND",
      );
    }

    const result: LyricsSearchResult = {
      provider: "lrclib",
      cached: false,
      candidate,
    };
    return jsonResponse(result, reqCtx.requestId);
  } catch (err) {
    const message =
      err instanceof Error ? err.message : "Failed to search lyrics.";
    return errorResponse(502, message, reqCtx.requestId, "UPSTREAM_ERROR");
  }
}

function formatSseEvent(event: LyricsStreamEvent): string {
  return `data: ${JSON.stringify(event)}\n\n`;
}

async function handleTrackLyricsStream(
  reqCtx: RequestContext,
  trackId: string,
): Promise<Response> {
  if (!trackId) {
    return errorResponse(400, "Track id is required.", reqCtx.requestId, "BAD_REQUEST");
  }

  let title = reqCtx.url.searchParams.get("title")?.trim() ?? "";
  let artist = reqCtx.url.searchParams.get("artist")?.trim() ?? "";
  const durationParam = reqCtx.url.searchParams.get("durationMs");
  let durationMs = durationParam ? Number.parseInt(durationParam, 10) : 0;
  if (!Number.isFinite(durationMs) || durationMs < 0) durationMs = 0;

  let artists: TrackArtist[] = artist ? [{ id: "unknown", name: artist }] : [];

  if (!title || !artist) {
    const resolved = await resolveTrackMetadata(trackId);
    if (resolved) {
      if (!title) title = resolved.title;
      if (!artist) {
        artists = resolved.artists;
        artist = resolved.artists[0]?.name ?? "";
      }
      if (!durationMs && resolved.durationMs > 0) {
        durationMs = resolved.durationMs;
      }
    }
  }

  const events: LyricsStreamEvent[] = [
    {
      type: "metadata",
      track: {
        id: trackId,
        title: title || trackId,
        artists,
        durationMs,
      },
    },
  ];

  const startedAt = Date.now();
  if (title && artist) {
    try {
      const { candidate, instrumental } = await lookupLrclib(
        title,
        artist,
        durationMs > 0 ? durationMs : null,
      );
      const elapsed = Date.now() - startedAt;

      if (candidate) {
        events.push({
          type: "provider",
          provider: "lrclib",
          status: "found",
          syncLevel: candidate.syncLevel,
          candidate,
          durationMs: elapsed,
        });
        events.push({
          type: "done",
          cached: false,
          best: {
            provider: "lrclib",
            quality: candidate.quality.total,
            syncLevel: candidate.syncLevel,
          },
        });
      } else {
        events.push({
          type: "provider",
          provider: "lrclib",
          status: "not_found",
          ...(instrumental ? { instrumental: true } : {}),
          durationMs: elapsed,
        });
        events.push({
          type: "done",
          cached: false,
          status: "not_found",
          ...(instrumental ? { instrumental: true } : {}),
        });
      }
    } catch (err) {
      const elapsed = Date.now() - startedAt;
      const message =
        err instanceof Error ? err.message : "LRCLIB request failed";
      events.push({
        type: "provider",
        provider: "lrclib",
        status: "error",
        message,
        durationMs: elapsed,
      });
      events.push({
        type: "done",
        cached: false,
        status: "not_found",
      });
    }
  } else {
    events.push({
      type: "provider",
      provider: "lrclib",
      status: "not_found",
      durationMs: 0,
    });
    events.push({
      type: "done",
      cached: false,
      status: "not_found",
    });
  }

  const sseBody = events.map(formatSseEvent).join("");
  const headers = baseHeaders(reqCtx.requestId, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
  });
  return new Response(sseBody, { status: 200, headers });
}

export const handleLyricsRequest: SubRouterHandler = async (reqCtx) => {
  if (reqCtx.request.method.toUpperCase() !== "GET") return null;

  const { pathname } = reqCtx.url;

  if (pathname === "/v1/me/lyrics/search") {
    return handleLyricsSearch(reqCtx);
  }

  if (pathname.startsWith("/v1/tracks/") && pathname.endsWith("/lyrics")) {
    const rawId = pathname.slice(
      "/v1/tracks/".length,
      pathname.length - "/lyrics".length,
    );
    const decoded = safeDecodeURIComponent(rawId);
    if (decoded === null) {
      return errorResponse(400, "Invalid track id.", reqCtx.requestId, "BAD_REQUEST");
    }
    const trackId = decoded.trim();
    if (trackId && !trackId.includes("/")) {
      return handleTrackLyricsStream(reqCtx, trackId);
    }
  }

  return null;
};
