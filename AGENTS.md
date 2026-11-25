# AGENTS.md — `c:\liner_mod` (`vasyan`)

Экосистема **vasyan** (форк Liner): self-hosted Cloudflare Worker бэкенд и два синхронизированных клиента (Windows/Electron и Android). Этот файл — единый источник истины по структуре, архитектуре, дизайн-системе и инвариантам проекта.

---

## 1. Подробная структура проекта

```text
c:\liner_mod\
├── AGENTS.md                         # Главные инструкции и архитектура для всех агентов
├── docs\ARCHITECTURE.md              # Исходный обзор архитектуры (консолидирован в AGENTS.md)
├── playlist-to-likes.js              # Утилита переноса треков плейлиста в лайки
│
├── liner-worker\                     # REST + SSE бэкенд (Cloudflare Workers + D1 + Workers AI)
│   ├── wrangler.toml                 # Конфиг воркера, D1 (liner-db), AI binding
│   ├── schema.sql                    # Таблицы D1: users, refresh_tokens, liked_tracks, playlists, playlist_items, playback_history
│   └── src\
│       ├── index.ts                  # HTTP/SSE маршрутизатор и CORS
│       ├── auth.ts                   # Challenge, register/login (PBKDF2 100k), refresh, /v1/me, /v1/users/me
│       ├── catalog.ts                # YouTube Music WEB_REMIX поиск, треки/артисты/альбомы/плейлисты, /v1/explore/popular
│       ├── spotifyCatalog.ts         # Поиск и метаданные Spotify (Pathfinder + TOTP, sp:artist:*, sp:album:*, sp:playlist:*)
│       ├── spotifyImport.ts          # Импорт плейлистов/библиотеки Spotify (до 2000 треков)
│       ├── library.ts                # Лайки, пользовательские плейлисты, playback events & history
│       ├── recommendations.ts        # Daily Mixes (Workers AI + k-means + llama-3.1-8b) + Моя волна (POST /v1/me/recommendations/wave) + Last.fm
│       ├── lyrics.ts                 # Поиск и SSE-стриминг текстов (LRCLIB)
│       ├── streamProxy.ts            # Аудио-релей через SoundCloud progressive/HLS (/v1/stream/{resolve,play,play.m3u8,seg})
│       ├── http.ts                   # JSON/SSE ответы, заголовки, обработка ошибок
│       └── types.ts                  # Env и общие доменные типы
│
├── liner-desktop\                    # Electron + React + Vite десктоп-клиент (бренд "vasyan")
│   ├── docs\                         # DESIGN.md, FAQ.md, I18N.md, PRIVACY.md, STORAGE.md
│   ├── electron\                     # Main/Preload процесс Electron и нативные модули
│   │   ├── main.ts, preload.ts       # Точка входа Electron и IPC bridge
│   │   ├── lan-connect.ts            # UDP beacon (45788) + WebSocket сервер (45789)
│   │   ├── bundle-manager.ts         # Загрузка локального dist/index.html (remote bundle отключён)
│   │   ├── signer.ts, crypto.ts      # Подпись edge-запросов и криптография
│   │   ├── auth-session-*.ts         # Хранение и IPC auth-сессии
│   │   ├── local-tracks\             # Импорт, метаданные, протокол и библиотека локальных треков
│   │   ├── saved-tracks\             # Офлайн-кэш сохранённых треков
│   │   ├── youtube\                  # BotGuard / YT-утилиты
│   │   ├── compositor\               # Поддержка Hyprland/Sway и overlay
│   │   └── netprobe\, netsim\        # Сетевая диагностика
│   └── src\                          # Feature-Sliced React-фронтенд
│       ├── app\                      # Инициализация и провайдеры
│       ├── assets\                   # animations, fonts (SF Pro / Roboto Flex)
│       ├── entities\                 # artist, playlist, track, user
│       ├── features\                 # artist, auth, collection, connectivity, covers, discord, home,
│       │                             # human-check, library, lyrics, navigation, overlay, player,
│       │                             # search, settings, shortcuts, touchbar, updater, youtube-auth
│       ├── languages\locales\        # Локализации
│       ├── shared\                   # api, cache, config, contracts, effects, hooks, lib, media,
│       │                             # presence, storage, telemetry, types, ui, utils, youtube
│       └── widgets\                  # app-frame, fullscreen-player, header, mini-player,
│                                     # player-bar, right-drawer, settings-modal, sidebar
│
└── liner-android\                    # Kotlin + Jetpack Compose + Media3 Android-клиент (бренд "vasyan")
    ├── core\
    │   ├── model\                    # ApiError, Auth, Catalog, Cover, History, Leaderboards, Lyrics, Playlists, Pow, Recommendations, Track, Users
    │   ├── crypto\                   # Argon2id, ProofOfWorkSolver, WasmRequestSigner
    │   ├── lyrics\                   # LyricsModels, LyricsParser, SseEventParser
    │   └── network\                  # LinerApiClient, InnertubeClient, HumanCheck, SessionStorage
    └── app\src\main\java\fun\tryliner\android\
        ├── LinerApplication.kt       # Инициализация Application и ImageLoader
        ├── AppContainer.kt           # DI-контейнер (API, PlayerController, LanConnectManager, DownloadManager)
        ├── MainActivity.kt           # Single-activity хост, edge-to-edge, системные бары
        ├── data\EncryptedSessionStorage.kt
        ├── playback\
        │   ├── LinerPlaybackService.kt          # Foreground MediaSessionService (Media3)
        │   ├── PlayerController.kt              # Очередь, RepeatMode, воспроизведение, Local/Remote переключение
        │   ├── LanConnectManager.kt             # UDP discovery (45788) + WS клиент (45789), Inverted Master-Slave
        │   ├── RemoteMediaPlayer.kt             # Прокси Media3 Player для уведомления/кнопок громкости в LAN Remote режиме
        │   ├── LocalFirstDataSource.kt          # Чтение из SimpleCache (512 MB) / офлайн-загрузок с fallback в сеть
        │   ├── ChunkedGoogleVideoDataSource.kt  # 4-поточная чанковая загрузка аудиопотоков
        │   └── TrackDownloadManager.kt          # Фоновая загрузка треков для офлайна
        └── ui\
            ├── LinerApp.kt                      # Навигация, единый LocalAppHazeState, Top/Bottom Blur, MiniPlayer
            ├── theme\                           # Color.kt, Shape.kt, Motion.kt, Type.kt, Theme.kt
            ├── components\                      # CoverAmbientExtractor, LinerComponents, TrackActionSheet, TrackRow, HumanCheckDialog
            ├── home\                            # HomeScreen.kt, HomeViewModel.kt ("vasyan", миксы, тренды)
            ├── search\                          # SearchScreen.kt, SearchViewModel.kt
            ├── library\                         # LibraryScreen.kt, LibraryViewModel.kt
            ├── playlist\                        # PlaylistDetailScreen.kt, PlaylistDetailViewModel.kt (альбомы/плейлисты)
            ├── artist\                          # ArtistScreen.kt, ArtistViewModel.kt
            ├── player\                          # NowPlayingScreen.kt, MiniPlayerBar.kt
            └── login\                           # LoginScreen.kt, LoginViewModel.kt
```

