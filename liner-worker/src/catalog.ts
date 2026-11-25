import { errorResponse, jsonResponse, safeDecodeURIComponent } from "./http";
import {
  getSpotifyAlbum,
  getSpotifyArtist,
  getSpotifyPlaylist,
  searchSpotifyCatalog,
} from "./spotifyCatalog";
import { importSpotifyEntity } from "./spotifyImport";
import type {
  AlbumDetails,
  ArtistAlbum,
  ArtistDetails,
  ArtistRelease,
  CatalogPlaylistDetails,
  Cover,
  PopularTrackItem,
  PopularTracksResponse,
  RequestContext,
  SearchResponse,
  SearchResult,
  SubRouterHandler,
  Track,
  TrackAlbum,
  TrackArtist,
  TrackDetails,
} from "./types";

type JsonRecord = Record<string, unknown>;
type SearchType = "all" | "track" | "artist" | "album" | "playlist";
type InnertubeEndpoint = "search" | "browse" | "next";

const INNERTUBE_ORIGIN = "https://music.youtube.com";
const INNERTUBE_CLIENT_NAME = "WEB_REMIX";
const INNERTUBE_CLIENT_VERSION = "1.20260911.08.00";
const INNERTUBE_CLIENT_ID = "67";
const INNERTUBE_TIMEOUT_MS = 10_000;

const SEARCH_PARAMS: Partial<Record<SearchType | "video", string>> = {
  track: "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
  video: "EgWKAQIQAWoKEAkQBRAKEAMQBA%3D%3D",
  artist: "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D",
  album: "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D",
  playlist: "EgWKAQIoAWoKEAkQBRAKEAMQBA%3D%3D",
};

const ARTWORK_HOST =
  /(?:[a-zA-Z0-9_-]+\.googleusercontent\.com|yt3\.ggpht\.com)/i;

const RESULT_TYPE_LABELS = new Set([
  "song",
  "video",
  "episode",
  "podcast",
  "single",
  "ep",
  "album",
  "playlist",
  "artist",
  "profile",
]);

const ARTIST_JOINERS = /^\s*(?:,|&|and|x|feat\.?|ft\.?)\s*$/i;

function asRecord(value: unknown): JsonRecord | undefined {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as JsonRecord)
    : undefined;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function getRuns(value: unknown): JsonRecord[] {
  return asArray(asRecord(value)?.runs)
    .map(asRecord)
    .filter((run): run is JsonRecord => Boolean(run));
}

function getText(value: unknown): string | undefined {
  const record = asRecord(value);
  if (typeof record?.simpleText === "string" && record.simpleText.trim()) {
    return record.simpleText.trim();
  }
  const text = getRuns(value)
    .map((run) => run.text)
    .filter((part): part is string => typeof part === "string")
    .join("")
    .trim();
  return text || undefined;
}

function getBrowseEndpoint(value: unknown): JsonRecord | undefined {
  return asRecord(
    asRecord(asRecord(value)?.navigationEndpoint)?.browseEndpoint,
  );
}

function getPageType(endpoint: JsonRecord | undefined): string | undefined {
  const config = asRecord(
    asRecord(endpoint?.browseEndpointContextSupportedConfigs)
      ?.browseEndpointContextMusicConfig,
  );
  return typeof config?.pageType === "string" ? config.pageType : undefined;
}

function isArtistPage(pageType: string | undefined): boolean {
  return (
    pageType === "MUSIC_PAGE_TYPE_ARTIST" ||
    pageType === "MUSIC_PAGE_TYPE_USER_CHANNEL"
  );
}

function runsToArtists(
  runs: JsonRecord[],
  accept: (pageType: string | undefined) => boolean = isArtistPage,
): TrackArtist[] {
  return runs.flatMap((run) => {
    const endpoint = getBrowseEndpoint(run);
    const name = typeof run.text === "string" ? run.text.trim() : "";
    return accept(getPageType(endpoint)) &&
      typeof endpoint?.browseId === "string" &&
      name
      ? [{ id: endpoint.browseId, name }]
      : [];
  });
}

function runsToAlbum(runs: JsonRecord[]): TrackAlbum | undefined {
  const albumRun = runs.find(
    (run) => getPageType(getBrowseEndpoint(run)) === "MUSIC_PAGE_TYPE_ALBUM",
  );
  const endpoint = albumRun ? getBrowseEndpoint(albumRun) : undefined;
  return typeof endpoint?.browseId === "string" &&
    typeof albumRun?.text === "string"
    ? { id: endpoint.browseId, title: albumRun.text }
    : undefined;
}

function yearFromRuns(value: unknown): number | undefined {
  return getRuns(value)
    .map((run) =>
      typeof run.text === "string" && /^\d{4}$/.test(run.text)
        ? Number(run.text)
        : undefined,
    )
    .find((year): year is number => year !== undefined);
}

function toSquareArtworkUrl(url: string, size = 512): string {
  if (!ARTWORK_HOST.test(url)) return url;
  const suffix = size === 0 ? "=s0" : `=w${size}-h${size}-l90-rj`;
  if (/=w\d+-h\d+/.test(url)) {
    return url.replace(/=w\d+-h\d+(?:-[a-zA-Z0-9_-]+)*/, suffix);
  }
  if (/=s\d+/.test(url)) {
    return url.replace(/=s\d+(?:-[a-zA-Z0-9_-]+)*/, suffix);
  }
  return `${url}${suffix}`;
}

function coverFromThumbnail(
  thumbnail: unknown,
  isArtist = false,
): Cover | undefined {
  const record = asRecord(thumbnail);
  const inner =
    asRecord(asRecord(record?.musicThumbnailRenderer)?.thumbnail) ?? record;
  const entries = asArray(inner?.thumbnails)
    .map(asRecord)
    .flatMap((entry) =>
      entry && typeof entry.url === "string"
        ? [
            {
              url: entry.url,
              width: typeof entry.width === "number" ? entry.width : 0,
              height: typeof entry.height === "number" ? entry.height : 0,
            },
          ]
        : [],
    )
    .sort((a, b) => Math.max(b.width, b.height) - Math.max(a.width, a.height));

  const square = entries.find(
    (e) =>
      (e.width > 0 && e.height > 0 && e.width === e.height) ||
      ARTWORK_HOST.test(e.url),
  );
  if (square) {
    const size = isArtist ? 0 : 512;
    const dim = size === 0 ? Math.max(square.width, 1024) : size;
    return {
      url: toSquareArtworkUrl(square.url, size),
      width: dim,
      height: dim,
    };
  }
  const fallback = entries[0];
  if (!fallback) return undefined;
  return {
    url:
      isArtist && ARTWORK_HOST.test(fallback.url)
        ? toSquareArtworkUrl(fallback.url, 0)
        : fallback.url,
    width: fallback.width,
    height: fallback.height,
  };
}

