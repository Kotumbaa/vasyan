# 005 — Player Polish & Tactile Micro-Interactions

- **Commit:** `bcb517b`
- **Severity:** LOW + Missed Opportunities 1–4
- **Depends on:** `001-motion-tokens-and-reduced-motion.md`
- **Files:**
  - Update: `app/src/main/java/fun/tryliner/android/ui/player/NowPlayingScreen.kt` (artwork/lyrics `AnimatedContent`, Like button, Play button, `MinimalScrubber`)
  - Update: `app/src/main/java/fun/tryliner/android/ui/components/TrackRow.kt` (`DownloadButton`, Like button)
  - Update: `app/src/main/java/fun/tryliner/android/ui/home/HomeScreen.kt` (`QuickAccessTile`, `DailyMixCard`, `SquareTrackCard`)

## Problem
1. `NowPlayingScreen` artwork ↔ lyrics switch (`NowPlayingScreen.kt:412`) is a flat fade without subtle scale landing.
2. Home cards and Play/Pause button have no tactile press scale.
3. Like heart toggles color abruptly without a spring pop.
4. `MinimalScrubber` (`NowPlayingScreen.kt:755`) does not visually expand while actively dragging.
5. `DownloadButton` progress ring (`TrackRow.kt:209`) jumps discretely every 512KB chunk.

## Exact Target Spec
1. **Artwork ↔ Lyrics `AnimatedContent` (`NowPlayingScreen.kt:412-415`)**:
   - `enter`: `fadeIn(tween(220, easing = LinerMotion.EaseOut)) + scaleIn(tween(220, easing = LinerMotion.EaseOut), initialScale = 0.96f)`
   - `exit`: `fadeOut(tween(160, easing = LinerMotion.EaseOut)) + scaleOut(tween(160, easing = LinerMotion.EaseOut), targetScale = 0.98f)`
2. **Press-scale modifier** (helper `Modifier.pressScale(target = 0.97f)` using `MutableInteractionSource.collectIsPressedAsState()` + `animateFloatAsState(spring(stiffness = 500f, dampingRatio = 0.8f))`):
   - Apply to `QuickAccessTile`, `DailyMixCard`, `SquareTrackCard` in `HomeScreen.kt`, and the `64.dp` Play/Pause button in `NowPlayingScreen.kt`.
3. **Heart Like spring pop**:
   - Trigger `Animatable(1f)` on `isLiked` transition to `true`: `animateTo(1.22f, tween(90))` then `animateTo(1f, LinerMotion.bounceSpring())` on the heart `Icon` in `TrackRow.kt`, `MiniPlayerBar.kt`, and `NowPlayingScreen.kt`.
4. **Scrubber active drag expansion (`MinimalScrubber`)**:
   - Track `isDragging: Boolean`; animate `trackHeight` (`3.dp` -> `5.dp`) and `thumbRadius` (`5.5.dp` -> `8.dp`) via `animateDpAsState(tween(120, easing = LinerMotion.EaseOut))`.
5. **Smooth download progress (`TrackRow.kt:209` & `PlaylistDetailScreen.kt:415`)**:
   - Wrap `state.progress` in `animateFloatAsState(targetValue = state.progress, animationSpec = tween(180, easing = LinerMotion.EaseOut))`.

## Steps
1. Add `pressScale` helper in `LinerComponents.kt` (or `Motion.kt`) and apply to Home cards and NowPlaying Play button.
2. Update `AnimatedContent` in `NowPlayingScreen.kt` with `scaleIn(0.96f)` / `scaleOut(0.98f)`.
3. Add Like heart spring pop and `MinimalScrubber` active-drag expansion.
4. Smooth `DownloadButton` and `PlaylistDownloadButton` progress with `animateFloatAsState`.

## Scope Boundary
Do not change audio/download logic or layout proportions.

## Verification
- Build with `.\gradlew.bat assembleDebug` and test on device.
- **Feel check:** Tap Like on a track (crisp subtle pop, no overshoot jitter), drag the scrubber thumb (smooth expansion under finger), toggle artwork ↔ lyrics, and download a track (fluid ring fill).
