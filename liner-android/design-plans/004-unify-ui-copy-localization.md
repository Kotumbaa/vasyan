# Unify Mixed RU/EN UI Copy via String Resources

Written against: `a58c81a`

## Evidence chain

- Surface: `LibraryScreen.kt:308`, `NowPlayingScreen.kt:336,685`, `TrackRow.kt:267-277`, `PlaylistDetailScreen.kt:429-435`
- Problem: User-facing labels mix English (`"Home"`, `"Search"`, `"Library"`, `"Play all"`, `"Liked Tracks"`, `"Nothing playing"`, `"No lyrics available"`) and hardcoded Russian (`"Скачанные"`, `"СЕЙЧАС ИГРАЕТ"`, `"ИГРАЕТ НА ПК"`, `"Предпросмотр текста"`, `"Удалить загрузку?"`) inside the same screens.
- Design evidence: `app/src/main/res/values/strings.xml` (existing English string resource owner for `cd_like`, `cd_play`, etc.) vs hardcoded Russian literals introduced in `LibraryScreen.kt`, `NowPlayingScreen.kt`, `TrackRow.kt`, `PlaylistDetailScreen.kt`.
- Owner: `app/src/main/res/values/strings.xml` and `app/src/main/res/values-ru/strings.xml`
- Scope and affected surfaces: `strings.xml`, `LibraryScreen.kt`, `NowPlayingScreen.kt`, `TrackRow.kt`, `PlaylistDetailScreen.kt`
- Uncertainty: none

## Design decision

Move all newly added hardcoded Russian UI strings into `res/values/strings.xml` (English default, matching the rest of the app) and provide `res/values-ru/strings.xml` (Russian translation when the phone locale is Russian), eliminating mixed-language screens.

## Reuse

- `stringResource(R.string.*)` in Compose
- Exemplar: `app/src/main/java/fun/tryliner/android/ui/components/TrackRow.kt:182`

## Changes

1. `app/src/main/res/values/strings.xml` & `app/src/main/res/values-ru/strings.xml`
   - Change: Add string resources for:
     - `downloads_title` (`"Downloads"` / `"Скачанные"`)
     - `now_playing_header` (`"NOW PLAYING"` / `"СЕЙЧАС ИГРАЕТ"`)
     - `now_playing_remote_header` (`"PLAYING ON PC"` / `"ИГРАЕТ НА ПК"`)
     - `lyrics_preview_title` (`"Lyrics preview"` / `"Предпросмотр текста"`)
     - `lyrics_preview_tap` (`"Tap to open lyrics"` / `"Нажмите, чтобы открыть текст"`)
     - `lyrics_loading` (`"Loading lyrics..."` / `"Загрузка текста..."`)
     - `delete_download_title`, `delete_download_message`, `delete_playlist_downloads_title`, `delete_playlist_downloads_message`, `action_delete`, `action_cancel`
   - Preserve: All existing `cd_*` and `app_name` (`vasyan`) resources.
   - Verify: Resource compilation succeeds with zero missing translations.
2. `LibraryScreen.kt`, `NowPlayingScreen.kt`, `TrackRow.kt`, `PlaylistDetailScreen.kt`
   - Change: Replace hardcoded Russian literals with `stringResource(R.string.*)`.
   - Preserve: All layout, actions, and dialog behaviors.
   - Verify: English-locale devices render 100% English UI; Russian-locale devices render Russian strings for all extracted entries.

## Scope

- Inherit: `LibraryScreen`, `NowPlayingScreen`, `TrackRow`, `PlaylistDetailScreen`.
- Verify: `app_name` remains `vasyan` in both locales.
- Exclude: Do not rename `vasyan` branding or LAN Connect protocol strings.

## Validation

- Product: Inspect `LibraryScreen` ("Downloads" / "Скачанные"), `NowPlayingScreen` header + lyrics preview card, and delete-download confirmation dialogs.
- Interface: Confirm `"NOW PLAYING"` / `"СЕЙЧАС ИГРАЕТ"` fits on 1 line at standard font scale.
- System: Zero mixed-language hardcoded literals in the 4 edited files.
- Repository: `.\gradlew.bat assembleDebug` → `BUILD SUCCESSFUL`.

## Stop conditions

- Stop if `values-ru/strings.xml` conflicts with any existing localization setup.

## Design documentation

- After acceptance and validation: none.
