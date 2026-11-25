type JsonRecord = Record<string, unknown>;

export interface ImportedSpotifyTrack {
  title: string;
  artist: string;
  durationMs: number;
}

export interface ImportedSpotifyResult {
  title: string;
  tracks: ImportedSpotifyTrack[];
}

interface SpotifySession {
  accessToken: string;
  clientToken: string;
  expiresAt: number;
}

const SPOTIFY_CLIENT_VERSION = "1.3.2.216.g7f3fbea1";
const SPOTIFY_PATHFINDER_HASH =
  "86dde7b9d9356e2369414647cf6950cfed96e778e129cfdfc99aea6c1613b3b0";
const WEB_PLAYER_SCRIPT =
  /src="(https:\/\/open\.spotifycdn\.com\/cdn\/build\/(?:mobile-)?web-player\/(?:mobile-)?web-player\.[a-f0-9]+\.js)"/;
const SECRET_ENTRY = /\{secret:(['"])((?:\\.|(?!\1)[^\\])*)\1,version:(\d+)\}/g;
const PAGE_LIMIT = 100;
const MAX_PAGES = 20;

let cachedSession: SpotifySession | null = null;

function asRecord(v: unknown): JsonRecord | undefined {
  return v && typeof v === "object" && !Array.isArray(v) ? (v as JsonRecord) : undefined;
}

function asArray(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

function recordAt(v: unknown, ...keys: string[]): JsonRecord | undefined {
  let cur: unknown = v;
  for (const k of keys) {
    cur = asRecord(cur)?.[k];
  }
  return asRecord(cur);
}

function findFirst(node: unknown, key: string): JsonRecord | undefined {
  if (!node || typeof node !== "object") return undefined;
  if (Array.isArray(node)) {
    for (const item of node) {
      const hit = findFirst(item, key);
      if (hit) return hit;
    }
    return undefined;
  }
  const rec = node as JsonRecord;
  const direct = asRecord(rec[key]);
  if (direct) return direct;
  for (const val of Object.values(rec)) {
    const hit = findFirst(val, key);
    if (hit) return hit;
  }
  return undefined;
}

function spotifySecretBytes(secret: string): Uint8Array<ArrayBuffer> {
  const transformed = secret
    .split("")
    .map((ch, idx) => ch.charCodeAt(0) ^ ((idx % 33) + 9));
  const encoded = new TextEncoder().encode(transformed.join(""));
  const bytes = new Uint8Array(new ArrayBuffer(encoded.length));
  bytes.set(encoded);
  return bytes;
}

async function generateTotp(
  secret: Uint8Array<ArrayBuffer>,
  timeMs: number,
): Promise<string> {
  const buffer = new ArrayBuffer(8);
  new DataView(buffer).setBigInt64(0, BigInt(Math.floor(timeMs / 1000 / 30)));
  const key = await crypto.subtle.importKey(
    "raw",
    secret,
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const hmac = new Uint8Array(await crypto.subtle.sign("HMAC", key, buffer));
  const offset = hmac[hmac.length - 1] & 0xf;
  const code =
    ((hmac[offset] & 0x7f) << 24) |
    ((hmac[offset + 1] & 0xff) << 16) |
    ((hmac[offset + 2] & 0xff) << 8) |
    (hmac[offset + 3] & 0xff);
  return (code % 1_000_000).toString().padStart(6, "0");
}

export async function getSpotifyGuestSession(): Promise<SpotifySession> {
  if (cachedSession && Date.now() < cachedSession.expiresAt - 60_000) {
    return cachedSession;
  }

  const pageRes = await fetch("https://open.spotify.com/", {
    headers: {
      "user-agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    },
    signal: AbortSignal.timeout(8000),
  });
  if (!pageRes.ok) throw new Error(`Spotify home HTTP ${pageRes.status}`);
  const html = await pageRes.text();
  const scriptUrl = html.match(WEB_PLAYER_SCRIPT)?.[1];
  if (!scriptUrl) throw new Error("Spotify web player script URL not found");

  const [jsRes, timeRes] = await Promise.all([
    fetch(scriptUrl, { signal: AbortSignal.timeout(10_000) }),
    fetch("https://open.spotify.com/api/server-time", {
      signal: AbortSignal.timeout(6000),
    }).catch(() => null),
  ]);
  if (!jsRes.ok) throw new Error(`Spotify bundle HTTP ${jsRes.status}`);
  const js = await jsRes.text();

  let newest: { secret: string; version: number } | undefined;
  for (const m of js.matchAll(SECRET_ENTRY)) {
    const candidate = {
      secret: m[2].replace(/\\(.)/g, "$1"),
      version: Number(m[3]),
    };
    if (!newest || candidate.version > newest.version) newest = candidate;
  }
  if (!newest) throw new Error("Spotify TOTP secret not found");

  let serverTimeSec: number | null = null;
  if (timeRes?.ok) {
    const tJson = asRecord(await timeRes.json().catch(() => null));
    const num = Number(tJson?.serverTime);
    if (!Number.isNaN(num) && num > 0) serverTimeSec = num;
  }

  const secretBytes = spotifySecretBytes(newest.secret);
  const totp = await generateTotp(secretBytes, Date.now());
  const totpServer = serverTimeSec
    ? await generateTotp(secretBytes, serverTimeSec * 1000)
    : "unavailable";

  const tokenParams = new URLSearchParams({
    reason: "init",
    productType: "web-player",
    totp,
    totpServer,
    totpVer: String(newest.version),
  });
  const tokenRes = await fetch(
    `https://open.spotify.com/api/token?${tokenParams.toString()}`,
    {
      headers: { accept: "application/json" },
      signal: AbortSignal.timeout(8000),
    },
  );
  if (!tokenRes.ok) throw new Error(`Spotify token HTTP ${tokenRes.status}`);
  const tokenData = asRecord(await tokenRes.json());
  const accessToken =
    typeof tokenData?.accessToken === "string" ? tokenData.accessToken : "";
  if (!accessToken) throw new Error("Missing Spotify accessToken");
  const expiresAt =
    typeof tokenData?.accessTokenExpirationTimestampMs === "number"
      ? tokenData.accessTokenExpirationTimestampMs
      : Date.now() + 3_500_000;

  let clientToken = "";
  try {
    const ctRes = await fetch("https://clienttoken.spotify.com/v1/clienttoken", {
      method: "POST",
      headers: {
        accept: "application/json",
        "content-type": "application/json",
      },
      body: JSON.stringify({
        client_data: {
          client_version: SPOTIFY_CLIENT_VERSION,
          client_id: "d8a5ed958d274c2e8ee717e6a4b0971d",
          js_sdk_data: {
            device_brand: "Apple",
            device_model: "unknown",
            os: "macOS",
            os_version: "unknown",
            device_id: "d9b7ebf9-813c-4c6e-8d82-841f71a0670d",
            device_type: "computer",
            platform_identifier: "web-player",
          },
        },
      }),
      signal: AbortSignal.timeout(6000),
    });
    if (ctRes.ok) {
      const ctData = await ctRes.json();
      const granted = recordAt(ctData, "granted_token")?.token;
      if (typeof granted === "string") clientToken = granted;
    }
  } catch {
    // Proceed without client token
  }

  cachedSession = { accessToken, clientToken, expiresAt };
  return cachedSession;
}

async function fetchPlaylistViaPathfinder(
  playlistId: string,
): Promise<ImportedSpotifyTrack[]> {
  let session = await getSpotifyGuestSession();
  const tracks: ImportedSpotifyTrack[] = [];
  let offset = 0;
  let total: number | undefined;

  for (let page = 0; page < MAX_PAGES; page++) {
    const params = new URLSearchParams({
      operationName: "fetchPlaylistContents",
      variables: JSON.stringify({
        uri: `spotify:playlist:${playlistId}`,
        offset,
        limit: PAGE_LIMIT,
      }),
      extensions: JSON.stringify({
        persistedQuery: { version: 1, sha256Hash: SPOTIFY_PATHFINDER_HASH },
      }),
    });
    const url = `https://api-partner.spotify.com/pathfinder/v1/query?${params.toString()}`;

    let res = await fetch(url, {
      headers: {
        authorization: `Bearer ${session.accessToken}`,
        ...(session.clientToken ? { "client-token": session.clientToken } : {}),
        accept: "application/json",
        "app-platform": "WebPlayer",
        "spotify-app-version": SPOTIFY_CLIENT_VERSION,
      },
      signal: AbortSignal.timeout(10_000),
    });

    if (res.status === 401 || res.status === 403) {
      cachedSession = null;
      session = await getSpotifyGuestSession();
      res = await fetch(url, {
        headers: {
          authorization: `Bearer ${session.accessToken}`,
          ...(session.clientToken ? { "client-token": session.clientToken } : {}),
          accept: "application/json",
          "app-platform": "WebPlayer",
          "spotify-app-version": SPOTIFY_CLIENT_VERSION,
        },
        signal: AbortSignal.timeout(10_000),
      });
    }

    if (!res.ok) break;
    const body = await res.json();
    const content = recordAt(body, "data", "playlistV2", "content");
    if (!content) break;

    if (typeof content.totalCount === "number") {
      total = content.totalCount;
    }
    const items = asArray(content.items);
    if (items.length === 0) break;

    for (const raw of items) {
      const data = recordAt(raw, "itemV2", "data");
      if (!data || data.__typename !== "Track") continue;
      const title = typeof data.name === "string" ? data.name.trim() : "";
      if (!title) continue;
      const artists = asArray(recordAt(data, "artists")?.items)
        .map((a) => {
          const rec = asRecord(a);
          const pName = recordAt(rec, "profile")?.name;
          return typeof pName === "string"
            ? pName.trim()
            : typeof rec?.name === "string"
              ? rec.name.trim()
              : "";
        })
        .filter(Boolean);
      const dur = recordAt(data, "trackDuration")?.totalMilliseconds;
      tracks.push({
        title,
        artist: artists.join(", "),
        durationMs: typeof dur === "number" ? dur : 0,
      });
    }

    offset += items.length;
    if (total !== undefined && offset >= total) break;
    if (items.length < PAGE_LIMIT) break;
  }

  return tracks;
}

async function fetchViaEmbed(
  entityType: string,
  entityId: string,
): Promise<ImportedSpotifyResult> {
  const embedUrl = `https://open.spotify.com/embed/${entityType}/${encodeURIComponent(entityId)}`;
  const res = await fetch(embedUrl, {
    headers: {
      "user-agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
      "accept-language": "en-US,en;q=0.9",
    },
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) throw new Error(`Spotify returned HTTP ${res.status}`);
  const html = await res.text();
  const scriptMatch =
    html.match(/<script id="__NEXT_DATA__"[^>]*>([\s\S]*?)<\/script>/) ??
    html.match(/<script id="(?:initial-state|session)"[^>]*>([\s\S]*?)<\/script>/);
  if (!scriptMatch?.[1]) {
    throw new Error("Could not parse Spotify embed data.");
  }

  const parsed = JSON.parse(scriptMatch[1]) as unknown;
  const entity =
    recordAt(parsed, "props", "pageProps", "state", "data", "entity") ??
    findFirst(parsed, "entity");
  const entityRec = asRecord(entity);
  if (!entityRec) throw new Error("Spotify entity not found.");

  const title =
    (typeof entityRec.title === "string" && entityRec.title.trim()) ||
    (typeof entityRec.name === "string" && entityRec.name.trim()) ||
    "Spotify Import";

  const tracks: ImportedSpotifyTrack[] = [];
  for (const rawItem of asArray(entityRec.trackList)) {
    const item = asRecord(rawItem);
    const tTitle = typeof item?.title === "string" ? item.title.trim() : "";
    if (!tTitle) continue;
    const tArtist =
      typeof item?.subtitle === "string"
        ? item.subtitle.replace(/\u00A0/g, " ").trim()
        : "";
    const durationMs = typeof item?.duration === "number" ? item.duration : 0;
    tracks.push({ title: tTitle, artist: tArtist, durationMs });
  }

  if (tracks.length === 0 && entityType === "track" && title) {
    const tArtist =
      typeof entityRec.subtitle === "string"
        ? entityRec.subtitle.replace(/\u00A0/g, " ").trim()
        : "";
    const durationMs = typeof entityRec.duration === "number" ? entityRec.duration : 0;
    tracks.push({ title, artist: tArtist, durationMs });
  }

  return { title, tracks };
}

export async function importSpotifyEntity(
  rawUrl: string,
): Promise<ImportedSpotifyResult> {
  let targetUrl = rawUrl.trim();
  let parsedUrl: URL;
  try {
    parsedUrl = new URL(targetUrl);
  } catch {
    throw new Error("Unsupported Spotify URL.");
  }

  const host = parsedUrl.hostname.toLowerCase();
  const isSpotifyShortLink =
    (parsedUrl.protocol === "https:" || parsedUrl.protocol === "http:") &&
    (host === "spotify.link" || host.endsWith(".spotify.link"));

  if (isSpotifyShortLink) {
    try {
      const redirected = await fetch(parsedUrl.toString(), {
        redirect: "follow",
        signal: AbortSignal.timeout(8000),
      });
      if (redirected.url) targetUrl = redirected.url;
    } catch {
      // Fall through
    }
  }

  const match = targetUrl.match(
    /open\.spotify\.com\/(?:intl-[a-z]{2}\/)?(?:embed\/)?(playlist|album|track)\/([A-Za-z0-9]+)/i,
  );
  if (!match) {
    throw new Error("Unsupported Spotify URL.");
  }

  const entityType = match[1].toLowerCase();
  const entityId = match[2];

  if (entityType === "playlist") {
    const [pathfinderTracks, embedResult] = await Promise.all([
      fetchPlaylistViaPathfinder(entityId).catch(() => [] as ImportedSpotifyTrack[]),
      fetchViaEmbed(entityType, entityId).catch(() => ({
        title: "Spotify Playlist",
        tracks: [] as ImportedSpotifyTrack[],
      })),
    ]);
    if (pathfinderTracks.length > embedResult.tracks.length) {
      return {
        title: embedResult.title || "Spotify Playlist",
        tracks: pathfinderTracks,
      };
    }
    return embedResult;
  }

  return fetchViaEmbed(entityType, entityId);
}