function videoThumbnailCover(videoId: string): Cover {
  return {
    url: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    width: 480,
    height: 360,
  };
}

function hasExplicitBadge(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(hasExplicitBadge);
  const record = asRecord(value);
  if (!record) return false;
  if (
    asRecord(asRecord(record.musicInlineBadgeRenderer)?.icon)?.iconType ===
    "MUSIC_EXPLICIT_BADGE"
  ) {
    return true;
  }
  return Object.values(record).some(hasExplicitBadge);
}

function parseDurationMs(value: string): number | undefined {
  const parts = value.trim().split(":");
  if (
    parts.length < 2 ||
    parts.length > 3 ||
    parts.some((part) => !/^\d{1,3}$/.test(part))
  ) {
    return undefined;
  }
  const numbers = parts.map(Number);
  const seconds = numbers[numbers.length - 1]!;
  const minutes = numbers[numbers.length - 2]!;
  const hours = parts.length === 3 ? numbers[0]! : 0;
  if (seconds > 59 || (parts.length === 3 && minutes > 59)) return undefined;
  const total = hours * 3600 + minutes * 60 + seconds;
  return total > 0 ? total * 1000 : undefined;
}

function durationFromRuns(runs: JsonRecord[]): number {
  return (
    runs
      .map((run) =>
        typeof run.text === "string" ? parseDurationMs(run.text) : undefined,
      )
      .find((duration): duration is number => duration !== undefined) ?? 0
  );
}

function columnText(
  renderer: JsonRecord,
  key: string,
  rendererKey: string,
  index: number,
): JsonRecord | undefined {
  const column = asRecord(asArray(renderer[key])[index]);
  return asRecord(asRecord(column?.[rendererKey])?.text);
}

function watchVideoId(value: unknown): string | undefined {
  const videoId = asRecord(asRecord(value)?.watchEndpoint)?.videoId;
  return typeof videoId === "string" ? videoId : undefined;
}

function resolveVideoId(
  renderer: JsonRecord,
  titleRun: JsonRecord | undefined,
): string | undefined {
  const playlistVideoId = asRecord(renderer.playlistItemData)?.videoId;
  if (typeof playlistVideoId === "string") return playlistVideoId;
  const overlay = asRecord(
    asRecord(asRecord(renderer.overlay)?.musicItemThumbnailOverlayRenderer)
      ?.content,
  );
  const playButton = asRecord(overlay?.musicPlayButtonRenderer);
  return (
    watchVideoId(titleRun?.navigationEndpoint) ??
    watchVideoId(playButton?.playNavigationEndpoint) ??
    watchVideoId(renderer.onTap) ??
    watchVideoId(renderer.navigationEndpoint)
  );
}

function fallbackArtistsFromRuns(runs: JsonRecord[]): TrackArtist[] {
  const segments: JsonRecord[][] = [[]];
  for (const run of runs) {
    const text = typeof run.text === "string" ? run.text : "";
    if (text.trim() === "\u2022") {
      segments.push([]);
    } else {
      segments[segments.length - 1]!.push(run);
    }
  }
  const nonEmpty = segments.filter((seg) =>
    seg.some((run) => typeof run.text === "string" && run.text.trim()),
  );
  const segText = (seg: JsonRecord[]) =>
    seg
      .map((r) => (typeof r.text === "string" ? r.text : ""))
      .join("")
      .trim();

  if (
    nonEmpty.length > 1 &&
    RESULT_TYPE_LABELS.has(segText(nonEmpty[0]!).toLowerCase())
  ) {
    nonEmpty.shift();
  }
  const segment = nonEmpty[0];
  if (!segment || parseDurationMs(segText(segment)) !== undefined) {
    return [{ id: "unknown", name: "Unknown Artist" }];
  }
  const artists = segment.flatMap((run): TrackArtist[] => {
    const text = typeof run.text === "string" ? run.text.trim() : "";
    if (!text || ARTIST_JOINERS.test(text)) return [];
    const linked = runsToArtists([run]);
    if (linked.length > 0) return linked;
    return text
      .split(/\s*(?:,|\s&\s)\s*/)
      .map((name) => name.trim())
      .filter(Boolean)
      .map((name) => ({ id: "unknown", name }));
  });
  return artists.length > 0
    ? artists
    : [{ id: "unknown", name: "Unknown Artist" }];
}

function mapInnertubeTrack(
  raw: unknown,
  fallbackArtists: TrackArtist[] = [],
): Track | null {
  const item = asRecord(raw);
  const renderer = asRecord(item?.musicResponsiveListItemRenderer) ?? item;
  if (!renderer) return null;

  const titleColumn = columnText(
    renderer,
    "flexColumns",
    "musicResponsiveListItemFlexColumnRenderer",
    0,
  );
  const title = getText(titleColumn) ?? getText(renderer.title);
  const id = resolveVideoId(renderer, getRuns(titleColumn)[0]);
  if (!id || !title) return null;

  const flexRuns = asArray(renderer.flexColumns).flatMap((_, index) =>
    getRuns(
      columnText(
        renderer,
        "flexColumns",
        "musicResponsiveListItemFlexColumnRenderer",
        index,
      ),
    ),
  );
  const fixedRuns = asArray(renderer.fixedColumns).flatMap((_, index) =>
    getRuns(
      columnText(
        renderer,
        "fixedColumns",
        "musicResponsiveListItemFixedColumnRenderer",
        index,
      ),
    ),
  );
  const rowArtists = runsToArtists(flexRuns);
  const artists =
    rowArtists.length > 0
      ? rowArtists
      : fallbackArtists.length > 0
        ? fallbackArtists
        : fallbackArtistsFromRuns(
            getRuns(
              columnText(
                renderer,
                "flexColumns",
                "musicResponsiveListItemFlexColumnRenderer",
                1,
              ),
            ),
          );

  return {
    id,
    title,
    artists,
    album: runsToAlbum(flexRuns) ?? null,
    durationMs: durationFromRuns([...flexRuns, ...fixedRuns]),
    cover: coverFromThumbnail(renderer.thumbnail) ?? videoThumbnailCover(id),
    explicit: hasExplicitBadge(renderer),
    type: "track",
  };
}

