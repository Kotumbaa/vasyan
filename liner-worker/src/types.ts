export interface Env {
  DB: D1Database;
  AI?: Ai;
  LASTFM_API_KEY?: string;
  JWT_SECRET?: string;
  EDGE_KEY?: string;
  ENVIRONMENT?: string;
}

// ---- Core Media & Track Types ----

export interface Cover {
  url: string;
  width: number;
  height: number;
  fallbackUrl?: string | null;
}

export interface TrackArtist {
  id: string;
  name: string;
}

export interface TrackAlbum {
  id: string;
  title: string;
}

export interface TrackViewer {
  liked: boolean;
}

export interface Track {
  id: string;
  title: string;
  artists: TrackArtist[];
  album?: TrackAlbum | null;
  durationMs: number;
  cover?: Cover | null;
  explicit?: boolean | null;
  audioVariant?: string | null;
  type?: string | null;
  viewer?: TrackViewer | null;
}

export interface TrackDetails extends Track {
  description?: string | null;
  releaseDate?: string | null;
  relatedTracks?: Track[];
}

// ---- Auth & User Types ----

export interface AuthUser {
  id: string;
  email?: string | null;
  username?: string | null;
  displayName?: string | null;
  avatarUrl?: string | null;
  bio?: string | null;
  isPublic?: boolean | null;
  showActivity?: boolean | null;
  showHistory?: boolean | null;
  showLikedTracks?: boolean | null;
  participateLeaderboards?: boolean | null;
}

export interface AuthSession {
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresAt: string;
  user: AuthUser;
  expiresAt?: number | null;
}

export interface UsernameAvailability {
  username: string;
  valid: boolean;
  available: boolean;
}

export interface TurnstileConfig {
  mode: string;
  siteKey?: string | null;
  edge?: boolean | null;
}

export interface PowChallenge {
  mode: string;
  algorithm: string;
  token: string;
  salt: string;
  difficulty: number;
  memoryKiB: number;
  iterations: number;
  parallelism: number;
  hashLength: number;
  expiresAt: string;
  turnstile?: TurnstileConfig | null;
}

export interface EdgeKeyResponse {
  key: string;
  rotatesAt: string;
}

export interface JwtPayload {
  sub: string;
  email?: string;
  username?: string;
  iat: number;
  exp: number;
  type: "access" | "refresh";
}

// ---- Library & Playlists ----

export interface PlaylistOwner {
  id: string;
  username?: string | null;
  displayName?: string | null;
  avatarUrl?: string | null;
}

export interface UserPlaylist {
  id: string;
  title: string;
  description?: string | null;
  isPublic: boolean;
  createdAt: string;
  updatedAt: string;
  revision: number;
  trackCount?: number | null;
  coverUrls?: string[] | null;
  ownerId?: string | null;
  owner?: PlaylistOwner | null;
}

export interface UserPlaylistItem {
  id: string;
  track: Track;
  position: number;
  addedAt: string;
}

export interface UserPlaylistDetails {
  partial: boolean;
  trackCount: number;
  durationMs: number;
  playlist: UserPlaylist;
  items: UserPlaylistItem[];
  etag?: string | null;
}

export interface LikedTrackItem {
  track: Track;
  likedAt: string;
}

export interface LikedTracksPage {
  items: LikedTrackItem[];
  nextCursor?: string | null;
}

// ---- Playback, History, Explore & Recommendations ----

export interface PlaybackContext {
  type: string;
  id?: string | null;
}

export interface RecordPlaybackEventRequest {
  playbackSessionId: string;
  listeningSessionId: string;
  trackId: string;
  trackSnapshot?: Track | null;
  playedDurationMs: number;
  trackDurationMs?: number | null;
  completionRate: number;
  completed?: boolean | null;
  skipped?: boolean | null;
  loopCount?: number | null;
  hasSeekBackward?: boolean | null;
  context?: PlaybackContext | null;
}

export interface PlaybackHistoryItem {
  id: string;
  playbackSessionId: string;
  listeningSessionId: string;
  track: Track;
  playedDurationMs: number;
  completionRate: number;
  completed: boolean;
  skipped: boolean;
  loopCount: number;
  hasSeekBackward: boolean;
  context?: PlaybackContext | null;
  listenedAt: string;
}

export interface PlaybackHistoryResponse {
  items: PlaybackHistoryItem[];
  nextCursor?: string | null;
}

export interface PopularTrackItem {
  rank: number;
  track: Track;
  playCount: number;
  uniqueListeners: number;
  score: number;
  viewer?: TrackViewer | null;
}

export interface PopularTracksResponse {
  window: string;
  generatedAt: string;
  items: PopularTrackItem[];
}

export interface DailyMixItem {
  id: string;
  title: string;
  description: string;
  clusterArtists: string[];
  cover?: Cover | null;
  trackCount: number;
  tracks: Track[];
}

