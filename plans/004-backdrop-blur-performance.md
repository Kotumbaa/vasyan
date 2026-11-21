# 004 — Remove Redundant GPU Blur on Pre-Blurred Ambient Bitmaps

- **Commit:** `bcb517b`
- **Severity:** MEDIUM (Performance)
- **Files:**
  - Update: `app/src/main/java/fun/tryliner/android/ui/components/LinerComponents.kt` (lines 75–92)
  - Update: `app/src/main/java/fun/tryliner/android/ui/player/NowPlayingScreen.kt` (lines 244–262)

## Problem
`CoverAmbientExtractor.kt:36-37,75` already downsamples cover art to `48x48` and runs a 2-pass `fastBoxBlur(safeBitmap, radius = 6)` on `Dispatchers.Default`. Yet both `AtmosphericBackdrop` (`LinerComponents.kt:80`) and `NowPlayingScreen` (`NowPlayingScreen.kt:248`) apply `Modifier.blur(56.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)` on top of `Crossfade`, forcing RenderEffect to blur two overlapping full-screen layers every frame during 600–650ms track-change crossfades.

## Exact Target Spec
- Remove `Modifier.blur(56.dp, ...)` from `AtmosphericBackdrop` (`LinerComponents.kt:80-90`) and `NowPlayingScreen` (`NowPlayingScreen.kt:248-258`) — `ContentScale.Crop` + `FilterQuality.High` bilinear upscaling of the `48x48` box-blurred bitmap already produces a smooth full-screen blur with zero RenderEffect overhead.
- If a slightly softer blur is desired, increase `BLUR_RADIUS` in `CoverAmbientExtractor.kt:37` from `6` to `8` (computed once per cover on `Dispatchers.Default`, cached in `LruCache`).

## Steps
1. Remove `Build.VERSION_CODES.S` `Modifier.blur(56.dp)` blocks in `LinerComponents.kt` and `NowPlayingScreen.kt` (and unused `BlurredEdgeTreatment` / `blur` imports).
2. Bump `BLUR_RADIUS = 8` in `CoverAmbientExtractor.kt:37`.

## Scope Boundary
Do not change gradient stops, alpha values, or `CoverAmbientExtractor` color sampling.

## Verification
- Build with `.\gradlew.bat assembleDebug`.
- **Feel check:** Rapidly skip 5 tracks in `NowPlayingScreen` with Android Profile GPU Rendering enabled; verify crossfade stays flat under the 16ms/8ms frame budget.
