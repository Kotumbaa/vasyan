# AGENTS.md — `liner-worker`

Cloudflare Worker + D1 (SQLite) backend for **vasyan** (`https://liner-worker.vasyan.workers.dev`).

## File Map
- `wrangler.toml` — Worker config (`DB` D1 binding -> `liner-db`, `da54d369-1579-47ef-9fce-d0110fbde605`; `AI` Workers AI binding).
- `schema.sql` — D1 tables: `users`, `refresh_tokens`, `liked_tracks`, `playlists`, `playlist_items`, `playback_history`, `daily_mix_cache`.
- `src/index.ts` — Main `fetch` router, CORS `OPTIONS`, `/health`, auth middleware, sub-router dispatch, and desktop stub routes (`/v1/me/library/artists`, `/v1/tracks/:id/ambient`, `/v1/presence/heartbeat`).
- `src/types.ts` — Shared `Env` (`DB`, `AI`, `LASTFM_API_KEY`, `JWT_SECRET`, `EDGE_KEY`, `ENVIRONMENT`), media/auth/library types, and `SubRouterHandler`.
- `src/http.ts` — `jsonResponse`, `errorResponse`, `baseHeaders`, body parsing helpers.
- `src/auth.ts` — PBKDF2 password hashing, HMAC-SHA256 JWT tokens, `/v1/auth/*`, `/v1/me`, `/v1/users/me`, `/v1/edge/key`.
- `src/library.ts` — Liked tracks, playlists + items (including batch add), playback events, history, and `/v1/me/recommendations/daily-mixes` handler.
- `src/recommendations.ts` — Daily Mixes generator (`buildOrGetDailyMixes`) using Workers AI (`@cf/baai/bge-small-en-v1.5` + k-means + `@cf/meta/llama-3.1-8b-instruct-fast`), Last.fm tags/similar tracks, YouTube radio, and `daily_mix_cache`.
- `src/catalog.ts` — YouTube Music InnerTube (`WEB_REMIX`) & Spotify dispatch for `/v1/catalog/*` and `/v1/explore/popular`.
- `src/spotifyCatalog.ts` — Spotify Pathfinder catalog search, album, artist, and playlist metadata (`sp:artist:*`, `sp:album:*`, `sp:playlist:*`).
- `src/spotifyImport.ts` — Spotify guest TOTP session, Pathfinder playlist pagination (up to 2000 tracks), and embed fallback for `/v1/catalog/import/spotify`.
- `src/lyrics.ts` — LRCLIB handlers for `/v1/me/lyrics/search` and SSE `/v1/tracks/:trackId/lyrics`.
- `src/streamProxy.ts` — SoundCloud audio relay (`/v1/stream/resolve`, `/v1/stream/play`, `/v1/stream/play.m3u8`, `/v1/stream/seg`) with progressive & rewritten HLS support.

## Commands
- Typecheck: `npx tsc --noEmit`
- Dry-run bundle: `npx wrangler deploy --dry-run --outdir dist`
- Local D1 init & dev:
  `npx wrangler d1 execute liner-db --local --file=./schema.sql; npx wrangler dev`
- Remote D1 & deploy:
  `npx wrangler d1 execute liner-db --remote --file=./schema.sql`
  `npx wrangler deploy`