function endpointFromCard(
  card: JsonRecord,
  titleRun: JsonRecord | undefined,
  kind: string,
  idKey: string,
): JsonRecord | undefined {
  const candidates: unknown[] = [
    card.onTap,
    titleRun?.navigationEndpoint,
    card.navigationEndpoint,
  ];
  const overlay = asRecord(
    asRecord(asRecord(card.thumbnailOverlay)?.musicItemThumbnailOverlayRenderer)
      ?.content,
  );
  candidates.push(
    asRecord(overlay?.musicPlayButtonRenderer)?.playNavigationEndpoint,
  );
  for (const button of asArray(card.buttons)) {
    const renderer = asRecord(asRecord(button)?.buttonRenderer);
    candidates.push(renderer?.command, renderer?.navigationEndpoint);
  }
  for (const candidate of candidates) {
    const endpoint = asRecord(asRecord(candidate)?.[kind]);
    if (typeof endpoint?.[idKey] === "string") return endpoint;
  }
  return undefined;
}

function mapCardShelf(raw: unknown): SearchResult | null {
  const record = asRecord(raw);
  const card =
    asRecord(record?.musicCardShelfRenderer) ??
    (record?.title && (record.onTap || record.buttons) ? record : undefined);
  if (!card) return null;

  const titleRun = getRuns(card.title)[0];
  const title = getText(card.title);
  if (!title) return null;

  const browse = endpointFromCard(card, titleRun, "browseEndpoint", "browseId");
  const watch = endpointFromCard(card, titleRun, "watchEndpoint", "videoId");
  const pageType = getPageType(browse);
  const browseId =
    typeof browse?.browseId === "string" ? browse.browseId : undefined;
  const subtitleRuns = getRuns(card.subtitle);
  const isArtist = pageType === "MUSIC_PAGE_TYPE_ARTIST";
  const cover = coverFromThumbnail(card.thumbnail, isArtist) ?? null;

  if (isArtist && browseId) {
    return { type: "artist", id: browseId, name: title, cover };
  }

  if (pageType === "MUSIC_PAGE_TYPE_ALBUM" && browseId) {
    const artists = runsToArtists(
      subtitleRuns,
      (type) => type === "MUSIC_PAGE_TYPE_ARTIST",
    );
    return {
      type: "album",
      id: browseId,
      title,
      artists,
      year: yearFromRuns(card.subtitle) ?? null,
      cover,
    };
  }

  if (pageType === "MUSIC_PAGE_TYPE_PLAYLIST" && browseId) {
    const author =
      subtitleRuns
        .map((run) => (typeof run.text === "string" ? run.text.trim() : ""))
        .find(Boolean) ?? null;
    return { type: "playlist", id: browseId, title, author, cover };
  }

  if (typeof watch?.videoId === "string") {
    const linked = runsToArtists(subtitleRuns);
    const plain = subtitleRuns.find((run) => {
      const t = typeof run.text === "string" ? run.text.trim() : "";
      if (!t || t === "\u2022") return false;
      if (["video", "song", "episode"].includes(t.toLowerCase())) return false;
      if (/views|plays|monthly/i.test(t)) return false;
      return parseDurationMs(t) === undefined;
    });
    const artists: TrackArtist[] =
      linked.length > 0
        ? linked
        : typeof plain?.text === "string"
          ? [{ id: "unknown", name: plain.text.trim() }]
          : [{ id: "unknown", name: "Unknown Artist" }];

    return {
      type: "track",
      id: watch.videoId,
      title,
      artists,
      album: runsToAlbum(subtitleRuns) ?? null,
      durationMs: durationFromRuns(subtitleRuns),
      cover: cover ?? videoThumbnailCover(watch.videoId),
      explicit: hasExplicitBadge(card),
    };
  }

  return null;
}

function mapListRow(raw: unknown): SearchResult | null {
  const renderer = asRecord(asRecord(raw)?.musicResponsiveListItemRenderer);
  if (!renderer) return null;
  const endpoint = getBrowseEndpoint(renderer);
  const title = getText(
    columnText(
      renderer,
      "flexColumns",
      "musicResponsiveListItemFlexColumnRenderer",
      0,
    ),
  );
  if (typeof endpoint?.browseId !== "string" || !title) return null;

  const browseId = endpoint.browseId;
  const pageType = getPageType(endpoint);
  const metadata = getRuns(
    columnText(
      renderer,
      "flexColumns",
      "musicResponsiveListItemFlexColumnRenderer",
      1,
    ),
  );

  if (pageType === "MUSIC_PAGE_TYPE_ARTIST") {
    return {
      type: "artist",
      id: browseId,
      name: title,
      cover: coverFromThumbnail(renderer.thumbnail, true) ?? null,
    };
  }

  const cover = coverFromThumbnail(renderer.thumbnail) ?? null;
  if (pageType === "MUSIC_PAGE_TYPE_ALBUM") {
    const year = metadata
      .map((run) =>
        typeof run.text === "string" && /^\d{4}$/.test(run.text)
          ? Number(run.text)
          : undefined,
      )
      .find((v): v is number => v !== undefined);
    return {
      type: "album",
      id: browseId,
      title,
      artists: runsToArtists(
        metadata,
        (type) => type === "MUSIC_PAGE_TYPE_ARTIST",
      ),
      year: year ?? null,
      cover,
    };
  }

  if (pageType === "MUSIC_PAGE_TYPE_PLAYLIST") {
    const author =
      metadata
        .map((run) => (typeof run.text === "string" ? run.text.trim() : ""))
        .find(Boolean) ?? null;
    return { type: "playlist", id: browseId, title, author, cover };
  }

  return null;
}

