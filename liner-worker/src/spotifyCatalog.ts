import { getSpotifyGuestSession, importSpotifyEntity } from "./spotifyImport";
import type {
  AlbumDetails,
  ArtistAlbum,
  ArtistDetails,
  ArtistRelease,
  CatalogPlaylistDetails,
  Cover,
  SearchResult,
  Track,
  TrackAlbum,
  TrackArtist,
} from "./types";

type JsonRecord = Record<string, unknown>;

const HASH_SEARCH_DESKTOP =
  "d9f785900f0710b31c07818d617f4f7600c1e21217e80f5b043d1e78d74e6026";
const HASH_GET_ALBUM =
  "0b6ce3511e1b02e4a40fa95440f992acbe85080eb308af9194322d708fc1914f";
const HASH_ARTIST_OVERVIEW =
  "eafb1620d783ece118e72fbfede2b0064483e029d3c1aafafa1c2de0e25a89e7";

function asRecord(v: unknown): JsonRecord | undefined {
  return v && typeof v === "object" && !Array.isArray(v)
    ? (v as JsonRecord)
    : undefined;
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

function recAt(v: unknown, ...keys: string[]): JsonRecord | undefined {
  let cur: unknown = v;
  for (const k of keys) cur = asRecord(cur)?.[k];
  return asRecord(cur);
}

function idFromUri(uri: unknown): string {
  if (typeof uri !== "string") return "";
  const parts = uri.split(":");
  return parts[parts.length - 1]?.trim() ?? "";
}

function pickCoverFromSources(sources: unknown): Cover | null {
  const arr = asArray(sources)
    .map(asRecord)
    .filter((i): i is JsonRecord => Boolean(i && typeof i.url === "string"))
    .sort((a, b) => (Number(b.width) || 0) - (Number(a.width) || 0));
  if (arr.length === 0) return null;
  const best = arr[0];
  const smallest = arr[arr.length - 1];
  const w = typeof best.width === "number" ? best.width : 640;
  const h = typeof best.height === "number" ? best.height : 640;
  return {
    url: String(best.url),
    fallbackUrl: smallest ? String(smallest.url) : undefined,
    width: w,
    height: h,
  };
}

function mapPathfinderArtists(rawItems: unknown): TrackArtist[] {
  return asArray(rawItems)
    .map(asRecord)
    .map((a): TrackArtist | null => {
      const id = idFromUri(a?.uri);
      const name =
        typeof recAt(a, "profile")?.name === "string"
          ? String(recAt(a, "profile")?.name).trim()
          : typeof a?.name === "string"
            ? a.name.trim()
            : "";
      if (!name) return null;
      return { id: id ? `sp:artist:${id}` : "unknown", name };
    })
    .filter((a): a is TrackArtist => Boolean(a));
}

function mapPathfinderTrack(
  raw: unknown,
  fallbackAlbum?: TrackAlbum | null,
  fallbackCover?: Cover | null,
): Track | null {
  const rec = asRecord(raw);
  if (!rec) return null;
  const id =
    (typeof rec.id === "string" && rec.id.trim()) || idFromUri(rec.uri);
  const title = typeof rec.name === "string" ? rec.name.trim() : "";
  if (!id || !title) return null;

  const albumRec = asRecord(rec.albumOfTrack);
  const albumId = albumRec
    ? (typeof albumRec.id === "string" && albumRec.id.trim()) ||
      idFromUri(albumRec.uri)
    : "";
  const album: TrackAlbum | null =
    albumRec && typeof albumRec.name === "string" && albumRec.name.trim()
      ? {
          id: albumId ? `sp:album:${albumId}` : "",
          title: albumRec.name.trim(),
        }
      : (fallbackAlbum ?? null);

  const cover =
    pickCoverFromSources(recAt(albumRec, "coverArt")?.sources) ??
    fallbackCover ??
    null;

  const durationMs =
    Number(recAt(rec, "duration")?.totalMilliseconds) ||
    Number(recAt(rec, "trackDuration")?.totalMilliseconds) ||
    0;

  const ratingLabel = String(recAt(rec, "contentRating")?.label ?? "").toUpperCase();
  const explicit = ratingLabel === "EXPLICIT";

  return {
    id: `sp:${id}`,
    title,
    artists: mapPathfinderArtists(recAt(rec, "artists")?.items),
    album,
    durationMs,
    cover,
    explicit,
  };
}

async function pathfinderQuery(
  operationName: string,
  sha256Hash: string,
  variables: Record<string, unknown>,
): Promise<JsonRecord> {
  const session = await getSpotifyGuestSession();
  const res = await fetch("https://api-partner.spotify.com/pathfinder/v2/query", {
    method: "POST",
    headers: {
      authorization: `Bearer ${session.accessToken}`,
      ...(session.clientToken ? { "client-token": session.clientToken } : {}),
      "content-type": "application/json",
      accept: "application/json",
      "app-platform": "WebPlayer",
    },
    body: JSON.stringify({
      operationName,
      variables,
      extensions: {
        persistedQuery: { version: 1, sha256Hash },
      },
    }),
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) {
    throw new Error(`Spotify Pathfinder HTTP ${res.status}`);
  }
  return (await res.json()) as JsonRecord;
}

export async function searchSpotifyCatalog(
  query: string,
  type: "all" | "track" | "artist" | "album" | "playlist",
  limit = 15,
): Promise<SearchResult[]> {
  const spLimit = Math.min(Math.max(limit, 5), 25);
  const body = await pathfinderQuery("searchDesktop", HASH_SEARCH_DESKTOP, {
    searchTerm: query,
    offset: 0,
    limit: spLimit,
    numberOfTopResults: 5,
    includeAudiobooks: false,
    includeArtistHasConcertsField: false,
    includePreReleases: false,
    includeLocalConcertsField: false,
    includeAuthors: false,
  });

  const searchV2 = recAt(body, "data", "searchV2");
  if (!searchV2) return [];

  const results: SearchResult[] = [];

  if (type === "all" || type === "track") {
    const items = asArray(recAt(searchV2, "tracksV2")?.items);
    for (const entry of items) {
      const data = recAt(entry, "item", "data");
      const t = mapPathfinderTrack(data);
      if (t) {
        results.push({
          type: "track",
          id: t.id,
          title: t.title,
          artists: t.artists,
          album: t.album,
          durationMs: t.durationMs,
          cover: t.cover,
          explicit: t.explicit,
        });
      }
    }
  }

  if (type === "all" || type === "artist") {
    const items = asArray(recAt(searchV2, "artists")?.items);
    for (const entry of items) {
      const data = recAt(entry, "data");
      const id = idFromUri(data?.uri);
      const name = String(recAt(data, "profile")?.name ?? "").trim();
      if (!id || !name) continue;
      results.push({
        type: "artist",
        id: `sp:artist:${id}`,
        name,
        cover: pickCoverFromSources(recAt(data, "visuals", "avatarImage")?.sources),
      });
    }
  }

  if (type === "all" || type === "album") {
    const items = asArray(recAt(searchV2, "albumsV2")?.items);
    for (const entry of items) {
      const data = recAt(entry, "data");
      const id = idFromUri(data?.uri);
      const title = typeof data?.name === "string" ? data.name.trim() : "";
      if (!id || !title) continue;
      const yearRaw = Number(recAt(data, "date")?.year);
      results.push({
        type: "album",
        id: `sp:album:${id}`,
        title,
        artists: mapPathfinderArtists(recAt(data, "artists")?.items),
        year: Number.isFinite(yearRaw) && yearRaw > 0 ? yearRaw : null,
        cover: pickCoverFromSources(recAt(data, "coverArt")?.sources),
      });
    }
  }

  if (type === "all" || type === "playlist") {
    const items = asArray(recAt(searchV2, "playlists")?.items);
    for (const entry of items) {
      const data = recAt(entry, "data");
      const id = idFromUri(data?.uri);
      const title = typeof data?.name === "string" ? data.name.trim() : "";
      if (!id || !title) continue;
      const ownerName = recAt(data, "ownerV2", "data")?.name;
      const firstImg = asArray(recAt(data, "images")?.items)[0];
      results.push({
        type: "playlist",
        id: `sp:playlist:${id}`,
        title,
        author: typeof ownerName === "string" ? ownerName : null,
        cover: pickCoverFromSources(asRecord(firstImg)?.sources),
      });
    }
  }

  return results;
}

export async function getSpotifyAlbum(spotifyAlbumId: string): Promise<AlbumDetails> {
  const body = await pathfinderQuery("getAlbum", HASH_GET_ALBUM, {
    uri: `spotify:album:${spotifyAlbumId}`,
    locale: "",
    offset: 0,
    limit: 50,
  });

  const union = recAt(body, "data", "albumUnion");
  if (!union) throw new Error("Spotify album not found");

  const title = typeof union.name === "string" ? union.name : "Album";
  const cover = pickCoverFromSources(recAt(union, "coverArt")?.sources);
  const albumMeta: TrackAlbum = { id: `sp:album:${spotifyAlbumId}`, title };
  const iso = String(recAt(union, "date")?.isoString ?? "");
  const yearMatch = iso.match(/^(\d{4})/);
  const year = yearMatch ? Number(yearMatch[1]) : null;

  const tracks = asArray(recAt(union, "tracksV2")?.items)
    .map((entry) => mapPathfinderTrack(asRecord(entry)?.track, albumMeta, cover))
    .filter((t): t is Track => Boolean(t));

  const totalCount = Number(recAt(union, "tracksV2")?.totalCount);

  return {
    type: "album",
    id: `sp:album:${spotifyAlbumId}`,
    title,
    artists: mapPathfinderArtists(recAt(union, "artists")?.items),
    year,
    cover,
    trackCount: Number.isFinite(totalCount) && totalCount > 0 ? totalCount : tracks.length,
    durationMs: tracks.reduce((sum, t) => sum + (t.durationMs || 0), 0),
    tracks,
  };
}

export async function getSpotifyArtist(spotifyArtistId: string): Promise<ArtistDetails> {
  const body = await pathfinderQuery("queryArtistOverview", HASH_ARTIST_OVERVIEW, {
    uri: `spotify:artist:${spotifyArtistId}`,
    locale: "",
  });

  const union = recAt(body, "data", "artistUnion");
  if (!union) throw new Error("Spotify artist not found");

  const profile = recAt(union, "profile");
  const stats = recAt(union, "stats");
  const name = typeof profile?.name === "string" ? profile.name : "Artist";
  const cover = pickCoverFromSources(recAt(union, "visuals", "avatarImage")?.sources);
  const followers = typeof stats?.followers === "number" ? stats.followers : null;
  const monthly =
    typeof stats?.monthlyListeners === "number" && stats.monthlyListeners > 0
      ? `${stats.monthlyListeners.toLocaleString("en-US")} monthly listeners`
      : followers
        ? `${followers.toLocaleString("en-US")} followers`
        : null;

  const tracks = asArray(recAt(union, "discography", "topTracks")?.items)
    .map((entry) => mapPathfinderTrack(asRecord(entry)?.track))
    .filter((t): t is Track => Boolean(t));

  const albums: ArtistAlbum[] = [];
  for (const group of asArray(recAt(union, "discography", "albums")?.items)) {
    for (const rel of asArray(recAt(group, "releases")?.items)) {
      const r = asRecord(rel);
      const id = (typeof r?.id === "string" && r.id) || idFromUri(r?.uri);
      const title = typeof r?.name === "string" ? r.name.trim() : "";
      if (!id || !title) continue;
      const year = Number(recAt(r, "date")?.year);
      const total = Number(recAt(r, "tracks")?.totalCount);
      albums.push({
        id: `sp:album:${id}`,
        title,
        year: Number.isFinite(year) && year > 0 ? year : null,
        cover: pickCoverFromSources(recAt(r, "coverArt")?.sources),
        totalTracks: Number.isFinite(total) && total > 0 ? total : null,
        trackCount: Number.isFinite(total) && total > 0 ? total : null,
      });
    }
  }

  const singles: ArtistRelease[] = [];
  for (const group of asArray(recAt(union, "discography", "singles")?.items)) {
    for (const rel of asArray(recAt(group, "releases")?.items)) {
      const r = asRecord(rel);
      const id = (typeof r?.id === "string" && r.id) || idFromUri(r?.uri);
      const title = typeof r?.name === "string" ? r.name.trim() : "";
      if (!id || !title) continue;
      const year = Number(recAt(r, "date")?.year);
      const total = Number(recAt(r, "tracks")?.totalCount);
      singles.push({
        id: `sp:album:${id}`,
        title,
        year: Number.isFinite(year) && year > 0 ? year : null,
        cover: pickCoverFromSources(recAt(r, "coverArt")?.sources),
        totalTracks: Number.isFinite(total) && total > 0 ? total : null,
        trackCount: Number.isFinite(total) && total > 0 ? total : null,
        releaseType: "single",
      });
    }
  }

  return {
    type: "artist",
    id: `sp:artist:${spotifyArtistId}`,
    name,
    bio: typeof recAt(profile, "biography")?.text === "string"
      ? String(recAt(profile, "biography")?.text)
      : null,
    cover,
    monthlyListeners: monthly,
    followers,
    verified: Boolean(profile?.verified),
    tracks,
    albums,
    singles,
  };
}

export async function getSpotifyPlaylist(
  spotifyPlaylistId: string,
): Promise<CatalogPlaylistDetails> {
  const imported = await importSpotifyEntity(
    `https://open.spotify.com/playlist/${spotifyPlaylistId}`,
  );
  const tracks: Track[] = imported.tracks.map((t, idx) => ({
    id: `sp:pl:${spotifyPlaylistId}:${idx}`,
    title: t.title,
    artists: t.artist
      .split(",")
      .map((name) => name.trim())
      .filter(Boolean)
      .map((name) => ({ id: "unknown", name })),
    album: null,
    durationMs: t.durationMs,
    cover: null,
    explicit: false,
  }));

  return {
    type: "playlist",
    id: `sp:playlist:${spotifyPlaylistId}`,
    title: imported.title,
    author: "Spotify",
    description: null,
    year: null,
    cover: null,
    trackCount: tracks.length,
    durationMs: tracks.reduce((sum, t) => sum + (t.durationMs || 0), 0),
    tracks,
  };
}
