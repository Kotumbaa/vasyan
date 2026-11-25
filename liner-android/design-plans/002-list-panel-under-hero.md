# Add Glass `ListPanel` Container Under Playlist & Library Track Lists

Written against: `a58c81a`

## Evidence chain

- Surface: `PlaylistDetailScreen` (`app/src/main/java/fun/tryliner/android/ui/playlist/PlaylistDetailScreen.kt:201-215`), `LibraryScreen` (`app/src/main/java/fun/tryliner/android/ui/library/LibraryScreen.kt:340-415`)
- Problem: Track rows in `PlaylistDetailScreen` and `LibraryScreen` render directly over the full-screen gradient backdrop without a `bg-fill-subtle` (`LinerColors.FillSubtle`) list panel container, breaking visual separation and diverging from `HomeScreen`'s `Trending Now` panels.
- Design evidence: `liner-desktop/docs/DESIGN.md:50-51, 98-101` ("List panel under a hero: `ListPanel` from `@/shared/ui` (`bg-fill-subtle`) ... Under it, the glass list panel with virtualised `SongCardWithMenu` rows"), `app/src/main/java/fun/tryliner/android/ui/theme/Color.kt:29` (`val FillSubtle = Color(0x0AFFFFFF)`), `app/src/main/java/fun/tryliner/android/ui/theme/Shape.kt:13` (`val Item = RoundedCornerShape(12.dp)`).
- Owner: `LinerColors.FillSubtle` & `LinerShapes.Item`
- Scope and affected surfaces: `PlaylistDetailScreen.kt`, `LibraryScreen.kt`
- Uncertainty: none

## Design decision

Wrap `TrackRow` items in `PlaylistDetailScreen` (and the Downloaded / Liked Tracks sections in `LibraryScreen`) inside rounded `LinerColors.FillSubtle` (`white @ 4%`) list containers (`LinerShapes.Item`, `12.dp`, `4.dp` inner vertical padding), matching `HomeScreen.kt`'s `Trending Now` column panels without breaking `LazyColumn` virtualization.

## Reuse

- `LinerColors.FillSubtle` (`Color.kt:29`)
- `LinerShapes.Item` (`Shape.kt:13`)
- Exemplar: `app/src/main/java/fun/tryliner/android/ui/home/HomeScreen.kt:247-253`

## Changes

1. `app/src/main/java/fun/tryliner/android/ui/playlist/PlaylistDetailScreen.kt:201-215`
   - Change: Give each `TrackRow` item a `LinerColors.FillSubtle` panel background using positional corner rounding inside the `LazyColumn` (top item `topStart/topEnd = 12.dp`, middle items `0.dp`, bottom item `bottomStart/bottomEnd = 12.dp`, or `12.dp` when `size == 1`), with `0.dp` vertical item spacing between contiguous rows inside the panel so it reads as one seamless `ListPanel` while staying 100% virtualized.
   - Preserve: `TrackRow` click, like, download button, and `itemsIndexed` keys.
   - Verify: Track list under `EntityHeroHeader` sits inside a unified subtle glass panel (`white @ 4%`) with `12.dp` outer corners.
2. `app/src/main/java/fun/tryliner/android/ui/library/LibraryScreen.kt:340-415`
   - Change: Apply the same positional `LinerColors.FillSubtle` panel background (`12.dp` top/bottom outer corners) to the `downloadedTracks` and `filteredLikedTracks` sections.
   - Preserve: Section headers, search pill, and empty states.
   - Verify: Both "Скачанные" and "Liked Tracks" lists appear inside clean `bg-fill-subtle` panels.

## Scope

- Inherit: `PlaylistDetailScreen` (user playlists and Liked Tracks detail view) and `LibraryScreen`.
- Verify: `TrackRow` active-track highlight (`LinerColors.Fill`, `7%`) remains clearly visible on top of `FillSubtle` (`4%`).
- Exclude: `SearchScreen` mixed results (already grouped by type).

## Validation

- Product: Open any playlist and `Liked Tracks`; track list reads as a cohesive glass panel below the hero header.
- Interface: Test 0 tracks (empty state), 1 track (all 4 corners `12.dp`), and 20+ tracks (smooth scrolling, no corner seams).
- System: Uses existing `LinerColors.FillSubtle` and `LinerShapes.Item` tokens.
- Repository: `.\gradlew.bat assembleDebug` → `BUILD SUCCESSFUL`.

## Stop conditions

- Stop if `LazyColumn` item spacing in `PlaylistDetailScreen` cannot be set to `0.dp` for the list block without breaking header spacing.

## Design documentation

- After acceptance and validation: none.
