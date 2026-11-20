# AGENTS.md — `liner-android`

Kotlin + Jetpack Compose + Media3 Android client branded **vasyan** (applicationId `fun.tryliner.android`).

## Modules & Key Files
- `gradle.properties` — Sets `liner.apiBaseUrl` (currently `https://liner-worker.vasyan.workers.dev`). **Note:** `app/build.gradle.kts` reads `gradle.properties` before `local.properties`.
- `:app` (`app/src/main/java/fun/tryliner/android/`):
  - `AppContainer.kt` — DI container wiring `EncryptedSessionStorage`, `WasmRequestSigner`, `LinerApiClient`, `InnertubeClient`, `PlayerController`, `LanConnectManager`.
  - `playback/PlayerController.kt` — Queue/playback state, `streamCache` (`ResolvedAudioStream` TTL cache), `customCacheKey = "track_${track.id}"`, handoff & LAN command handling.
  - `playback/LinerPlaybackService.kt` — Media3 `MediaSessionService`, `512 MB` `SimpleCache` (`cacheDir/audio_cache`), `CacheDataSource.Factory`, `DefaultLoadControl` (`500 ms` / `1000 ms`).
  - `playback/ChunkedGoogleVideoDataSource.kt` — `512 KB` `&range=start-end` chunked fetcher with `PREFETCH_CONCURRENCY = 4` for `*.googlevideo.com`.
  - `playback/LanConnectManager.kt` — UDP `45788` discovery + OkHttp WebSocket `45789` bidirectional sync/handoff.
  - `ui/theme/` (`Color.kt`, `Shape.kt`, `Type.kt`, `Theme.kt`) — Dark glass monochrome palette.
  - `ui/components/` (`LinerComponents.kt`, `TrackRow.kt`, `SyncedLyricsView.kt`, `HumanCheckDialog.kt`).
  - `ui/LinerApp.kt`, `ui/login/LoginScreen.kt`, `ui/player/MiniPlayerBar.kt`, `ui/player/NowPlayingScreen.kt`, `ui/library/LibraryScreen.kt`, `ui/search/SearchScreen.kt`, `ui/playlist/PlaylistDetailScreen.kt`.
  - `app/src/main/res/values/strings.xml` (`app_name = "vasyan"`), `app/src/main/res/xml/network_security_config.xml` (cleartext `ws://` permitted for LAN).
- `:core:model` — Kotlinx Serialization DTOs (`Auth.kt`, `Track.kt`, `Catalog.kt`, `Lyrics.kt`, `Playlists.kt`, etc.).
- `:core:crypto` — `WasmRequestSigner.kt` (Chicory + `signer.wasm`), `ProofOfWorkSolver.kt`, `Argon2id.kt`.
- `:core:network` — `LinerApiClient.kt` (Worker REST/SSE client), `InnertubeClient.kt` (direct YouTube stream resolution).
- `:core:lyrics` — LRC/ELRC parser & SSE event parser.

## Commands
- Build & unit tests: `.\gradlew.bat testDebugUnitTest assembleDebug`
- Install via Wi-Fi ADB:
  `$adb = "C:\Users\svyat\AppData\Local\Android\Sdk\platform-tools\adb.exe"; & $adb connect 192.168.1.231:43241; & $adb -s 192.168.1.231:43241 install -r app\build\outputs\apk\debug\app-debug.apk`
