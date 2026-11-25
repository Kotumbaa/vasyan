import { fetchYouTubeRadioTracks, searchYouTubeSong } from "./catalog";
import type {
  DailyMixItem,
  DailyMixesResponse,
  Env,
  MyWaveRequest,
  MyWaveResponse,
  Track,
  WaveVibe,
} from "./types";

const DAILY_MIX_CACHE_TTL_MS = 4 * 60 * 60 * 1000;
const FALLBACK_POPULAR_SEEDS = ["dQw4w9WgXcQ", "kJQP7kiw5Fk", "fJ9rUzIMcZQ"];
const EMBED_MODEL = "@cf/baai/bge-small-en-v1.5";
const TEXT_MODEL = "@cf/meta/llama-3.1-8b-instruct-fast";

const NOISE_TAGS = new Set([
  "seen live",
  "favorites",
  "favourite",
  "awesome",
  "my music",
  "good",
  "love",
  "cool",
  "best",
  "spotify",
  "youtube",
  "under 2000 listeners",
]);

interface LikedTrackRow {
  track_id: string;
  track_snapshot: string;
}

interface PlaybackHistoryRow {
  track_id: string;
  track_snapshot: string;
  completion_rate: number;
  completed: number;
  skipped: number;
  loop_count: number;
}

interface ScoredSeed {
  track: Track;
  score: number;
  tags: string[];
  embedding?: number[];
}

interface SeedCluster {
  seeds: ScoredSeed[];
  centroid: number[] | null;
  topTags: string[];
}

interface LastFmTrackRef {
  artist: string;
  title: string;
}

function parseTrackSnapshot(raw: string, fallbackId: string): Track {
  try {
    const parsed = JSON.parse(raw) as Partial<Track>;
    return {
      id: parsed.id || fallbackId,
      title: parsed.title || fallbackId,
      artists: Array.isArray(parsed.artists) ? parsed.artists : [],
      album: parsed.album ?? null,
      durationMs: typeof parsed.durationMs === "number" ? parsed.durationMs : 0,
      cover: parsed.cover ?? null,
      explicit: parsed.explicit ?? null,
      audioVariant: parsed.audioVariant ?? null,
      type: parsed.type ?? "track",
      viewer: parsed.viewer ?? null,
    };
  } catch {
    return {
      id: fallbackId,
      title: fallbackId,
      artists: [],
      durationMs: 0,
      type: "track",
    };
  }
}

function formatTrackForEmbedding(track: Track, tags: string[] = []): string {
  const artists = track.artists.map((a) => a.name).filter(Boolean).join(", ");
  const album = track.album?.title ? ` | album: ${track.album.title}` : "";
  const tagPart = tags.length > 0 ? ` | tags: ${tags.join(", ")}` : "";
  return `${track.title}${artists ? ` by ${artists}` : ""}${album}${tagPart}`;
}

function normalizeVec(vec: number[]): number[] {
  let norm = 0;
  for (let i = 0; i < vec.length; i++) norm += vec[i] * vec[i];
  const denom = Math.sqrt(norm) || 1;
  return vec.map((v) => v / denom);
}

function cosineSim(a: number[], b: number[]): number {
  const len = Math.min(a.length, b.length);
  let dot = 0;
  for (let i = 0; i < len; i++) dot += a[i] * b[i];
  return dot;
}

async function embedTexts(ai: Ai | undefined, texts: string[]): Promise<number[][] | null> {
  if (!ai || texts.length === 0) return null;
  try {
    // Batch into chunks of 32 to stay well within Workers AI input limits
    const allVectors: number[][] = [];
    for (let i = 0; i < texts.length; i += 32) {
      const chunk = texts.slice(i, i + 32);
      const res = (await ai.run(EMBED_MODEL as keyof AiModels, {
        text: chunk,
      } as never)) as { data?: number[][] };
      if (!Array.isArray(res?.data) || res.data.length !== chunk.length) {
        return null;
      }
      for (const v of res.data) {
        allVectors.push(normalizeVec(v));
      }
    }
    return allVectors;
  } catch {
    return null;
  }
}