---

## 2. Environment & Tooling Gotchas (Windows / PowerShell)
- **Shell (PowerShell 5.1):** Используй `;` (не `&&`) между командами. Gradle `-P` флаги с точками бери в кавычки: `.\gradlew.bat assembleDebug "-Pliner.apiBaseUrl=..."`.
- **pnpm:** Не установлен глобально — в `liner-desktop` всегда вызывай `npx --yes pnpm <cmd>`.
- **Android SDK / ADB:** `C:\Users\svyat\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  - Телефон (`192.168.1.231`) при включённой «Отладке по Wi-Fi» обычно **сам появляется по mDNS** как `adb-..._adb-tls-connect._tcp` без указания порта: запускай `adb start-server; Start-Sleep -Seconds 2; adb devices` и бери активное устройство из списка (не спрашивая порт у пользователя).
  - Ручной `adb connect 192.168.1.231:<port>` нужен только если `adb devices` пуст (например, mDNS не прошёл или отладку только что включили).
- **Gradle base URL precedence:** `providers.gradleProperty("liner.apiBaseUrl")` читает `gradle.properties` **раньше** `local.properties`. Обновляй `gradle.properties` или передавай `-Pliner.apiBaseUrl=...`.
- **Упаковка Desktop:** `electron-builder --win` падает с `EBUSY`, если запущен `liner-desktop/release/win-unpacked/liner.exe`. Закрывай `liner.exe` (включая трей) перед сборкой.
- **Скрипты и API-тесты в PowerShell:** Не пиши сложные многострочные `node -e "..."` или `curl.exe -d '{...}'` (ломается экранирование кавычек). Пиши scratch-файл в `<Artifact Directory>/scratch/*.js` и запускай `node <path>`, либо используй `Invoke-RestMethod`.

---

## 3. Production Backend, Auth & Rebranding Rules
- **Worker URL:** `https://liner-worker.vasyan.workers.dev` (лимит Cloudflare Workers: 100k req/day).
- **D1 Database:** `liner-db` (`database_id = "da54d369-1579-47ef-9fce-d0110fbde605"`). Секреты: `JWT_SECRET`, `LASTFM_API_KEY`. Таблицы в `schema.sql`: `users`, `refresh_tokens`, `liked_tracks`, `playlists`, `playlist_items`, `playback_history`, `daily_mix_cache`.
- **Auth, Validation & D1 Performance (`liner-worker`):**
  - Пароли хэшируются PBKDF2 (100k итераций) на воркере.
  - **Критический инвариант `/v1/auth/refresh`:** Никогда не делать деструктивный отзыв `refresh_tokens` при обновлении access-токена — параллельные запросы или сетевой сбой разлогинят клиента.
  - `AuthSession.expiresAt` всегда отдаётся в **миллисекундах** (`accessExpSec * 1000`) как в `issueSession`, так и в `/v1/auth/refresh`.
  - Валидация тела запросов и URL-параметров в воркере идёт через `readJsonObject`, `readOptionalString`, `readOptionalNullableString` и `safeDecodeURIComponent` (`src/http.ts`) — невалидный JSON или битый `%`-encoding возвращают `400 BAD_REQUEST` вместо `500`.
  - `GET /v1/me/playlists` агрегирует счётчики и до 4 обложек за 2 батч-запроса D1 (`LEFT JOIN` + `ROW_NUMBER() OVER (PARTITION BY ...)`), без N+1. Массовое добавление треков в `POST /v1/me/playlists/:id/tracks` выполняется чанками по 100 через `env.DB.batch()`.
- **Rebranding vs Internal Keys:**
  - Весь видимый UI, заголовки окон, установщики и Android `app_name` — **`vasyan`**.
  - **Не переименовывать** внутренние идентификаторы: `fun.tryliner.android`, `com.hkamori.liner`, `executableName: "liner"`, `linerElectron`, `liner-connect-beacon`, `LINER_DISCOVER`, `liner://`.

---

## 4. Streaming, Relay & Anti-DPI Rules
- **Запрет userspace DPI-прокси на Android:** Не добавлять `zapret`/userspace DPI-desync прокси в `liner-android` — на текущем ISP DPI работает только kernel-level `hostfakesplit`/`ts`.
- **SoundCloud Stream Relay (`streamProxy.ts`):** Так как прямые запросы YouTube с IP Cloudflare дают `LOGIN_REQUIRED`, воспроизведение резолвится через `/v1/stream/{resolve,play,play.m3u8,seg}` (SoundCloud progressive/HLS). Треки `sp:*` и `sc:*` всегда идут через relay. Ключи кэша `STREAM_CACHE` формируются через `buildStreamCacheKey` (сохраняя префикс `sc:`), а все `fetch()` к SoundCloud защищены `AbortSignal.timeout()` (`10s` API/m3u8, `15s` сегменты/аудио). Поиск SoundCloud (`resolveSoundCloudOnEdge`) ранжирует до 15 кандидатов с учётом `durationMs`, токенов названия/артиста и штрафует нежелательные варианты (`slowed`, `reverb`, `nightcore`, `sped up`, `remix`, `cover`, `karaoke`, `8d`, `mashup`, `live`).
- **Настройки источника и приоритета на Android (`PlaybackPreferencesStore` + `LibraryScreen`):** Пользователь может включить/выключить «Приоритет платформы» и выбрать предпочитаемую платформу (`YouTube` / `SoundCloud`), а также перекачать офлайн-треки (`TrackDownloadManager.redownloadAll()`) и очистить `streamCache` + `SimpleCache` (`PlayerController.clearStreamCache()`). При выключенном приоритете работает гонка `600 ms` + `select`.
- **Быстрый fallback, подпись и гонка на Android (`PlayerController.kt` + `LinerApiClient.kt` + `InnertubeClient.kt`):**
  - `InnertubeClient.resolveAudioStream` не блокируется на `sw.js_data` (запрашивает её только при `LOGIN_REQUIRED` или с таймаутом `3s`, а `ensureVisitorData` синхронизирован через `visitorDataMutex`) и не делает лишний `assertStreamReachable` перед передачей в `ExoPlayer` (при ошибке `ExoPlayer` на `YT` срабатывает авто-fallback на релей).
  - Если прямой запрос к YouTube не отвечает за `600 ms`, `PlayerController` запускает `resolveViaWorkerRelay` параллельно (`select` берёт первого победителя) через `apiClient.resolveStream()` (с WASM-подписью, `x-liner-edge`, авторизацией и `executeWithRecovery` `401 -> 403 -> 401`) и запоминает `preferRelayUntilMs` на 5 минут. Следующий трек очереди предзагружается в синхронизированный LRU `streamCache` (`MAX_STREAM_CACHE_ENTRIES = 50`) через `1s` после старта текущего.
  - При смене сети (например, переход 4G ↔ домашний Wi-Fi) `PlayerController.onPlayerError` и `playIndex` автоматически повторяют резолв/воспроизведение с текущей позиции `positionMs` до 3 раз (`1.5s * attempt`), чтобы трек не останавливался при кратковременном обрыве сокета.
  - **Zero-CPU режим в фоне (`MainActivity.onStop -> setUiForeground(false)`):** Когда экран выключен или приложение свёрнуто, `PlayerController` полностью останавливает `200 ms` тикер позиции (`positionTickerJob`) и не открывает фоновые SSE-потоки лирики (`loadLyrics` вызывается лениво при возврате в UI), `LanConnectManager` не шлёт UDP broadcast'ы и не перебирает `NetworkInterface`, а `AppContainer` формирует `userAgent` из `Build.VERSION.RELEASE`/`Build.MODEL` без вызова `WebSettings.getDefaultUserAgent` (чтобы не поднимать фоновый процесс Chromium WebView на 55 МБ).
- **Кэш, нормализация громкости, офлайн-загрузки и адаптивные чанки:**
  - **Android:** Связка `LocalFirstDataSource` + `SimpleCache` (`512 MB`) + `TrackDownloadManager` + 4-поточный `ChunkedGoogleVideoDataSource` с **адаптивным размером чанка** по запасу буфера впереди (`64 KB` на старте/перемотке → `128 KB` → `256 KB` → `512 KB` → `1 MB` → `2 MB`). `TrackDownloadManager` поддерживает офлайн-загрузку `sp:*`/`sc:*` через Worker relay + `downloadHls()` и сериализует удаление под `downloadSemaphore`. `LinerPlaybackService` использует `bufferForPlaybackMs = 250 ms`, освобождает `chunkedDataSourceFactory` в `onDestroy()` и применяет **автоматическую нормализацию громкости** (`InnertubeClient` `loudnessDb` + `LoudnessEnhancer` + `DynamicsProcessing` limiter на API 28+). `TrackLoudnessStore` (`filesDir/loudness_cache.json`, LRU 2000) сохраняет `loudnessDb` между перезапусками, а `AudioLoudnessAnalyzer` (на базе `MediaExtractor` + `MediaCodec` + ITU-R BS.1770 K-weighting к `-14 LUFS`) пересчитывает точную громкость скачанных треков (включая треки с Worker relay).
  - **Desktop (`liner-desktop`):** `innertubeAudioFormats` / `webRemixAudio` / `tvPlayerResponse` извлекают `loudnessDb` (`playerConfig.audioConfig`) и прокидывают через `PlaybackStream`. `PlayerRuntime` кэширует `trackLoudnessDb` (до 200 треков) и масштабирует целевую громкость в `effectiveVolume()` (`10 ** (-loudnessDb / 20)` в `[0.25, 1.0]`, либо `0.9` для потоков без `loudnessDb`), а `pitchShifter.ts` (`GRAPH_VERSION = 3`) пропускает выход графа через `DynamicsCompressorNode` limiter (`threshold -2 dB, ratio 4:1`) перед `ctx.destination`.

---

## 5. Shared LAN Connect Protocol (Desktop ↔ Android, Zero Cloud)
Синхронизация воспроизведения и удалённое управление работают **строго по локальной сети**:
- **UDP Discovery (`45788`):** Android шлёт `"LINER_DISCOVER"` broadcast только при наличии локальной сети (`TRANSPORT_WIFI` / `TRANSPORT_ETHERNET` в `LanConnectManager.isLocalLanAvailable()`, на мобильной сети спит по `10s`, не тратя CPU в фоне); Desktop слушает `0.0.0.0:45788` и отвечает `{ "type": "liner-connect-beacon", "deviceId", "deviceName", "userId", "wsPort": 45789 }`.
- **WebSocket Server (Desktop `0.0.0.0:45789`):**
  - `hello`: `{ "type": "hello", "deviceId", "deviceName", "platform": "android" | "desktop", "userId" }` (`4001` при несовпадении `userId`). В `electron/lan-connect.ts` `remoteStates` всегда индексируется по `peer.deviceId`, а `sendCommand` валидирует список разрешённых действий.
  - `state`: `{ "type": "state", "deviceId", "deviceName", "platform", "isPlaying", "positionMs", "durationMs", "updatedAt", "track": { "id", "title", "artist", "coverUrl", "durationMs" } | null }`.
  - `command`: `{ "type": "command", "targetDeviceId"?, "action": "play" | "pause" | "toggle" | "next" | "prev" | "seek" | "handoff", "positionMs"?, "track"? }`.
- **Инварианты Inverted Master–Slave:**
  - *Slave* — устройство с активным локальным аудио; *Master* — удалённый контроллер.
  - Сохранять `2500 ms` local-claim grace window против гонок состояний при перехвате воспроизведения (`playLocal()` и `togglePlayPause()` в `PlayerController.kt` вызывают `onLocalPlaybackClaimed()`).
  - В `LanConnectManager.kt` обработка входящего `"state"` выполняется на `Dispatchers.Main.immediate`, а `handoffToPhone()` сначала запускает локальное воспроизведение (`playSingle`), после чего шлёт `"handoff"` и актуальный `state`.
  - На Android в удалённом режиме (`RemoteMediaPlayer`) `LinerPlaybackService` удерживает `RemoteMediaPlayer` привязанным к `MediaSession` даже на паузе, чтобы работали системное уведомление, экран блокировки и аппаратные кнопки громкости.

---

## 6. Design System — «Atmospheric Monochrome Glass»
Единый визуальный язык Desktop (`liner-desktop/docs/DESIGN.md`) и Android (`liner-android/.../ui/theme/*`):

- **Принципы:**
  1. *Content is the colour* — интерфейс монохромный; цвет задаёт только обложка через `AtmosphericBackdrop` + `CoverAmbientExtractor` (на экранах плейлиста/альбома/артиста градиент обложки заходит под статус-бар и вырез камеры).
  2. *Glass, not boxes* — **никаких видимых рамок `1.dp`/`1px`** (`BorderAlpha14/20` = `0x0DFFFFFF`, `BorderAlpha33` = `0x14FFFFFF` — невидимы, разделение идёт тоном заливки и размытием).
  3. *Calm motion & SF Pro* — шрифт `SfProFamily` (`sf_pro_regular/medium/semibold/bold`), кривая `CubicBezierEasing(0.22f, 1f, 0.36f, 1f)`, пружины `appleDefaultSpring()` (stiffness `420`, без bounce) и `appleMomentumSpring()` (stiffness `380`, damping `0.82`).
- **Токены (`Color.kt` / `Shape.kt` / `Motion.kt`):**
  - Холст: `BgCanvas` / `AmbientBase` / `BgPrimary` = `0xFF0D0F14` (**никогда не `#000000`**), `BgElevated` = `0xFF14171F`, `BgPanel` = `0xFF181C26`.
  - Стекло: `SurfaceGlass` = `0xEB101218`, `NavBarGlass` = `0xE60D0F14`, `MiniPlayerGlass` = `0xC714161C`.
  - Заливки: `FillSubtle` `0x0DFFFFFF` (5%), `Fill` `0x12FFFFFF` (7%), `FillHover` `0x1AFFFFFF` (10%), `FillStrong` `0x24FFFFFF` (14%), `HeroGlass` `0x1AFFFFFF`.
  - Текст и акцент: `TextPrimary` `0xF2FFFFFF` (95%), `TextSecondary` `0x99FFFFFF` (60%), `TextTertiary` `0x73FFFFFF` (45%), `AccentPrimary` `0xF2FFFFFF`, `BtnPrimaryBg` `0xFFFFFFFF`, `BtnPrimaryText` `0xFF0D0F14`.
  - Скругления (`LinerShapes`): `Tile` `6.dp`, `Control` `8.dp`, `HeroButton` `9.dp`, `HeroCover` `10.dp`, `Item` `12.dp`, `Floating` `14.dp`, `BigArt` `16.dp`, `Panel` `20.dp`, `Pill` `CircleShape`.
- **Жёсткие UI/UX-инварианты Android:**
  - **Язык:** 100% русский видимый интерфейс (включая `LoginScreen` с регистрацией); заголовок Home — `vasyan` (не «Главная» и не микс EN/RU).
  - **Навигация (`LinerApp.kt`):** 3 независимых вложенных графа (`TAB_HOME`, `TAB_SEARCH`, `TAB_LIBRARY`) с отдельными back stack'ами, подсветкой через `destination.hierarchy`, симметричными горизонтальными переходами (`enter`/`exit`/`popEnter`/`popExit` для вкладок и детальных экранов) и переходом к альбому по нажатию на название трека в `NowPlayingScreen`.
  - **Motion, Reduced Motion и производительность рекомпозиций (`Motion.kt`, `LinerApp.kt`, `LinerComponents.kt`):**
    - Единый источник анимаций — `LinerMotion` (`EaseOut`, `DURATION_PRESS = 110`, `DURATION_FAST = 140`, `DURATION_LYRICS = 200`, `DURATION_NORMAL = 220`, `DURATION_SHEET = 300`, `DURATION_AMBIENT = 550`, `easeOutTween`, `appleDefaultSpring`, `appleMomentumSpring`, `bounceSpring`, `rubberband`, `projectMomentum`, `Modifier.pressScale`).
    - `LocalReducedMotion` провайдится на уровне `LinerApp` (с живой подпиской `ContentObserver` на `ANIMATOR_DURATION_SCALE` в `rememberReducedMotion()`). Все пространственные пружины, свайпы, `Crossfade` и `LinerShimmerBox` обязаны уважать `rememberReducedMotion()`.
    - Чтение `sheetProgress.value`, `swipeOffsetX.value`, `shimmerAlpha` и `animatedTint` выполняется внутри `graphicsLayer` / `drawBehind` / `derivedStateOf`, а экраны (`HomeScreen`, `SearchScreen`, `LibraryScreen`, `PlaylistDetailScreen`, `ArtistScreen`) подписываются только на `state.map { it.currentTrack?.id }.distinctUntilChanged()`, чтобы тики `positionMs` (`200 ms`) не вызывали рекомпозицию всего дерева.
    - `TrackListSkeleton` и скелетоны детальных экранов шарят один `rememberShimmerAlphaState()` и плавно переходят в контент через `Crossfade`. Индикаторы `PullToRefreshBox` смещены ниже `headerOverlayHeight`.
  - **Haze / Frosted Blur:** Использовать **один общий `LocalAppHazeState`** на уровне `LinerApp` для верхней плашки (Home, Playlist/Album, Artist, Search, Library) и нижней панели навигации. **Никогда не вкладывать `hazeSource` внутрь другого `hazeSource`**. На `PlaylistDetailScreen` и `ArtistScreen` размытая шапка плавно проявляется через `headerBlurAlpha` (`graphicsLayer { alpha = headerBlurAlpha }`) со стабильным контейнером `Box(Modifier.weight(1f))` под sticky-заголовок.
  - **Перенос строк:** Названия треков, исполнители и строки текста в плеере должны переноситься (`softWrap = true`), а не обрезаться троеточием (`Ellipsis`), где текст должен читаться полностью.
  - **Плеер (`NowPlayingScreen`, `MiniPlayerBar`), лирика и ботом-шиты (`TrackActionSheet`):**
    - Под обложкой в `NowPlayingScreen` зарезервирован стабильный слот `86.dp` (`14.dp` отступ + `72.dp` контейнер) для Spotify-style превью синхронной лирики по левому краю — асинхронная подгрузка/очистка текста при переключении трека никогда не сдвигает обложку по вертикали.
    - Свайпы обложки и `MiniPlayerBar` работают через `Animatable` + `LinerMotion.rubberband` + `VelocityTracker` + `projectMomentum` + `appleMomentumSpring()`. Смена метаданных, обложки (`LinerCoverArt`), иконки Play/Pause/Buffering и состояний загрузки (`DownloadButton`) анимируется через `AnimatedContent`/`Crossfade`, а прогресс-бар `MinimalScrubber` и `MiniPlayerBar` рисуется через `drawBehind` без layout-проходов на тиках.
    - Под кнопками Shuffle / Lyrics / Repeat зарезервировано фиксированное место под индикаторную точку (`alpha`/`scale` в `graphicsLayer`).
    - При клике по действиям в `TrackActionSheet` и модальной очереди вызывается `sheetState.hide()` перед размонтированием (`onDismiss()`), а очередь автоматически скроллится к `currentIndex`.

- **Рекомендации (`recommendations.ts`, «Моя волна» + Daily Mixes):**
  - `POST/GET /v1/me/recommendations/wave` принимает `{ vibe?: "all" | "favorite" | "discover" | "energy" | "calm", excludeIds?: string[], limit?: number }` и формирует бесконечный персональный поток на основе `liked_tracks`, `playback_history` (буст завершённых/зацикленных, исключение пропущенных), YouTube Music Radio, Last.fm similar tracks и настроения (`vibe`), разнося треки по артистам.
  - Клиенты: на Android (`PlayerController.startMyWave` / `maybeRefillWave`) автоподгрузка запускается за ≤3 трека до конца очереди (`excludeIds` до 150); на Desktop (`playerEngine.startMyWave` + `wave:<vibe>` в `radioMode.ts`) интегрировано в автодозаправку `radioMode`. На обоих клиентах на экране Home отображается баннер «Моя волна» с выбором настроения (`Для меня`, `Любимое`, `Незнакомое`, `Бодрое`, `Спокойное`).

---

## 7. Common Commands
- **Worker (`c:\liner_mod\liner-worker`):** `npx tsc --noEmit` | `npx wrangler deploy`
- **Desktop (`c:\liner_mod\liner-desktop`):** `npx --yes pnpm exec tsc -b; npx --yes pnpm exec vite build` | `npx --yes pnpm electron:build:win`
- **Android (`c:\liner_mod\liner-android`):** `.\gradlew.bat testDebugUnitTest assembleDebug` | `adb -s 192.168.1.231:<port> install -r app\build\outputs\apk\debug\app-debug.apk`
- Для детальных runbook'ов (D1, `adb logcat`, отладка Worker API) используй проектный навык `.agents\skills\vasyan-ops\SKILL.md`.

---

## 8. Documentation & Git Push Rules
- **Обязательное обновление доков:** После каждого существенного фикса, апдейта или архитектурного изменения дополняй `AGENTS.md` (и при необходимости профильные доки/навыки), фиксируя как именно работают новые или изменённые механизмы, чтобы следующим агентам было проще ориентироваться в проекте (соблюдая лимит `100,000` байт на `AGENTS.md`).
- **Обязательный push на GitHub:** После завершения правок, обновления документации и успешной проверки сборок/тестов коммить и пушить все изменения в `origin` на GitHub (`liner-worker` → `origin` `Kotumbaa/vasyan-worker` `main`, `liner-desktop` → `origin` `Kotumbaa/vasyan-desktop` `main`, `liner-android` → `origin` `Kotumbaa/vasyan-android` `main`; в `upstream` не пушить). Перед пушем проверять `git status` и не коммитить секреты/локальные бинарные артефакты (`node_modules`, `build`, `dist`, `release`, `.wrangler`).
