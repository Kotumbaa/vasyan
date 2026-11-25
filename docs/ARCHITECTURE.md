# Architecture Overview — `vasyan` (`c:\liner_mod`)

## 1. High-Level System Topology

```text
+--------------------+         HTTPS REST / SSE          +---------------------------+
|   liner-desktop    | --------------------------------> |       liner-worker        |
| (Electron + React) |                                   |  (Cloudflare Workers + D1)|
+--------------------+                                   +---------------------------+
         ^                                                  |          |          |
         | LAN Only:                                        v          v          v
         | UDP 45788 + WS 45789                          D1 SQLite  YouTube    LRCLIB
         v                                               (accounts, (InnerTube (lyrics)
+--------------------+         HTTPS REST / SSE           likes,     search/
|   liner-android    | -------------------------------->  playlists) catalog)
| (Kotlin + Compose) |
+--------------------+
         |
         +---- Direct Audio Streaming (YouTube *.googlevideo.com) or Worker Relay (/v1/stream/* via SoundCloud)
```

---

## 2. Backend (`liner-worker`)
- **Hosting:** Cloudflare Workers + Cloudflare D1 (`liner-db`, region `EEUR`) + Workers AI (`AI` binding).
- **Auth & Edge:**
  - `POST /v1/auth/challenge` returns `mode: "off"` and `turnstile.mode: "off"` so clients do not require PoW or Cloudflare Turnstile.
  - `POST /v1/auth/register`, `POST /v1/auth/login`, `POST /v1/auth/refresh`, `GET /v1/auth/username-available` issue PBKDF2-verified HMAC-SHA256 JWTs (`JWT_SECRET` Wrangler secret). `/v1/auth/refresh` is non-destructive (does not revoke refresh tokens).
  - `GET /v1/me`, `GET /v1/users/me`, `GET /v1/edge/key` satisfy client session initialization.
- **Library, Sync & Recommendations (D1 SQLite + Workers AI):**
  - `GET/PUT/DELETE /v1/me/library/tracks` — liked tracks with serialized track snapshots.
  - `GET/POST /v1/me/playlists`, `GET/PATCH/DELETE /v1/me/playlists/:id`, single & bulk track add/remove — user playlists.
  - `POST /v1/me/playback/events`, `GET /v1/me/history` — playback history.
  - `GET /v1/me/recommendations/daily-mixes` (`src/recommendations.ts`) — personalized Daily Mixes using Workers AI (`@cf/baai/bge-small-en-v1.5`, k-means clustering, `@cf/meta/llama-3.1-8b-instruct-fast`), Last.fm tags/similar tracks, YouTube radio, and `daily_mix_cache`.
  - Stubs for desktop compatibility: `/v1/me/library/artists`, `/v1/tracks/:id/ambient`, `/v1/presence/heartbeat`.
- **Catalog, Spotify Import, Lyrics & Stream Relay:**
  - `src/catalog.ts`: proxies search, track/artist/album/playlist details, and `/v1/explore/popular` via YouTube Music InnerTube (`WEB_REMIX`), plus Spotify catalog dispatch (`source=spotify`, `sp:*`).
  - `src/spotifyCatalog.ts` & `src/spotifyImport.ts`: Spotify Pathfinder + TOTP guest session for catalog metadata and playlist/album/track import (`/v1/catalog/import/spotify`, up to 2000 tracks).
  - `src/lyrics.ts`: fetches/streams synced and plain lyrics from LRCLIB (`https://lrclib.net/api/get` & `/api/search`) via SSE (`text/event-stream`).
  - `src/streamProxy.ts`: SoundCloud audio relay (`/v1/stream/resolve`, `/v1/stream/play`, `/v1/stream/play.m3u8`, `/v1/stream/seg`) for `sp:*`/`sc:*` tracks and YouTube fallbacks.

---

## 3. Desktop Client (`liner-desktop`)
- **Main process (`electron/main.ts`):**
  - `BundleManager` auto-update/remote bundle loading is **disabled** in `electron/main.ts` so packaged builds always load local `dist/index.html` instead of downloading official `tryliner.fun` UI bundles from `%APPDATA%\liner`.
  - Hosts `LanConnectManager` (`electron/lan-connect.ts`) and exposes `window.linerElectron.lanConnect` via `electron/preload.ts`.
- **Renderer (`src/`):**
  - `src/shared/api/baseUrl.ts`: `DEFAULT_PRIMARY_API` is `https://liner-worker.vasyan.workers.dev` (overridable via `VITE_API_URL` in `.env`).
  - `src/features/player/lan/useLanConnectSync.ts` & `lanConnectStore.ts`: publishes desktop playback state (`state` every ~1s while playing + on changes) and executes incoming remote commands.
  - `src/features/navigation/ui/AppFrame.tsx`: renders `<LanConnectBanner />` directly above `<MiniPlayer />` so remote Android playback controls are visible even when no local track is loaded on PC.

---

## 4. Android Client (`liner-android`)
- **Networking (`:core:network`):**
  - `LinerApiClient.kt` targets `BuildConfig.LINER_API_BASE_URL` (configured in `gradle.properties` / `app/build.gradle.kts`).
  - `InnertubeClient.kt` resolves direct `*.googlevideo.com` audio stream URLs locally on the device.
- **Audio Playback, 4-Thread Streaming & Disk Cache (`:app/playback`):**
  - `PlayerController.kt`: caches `ResolvedAudioStream` in memory (`streamCache`) by `track.id` until `expiresAt` so skipping back to recent tracks avoids redundant InnerTube calls. Sets `MediaItem.setCustomCacheKey("track_${track.id}")`.
  - `LinerPlaybackService.kt`: configures `DefaultLoadControl` (`500 ms` playback buffer, `1000 ms` rebuffer) and wraps `ChunkedGoogleVideoDataSource.Factory()` inside Media3 `CacheDataSource.Factory` backed by a `512 MB` `SimpleCache` (`cacheDir/audio_cache`).
  - `ChunkedGoogleVideoDataSource.kt`: splits `*.googlevideo.com` streams into `512 KB` `&range=start-end` HTTP requests with a **4-thread concurrent prefetch window** (`PREFETCH_CONCURRENCY = 4`), matching `liner-desktop`'s `chunkedAudioDownload.ts` to bypass single-stream YouTube throttling.
- **LAN Connect (`:app/playback` & `:app/ui`):**
  - `LanConnectManager.kt`: discovers Desktop over UDP `45788`, connects via OkHttp WebSocket to `ws://<peerIp>:45789`, exchanges `hello`/`state`/`command`, and supports `"Play on Phone"` handoff.
  - `network_security_config.xml`: permits cleartext `ws://` for LAN IPs.
  - `MiniPlayerBar.kt` + `LinerApp.kt` + `NowPlayingScreen.kt`: display `RemotePlayerBanner` and LAN Connect handoff controls.
- **UI & Theme:**
  - Atmospheric Monochrome Glass theme (see root `AGENTS.md` §6: `BgCanvas = 0xFF0D0F14`, no `#000000`, no visible `1.dp` borders, `SfProFamily`, unified `LocalAppHazeState`, 100% Russian UI).
