# 003 — Velocity-Aware MiniPlayer Swipe & Seamless Sheet Dismiss

- **Commit:** `bcb517b`
- **Severity:** HIGH (Interruptibility & Physicality)
- **Depends on:** `001-motion-tokens-and-reduced-motion.md`
- **Files:**
  - Update: `app/src/main/java/fun/tryliner/android/ui/player/MiniPlayerBar.kt` (lines 148–190)
  - Update: `app/src/main/java/fun/tryliner/android/ui/player/NowPlayingScreen.kt` (lines 207–238, 304–325)

## Problem
1. In `MiniPlayerBar.kt:162-177`, horizontal swipe only checks `totalDrag >= 48.dp`, ignoring flick velocity (`VelocityTracker`). A quick short flick snaps back instead of skipping tracks, and there is no subtle opacity feedback during drag.
2. In `NowPlayingScreen.kt:228-232`, `dismissOffsetY = 0f` is reset immediately after `onCollapse()`, causing the sheet to spring back upward for 1–2 frames while `AnimatedVisibility` slides it downward. Also ignores flick velocity.

## Exact Target Spec
- **`MiniPlayerBar.kt`**:
  - Track horizontal velocity with `androidx.compose.ui.input.pointer.util.VelocityTracker` inside `pointerInput`.
  - Trigger `mainNext()` if `totalDrag <= -swipeThresholdPx || velocityX <= -450f`, and `mainPrevious()` if `totalDrag >= swipeThresholdPx || velocityX >= 450f`.
  - Apply subtle alpha fade during drag: `alpha = (1f - (abs(animatedOffsetX) / (swipeThresholdPx * 2.5f))).coerceIn(0.65f, 1f)`.
- **`NowPlayingScreen.kt`**:
  - Track vertical velocity with `VelocityTracker` in both root and top-bar `detectVerticalDragGestures`.
  - Trigger `onCollapse()` if `dismissOffsetY >= dismissThresholdPx || velocityY >= 650f`.
  - When `onCollapse()` is triggered, set `isCollapsing = true` (do **not** reset `dismissOffsetY = 0f`) so `translationY` stays at the user's release position while `AnimatedVisibility` finishes sliding out. Reset `dismissOffsetY = 0f` and `isCollapsing = false` in `DisposableEffect(Unit) { onDispose { ... } }`.

## Steps
1. Add `VelocityTracker` to `MiniPlayerBar.kt` horizontal drag handler and apply `graphicsLayer { translationX = animatedOffsetX; alpha = dragAlpha }`.
2. Add `VelocityTracker` and `isCollapsing` guard to `NowPlayingScreen.kt` vertical dismiss drag handlers so release-to-dismiss never snaps back upward.

## Scope Boundary
Only gesture tracking and drag transform in `MiniPlayerBar.kt` and `NowPlayingScreen.kt`.

## Verification
- Build with `.\gradlew.bat assembleDebug` and install on device.
- **Feel check:** Perform fast short flicks left/right on `MiniPlayerBar` (must skip tracks reliably); swipe down slowly past threshold and release on `NowPlayingScreen` (must continue downward smoothly with zero upward bounce).
