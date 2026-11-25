# Unify Liked Heart Accent to `LinerColors.AccentRed`

Written against: `a58c81a`

## Evidence chain

- Surface: `MiniPlayerBar` (`app/src/main/java/fun/tryliner/android/ui/player/MiniPlayerBar.kt:290`), `NowPlayingScreen` (`app/src/main/java/fun/tryliner/android/ui/player/NowPlayingScreen.kt:525`), `TrackRow` (`app/src/main/java/fun/tryliner/android/ui/components/TrackRow.kt:183`)
- Problem: The active liked heart icon renders in `#FF4D4D` (`LinerColors.AccentRed`) inside `TrackRow`, while `MiniPlayerBar` and `NowPlayingScreen` hardcode Spotify green `Color(0xFF1ED760)` for the exact same `isLiked == true` state.
- Design evidence: `liner-desktop/docs/DESIGN.md:30` (`Accent | accent-primary (liked heart, active state)`), `liner-desktop/src/app/globals.css:172` (`--accent-primary: #ff4d4d`), `app/src/main/java/fun/tryliner/android/ui/theme/Color.kt:23` (`val AccentRed = Color(0xFFFF4D4D)`).
- Owner: `LinerColors` in `app/src/main/java/fun/tryliner/android/ui/theme/Color.kt`
- Scope and affected surfaces: `MiniPlayerBar.kt`, `NowPlayingScreen.kt`
- Uncertainty: none

## Design decision

Replace the hardcoded `Color(0xFF1ED760)` on the liked heart icon in `MiniPlayerBar` and `NowPlayingScreen` with `LinerColors.AccentRed` so every liked-track indicator across lists and players uses the same documented accent token.

## Reuse

- `LinerColors.AccentRed` (`app/src/main/java/fun/tryliner/android/ui/theme/Color.kt:23`)
- Exemplar: `app/src/main/java/fun/tryliner/android/ui/components/TrackRow.kt:183`

## Changes

1. `app/src/main/java/fun/tryliner/android/ui/player/MiniPlayerBar.kt:290`
   - Change: Replace `tint = if (isLiked) Color(0xFF1ED760) else Color.White.copy(alpha = 0.72f)` with `tint = if (isLiked) LinerColors.AccentRed else Color.White.copy(alpha = 0.72f)`.
   - Preserve: `Animatable` heart spring pop (`1.22f -> 1f`) and `ic_mgc_heart_fill` / `ic_mgc_heart_line` icons.
   - Verify: Liking a track in `MiniPlayerBar` renders `#FF4D4D`, matching `TrackRow`.
2. `app/src/main/java/fun/tryliner/android/ui/player/NowPlayingScreen.kt:525`
   - Change: Replace `tint = if (isLiked) Color(0xFF1ED760) else Color.White.copy(alpha = 0.75f)` with `tint = if (isLiked) LinerColors.AccentRed else Color.White.copy(alpha = 0.75f)`.
   - Preserve: `Animatable` heart spring pop and `26.dp` icon sizing.
   - Verify: Opening `NowPlayingScreen` for a liked track shows `#FF4D4D` on the heart icon.

## Scope

- Inherit: All tracks shown in `MiniPlayerBar` and `NowPlayingScreen`.
- Verify: `TrackRow` remains `#FF4D4D`; remote LAN Connect green status indicator (`#1ED760` on `Computer` icon) is intentionally separate and left untouched.
- Exclude: Do not change `TrackRow.kt` or LAN Connect status colors.

## Validation

- Product: Like/unlike a track from a playlist, mini-player, and fullscreen player; heart color stays consistent (`#FF4D4D`) across all three surfaces.
- Interface: Both liked (`ic_mgc_heart_fill`) and unliked (`ic_mgc_heart_line`) states remain legible against cover-tinted backgrounds.
- System: Zero hardcoded `#1ED760` remaining on liked heart icons.
- Repository: `.\gradlew.bat assembleDebug` → `BUILD SUCCESSFUL`.

## Stop conditions

- Stop if `LinerColors.AccentRed` is removed or renamed in `Color.kt`.

## Design documentation

- After acceptance and validation: none (`Color.kt` already documents `AccentRed` as `--accent-primary`).