function mapSearchResponse(response: unknown): SearchResult[] {
  const tabs = asArray(
    asRecord(
      asRecord(asRecord(response)?.contents)?.tabbedSearchResultsRenderer,
    )?.tabs,
  );
  const tabContent = asRecord(
    asRecord(asRecord(tabs[0])?.tabRenderer)?.content,
  );
  const sections = asRecord(tabContent?.sectionListRenderer)?.contents;
  if (!Array.isArray(sections)) return [];

  const results: SearchResult[] = [];
  for (const section of sections) {
    const record = asRecord(section);
    const cardShelf = asRecord(record?.musicCardShelfRenderer);
    if (cardShelf) {
      const card = mapCardShelf({ musicCardShelfRenderer: cardShelf });
      if (card) results.push(card);
      const fallbackArtists: TrackArtist[] =
        card?.type === "artist"
          ? [{ id: card.id, name: card.name }]
          : card?.type === "track" || card?.type === "album"
            ? card.artists.filter((a) => a.id !== "unknown")
            : [];
      for (const item of asArray(cardShelf.contents)) {
        const track = mapInnertubeTrack(item, fallbackArtists);
        if (track) {
          results.push({ ...track, type: "track" });
        } else {
          const row = mapListRow(item);
          if (row) results.push(row);
        }
      }
      continue;
    }

    const shelf = asRecord(record?.musicShelfRenderer);
    const items = Array.isArray(shelf?.contents)
      ? shelf.contents
      : asArray(asRecord(record?.itemSectionRenderer)?.contents);

    for (const item of items) {
      const card = mapCardShelf(item);
      if (card) {
        results.push(card);
        continue;
      }
      const track = mapInnertubeTrack(item);
      if (track) {
        results.push({ ...track, type: "track" });
        continue;
      }
      const row = mapListRow(item);
      if (row) results.push(row);
    }
  }
  return results;
}

async function postInnertube(
  endpoint: InnertubeEndpoint,
  body: Record<string, unknown>,
): Promise<unknown> {
  const url = `${INNERTUBE_ORIGIN}/youtubei/v1/${endpoint}?prettyPrint=false`;
  const payload = {
    ...body,
    context: {
      client: {
        clientName: INNERTUBE_CLIENT_NAME,
        clientVersion: INNERTUBE_CLIENT_VERSION,
        hl: "en",
        gl: "US",
      },
      user: {
        lockedSafetyMode: false,
      },
    },
  };

  const response = await fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      accept: "application/json",
      "x-youtube-client-name": INNERTUBE_CLIENT_ID,
      "x-youtube-client-version": INNERTUBE_CLIENT_VERSION,
      "x-origin": INNERTUBE_ORIGIN,
    },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(INNERTUBE_TIMEOUT_MS),
  });

  if (!response.ok) {
    throw new Error(`YouTube Music ${endpoint} failed with HTTP ${response.status}.`);
  }
  return (await response.json()) as unknown;
}

async function searchRaw(
  query: string,
  filter?: SearchType | "video",
): Promise<SearchResult[]> {
  const params = filter ? SEARCH_PARAMS[filter] : undefined;
  const body = await postInnertube("search", {
    query,
    ...(params ? { params } : {}),
  });
  return mapSearchResponse(body);
}