export interface DailyMixesResponse {
  items: DailyMixItem[];
  generatedAt: string;
}

export type WaveVibe = "all" | "favorite" | "discover" | "energy" | "calm";

export interface MyWaveRequest {
  vibe?: WaveVibe;
  excludeIds?: string[];
  limit?: number;
}

export interface MyWaveResponse {
  items: Track[];
  vibe: WaveVibe;
  seedLabel: string;
  generatedAt: string;
}

// ---- Catalog & Lyrics Contracts ----

export type SearchResult =
  | {
      type: "track";
      id: string;
      title: string;
      artists: TrackArtist[];
      album?: TrackAlbum | null;
      durationMs: number;
      cover?: Cover | null;
      explicit?: boolean | null;
      audioVariant?: string | null;
      viewer?: TrackViewer | null;
    }
  | {
      type: "artist";
      id: string;
      name: string;
      cover?: Cover | null;
    }
  | {
      type: "album";
      id: string;
      title: string;
      artists: TrackArtist[];
      year?: number | null;
      cover?: Cover | null;
    }
  | {
      type: "playlist";
      id: string;
      title: string;
      author?: string | null;
      cover?: Cover | null;
    };

export interface SearchResponse {
  items: SearchResult[];
}

export interface ArtistAlbum {
  id: string;
  title: string;
  year?: number | null;
  cover?: Cover | null;
  explicit?: boolean | null;
  totalTracks?: number | null;
  trackCount?: number | null;
}

export interface ArtistRelease {
  id: string;
  title: string;
  year?: number | null;
  cover?: Cover | null;
  explicit?: boolean | null;
  totalTracks?: number | null;
  trackCount?: number | null;
  releaseType: string;
}

export interface ArtistDetails {
  type: "artist";
  id: string;
  name: string;
  bio?: string | null;
  cover?: Cover | null;
  monthlyListeners?: string | null;
  followers?: number | null;
  verified: boolean;
  geniusImageUrl?: string | null;
  geniusAka?: string[] | null;
  geniusUsername?: string | null;
  tracks: Track[];
  albums: ArtistAlbum[];
  singles: ArtistRelease[];
}

export interface AlbumDetails {
  type: "album";
  id: string;
  title: string;
  artists: TrackArtist[];
  year?: number | null;
  cover?: Cover | null;
  trackCount?: number | null;
  durationMs?: number | null;
  tracks: Track[];
}

export interface CatalogPlaylistDetails {
  type: "playlist";
  id: string;
  title: string;
  author?: string | null;
  description?: string | null;
  year?: number | null;
  cover?: Cover | null;
  trackCount?: number | null;
  durationMs?: number | null;
  tracks: Track[];
}

export interface LyricsMatchedTrack {
  title?: string | null;
  artists?: string[] | null;
  durationMs?: number | null;
}

export interface LyricsPayload {
  format: string;
  content: string;
}

export interface LyricsQuality {
  total: number;
  match: number;
  content: number;
  sync: number;
  source: number;
  reasons: string[];
}

export interface LyricsCandidate {
  matchedTrack?: LyricsMatchedTrack | null;
  lyrics: LyricsPayload;
  syncHint?: string | null;
  provider: string;
  contentKind: string;
  syncLevel: string;
  topPriority?: boolean | null;
  quality: LyricsQuality;
}

export interface LyricsSearchResult {
  provider?: string | null;
  cached: boolean;
  candidate: LyricsCandidate;
}

export interface LyricsStreamTrack {
  id: string;
  title: string;
  artists: TrackArtist[];
  durationMs: number;
}

export interface LyricsBestResult {
  provider: string;
  quality: number;
  syncLevel: string;
}

export type LyricsStreamEvent =
  | { type: "metadata"; track: LyricsStreamTrack }
  | {
      type: "provider";
      provider: string;
      status: "found";
      syncLevel: string;
      candidate: LyricsCandidate;
      durationMs?: number | null;
    }
  | {
      type: "provider";
      provider: string;
      status: "not_found";
      instrumental?: boolean | null;
      durationMs?: number | null;
    }
  | {
      type: "provider";
      provider: string;
      status: "error";
      message: string;
      durationMs?: number | null;
    }
  | {
      type: "done";
      cached: boolean;
      best?: LyricsBestResult | null;
      status?: string | null;
      instrumental?: boolean | null;
    };

// ---- Shared Router & Handler Interfaces ----

export interface ApiErrorBody {
  message: string;
  error: string;
  code?: string;
  requestId: string;
  reqId: string;
}

export interface RequestContext {
  request: Request;
  env: Env;
  ctx: ExecutionContext;
  url: URL;
  requestId: string;
  user: AuthUser | null;
  userId: string | null;
}

export type SubRouterHandler = (reqCtx: RequestContext) => Promise<Response | null>;
