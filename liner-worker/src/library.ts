import {
  decodeCursor,
  encodeCursor,
  errorResponse,
  jsonResponse,
  noContentResponse,
  parseJsonBody,
  readJsonObject,
  readOptionalNullableString,
  readOptionalString,
  safeDecodeURIComponent,
} from "./http";
import { buildMyWaveBatch, buildOrGetDailyMixes } from "./recommendations";
import type {
  AuthUser,
  LikedTrackItem,
  LikedTracksPage,
  PlaybackHistoryItem,
  PlaybackHistoryResponse,
  RecordPlaybackEventRequest,
  RequestContext,
  Track,
  UserPlaylist,
  UserPlaylistDetails,
  UserPlaylistItem,
} from "./types";

interface LikedTrackRow {
  user_id: string;
  track_id: string;
  track_snapshot: string;
  liked_at: string;
}

interface PlaylistRow {
  id: string;
  user_id: string;
  title: string;
  description: string | null;
  is_public: number;
  revision: number;
  created_at: string;
  updated_at: string;
}

interface PlaylistItemRow {
  id: string;
  playlist_id: string;
  track_id: string;
  track_snapshot: string;
  position: number;
  added_at: string;
}

interface PlaybackHistoryRow {
  id: string;
  user_id: string;
  playback_session_id: string;
  listening_session_id: string;
  track_id: string;
  track_snapshot: string;
  played_duration_ms: number;
  track_duration_ms: number | null;
  completion_rate: number;
  completed: number;
  skipped: number;
  loop_count: number;
  has_seek_backward: number;
  context_type: string | null;
  context_id: string | null;
  listened_at: string;
}

function fallbackTrack(trackId: string): Track {
  return {
    id: trackId,
    title: trackId,
    artists: [],
    durationMs: 0,
  };
}

function parseTrackSnapshot(raw: string | null | undefined, trackId: string): Track {
  if (!raw) return fallbackTrack(trackId);
  try {
    const parsed = JSON.parse(raw) as Track;
    return {
      ...parsed,
      id: parsed.id || trackId,
      artists: Array.isArray(parsed.artists) ? parsed.artists : [],
      durationMs: typeof parsed.durationMs === "number" ? parsed.durationMs : 0,
    };
  } catch {
    return fallbackTrack(trackId);
  }
}

function extractTrackFromBody(
  body: { track?: Track | null; trackSnapshot?: Track | null } | null,
  trackId: string,
): Track {
  const candidate = body?.trackSnapshot ?? body?.track;
  if (!candidate) return fallbackTrack(trackId);
  return {
    ...candidate,
    id: candidate.id || trackId,
    artists: Array.isArray(candidate.artists) ? candidate.artists : [],
    durationMs: typeof candidate.durationMs === "number" ? candidate.durationMs : 0,
  };
}

function mapPlaylistRow(
  row: PlaylistRow,
  user: AuthUser,
  trackCount?: number,
  coverUrls?: string[],
): UserPlaylist {
  return {
    id: row.id,
    title: row.title,
    description: row.description,
    isPublic: row.is_public === 1,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    revision: row.revision,
    trackCount: trackCount ?? 0,
    coverUrls: coverUrls ?? [],
    ownerId: row.user_id,
    owner: {
      id: user.id,
      username: user.username,
      displayName: user.displayName,
      avatarUrl: user.avatarUrl,
    },
  };
}