async function lastfmGet(
  method: string,
  params: Record<string, string>,
  apiKey: string,
): Promise<Record<string, unknown> | null> {
  try {
    const qs = new URLSearchParams({
      method,
      api_key: apiKey,
      format: "json",
      autocorrect: "1",
      ...params,
    });
    const res = await fetch(`https://ws.audioscrobbler.com/2.0/?${qs.toString()}`, {
      signal: AbortSignal.timeout(4500),
    });
    if (!res.ok) return null;
    return (await res.json()) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function extractTagNames(rawTags: unknown, limit = 4): string[] {
  const list = Array.isArray(rawTags) ? rawTags : [];
  const out: string[] = [];
  for (const item of list) {
    const name = String((item as { name?: unknown })?.name ?? "")
      .toLowerCase()
      .trim();
    if (!name || name.length < 2 || NOISE_TAGS.has(name)) continue;
    if (!out.includes(name)) out.push(name);
    if (out.length >= limit) break;
  }
  return out;
}

async function fetchLastFmTrackTags(
  artist: string,
  title: string,
  apiKey: string,
): Promise<string[]> {
  if (!artist || !title) return [];
  const trackData = await lastfmGet(
    "track.getTopTags",
    { artist, track: title },
    apiKey,
  );
  const trackTags = extractTagNames(
    (trackData?.toptags as { tag?: unknown } | undefined)?.tag,
  );
  if (trackTags.length > 0) return trackTags;

  const artistData = await lastfmGet("artist.getTopTags", { artist }, apiKey);
  return extractTagNames(
    (artistData?.toptags as { tag?: unknown } | undefined)?.tag,
  );
}

async function fetchLastFmSimilarTracks(
  artist: string,
  title: string,
  apiKey: string,
  limit = 6,
): Promise<LastFmTrackRef[]> {
  if (!artist || !title) return [];
  const data = await lastfmGet(
    "track.getSimilar",
    { artist, track: title, limit: String(limit) },
    apiKey,
  );
  const rawList = (data?.similartracks as { track?: unknown } | undefined)?.track;
  const out: LastFmTrackRef[] = [];
  if (Array.isArray(rawList)) {
    for (const item of rawList) {
      const rec = item as { name?: unknown; artist?: { name?: unknown } };
      const tName = String(rec?.name ?? "").trim();
      const aName = String(rec?.artist?.name ?? "").trim();
      if (tName && aName) out.push({ artist: aName, title: tName });
      if (out.length >= limit) break;
    }
  }
  if (out.length > 0) return out;

  // Fallback to similar artists' top tracks if track.getSimilar has no coverage
  const similarArtists = await lastfmGet(
    "artist.getSimilar",
    { artist, limit: "3" },
    apiKey,
  );
  const artistsArr = (
    similarArtists?.similarartists as { artist?: unknown } | undefined
  )?.artist;
  if (!Array.isArray(artistsArr)) return [];

  const topTracks = await Promise.allSettled(
    artistsArr.slice(0, 3).map((a) => {
      const aName = String((a as { name?: unknown })?.name ?? "").trim();
      return aName
        ? lastfmGet("artist.getTopTracks", { artist: aName, limit: "2" }, apiKey)
        : Promise.resolve(null);
    }),
  );
  for (const res of topTracks) {
    if (res.status !== "fulfilled" || !res.value) continue;
    const tracksArr = (
      res.value.toptracks as { track?: unknown } | undefined
    )?.track;
    if (!Array.isArray(tracksArr)) continue;
    for (const t of tracksArr) {
      const rec = t as { name?: unknown; artist?: { name?: unknown } };
      const tName = String(rec?.name ?? "").trim();
      const aName = String(rec?.artist?.name ?? "").trim();
      if (tName && aName) out.push({ artist: aName, title: tName });
    }
  }
  return out.slice(0, limit);
}

function clusterSeedsWithEmbeddings(
  seeds: ScoredSeed[],
  maxClusters: number,
): SeedCluster[] {
  const embedded = seeds.filter((s) => Array.isArray(s.embedding));
  if (embedded.length < 2) {
    return seeds.slice(0, maxClusters).map((s) => ({
      seeds: [s],
      centroid: s.embedding ?? null,
      topTags: s.tags.slice(0, 3),
    }));
  }

  const k = Math.min(maxClusters, embedded.length);
  // Farthest-point initialization for well-separated musical vibes
  const centroids: number[][] = [embedded[0].embedding!];
  while (centroids.length < k) {
    let bestIdx = 0;
    let lowestMaxSim = Infinity;
    for (let i = 0; i < embedded.length; i++) {
      let maxSim = -Infinity;
      for (const c of centroids) {
        const sim = cosineSim(embedded[i].embedding!, c);
        if (sim > maxSim) maxSim = sim;
      }
      if (maxSim < lowestMaxSim) {
        lowestMaxSim = maxSim;
        bestIdx = i;
      }
    }
    centroids.push(embedded[bestIdx].embedding!);
  }

  let assignments = new Array<number>(embedded.length).fill(0);
  for (let iter = 0; iter < 8; iter++) {
    for (let i = 0; i < embedded.length; i++) {
      let bestC = 0;
      let bestSim = -Infinity;
      for (let c = 0; c < k; c++) {
        const sim = cosineSim(embedded[i].embedding!, centroids[c]);
        if (sim > bestSim) {
          bestSim = sim;
          bestC = c;
        }
      }
      assignments[i] = bestC;
    }

    const dim = centroids[0].length;
    for (let c = 0; c < k; c++) {
      const sum = new Array<number>(dim).fill(0);
      let weightSum = 0;
      for (let i = 0; i < embedded.length; i++) {
        if (assignments[i] !== c) continue;
        const w = Math.max(0.5, embedded[i].score);
        weightSum += w;
        const vec = embedded[i].embedding!;
        for (let d = 0; d < dim; d++) sum[d] += vec[d] * w;
      }
      if (weightSum > 0) {
        centroids[c] = normalizeVec(sum);
      }
    }
  }

  const clusters: SeedCluster[] = [];
  for (let c = 0; c < k; c++) {
    const members = embedded
      .filter((_, idx) => assignments[idx] === c)
      .sort((a, b) => b.score - a.score);
    if (members.length === 0) continue;

    const tagCounts = new Map<string, number>();
    for (const m of members) {
      for (const tag of m.tags) {
        tagCounts.set(tag, (tagCounts.get(tag) ?? 0) + 1);
      }
    }
    const topTags = Array.from(tagCounts.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 3)
      .map(([tag]) => tag);

    clusters.push({
      seeds: members,
      centroid: centroids[c],
      topTags,
    });
  }

  return clusters;
}

function clusterSeedsByArtist(
  seeds: ScoredSeed[],
  maxClusters: number,
): SeedCluster[] {
  const clusters: SeedCluster[] = [];
  const usedArtists = new Set<string>();
  for (const s of seeds) {
    const artist = s.track.artists[0]?.name?.toLowerCase().trim() || s.track.id;
    if (usedArtists.has(artist)) continue;
    usedArtists.add(artist);
    clusters.push({
      seeds: [s],
      centroid: s.embedding ?? null,
      topTags: s.tags.slice(0, 3),
    });
    if (clusters.length >= maxClusters) break;
  }
  return clusters;
}

function formatTagTitle(tags: string[]): string | null {
  if (tags.length === 0) return null;
  const formatted = tags
    .slice(0, 2)
    .map((t) =>
      t
        .split(/[\s-]+/)
        .map((w) => (w ? w[0].toUpperCase() + w.slice(1) : ""))
        .join(" "),
    )
    .join(" & ");
  return formatted ? `${formatted} Mix` : null;
}

async function generateMixTitle(
  ai: Ai | undefined,
  artists: string[],
  tags: string[],
  fallbackIndex: number,
): Promise<string> {
  const tagTitle = formatTagTitle(tags);
  if (!ai || (artists.length === 0 && tags.length === 0)) {
    return tagTitle ?? `My Mix ${fallbackIndex}`;
  }
  try {
    const prompt = [
      "Create a short, catchy 2-4 word Spotify-style playlist title.",
      tags.length > 0 ? `Genres/tags: ${tags.join(", ")}.` : "",
      artists.length > 0 ? `Artists: ${artists.slice(0, 3).join(", ")}.` : "",
      "Reply ONLY with the title, no quotes, no punctuation at the end.",
    ]
      .filter(Boolean)
      .join(" ");
    const res = (await ai.run(TEXT_MODEL as keyof AiModels, {
      messages: [{ role: "user", content: prompt }],
      max_tokens: 24,
      temperature: 0.4,
    } as never)) as { response?: string };
    const clean = String(res?.response ?? "")
      .replace(/^["'\s]+|["'.\s]+$/g, "")
      .split("\n")[0]
      .trim();
    if (clean.length >= 3 && clean.length <= 36) {
      return clean;
    }
  } catch {
    // Fallback below
  }
  return tagTitle ?? `My Mix ${fallbackIndex}`;
}

export async function buildOrGetDailyMixes(
  env: Env,
  userId: string,
  forceRefresh = false,
): Promise<DailyMixesResponse> {

  if (!forceRefresh) {
    const cached = await env.DB.prepare(
      `SELECT payload, generated_at FROM daily_mix_cache WHERE user_id = ?`,
    )
      .bind(userId)
      .first<{ payload: string; generated_at: string }>();

    if (cached) {
      const ageMs = Date.now() - Date.parse(cached.generated_at);
      if (Number.isFinite(ageMs) && ageMs < DAILY_MIX_CACHE_TTL_MS) {
        try {
          const parsed = JSON.parse(cached.payload) as DailyMixesResponse;
          if (Array.isArray(parsed.items) && parsed.items.length > 0) {
            return parsed;
          }
        } catch {
          // Ignore corrupted cache
        }
      }
    }
  }

  const [likedRows, historyRows] = await Promise.all([
    env.DB.prepare(
      `SELECT track_id, track_snapshot FROM liked_tracks
       WHERE user_id = ? ORDER BY liked_at DESC LIMIT 60`,
    )
      .bind(userId)
      .all<LikedTrackRow>(),
    env.DB.prepare(
      `SELECT track_id, track_snapshot, completion_rate, completed, skipped, loop_count
       FROM playback_history
       WHERE user_id = ? ORDER BY listened_at DESC LIMIT 120`,
    )
      .bind(userId)
      .all<PlaybackHistoryRow>(),
  ]);

  const skippedIds = new Set<string>();
  const trackScores = new Map<string, { track: Track; score: number }>();

  for (const row of likedRows.results) {
    const track = parseTrackSnapshot(row.track_snapshot, row.track_id);
    if (!track.id) continue;
    const prev = trackScores.get(track.id);
    trackScores.set(track.id, {
      track,
      score: (prev?.score ?? 0) + 3.0,
    });
  }

  for (const row of historyRows.results) {
    const track = parseTrackSnapshot(row.track_snapshot, row.track_id);
    if (!track.id) continue;
    if (row.skipped === 1 && row.completion_rate < 0.25) {
      skippedIds.add(track.id);
    }
    const delta =
      (row.completed ? 2.0 : row.completion_rate * 1.5) +
      Math.min(3, row.loop_count) * 1.2 -
      (row.skipped && row.completion_rate < 0.25 ? 2.5 : 0);
    const prev = trackScores.get(track.id);
    trackScores.set(track.id, {
      track: prev?.track ?? track,
      score: (prev?.score ?? 0) + delta,
    });
  }

  let rankedSeeds: ScoredSeed[] = Array.from(trackScores.values())
    .filter(
      (entry) =>
        entry.score > 0 &&
        !skippedIds.has(entry.track.id) &&
        entry.track.title !== entry.track.id,
    )
    .sort((a, b) => b.score - a.score)
    .slice(0, 14)
    .map((entry) => ({ track: entry.track, score: entry.score, tags: [] }));

  // Fallback to global recent tracks if this user has no history yet
  if (rankedSeeds.length === 0) {
    const globalRows = await env.DB.prepare(
      `SELECT track_id, track_snapshot FROM playback_history
       ORDER BY listened_at DESC LIMIT 20`,
    ).all<{ track_id: string; track_snapshot: string }>();
    const seenGlobal = new Set<string>();
    for (const row of globalRows.results) {
      const t = parseTrackSnapshot(row.track_snapshot, row.track_id);
      if (seenGlobal.has(t.id) || t.title === t.id) continue;
      seenGlobal.add(t.id);
      rankedSeeds.push({ track: t, score: 1.0, tags: [] });
      if (rankedSeeds.length >= 6) break;
    }
  }

  if (rankedSeeds.length === 0) {
    rankedSeeds = FALLBACK_POPULAR_SEEDS.map((id) => ({
      track: { id, title: id, artists: [], durationMs: 0, type: "track" },
      score: 1.0,
      tags: [],
    }));
  }

  const lastfmKey = env.LASTFM_API_KEY?.trim() || "";

  // Enrich top seeds with Last.fm genre/vibe tags when LASTFM_API_KEY is configured
  if (lastfmKey) {
    const tagResults = await Promise.allSettled(
      rankedSeeds.map((s) =>
        fetchLastFmTrackTags(
          s.track.artists[0]?.name ?? "",
          s.track.title,
          lastfmKey,
        ),
      ),
    );
    tagResults.forEach((res, idx) => {
      if (res.status === "fulfilled") {
        rankedSeeds[idx].tags = res.value;
      }
    });
  }

  // Embed user's seeds with Cloudflare Workers AI to cluster by musical vibe
  const seedVectors = await embedTexts(
    env.AI,
    rankedSeeds.map((s) => formatTrackForEmbedding(s.track, s.tags)),
  );
  if (seedVectors) {
    rankedSeeds.forEach((s, idx) => {
      s.embedding = seedVectors[idx];
    });
  }

  const clusters = seedVectors
    ? clusterSeedsWithEmbeddings(rankedSeeds, 4)
    : clusterSeedsByArtist(rankedSeeds, 4);

  // Expand each cluster using YouTube Music Radio + Last.fm Similar Tracks
  const expandedClusters = await Promise.all(
    clusters.map(async (cluster) => {
      const leadSeed = cluster.seeds[0];
      const radioPromise = fetchYouTubeRadioTracks(leadSeed.track.id, 20);
      const secondaryRadioPromise =
        cluster.seeds.length > 1 &&
        cluster.seeds[1].track.id !== leadSeed.track.id
          ? fetchYouTubeRadioTracks(cluster.seeds[1].track.id, 10)
          : Promise.resolve([] as Track[]);

      const lastfmTracksPromise = lastfmKey
        ? fetchLastFmSimilarTracks(
            leadSeed.track.artists[0]?.name ?? "",
            leadSeed.track.title,
            lastfmKey,
            4,
          ).then((refs) =>
            Promise.allSettled(
              refs.map((r) => searchYouTubeSong(`${r.artist} ${r.title}`)),
            ),
          )
        : Promise.resolve([] as PromiseSettledResult<Track | null>[]);

      const [primaryRadio, secondaryRadio, lastfmResolved] = await Promise.all([
        radioPromise,
        secondaryRadioPromise,
        lastfmTracksPromise,
      ]);

      const candidateMap = new Map<
        string,
        { track: Track; sourceBoost: number }
      >();

      for (const s of cluster.seeds.slice(0, 2)) {
        if (s.track.title !== s.track.id && !skippedIds.has(s.track.id)) {
          candidateMap.set(s.track.id, { track: s.track, sourceBoost: 0.45 });
        }
      }
      for (const res of lastfmResolved) {
        if (res.status !== "fulfilled" || !res.value) continue;
        const t = res.value;
        if (skippedIds.has(t.id) || candidateMap.has(t.id)) continue;
        candidateMap.set(t.id, { track: t, sourceBoost: 0.35 });
      }
      for (const t of primaryRadio) {
        if (skippedIds.has(t.id) || candidateMap.has(t.id)) continue;
        candidateMap.set(t.id, { track: t, sourceBoost: 0.25 });
      }
      for (const t of secondaryRadio) {
        if (skippedIds.has(t.id) || candidateMap.has(t.id)) continue;
        candidateMap.set(t.id, { track: t, sourceBoost: 0.15 });
      }

      return {
        cluster,
        candidates: Array.from(candidateMap.values()),
      };
    }),
  );

  // Rerank candidates per cluster by Workers AI cosine similarity + artist diversity
  const items: DailyMixItem[] = [];
  for (let idx = 0; idx < expandedClusters.length; idx++) {
    const { cluster, candidates } = expandedClusters[idx];
    if (candidates.length < 3) continue;

    let rankedCandidates = candidates.map((c) => ({
      track: c.track,
      score: c.sourceBoost,
    }));

    if (cluster.centroid && env.AI) {
      const candVecs = await embedTexts(
        env.AI,
        candidates.slice(0, 24).map((c) => formatTrackForEmbedding(c.track)),
      );
      if (candVecs) {
        rankedCandidates = candidates.slice(0, 24).map((c, i) => ({
          track: c.track,
          score: 0.65 * cosineSim(candVecs[i], cluster.centroid!) + c.sourceBoost,
        }));
      }
    }

    rankedCandidates.sort((a, b) => b.score - a.score);

    // Enforce artist diversity so one artist does not flood the mix
    const chosen: Track[] = [];
    const artistFreq = new Map<string, number>();
    for (const c of rankedCandidates) {
      const primaryArtist =
        c.track.artists[0]?.name?.toLowerCase().trim() || "unknown";
      const count = artistFreq.get(primaryArtist) ?? 0;
      if (count >= 3) continue;
      artistFreq.set(primaryArtist, count + 1);
      chosen.push(c.track);
      if (chosen.length >= 18) break;
    }

    if (chosen.length < 3) {
      for (const c of rankedCandidates) {
        if (!chosen.some((t) => t.id === c.track.id)) {
          chosen.push(c.track);
          if (chosen.length >= 18) break;
        }
      }
    }

    if (chosen.length < 3) continue;

    const artistCounts = new Map<string, number>();
    for (const t of chosen) {
      for (const a of t.artists) {
        if (!a.name) continue;
        artistCounts.set(a.name, (artistCounts.get(a.name) ?? 0) + 1);
      }
    }
    const clusterArtists = Array.from(artistCounts.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, 4)
      .map(([name]) => name);

    const title = await generateMixTitle(
      env.AI,
      clusterArtists,
      cluster.topTags,
      items.length + 1,
    );
    const leadArtists = clusterArtists.slice(0, 3).join(", ");
    const tagSummary =
      cluster.topTags.length > 0 ? ` • ${cluster.topTags.slice(0, 2).join(", ")}` : "";
    const description = leadArtists
      ? `With ${leadArtists} and more${tagSummary}`
      : "Personalized AI mix";
    const leadCover = chosen.find((t) => t.cover?.url)?.cover ?? null;

    items.push({
      id: `daily-mix-${idx + 1}`,
      title,
      description,
      clusterArtists,
      cover: leadCover,
      trackCount: chosen.length,
      tracks: chosen,
    });
  }

  const response: DailyMixesResponse = {
    items,
    generatedAt: new Date().toISOString(),
  };

  if (items.length > 0) {
    await env.DB.prepare(
      `INSERT INTO daily_mix_cache (user_id, payload, generated_at)
       VALUES (?, ?, ?)
       ON CONFLICT(user_id) DO UPDATE SET
         payload = excluded.payload,
         generated_at = excluded.generated_at`,
    )
      .bind(userId, JSON.stringify(response), response.generatedAt)
      .run();
  }

  return response;
}

const VALID_WAVE_VIBES = new Set<WaveVibe>([
  "all",
  "favorite",
  "discover",
  "energy",
  "calm",
]);

function normalizeWaveVibe(raw?: string | null): WaveVibe {
  const v = (raw ?? "all").trim().toLowerCase() as WaveVibe;
  return VALID_WAVE_VIBES.has(v) ? v : "all";
}

function shuffleInPlace<T>(arr: T[]): T[] {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
}

function spreadByArtist(tracks: Track[], limit: number): Track[] {
  const remaining = [...tracks];
  const out: Track[] = [];
  let lastArtist = "";

  while (remaining.length > 0 && out.length < limit) {
    const idx = remaining.findIndex((t) => {
      const a = t.artists[0]?.name?.toLowerCase().trim() || "";
      return !a || a !== lastArtist;
    });
    const pickIdx = idx >= 0 ? idx : 0;
    const [picked] = remaining.splice(pickIdx, 1);
    out.push(picked);
    lastArtist = picked.artists[0]?.name?.toLowerCase().trim() || "";
  }
  return out;
}

export async function buildMyWaveBatch(
  env: Env,
  userId: string,
  req: MyWaveRequest = {},
): Promise<MyWaveResponse> {
  const vibe = normalizeWaveVibe(req.vibe);
  const limit = Math.max(5, Math.min(30, Number(req.limit) || 20));
  const excludeSet = new Set(
    (Array.isArray(req.excludeIds) ? req.excludeIds : [])
      .map((id) => String(id).trim())
      .filter(Boolean),
  );

  const [likedRes, historyRes] = await Promise.all([
    env.DB.prepare(
      `SELECT track_id, track_snapshot
       FROM liked_tracks
       WHERE user_id = ?
       ORDER BY liked_at DESC
       LIMIT 60`,
    )
      .bind(userId)
      .all<LikedTrackRow>(),
    env.DB.prepare(
      `SELECT track_id, track_snapshot, completion_rate, completed, skipped, loop_count
       FROM playback_history
       WHERE user_id = ?
       ORDER BY listened_at DESC
       LIMIT 100`,
    )
      .bind(userId)
      .all<PlaybackHistoryRow>(),
  ]);

  const likedRows = likedRes.results ?? [];
  const historyRows = historyRes.results ?? [];
  const likedIds = new Set<string>();
  const knownIds = new Set<string>();
  const skippedIds = new Set<string>();
  const seedMap = new Map<string, ScoredSeed>();

  for (const row of likedRows) {
    likedIds.add(row.track_id);
    knownIds.add(row.track_id);
    const track = parseTrackSnapshot(row.track_snapshot, row.track_id);
    if (!track.id) continue;
    seedMap.set(track.id, { track, score: 1.8, tags: [] });
  }

  for (const row of historyRows) {
    knownIds.add(row.track_id);
    if (row.skipped === 1 && !likedIds.has(row.track_id)) {
      skippedIds.add(row.track_id);
      continue;
    }
    const track = parseTrackSnapshot(row.track_snapshot, row.track_id);
    if (!track.id) continue;
    const delta =
      (row.completed ? 0.65 : (Number(row.completion_rate) || 0) * 0.45) +
      Math.min(Number(row.loop_count) || 0, 3) * 0.35;
    const existing = seedMap.get(track.id);
    if (existing) {
      existing.score += delta;
    } else {
      seedMap.set(track.id, { track, score: 0.6 + delta, tags: [] });
    }
  }

  // Pick randomized weighted seeds from top personal pool
  let seedPool = Array.from(seedMap.values())
    .filter((s) => s.track.id && !s.track.id.startsWith("sp:") && !s.track.id.startsWith("sc:"))
    .sort((a, b) => b.score - a.score);

  if (vibe === "favorite") {
    const likedSeeds = seedPool.filter((s) => likedIds.has(s.track.id));
    if (likedSeeds.length > 0) seedPool = likedSeeds;
  }

  const topSeeds = shuffleInPlace(seedPool.slice(0, 18)).slice(0, 3);
  const seedTrackIds = topSeeds.map((s) => s.track.id);
  if (seedTrackIds.length === 0) {
    seedTrackIds.push(...shuffleInPlace([...FALLBACK_POPULAR_SEEDS]).slice(0, 2));
  }

  const vibeQueries: Record<WaveVibe, string[]> = {
    all: [],
    favorite: [],
    discover: ["indie discovery mix", "fresh alternative hits"],
    energy: ["upbeat electronic dance hits", "energetic workout rock"],
    calm: ["chill lofi ambient", "calm acoustic indie"],
  };

  const radioPromises = seedTrackIds.map((id) => fetchYouTubeRadioTracks(id, 22));
  const vibeSearchQueries = vibeQueries[vibe];
  const vibeSearchPromises = vibeSearchQueries.map((q) => searchYouTubeSong(q));

  const lastfmKey = env.LASTFM_API_KEY?.trim() || "";
  const similarPromises =
    lastfmKey && topSeeds.length > 0
      ? topSeeds.slice(0, 2).map((s) => {
          const artist = s.track.artists[0]?.name || "";
          return artist
            ? fetchLastFmSimilarTracks(artist, s.track.title, lastfmKey, 5)
            : Promise.resolve([] as LastFmTrackRef[]);
        })
      : [];

  const [radioResults, vibeSeedTracks, similarResults] = await Promise.all([
    Promise.all(radioPromises),
    Promise.all(vibeSearchPromises),
    Promise.all(similarPromises),
  ]);

  // If mood search returned a seed track, also fetch a quick radio batch from it
  const validVibeSeeds = vibeSeedTracks.filter((t): t is Track => Boolean(t?.id));
  const extraVibeRadios =
    validVibeSeeds.length > 0
      ? await Promise.all(
          validVibeSeeds.slice(0, 2).map((t) => fetchYouTubeRadioTracks(t.id, 18)),
        )
      : [];

  // Resolve a few Last.fm similar tracks into playable YouTube tracks
  const similarRefs = shuffleInPlace(similarResults.flat()).slice(0, 6);
  const resolvedSimilar =
    similarRefs.length > 0
      ? (
          await Promise.all(
            similarRefs.map((ref) => searchYouTubeSong(`${ref.artist} ${ref.title}`)),
          )
        ).filter((t): t is Track => Boolean(t?.id))
      : [];

  const candidateMap = new Map<string, Track>();
  const addCandidate = (t: Track) => {
    if (!t?.id || excludeSet.has(t.id) || skippedIds.has(t.id)) return;
    if (vibe === "discover" && knownIds.has(t.id)) return;
    if (!candidateMap.has(t.id)) {
      candidateMap.set(t.id, {
        ...t,
        viewer: { liked: likedIds.has(t.id) },
      });
    }
  };

  // Prioritize mood-specific radio first when energy/calm is chosen
  if (vibe === "energy" || vibe === "calm") {
    for (const list of extraVibeRadios) list.forEach(addCandidate);
  }
  for (const t of resolvedSimilar) addCandidate(t);
  for (const list of radioResults) list.forEach(addCandidate);
  if (vibe !== "energy" && vibe !== "calm") {
    for (const list of extraVibeRadios) list.forEach(addCandidate);
  }

  // In "favorite" mode, blend in some non-excluded liked tracks as anchors
  if (vibe === "favorite") {
    const likedPool = shuffleInPlace(
      likedRows
        .map((r) => parseTrackSnapshot(r.track_snapshot, r.track_id))
        .filter((t) => t.id && !excludeSet.has(t.id)),
    ).slice(0, 8);
    for (const t of likedPool) addCandidate(t);
  }

  const shuffled = shuffleInPlace(Array.from(candidateMap.values()));
  const items = spreadByArtist(shuffled, limit);

  const seedArtists = Array.from(
    new Set(
      topSeeds
        .map((s) => s.track.artists[0]?.name?.trim())
        .filter((n): n is string => Boolean(n)),
    ),
  ).slice(0, 2);

  const seedLabel =
    vibe === "energy"
      ? "Бодрая волна под твой ритм"
      : vibe === "calm"
        ? "Спокойный поток для отдыха и фокуса"
        : vibe === "discover"
          ? "Новые треки и свежие открытия"
          : vibe === "favorite"
            ? "Твои любимые треки и близкие по духу"
            : seedArtists.length > 0
              ? `На основе ${seedArtists.join(", ")} и твоего вкуса`
              : "Бесконечный поток под твоё настроение";

  return {
    items,
    vibe,
    seedLabel,
    generatedAt: new Date().toISOString(),
  };
}