function dedupeResults(items: SearchResult[]): SearchResult[] {
  const seen = new Set<string>();
  const out: SearchResult[] = [];
  for (const item of items) {
    const key = `${item.type}:${item.id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

function getQueueItems(response: unknown): unknown[] {
  const contents = asRecord(asRecord(response)?.contents);
  const singleColumn = asRecord(
    contents?.singleColumnMusicWatchNextResultsRenderer,
  );
  const watchNext = asRecord(
    asRecord(singleColumn?.tabbedRenderer)?.watchNextTabbedResultsRenderer,
  );
  for (const tab of asArray(watchNext?.tabs)) {
    const queue = asRecord(
      asRecord(asRecord(asRecord(tab)?.tabRenderer)?.content)
        ?.musicQueueRenderer,
    );
    const panel = asArray(
      asRecord(asRecord(queue?.content)?.playlistPanelRenderer)?.contents,
    );
    if (panel.length > 0) return panel;
  }
  const direct = asArray(
    asRecord(singleColumn?.playlistPanelRenderer)?.contents,
  );
  if (direct.length > 0) return direct;
  const twoColumn = asRecord(contents?.twoColumnWatchNextResults);
  return asArray(asRecord(asRecord(twoColumn?.playlist)?.playlist)?.contents);
}

function mapPanelTrack(raw: unknown): TrackDetails | null {
  const renderer = asRecord(asRecord(raw)?.playlistPanelVideoRenderer);
  if (!renderer) return null;
  const id = renderer.videoId;
  const title = getText(renderer.title);
  if (typeof id !== "string" || !title) return null;

  const byline =
    asRecord(renderer.longBylineText) ??
    asRecord(renderer.shortBylineText) ??
    asRecord(renderer.bylineText);
  const runs = getRuns(byline);
  const artists = runsToArtists(runs);
  const fallbackArtistName =
    runs
      .map((r) => (typeof r.text === "string" ? r.text.trim() : ""))
      .find((t) => t && t !== "\u2022" && !/^\d{4}$/.test(t)) ??
    getText(byline)?.split("\u2022")[0]?.trim() ??
    "Unknown Artist";

  const releaseDate =
    runs
      .map((r) => r.text)
      .find(
        (text): text is string =>
          typeof text === "string" && /^\d{4}$/.test(text),
      ) ?? null;

  const durationText =
    getText(renderer.lengthText) ?? getText(renderer.duration) ?? "";

  return {
    id,
    title,
    artists:
      artists.length > 0
        ? artists
        : [{ id: "unknown", name: fallbackArtistName }],
    album: runsToAlbum(runs) ?? null,
    durationMs: parseDurationMs(durationText) ?? 0,
    cover: coverFromThumbnail(renderer.thumbnail) ?? videoThumbnailCover(id),
    explicit: hasExplicitBadge(renderer),
    releaseDate,
  };
}

async function isTrackLiked(
  env: RequestContext["env"],
  userId: string | null,
  trackId: string,
): Promise<boolean> {
  if (!userId) return false;
  const row = await env.DB.prepare(
    "SELECT 1 AS found FROM liked_tracks WHERE user_id = ? AND track_id = ? LIMIT 1",
  )
    .bind(userId, trackId)
    .first<{ found: number }>();
  return Boolean(row?.found);
}

async function handleCatalogSearch(reqCtx: RequestContext): Promise<Response> {
  const query = reqCtx.url.searchParams.get("q")?.trim() ?? "";
  if (!query) {
    return errorResponse(400, "Query parameter 'q' is required.", reqCtx.requestId, "BAD_REQUEST");
  }

  const rawType = (reqCtx.url.searchParams.get("type")?.trim() ?? "all").toLowerCase();
  const type: SearchType =
    rawType === "track" ||
    rawType === "artist" ||
    rawType === "album" ||
    rawType === "playlist"
      ? rawType
      : "all";

  const source = (reqCtx.url.searchParams.get("source")?.trim() ?? "").toLowerCase();
  if (source === "spotify") {
    try {
      const items = await searchSpotifyCatalog(query, type);
      return jsonResponse<SearchResponse>({ items }, reqCtx.requestId);
    } catch (err) {
      return errorResponse(
        502,
        err instanceof Error ? err.message : "Spotify catalog search failed.",
        reqCtx.requestId,
        "UPSTREAM_ERROR",
      );
    }
  }

  if (type === "all") {
    const [mixedRes, tracksRes] = await Promise.allSettled([
      searchRaw(query),
      searchRaw(query, "track"),
    ]);
    if (mixedRes.status === "rejected" && tracksRes.status === "rejected") {
      return errorResponse(502, "Catalog search upstream failed.", reqCtx.requestId, "UPSTREAM_ERROR");
    }
    const mixed = mixedRes.status === "fulfilled" ? mixedRes.value : [];
    const tracks = tracksRes.status === "fulfilled" ? tracksRes.value : [];
    const items = dedupeResults([...mixed, ...tracks]);
    const payload: SearchResponse = { items };
    return jsonResponse(payload, reqCtx.requestId);
  }

  if (type === "track") {
    try {
      const items = dedupeResults(
        (await searchRaw(query, "track")).filter((item) => item.type === "track"),
      );
      return jsonResponse<SearchResponse>({ items }, reqCtx.requestId);
    } catch {
      return errorResponse(502, "Catalog search upstream failed.", reqCtx.requestId, "UPSTREAM_ERROR");
    }
  }

  const [mixedRes, typedRes] = await Promise.allSettled([
    searchRaw(query),
    searchRaw(query, type),
  ]);
  if (mixedRes.status === "rejected" && typedRes.status === "rejected") {
    return errorResponse(502, "Catalog search upstream failed.", reqCtx.requestId, "UPSTREAM_ERROR");
  }
  const mixed = mixedRes.status === "fulfilled" ? mixedRes.value : [];
  const typed = typedRes.status === "fulfilled" ? typedRes.value : [];
  const items = dedupeResults([
    ...(mixed[0]?.type === type ? [mixed[0]] : []),
    ...typed.filter((item) => item.type === type),
  ]);
  return jsonResponse<SearchResponse>({ items }, reqCtx.requestId);
}

async function handleGetTrack(
  reqCtx: RequestContext,
  trackId: string,
): Promise<Response> {
  if (!trackId) {
    return errorResponse(400, "Track id is required.", reqCtx.requestId, "BAD_REQUEST");
  }

  let details: TrackDetails | null = null;
  const relatedTracks: Track[] = [];

  try {
    const nextBody = await postInnertube("next", { videoId: trackId });
    const queueItems = getQueueItems(nextBody);
    const primaryRaw =
      queueItems.find(
        (item) => asRecord(asRecord(item)?.playlistPanelVideoRenderer)?.selected === true,
      ) ??
      queueItems.find(
        (item) => asRecord(asRecord(item)?.playlistPanelVideoRenderer)?.videoId === trackId,
      );
    details = mapPanelTrack(primaryRaw);

    for (const raw of queueItems) {
      const mapped = mapPanelTrack(raw);
      if (mapped && mapped.id !== trackId && relatedTracks.length < 15) {
        relatedTracks.push(mapped);
      }
    }
  } catch {
    // Fall back to search below
  }

  if (!details) {
    try {
      const searched = await searchRaw(trackId, "track");
      const match = searched.find(
        (item): item is Extract<SearchResult, { type: "track" }> =>
          item.type === "track" && item.id === trackId,
      );
      if (match) {
        details = {
          id: match.id,
          title: match.title,
          artists: match.artists,
          album: match.album ?? null,
          durationMs: match.durationMs,
          cover: match.cover ?? null,
          explicit: match.explicit ?? null,
          audioVariant: match.audioVariant ?? null,
          type: "track",
        };
      }
    } catch {
      // Handled as not found below
    }
  }

  if (!details) {
    return errorResponse(404, "Track was not found.", reqCtx.requestId, "NOT_FOUND");
  }

  const liked = await isTrackLiked(reqCtx.env, reqCtx.userId, trackId);
  return jsonResponse<TrackDetails>(
    {
      ...details,
      viewer: reqCtx.userId ? { liked } : details.viewer ?? null,
      relatedTracks,
    },
    reqCtx.requestId,
  );
}

export async function fetchYouTubeRadioTracks(
  videoId: string,
  limit = 20,
): Promise<Track[]> {
  try {
    let queueItems = getQueueItems(
      await postInnertube("next", {
        videoId,
        playlistId: `RDAMVM${videoId}`,
      }),
    );
    if (queueItems.length === 0) {
      queueItems = getQueueItems(await postInnertube("next", { videoId }));
    }
    const tracks: Track[] = [];
    const seen = new Set<string>();
    for (const raw of queueItems) {
      const mapped = mapPanelTrack(raw);
      if (!mapped || seen.has(mapped.id)) continue;
      seen.add(mapped.id);
      tracks.push({
        id: mapped.id,
        title: mapped.title,
        artists: mapped.artists,
        album: mapped.album ?? null,
        durationMs: mapped.durationMs,
        cover: mapped.cover ?? null,
        explicit: mapped.explicit ?? null,
        audioVariant: mapped.audioVariant ?? null,
        type: "track",
      });
      if (tracks.length >= limit) break;
    }
    return tracks;
  } catch {
    return [];
  }
}

export async function searchYouTubeSong(query: string): Promise<Track | null> {
  try {
    const results = await searchRaw(query, "track");
    for (const item of results) {
      if (item.type === "track") {
        return {
          id: item.id,
          title: item.title,
          artists: item.artists,
          album: item.album ?? null,
          durationMs: item.durationMs,
          cover: item.cover ?? null,
          explicit: item.explicit ?? null,
          audioVariant: item.audioVariant ?? null,
          type: "track",
          viewer: item.viewer ?? null,
        };
      }
    }
    return null;
  } catch {
    return null;
  }
}

function windowCutoffIso(window: string): string | null {
  const now = Date.now();
  switch (window) {
    case "24h":
    case "1d":
      return new Date(now - 24 * 60 * 60 * 1000).toISOString();
    case "7d":
      return new Date(now - 7 * 24 * 60 * 60 * 1000).toISOString();
    case "30d":
      return new Date(now - 30 * 24 * 60 * 60 * 1000).toISOString();
    default:
      return null;
  }
}

async function handleExplorePopular(reqCtx: RequestContext): Promise<Response> {
  const window = reqCtx.url.searchParams.get("window")?.trim() || "7d";
  const limitRaw = Number.parseInt(
    reqCtx.url.searchParams.get("limit") ?? "50",
    10,
  );
  const limit = Number.isFinite(limitRaw)
    ? Math.max(1, Math.min(limitRaw, 100))
    : 50;

  const cutoff = windowCutoffIso(window);
  const sql = cutoff
    ? `SELECT track_id, MAX(track_snapshot) AS track_snapshot, COUNT(*) AS play_count, COUNT(DISTINCT user_id) AS unique_listeners
       FROM playback_history
       WHERE listened_at >= ?
       GROUP BY track_id
       ORDER BY play_count DESC, unique_listeners DESC
       LIMIT ?`
    : `SELECT track_id, MAX(track_snapshot) AS track_snapshot, COUNT(*) AS play_count, COUNT(DISTINCT user_id) AS unique_listeners
       FROM playback_history
       GROUP BY track_id
       ORDER BY play_count DESC, unique_listeners DESC
       LIMIT ?`;

  const stmt = cutoff
    ? reqCtx.env.DB.prepare(sql).bind(cutoff, limit)
    : reqCtx.env.DB.prepare(sql).bind(limit);

  const { results } = await stmt.all<{
    track_id: string;
    track_snapshot: string;
    play_count: number;
    unique_listeners: number;
  }>();

  const items: PopularTrackItem[] = [];
  const seenIds = new Set<string>();

  for (const row of results) {
    try {
      const track = JSON.parse(row.track_snapshot) as Track;
      if (!track?.id) continue;
      seenIds.add(track.id);
      items.push({
        rank: items.length + 1,
        track,
        playCount: Number(row.play_count) || 0,
        uniqueListeners: Number(row.unique_listeners) || 0,
        score: Number(row.play_count) || 0,
      });
    } catch {
      // Skip malformed snapshot
    }
  }

  if (items.length < Math.min(limit, 20)) {
    try {
      const fallback = await searchRaw("top hits", "track");
      for (const entry of fallback) {
        if (items.length >= limit) break;
        if (entry.type !== "track" || seenIds.has(entry.id)) continue;
        seenIds.add(entry.id);
        items.push({
          rank: items.length + 1,
          track: {
            id: entry.id,
            title: entry.title,
            artists: entry.artists,
            album: entry.album ?? null,
            durationMs: entry.durationMs,
            cover: entry.cover ?? null,
            explicit: entry.explicit ?? null,
            type: "track",
          },
          playCount: 0,
          uniqueListeners: 0,
          score: Math.max(0.1, 1 - items.length * 0.02),
        });
      }
    } catch {
      // Return whatever D1 had
    }
  }

  const payload: PopularTracksResponse = {
    window,
    generatedAt: new Date().toISOString(),
    items,
  };
  return jsonResponse(payload, reqCtx.requestId);
}

// ---- Artist, Album & Catalog Playlist Browse ----

function walkRecords(
  node: unknown,
  visit: (record: JsonRecord) => boolean | void,
): void {
  if (!node || typeof node !== "object") return;
  if (Array.isArray(node)) {
    for (const item of node) walkRecords(item, visit);
    return;
  }
  const record = node as JsonRecord;
  if (visit(record) === true) return;
  for (const val of Object.values(record)) {
    walkRecords(val, visit);
  }
}

function findFirst(node: unknown, key: string): JsonRecord | undefined {
  let found: JsonRecord | undefined;
  walkRecords(node, (record) => {
    if (found) return true;
    const candidate = asRecord(record[key]);
    if (candidate) {
      found = candidate;
      return true;
    }
    return false;
  });
  return found;
}

function mapBrowseTracks(
  body: unknown,
  fallbackArtists: TrackArtist[] = [],
): Track[] {
  const tracks: Track[] = [];
  const seen = new Set<string>();
  walkRecords(body, (record) => {
    if (!record.musicResponsiveListItemRenderer) return false;
    const mapped = mapInnertubeTrack(record, fallbackArtists);
    if (mapped && !seen.has(mapped.id)) {
      seen.add(mapped.id);
      tracks.push(mapped);
    }
    return true;
  });
  return tracks;
}

function microformatCover(body: unknown, isArtist = false): Cover | undefined {
  return coverFromThumbnail(
    findFirst(body, "microformatDataRenderer")?.thumbnail,
    isArtist,
  );
}

function trackCountFrom(value: unknown): number | undefined {
  const match = getText(value)?.match(/([\d,]+)\s+(?:tracks?|songs?)/i);
  return match ? Number(match[1].replace(/,/g, "")) : undefined;
}

function mapReleaseCard(raw: unknown): ArtistAlbum | null {
  const item = asRecord(asRecord(raw)?.musicTwoRowItemRenderer);
  const titleRun = getRuns(item?.title)[0];
  const endpoint =
    asRecord(asRecord(titleRun?.navigationEndpoint)?.browseEndpoint) ??
    asRecord(asRecord(item?.navigationEndpoint)?.browseEndpoint);
  const title = getText(item?.title);
  if (
    getPageType(endpoint) !== "MUSIC_PAGE_TYPE_ALBUM" ||
    typeof endpoint?.browseId !== "string" ||
    !title
  ) {
    return null;
  }
  const year = yearFromRuns(item?.subtitle);
  const cover = coverFromThumbnail(item?.thumbnailRenderer);
  return {
    id: endpoint.browseId,
    title,
    year: year ?? null,
    cover: cover ?? null,
    explicit: hasExplicitBadge(item),
  };
}

function releaseTypeOfCard(raw: unknown): string {
  const item = asRecord(asRecord(raw)?.musicTwoRowItemRenderer);
  const first = getRuns(item?.subtitle)[0]?.text;
  return typeof first === "string" && first.trim().toLowerCase() === "ep"
    ? "ep"
    : "single";
}

function mapArtistDetails(id: string, body: unknown): ArtistDetails | null {
  const header =
    findFirst(body, "musicImmersiveHeaderRenderer") ??
    findFirst(body, "musicVisualHeaderRenderer") ??
    findFirst(body, "musicHeaderRenderer") ??
    findFirst(body, "musicResponsiveHeaderRenderer");
  const name = getText(header?.title);
  if (!name) return null;

  const listenersPattern =
    /[\d.,]+[KMBkmb]?\s*(?:monthly (?:audience|listeners)|слушателей за месяц|слухачів за місяць)/i;
  const monthlyListeners =
    getText(header?.monthlyListenerCount) ??
    getText(header?.subtitle)?.match(listenersPattern)?.[0] ??
    getText(header?.secondSubtitle)?.match(listenersPattern)?.[0] ??
    null;

  const bio = getText(header?.description)?.trim() ?? null;
  const cover =
    microformatCover(body, true) ??
    coverFromThumbnail(header?.thumbnail, true) ??
    null;

  const albums: ArtistAlbum[] = [];
  const singles: ArtistRelease[] = [];
  const seenReleases = new Set<string>();

  walkRecords(body, (record) => {
    const carousel = asRecord(record.musicCarouselShelfRenderer);
    if (!carousel) return false;
    const cHeader = asRecord(
      asRecord(carousel.header)?.musicCarouselShelfBasicHeaderRenderer,
    );
    const shelfTitle = getText(cHeader?.title)?.toLowerCase() ?? "";
    const isAlbums = shelfTitle === "albums" || shelfTitle === "альбомы";
    const isSingles =
      shelfTitle === "singles & eps" ||
      shelfTitle === "singles" ||
      shelfTitle.includes("сингл");
    if (!isAlbums && !isSingles) return true;

    for (const raw of asArray(carousel.contents)) {
      const rel = mapReleaseCard(raw);
      if (!rel || seenReleases.has(rel.id)) continue;
      seenReleases.add(rel.id);
      if (isAlbums) albums.push(rel);
      else singles.push({ ...rel, releaseType: releaseTypeOfCard(raw) });
    }
    return true;
  });

  const tracks = mapBrowseTracks(body, [{ id, name }]);
  return {
    type: "artist",
    id,
    name,
    bio,
    cover,
    monthlyListeners,
    verified: Boolean(monthlyListeners),
    tracks,
    albums,
    singles,
  };
}

function getArtistTopSongsBrowse(
  body: unknown,
): { browseId: string; params?: string } | undefined {
  let found: { browseId: string; params?: string } | undefined;
  walkRecords(body, (record) => {
    if (found) return true;
    const shelf = asRecord(record.musicShelfRenderer);
    if (!shelf) return false;
    const titleRun = getRuns(shelf.title)[0];
    const endpoint =
      asRecord(asRecord(titleRun?.navigationEndpoint)?.browseEndpoint) ??
      asRecord(asRecord(shelf.bottomEndpoint)?.browseEndpoint);
    if (typeof endpoint?.browseId !== "string") return false;
    found = {
      browseId: endpoint.browseId,
      ...(typeof endpoint.params === "string" ? { params: endpoint.params } : {}),
    };
    return true;
  });
  return found;
}

async function handleGetArtist(
  reqCtx: RequestContext,
  artistId: string,
): Promise<Response> {
  try {
    const body = await postInnertube("browse", { browseId: artistId });
    const details = mapArtistDetails(artistId, body);
    if (!details) {
      return errorResponse(404, "Artist not found", reqCtx.requestId, "NOT_FOUND");
    }

    const topSongsBrowse = getArtistTopSongsBrowse(body);
    if (topSongsBrowse && details.tracks.length < 15) {
      try {
        const songsBody = await postInnertube("browse", {
          browseId: topSongsBrowse.browseId,
          ...(topSongsBrowse.params ? { params: topSongsBrowse.params } : {}),
        });
        const moreTracks = mapBrowseTracks(songsBody, [
          { id: details.id, name: details.name },
        ]);
        if (moreTracks.length > details.tracks.length) {
          details.tracks = moreTracks.slice(0, 30);
        }
      } catch {
        // Keep initial shelf tracks
      }
    }

    return jsonResponse(details, reqCtx.requestId);
  } catch (err) {
    return errorResponse(
      502,
      err instanceof Error ? err.message : "Failed to load artist",
      reqCtx.requestId,
      "UPSTREAM_ERROR",
    );
  }
}

async function handleGetAlbum(
  reqCtx: RequestContext,
  rawAlbumId: string,
): Promise<Response> {
  try {
    let browseId = rawAlbumId;
    let body: unknown;
    if (rawAlbumId.startsWith("OLAK5uy_")) {
      const plBody = await postInnertube("browse", {
        browseId: `VL${rawAlbumId}`,
      });
      const match = JSON.stringify(plBody).match(/MPREb_[\w-]+/)?.[0];
      if (match) {
        browseId = match;
        body = await postInnertube("browse", { browseId });
      } else {
        body = plBody;
      }
    } else {
      body = await postInnertube("browse", { browseId });
    }

    const header =
      findFirst(body, "musicResponsiveHeaderRenderer") ??
      findFirst(body, "musicDetailHeaderRenderer");
    const title = getText(header?.title);
    if (!title) {
      return errorResponse(404, "Album not found", reqCtx.requestId, "NOT_FOUND");
    }
    const artists = runsToArtists(
      getRuns(header?.straplineTextOne),
      (pageType) => pageType === "MUSIC_PAGE_TYPE_ARTIST",
    );
    const cover =
      coverFromThumbnail(header?.thumbnail) ?? microformatCover(body) ?? null;
    const year = yearFromRuns(header?.subtitle) ?? null;
    const tracks = mapBrowseTracks(body, artists).map((t) => ({
      ...t,
      cover: cover ?? t.cover ?? null,
      album: t.album ?? { id: browseId, title },
    }));
    const trackCount = trackCountFrom(header?.secondSubtitle) ?? tracks.length;
    const durationMs = tracks.reduce((sum, t) => sum + (t.durationMs || 0), 0);

    const payload: AlbumDetails = {
      type: "album",
      id: browseId,
      title,
      artists,
      year,
      cover,
      trackCount,
      durationMs,
      tracks,
    };
    return jsonResponse(payload, reqCtx.requestId);
  } catch (err) {
    return errorResponse(
      502,
      err instanceof Error ? err.message : "Failed to load album",
      reqCtx.requestId,
      "UPSTREAM_ERROR",
    );
  }
}

async function handleGetCatalogPlaylist(
  reqCtx: RequestContext,
  playlistId: string,
): Promise<Response> {
  try {
    const browseId = /^(VL|MPRE|UC|FE)/.test(playlistId)
      ? playlistId
      : `VL${playlistId}`;
    const body = await postInnertube("browse", { browseId });
    const header =
      findFirst(body, "musicResponsiveHeaderRenderer") ??
      findFirst(body, "musicDetailHeaderRenderer");
    const title = getText(header?.title);
    if (!title) {
      return errorResponse(404, "Playlist not found", reqCtx.requestId, "NOT_FOUND");
    }
    const cover =
      coverFromThumbnail(header?.thumbnail) ?? microformatCover(body) ?? null;
    const author =
      getText(header?.straplineTextOne) ??
      getText(header?.subtitle)
        ?.split("•")
        .map((s) => s.trim())
        .find((s) => s && !/^(playlist|плейлист|\d{4})$/i.test(s)) ??
      null;
    const description = getText(header?.description) ?? null;
    const year = yearFromRuns(header?.subtitle) ?? null;
    const tracks = mapBrowseTracks(body);
    const trackCount = trackCountFrom(header?.secondSubtitle) ?? tracks.length;
    const durationMs = tracks.reduce((sum, t) => sum + (t.durationMs || 0), 0);

    const payload: CatalogPlaylistDetails = {
      type: "playlist",
      id: playlistId,
      title,
      author,
      description,
      year,
      cover,
      trackCount,
      durationMs,
      tracks,
    };
    return jsonResponse(payload, reqCtx.requestId);
  } catch (err) {
    return errorResponse(
      502,
      err instanceof Error ? err.message : "Failed to load playlist",
      reqCtx.requestId,
      "UPSTREAM_ERROR",
    );
  }
}

async function handleSpotifyImport(reqCtx: RequestContext): Promise<Response> {
  const rawUrl = reqCtx.url.searchParams.get("url")?.trim() ?? "";
  if (!rawUrl) {
    return errorResponse(400, "Query parameter 'url' is required.", reqCtx.requestId, "BAD_REQUEST");
  }
  try {
    const result = await importSpotifyEntity(rawUrl);
    return jsonResponse(result, reqCtx.requestId);
  } catch (err) {
    const message = err instanceof Error ? err.message : "Failed to fetch Spotify playlist";
    const status = message.includes("Unsupported") ? 400 : 502;
    return errorResponse(status, message, reqCtx.requestId, status === 400 ? "BAD_REQUEST" : "UPSTREAM_ERROR");
  }
}

export const handleCatalogRequest: SubRouterHandler = async (reqCtx) => {
  const method = reqCtx.request.method.toUpperCase();
  if (method !== "GET") return null;

  const { pathname } = reqCtx.url;

  if (pathname === "/v1/catalog/search") {
    return handleCatalogSearch(reqCtx);
  }

  if (pathname === "/v1/catalog/import/spotify") {
    return handleSpotifyImport(reqCtx);
  }

  if (pathname.startsWith("/v1/catalog/tracks/")) {
    const rawId = pathname.slice("/v1/catalog/tracks/".length);
    const trackId = safeDecodeURIComponent(rawId)?.trim();
    if (rawId && !trackId) {
      return errorResponse(400, "Invalid track id.", reqCtx.requestId, "BAD_REQUEST");
    }
    if (trackId && !trackId.includes("/")) {
      return handleGetTrack(reqCtx, trackId);
    }
  }

  if (pathname.startsWith("/v1/catalog/artists/")) {
    const rawId = pathname.slice("/v1/catalog/artists/".length);
    const artistId = safeDecodeURIComponent(rawId)?.trim();
    if (rawId && !artistId) {
      return errorResponse(400, "Invalid artist id.", reqCtx.requestId, "BAD_REQUEST");
    }
    if (artistId && !artistId.includes("/")) {
      if (artistId.startsWith("sp:artist:")) {
        try {
          const data = await getSpotifyArtist(artistId.slice("sp:artist:".length));
          return jsonResponse(data, reqCtx.requestId);
        } catch (err) {
          return errorResponse(
            502,
            err instanceof Error ? err.message : "Failed to load Spotify artist",
            reqCtx.requestId,
            "UPSTREAM_ERROR",
          );
        }
      }
      return handleGetArtist(reqCtx, artistId);
    }
  }

  if (pathname.startsWith("/v1/catalog/albums/")) {
    const rawId = pathname.slice("/v1/catalog/albums/".length);
    const albumId = safeDecodeURIComponent(rawId)?.trim();
    if (rawId && !albumId) {
      return errorResponse(400, "Invalid album id.", reqCtx.requestId, "BAD_REQUEST");
    }
    if (albumId && !albumId.includes("/")) {
      if (albumId.startsWith("sp:album:")) {
        try {
          const data = await getSpotifyAlbum(albumId.slice("sp:album:".length));
          return jsonResponse(data, reqCtx.requestId);
        } catch (err) {
          return errorResponse(
            502,
            err instanceof Error ? err.message : "Failed to load Spotify album",
            reqCtx.requestId,
            "UPSTREAM_ERROR",
          );
        }
      }
      return handleGetAlbum(reqCtx, albumId);
    }
  }

  if (pathname.startsWith("/v1/catalog/playlists/")) {
    const rawId = pathname.slice("/v1/catalog/playlists/".length);
    const playlistId = safeDecodeURIComponent(rawId)?.trim();
    if (rawId && !playlistId) {
      return errorResponse(400, "Invalid playlist id.", reqCtx.requestId, "BAD_REQUEST");
    }
    if (playlistId && !playlistId.includes("/")) {
      if (playlistId.startsWith("sp:playlist:")) {
        try {
          const data = await getSpotifyPlaylist(playlistId.slice("sp:playlist:".length));
          return jsonResponse(data, reqCtx.requestId);
        } catch (err) {
          return errorResponse(
            502,
            err instanceof Error ? err.message : "Failed to load Spotify playlist",
            reqCtx.requestId,
            "UPSTREAM_ERROR",
          );
        }
      }
      return handleGetCatalogPlaylist(reqCtx, playlistId);
    }
  }

  if (pathname === "/v1/explore/popular") {
    return handleExplorePopular(reqCtx);
  }

  return null;
};
