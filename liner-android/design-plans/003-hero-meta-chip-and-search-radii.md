# Align Entity Hero Meta Chip, Search Radii & Mingcute Search Icons

Written against: `a58c81a`

## Evidence chain

- Surface: `PlaylistDetailScreen` (`app/src/main/java/fun/tryliner/android/ui/playlist/PlaylistDetailScreen.kt:290`), `SearchScreen` (`app/src/main/java/fun/tryliner/android/ui/search/SearchScreen.kt:154,163,171,198`), `LibraryScreen` (`app/src/main/java/fun/tryliner/android/ui/library/LibraryScreen.kt:515`)
- Problem:
  1. `PlaylistDetailScreen.kt:290` renders playlist count/duration (`"6 tracks · 13:10"`) as bare text instead of a `HeroGlass` chip (`9.dp` radius).
  2. `SearchScreen.kt:171,198` uses `RoundedCornerShape(999.dp)` on the search input and filter chips instead of `LinerShapes.Item` (`12.dp`) and `LinerShapes.Control` (`8.dp`).
  3. `SearchScreen.kt:154,163` and `LibraryScreen.kt` search pill use Material `Icons.Rounded.Search` / `Clear` instead of the bundled Mingcute drawables `ic_mgc_search_line` / `ic_mgc_close_line`.
- Design evidence: `liner-desktop/docs/DESIGN.md:67-72, 99` (`rounded-control` `8px`, `rounded-item` `12px`, hero chips `9px`, `HERO_CHIP_CLASS: count · duration`); `Shape.kt:10-13`; `res/drawable/ic_mgc_search_line.xml`, `res/drawable/ic_mgc_close_line.xml`.
- Owner: `LinerShapes`, `LinerColors.HeroGlass`, `res/drawable/ic_mgc_*`
- Scope and affected surfaces: `PlaylistDetailScreen.kt`, `SearchScreen.kt`, `LibraryScreen.kt`
- Uncertainty: none

## Design decision

Format the playlist hero metadata as a `LinerColors.HeroGlass` chip with `LinerShapes.HeroButton` (`9.dp`), align `SearchScreen` input/filter radii to `LinerShapes.Item` (`12.dp`) and `LinerShapes.Control` (`8.dp`), and replace remaining Material search/clear icons with `ic_mgc_search_line` and `ic_mgc_close_line`.

## Reuse

- `LinerShapes.HeroButton` (`9.dp`), `LinerShapes.Item` (`12.dp`), `LinerShapes.Control` (`8.dp`)
- `LinerColors.HeroGlass` (`Color.kt:33`)
- `R.drawable.ic_mgc_search_line`, `R.drawable.ic_mgc_close_line`

## Changes

1. `app/src/main/java/fun/tryliner/android/ui/playlist/PlaylistDetailScreen.kt:290`
   - Change: Wrap `"${state.tracks.size} tracks$durationText"` in `Surface(shape = LinerShapes.HeroButton, color = LinerColors.HeroGlass)` with `padding(horizontal = 10.dp, vertical = 4.dp)` and `LinerColors.TextSecondary`.
   - Preserve: Track count and duration formatting.
   - Verify: Hero metadata renders as a compact `9.dp` glass chip matching the `Play all` / `Shuffle` / `Download` button row.
2. `app/src/main/java/fun/tryliner/android/ui/search/SearchScreen.kt:152-209`
   - Change: Set `OutlinedTextField` `shape = LinerShapes.Item` (`12.dp`), filter chip `shape = LinerShapes.Control` (`8.dp`), `leadingIcon` to `painterResource(R.drawable.ic_mgc_search_line)` (`18.dp`), and `trailingIcon` clear button to `painterResource(R.drawable.ic_mgc_close_line)` (`18.dp`).
   - Preserve: Search query state, filter selection, and active chip contrast.
   - Verify: Search input and chips follow `DESIGN.md` radii and Mingcute icon style.
3. `app/src/main/java/fun/tryliner/android/ui/library/LibraryScreen.kt` (`PlaylistSearchPill`)
   - Change: Replace `Icons.Rounded.Search` / `Icons.Rounded.Clear` with `R.drawable.ic_mgc_search_line` and `R.drawable.ic_mgc_close_line`, and use `LinerShapes.Item` (`12.dp`) on `PlaylistSearchPill`.
   - Preserve: In-playlist filtering and clear action.
   - Verify: Search bars in `SearchScreen` and `LibraryScreen` are visually identical.

## Scope

- Inherit: `PlaylistDetailScreen`, `SearchScreen`, `LibraryScreen`.
- Verify: `SearchFilter` chips wrap and scroll smoothly at `8.dp` radius.
- Exclude: `HomeScreen` quick-access tiles (already `LinerShapes.Control` `8.dp`).

## Validation

- Product: Open Search, type a query, clear it, and switch filters; open a playlist and inspect the track-count chip.
- Interface: Verify 0-track and long-title playlists do not clip the hero meta chip.
- System: No `Icons.Rounded.Search` or `Icons.Rounded.Clear` remaining in `SearchScreen` or `LibraryScreen`.
- Repository: `.\gradlew.bat assembleDebug` → `BUILD SUCCESSFUL`.

## Stop conditions

- Stop if `ic_mgc_search_line.xml` or `ic_mgc_close_line.xml` fails to compile as a `painterResource`.

## Design documentation

- After acceptance and validation: none.