export async function handleLibraryRequest(reqCtx: RequestContext): Promise<Response | null> {
  const { request, env, url, requestId, user, userId } = reqCtx;
  const path = url.pathname;
  const method = request.method.toUpperCase();

  // All /v1/me/* library/playlist/playback/history/recommendation routes require auth
  const isLibraryRoute =
    path === "/v1/me/library/tracks" ||
    path.startsWith("/v1/me/library/tracks/") ||
    path === "/v1/me/playlists" ||
    path.startsWith("/v1/me/playlists/") ||
    path === "/v1/me/playback/events" ||
    path === "/v1/me/history" ||
    path === "/v1/me/recommendations/daily-mixes" ||
    path === "/v1/me/recommendations/wave";

  if (!isLibraryRoute) return null;

  if (!user || !userId) {
    return errorResponse(401, "Unauthorized", requestId, "UNAUTHORIZED");
  }

  // ---- Liked Tracks ----

  // GET /v1/me/library/tracks
  if (method === "GET" && path === "/v1/me/library/tracks") {
    const rawLimit = Number.parseInt(url.searchParams.get("limit") ?? "100", 10);
    const limit = Number.isFinite(rawLimit) ? Math.min(Math.max(rawLimit, 1), 200) : 100;
    const offset = decodeCursor(url.searchParams.get("cursor"));

    const rows = await env.DB.prepare(
      `SELECT user_id, track_id, track_snapshot, liked_at
       FROM liked_tracks
       WHERE user_id = ?
       ORDER BY liked_at DESC
       LIMIT ? OFFSET ?`,
    )
      .bind(userId, limit + 1, offset)
      .all<LikedTrackRow>();

    const results = rows.results ?? [];
    const hasMore = results.length > limit;
    const pageRows = hasMore ? results.slice(0, limit) : results;

    const items: LikedTrackItem[] = pageRows.map((row) => {
      const track = parseTrackSnapshot(row.track_snapshot, row.track_id);
      return {
        track: { ...track, viewer: { liked: true } },
        likedAt: row.liked_at,
      };
    });

    const page: LikedTracksPage = {
      items,
      nextCursor: hasMore ? encodeCursor(offset + limit) : null,
    };
    return jsonResponse(page, requestId);
  }

  // PUT /v1/me/library/tracks/:id
  const likeTrackMatch = path.match(/^\/v1\/me\/library\/tracks\/([^/]+)$/);
  if (likeTrackMatch && method === "PUT") {
    const trackId = safeDecodeURIComponent(likeTrackMatch[1]);
    if (!trackId) {
      return errorResponse(400, "Invalid track id.", requestId, "BAD_REQUEST");
    }
    const body = await parseJsonBody<{ track?: Track | null; trackSnapshot?: Track | null }>(
      request,
    );
    const snapshot = extractTrackFromBody(body, trackId);
    const nowIso = new Date().toISOString();

    await env.DB.prepare(
      `INSERT INTO liked_tracks (user_id, track_id, track_snapshot, liked_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(user_id, track_id) DO UPDATE SET
         track_snapshot = excluded.track_snapshot,
         liked_at = excluded.liked_at`,
    )
      .bind(userId, trackId, JSON.stringify(snapshot), nowIso)
      .run();

    return noContentResponse(requestId);
  }

  // DELETE /v1/me/library/tracks/:id
  if (likeTrackMatch && method === "DELETE") {
    const trackId = safeDecodeURIComponent(likeTrackMatch[1]);
    if (!trackId) {
      return errorResponse(400, "Invalid track id.", requestId, "BAD_REQUEST");
    }
    await env.DB.prepare("DELETE FROM liked_tracks WHERE user_id = ? AND track_id = ?")
      .bind(userId, trackId)
      .run();
    return noContentResponse(requestId);
  }

  // ---- Playlists ----

  // GET /v1/me/playlists
  if (method === "GET" && path === "/v1/me/playlists") {
    const [playlistsResult, coverItemsResult] = await Promise.all([
      env.DB.prepare(
        `SELECT p.*, COUNT(pi.id) AS track_count
         FROM playlists p
         LEFT JOIN playlist_items pi ON pi.playlist_id = p.id
         WHERE p.user_id = ?
         GROUP BY p.id
         ORDER BY p.updated_at DESC`,
      )
        .bind(userId)
        .all<PlaylistRow & { track_count: number }>(),
      env.DB.prepare(
        `SELECT playlist_id, track_id, track_snapshot
         FROM (
           SELECT pi.playlist_id, pi.track_id, pi.track_snapshot,
                  ROW_NUMBER() OVER (PARTITION BY pi.playlist_id ORDER BY pi.position ASC, pi.added_at ASC) AS rn
           FROM playlist_items pi
           INNER JOIN playlists p ON p.id = pi.playlist_id
           WHERE p.user_id = ?
         )
         WHERE rn <= 8
         ORDER BY playlist_id, rn ASC`,
      )
        .bind(userId)
        .all<{ playlist_id: string; track_id: string; track_snapshot: string }>(),
    ]);

    const coversByPlaylist = new Map<string, string[]>();
    for (const item of coverItemsResult.results ?? []) {
      let urls = coversByPlaylist.get(item.playlist_id);
      if (!urls) {
        urls = [];
        coversByPlaylist.set(item.playlist_id, urls);
      }
      if (urls.length >= 4) continue;
      const track = parseTrackSnapshot(item.track_snapshot, item.track_id);
      const urlStr = track.cover?.url;
      if (urlStr && !urls.includes(urlStr)) {
        urls.push(urlStr);
      }
    }

    const items: UserPlaylist[] = (playlistsResult.results ?? []).map((pl) =>
      mapPlaylistRow(
        pl,
        user,
        Number(pl.track_count) || 0,
        coversByPlaylist.get(pl.id) ?? [],
      ),
    );

    return jsonResponse({ items }, requestId);
  }

  // POST /v1/me/playlists
  if (method === "POST" && path === "/v1/me/playlists") {
    const body = await readJsonObject(request);
    if (!body) {
      return errorResponse(400, "Invalid JSON request body.", requestId, "INVALID_BODY");
    }
    const titleField = readOptionalString(body, "title");
    const descField = readOptionalNullableString(body, "description");
    if (!titleField.valid || !descField.valid) {
      return errorResponse(400, "Invalid playlist fields.", requestId, "INVALID_BODY");
    }

    const title = titleField.value?.trim();
    if (!title) {
      return errorResponse(400, "Playlist title is required.", requestId, "INVALID_TITLE");
    }

    const id = crypto.randomUUID();
    const nowIso = new Date().toISOString();
    const description = descField.value?.trim() || null;
    const isPublic = body.isPublic === true ? 1 : 0;

    await env.DB.prepare(
      `INSERT INTO playlists (id, user_id, title, description, is_public, revision, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, 1, ?, ?)`,
    )
      .bind(id, userId, title, description, isPublic, nowIso, nowIso)
      .run();

    const created = await env.DB.prepare("SELECT * FROM playlists WHERE id = ?")
      .bind(id)
      .first<PlaylistRow>();
    if (!created) {
      return errorResponse(500, "Failed to create playlist.", requestId, "INTERNAL_ERROR");
    }

    return jsonResponse(mapPlaylistRow(created, user, 0, []), requestId, 201);
  }

  // /v1/me/playlists/:id
  const playlistMatch = path.match(/^\/v1\/me\/playlists\/([^/]+)$/);
  if (playlistMatch) {
    const playlistId = safeDecodeURIComponent(playlistMatch[1]);
    if (!playlistId) {
      return errorResponse(400, "Invalid playlist id.", requestId, "BAD_REQUEST");
    }
    const playlist = await env.DB.prepare(
      "SELECT * FROM playlists WHERE id = ? AND user_id = ?",
    )
      .bind(playlistId, userId)
      .first<PlaylistRow>();

    if (!playlist) {
      return errorResponse(404, "Playlist not found.", requestId, "PLAYLIST_NOT_FOUND");
    }

    if (method === "GET") {
      const etag = `"pl-${playlist.id}-r${playlist.revision}"`;
      const ifNoneMatch = request.headers.get("If-None-Match");
      if (ifNoneMatch && ifNoneMatch === etag) {
        return noContentResponse(requestId, 304, { ETag: etag });
      }

      const itemRowsResult = await env.DB.prepare(
        `SELECT * FROM playlist_items WHERE playlist_id = ? ORDER BY position ASC, added_at ASC`,
      )
        .bind(playlistId)
        .all<PlaylistItemRow>();

      const itemRows = itemRowsResult.results ?? [];
      let durationMs = 0;
      const coverUrls: string[] = [];
      const items: UserPlaylistItem[] = itemRows.map((row) => {
        const track = parseTrackSnapshot(row.track_snapshot, row.track_id);
        durationMs += track.durationMs || 0;
        if (coverUrls.length < 4 && track.cover?.url && !coverUrls.includes(track.cover.url)) {
          coverUrls.push(track.cover.url);
        }
        return {
          id: row.id,
          track,
          position: row.position,
          addedAt: row.added_at,
        };
      });

      const details: UserPlaylistDetails = {
        partial: false,
        trackCount: items.length,
        durationMs,
        playlist: mapPlaylistRow(playlist, user, items.length, coverUrls),
        items,
        etag,
      };
      return jsonResponse(details, requestId, 200, { ETag: etag });
    }

    if (method === "PATCH") {
      const body = await readJsonObject(request);
      if (!body) {
        return errorResponse(400, "Invalid JSON request body.", requestId, "INVALID_BODY");
      }

      const titleField = readOptionalString(body, "title");
      const descField = readOptionalNullableString(body, "description");
      if (!titleField.valid) {
        return errorResponse(400, "Playlist title must be a string.", requestId, "INVALID_TITLE");
      }
      if (!descField.valid) {
        return errorResponse(400, "Playlist description must be a string or null.", requestId, "INVALID_BODY");
      }
      const nextTitle = titleField.value !== undefined ? titleField.value.trim() : playlist.title;
      if (!nextTitle) {
        return errorResponse(400, "Playlist title cannot be empty.", requestId, "INVALID_TITLE");
      }

      const nextDescription =
        descField.value !== undefined ? descField.value : playlist.description;
      const nextIsPublic =
        body.isPublic !== undefined ? (body.isPublic ? 1 : 0) : playlist.is_public;
      const nowIso = new Date().toISOString();

      await env.DB.prepare(
        `UPDATE playlists
         SET title = ?, description = ?, is_public = ?, revision = revision + 1, updated_at = ?
         WHERE id = ? AND user_id = ?`,
      )
        .bind(nextTitle, nextDescription, nextIsPublic, nowIso, playlistId, userId)
        .run();

      const [updated, itemRows] = await Promise.all([
        env.DB.prepare("SELECT * FROM playlists WHERE id = ?")
          .bind(playlistId)
          .first<PlaylistRow>(),
        env.DB.prepare(
          "SELECT track_id, track_snapshot FROM playlist_items WHERE playlist_id = ? ORDER BY position ASC",
        )
          .bind(playlistId)
          .all<{ track_id: string; track_snapshot: string }>(),
      ]);
      const coverUrls: string[] = [];
      for (const row of itemRows.results) {
        const track = parseTrackSnapshot(row.track_snapshot, row.track_id);
        const url = track.cover?.url || track.cover?.fallbackUrl;
        if (url && !coverUrls.includes(url)) {
          coverUrls.push(url);
          if (coverUrls.length >= 4) break;
        }
      }
      return jsonResponse(
        mapPlaylistRow(updated ?? playlist, user, itemRows.results.length, coverUrls),
        requestId,
      );
    }

    if (method === "DELETE") {
      await env.DB.prepare("DELETE FROM playlists WHERE id = ? AND user_id = ?")
        .bind(playlistId, userId)
        .run();
      return noContentResponse(requestId);
    }
  }

  // POST /v1/me/playlists/:id/tracks/:trackId
  const addTrackMatch = path.match(/^\/v1\/me\/playlists\/([^/]+)\/tracks\/([^/]+)$/);
  if (addTrackMatch && method === "POST") {
    const playlistId = safeDecodeURIComponent(addTrackMatch[1]);
    const trackId = safeDecodeURIComponent(addTrackMatch[2]);
    if (!playlistId || !trackId) {
      return errorResponse(400, "Invalid playlist or track id.", requestId, "BAD_REQUEST");
    }

    const playlist = await env.DB.prepare(
      "SELECT * FROM playlists WHERE id = ? AND user_id = ?",
    )
      .bind(playlistId, userId)
      .first<PlaylistRow>();
    if (!playlist) {
      return errorResponse(404, "Playlist not found.", requestId, "PLAYLIST_NOT_FOUND");
    }

    const body = await parseJsonBody<{ track?: Track | null; trackSnapshot?: Track | null }>(
      request,
    );
    const track = extractTrackFromBody(body, trackId);

    const maxPosRow = await env.DB.prepare(
      "SELECT COALESCE(MAX(position), -1) AS max_pos FROM playlist_items WHERE playlist_id = ?",
    )
      .bind(playlistId)
      .first<{ max_pos: number }>();

    const nextPosition = (maxPosRow?.max_pos ?? -1) + 1;
    const itemId = crypto.randomUUID();
    const nowIso = new Date().toISOString();

    await env.DB.prepare(
      `INSERT INTO playlist_items (id, playlist_id, track_id, track_snapshot, position, added_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    )
      .bind(itemId, playlistId, trackId, JSON.stringify(track), nextPosition, nowIso)
      .run();

    await env.DB.prepare(
      "UPDATE playlists SET revision = revision + 1, updated_at = ? WHERE id = ?",
    )
      .bind(nowIso, playlistId)
      .run();

    const item: UserPlaylistItem = {
      id: itemId,
      track,
      position: nextPosition,
      addedAt: nowIso,
    };
    return jsonResponse(item, requestId, 201);
  }

  // POST /v1/me/playlists/:id/tracks (bulk snapshot add)
  const bulkAddMatch = path.match(/^\/v1\/me\/playlists\/([^/]+)\/tracks$/);
  if (bulkAddMatch && method === "POST") {
    const playlistId = safeDecodeURIComponent(bulkAddMatch[1]);
    if (!playlistId) {
      return errorResponse(400, "Invalid playlist id.", requestId, "BAD_REQUEST");
    }
    const playlist = await env.DB.prepare(
      "SELECT * FROM playlists WHERE id = ? AND user_id = ?",
    )
      .bind(playlistId, userId)
      .first<PlaylistRow>();
    if (!playlist) {
      return errorResponse(404, "Playlist not found.", requestId, "PLAYLIST_NOT_FOUND");
    }

    const body = await parseJsonBody<{ tracks?: Track[] }>(request);
    const tracks = Array.isArray(body?.tracks) ? body.tracks : [];
    const maxPosRow = await env.DB.prepare(
      "SELECT COALESCE(MAX(position), -1) AS max_pos FROM playlist_items WHERE playlist_id = ?",
    )
      .bind(playlistId)
      .first<{ max_pos: number }>();

    let nextPos = (maxPosRow?.max_pos ?? -1) + 1;
    const nowIso = new Date().toISOString();
    const addedItems: UserPlaylistItem[] = [];
    let rejected = 0;

    const insertStmt = env.DB.prepare(
      `INSERT INTO playlist_items (id, playlist_id, track_id, track_snapshot, position, added_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    );
    const statements: D1PreparedStatement[] = [];

    for (const rawTrack of tracks) {
      if (!rawTrack?.id) {
        rejected++;
        continue;
      }
      const track: Track = {
        ...rawTrack,
        artists: Array.isArray(rawTrack.artists) ? rawTrack.artists : [],
        durationMs: typeof rawTrack.durationMs === "number" ? rawTrack.durationMs : 0,
      };
      const itemId = crypto.randomUUID();
      statements.push(
        insertStmt.bind(itemId, playlistId, track.id, JSON.stringify(track), nextPos, nowIso),
      );

      addedItems.push({
        id: itemId,
        track,
        position: nextPos,
        addedAt: nowIso,
      });
      nextPos++;
    }

    if (statements.length > 0) {
      statements.push(
        env.DB.prepare(
          "UPDATE playlists SET revision = revision + 1, updated_at = ? WHERE id = ?",
        ).bind(nowIso, playlistId),
      );
      // Chunk batches to stay well within D1 batch size limits on large imports
      const BATCH_CHUNK = 100;
      for (let i = 0; i < statements.length; i += BATCH_CHUNK) {
        await env.DB.batch(statements.slice(i, i + BATCH_CHUNK));
      }
    }

    return jsonResponse(
      { items: addedItems, added: addedItems.length, rejected },
      requestId,
      201,
    );
  }

  // DELETE /v1/me/playlists/:id/items/:itemId
  const removeItemMatch = path.match(/^\/v1\/me\/playlists\/([^/]+)\/items\/([^/]+)$/);
  if (removeItemMatch && method === "DELETE") {
    const playlistId = safeDecodeURIComponent(removeItemMatch[1]);
    const itemId = safeDecodeURIComponent(removeItemMatch[2]);
    if (!playlistId || !itemId) {
      return errorResponse(400, "Invalid playlist or item id.", requestId, "BAD_REQUEST");
    }

    const playlist = await env.DB.prepare(
      "SELECT * FROM playlists WHERE id = ? AND user_id = ?",
    )
      .bind(playlistId, userId)
      .first<PlaylistRow>();
    if (!playlist) {
      return errorResponse(404, "Playlist not found.", requestId, "PLAYLIST_NOT_FOUND");
    }

    await env.DB.prepare(
      "DELETE FROM playlist_items WHERE id = ? AND playlist_id = ?",
    )
      .bind(itemId, playlistId)
      .run();

    await env.DB.prepare(
      "UPDATE playlists SET revision = revision + 1, updated_at = ? WHERE id = ?",
    )
      .bind(new Date().toISOString(), playlistId)
      .run();

    return noContentResponse(requestId);
  }

  // ---- Playback Events & History ----

  // POST /v1/me/playback/events
  if (method === "POST" && path === "/v1/me/playback/events") {
    const rawBody = await readJsonObject(request);
    if (!rawBody) {
      return errorResponse(400, "Invalid JSON request body.", requestId, "INVALID_BODY");
    }
    const trackIdField = readOptionalString(rawBody, "trackId");
    const playbackSessionField = readOptionalString(rawBody, "playbackSessionId");
    const listeningSessionField = readOptionalString(rawBody, "listeningSessionId");
    if (
      !trackIdField.valid ||
      !playbackSessionField.valid ||
      !listeningSessionField.valid ||
      !trackIdField.value?.trim() ||
      !playbackSessionField.value?.trim() ||
      !listeningSessionField.value?.trim()
    ) {
      return errorResponse(
        400,
        "playbackSessionId, listeningSessionId, and trackId are required.",
        requestId,
        "INVALID_PLAYBACK_EVENT",
      );
    }
    const body = rawBody as unknown as RecordPlaybackEventRequest;

    const track = extractTrackFromBody(
      { trackSnapshot: body.trackSnapshot ?? null },
      body.trackId,
    );
    const id = crypto.randomUUID();
    const nowIso = new Date().toISOString();

    await env.DB.prepare(
      `INSERT INTO playback_history (
        id, user_id, playback_session_id, listening_session_id,
        track_id, track_snapshot, played_duration_ms, track_duration_ms,
        completion_rate, completed, skipped, loop_count, has_seek_backward,
        context_type, context_id, listened_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(
        id,
        userId,
        body.playbackSessionId,
        body.listeningSessionId,
        body.trackId,
        JSON.stringify(track),
        body.playedDurationMs ?? 0,
        body.trackDurationMs ?? null,
        body.completionRate ?? 0,
        body.completed ? 1 : 0,
        body.skipped ? 1 : 0,
        body.loopCount ?? 0,
        body.hasSeekBackward ? 1 : 0,
        body.context?.type ?? null,
        body.context?.id ?? null,
        nowIso,
      )
      .run();

    return noContentResponse(requestId);
  }

  // GET /v1/me/history
  if (method === "GET" && path === "/v1/me/history") {
    const rawLimit = Number.parseInt(url.searchParams.get("limit") ?? "30", 10);
    const limit = Number.isFinite(rawLimit) ? Math.min(Math.max(rawLimit, 1), 100) : 30;
    const offset = decodeCursor(url.searchParams.get("cursor"));

    const rows = await env.DB.prepare(
      `SELECT * FROM playback_history
       WHERE user_id = ?
       ORDER BY listened_at DESC
       LIMIT ? OFFSET ?`,
    )
      .bind(userId, limit + 1, offset)
      .all<PlaybackHistoryRow>();

    const results = rows.results ?? [];
    const hasMore = results.length > limit;
    const pageRows = hasMore ? results.slice(0, limit) : results;

    const items: PlaybackHistoryItem[] = pageRows.map((row) => ({
      id: row.id,
      playbackSessionId: row.playback_session_id,
      listeningSessionId: row.listening_session_id,
      track: parseTrackSnapshot(row.track_snapshot, row.track_id),
      playedDurationMs: row.played_duration_ms,
      completionRate: row.completion_rate,
      completed: row.completed === 1,
      skipped: row.skipped === 1,
      loopCount: row.loop_count,
      hasSeekBackward: row.has_seek_backward === 1,
      context: row.context_type
        ? { type: row.context_type, id: row.context_id }
        : null,
      listenedAt: row.listened_at,
    }));

    const response: PlaybackHistoryResponse = {
      items,
      nextCursor: hasMore ? encodeCursor(offset + limit) : null,
    };
    return jsonResponse(response, requestId);
  }

  // ---- Recommendations ----

  // GET /v1/me/recommendations/daily-mixes
  if (method === "GET" && path === "/v1/me/recommendations/daily-mixes") {
    if (!reqCtx.userId) {
      return errorResponse(401, "Unauthorized", reqCtx.requestId, "UNAUTHORIZED");
    }
    const forceRefresh = reqCtx.url.searchParams.get("refresh") === "1";
    const response = await buildOrGetDailyMixes(reqCtx.env, reqCtx.userId, forceRefresh);
    return jsonResponse(response, requestId);
  }

  // POST/GET /v1/me/recommendations/wave
  if (
    (method === "POST" || method === "GET") &&
    path === "/v1/me/recommendations/wave"
  ) {
    if (!reqCtx.userId) {
      return errorResponse(401, "Unauthorized", reqCtx.requestId, "UNAUTHORIZED");
    }
    let vibe = reqCtx.url.searchParams.get("vibe") ?? undefined;
    let excludeIds: string[] = [];
    let limit = Number(reqCtx.url.searchParams.get("limit") ?? "20") || 20;

    if (method === "POST") {
      const body = await readJsonObject(request);
      if (body) {
        if (typeof body.vibe === "string") vibe = body.vibe;
        if (Array.isArray(body.excludeIds)) {
          excludeIds = body.excludeIds.filter((id): id is string => typeof id === "string");
        }
        if (typeof body.limit === "number" && Number.isFinite(body.limit)) {
          limit = body.limit;
        }
      }
    }

    const response = await buildMyWaveBatch(reqCtx.env, reqCtx.userId, {
      vibe: vibe as import("./types").WaveVibe | undefined,
      excludeIds,
      limit,
    });
    return jsonResponse(response, requestId);
  }

  return null;
}
