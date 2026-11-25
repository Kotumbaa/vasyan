# 001 — Motion Tokens & Reduced Motion

- **Commit:** `bcb517b`
- **Severity:** MEDIUM (Cohesion & Accessibility)
- **Files:**
  - Create: `app/src/main/java/fun/tryliner/android/ui/theme/Motion.kt`
  - Update: `app/src/main/java/fun/tryliner/android/ui/components/LinerComponents.kt`
  - Update: `app/src/main/java/fun/tryliner/android/ui/components/SyncedLyricsView.kt`
  - Update: `app/src/main/java/fun/tryliner/android/ui/player/MiniPlayerBar.kt`
  - Update: `app/src/main/java/fun/tryliner/android/ui/player/NowPlayingScreen.kt`

## Problem
Easing curves, durations, and spring specs are duplicated inline across 5 files (`CubicBezierEasing(0.22f, 1f, 0.36f, 1f)` in `NowPlayingScreen.kt:685`, `tween(180..650)` and `spring(stiffness = 380f/400f, dampingRatio = 0.8f/0.85f)` across components), and none of the animations respect Android's system reduced-motion setting (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`).

## Exact Target Spec
Create `app/src/main/java/fun/tryliner/android/ui/theme/Motion.kt`:
```kotlin
package `fun`.tryliner.android.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

object LinerMotion {
    /** Desktop DESIGN.md default curve: [0.22, 1, 0.36, 1] */
    val EaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    const val DURATION_FAST = 140
    const val DURATION_NORMAL = 220
    const val DURATION_SHEET = 300
    const val DURATION_AMBIENT = 550

    fun <T> easeOutTween(durationMs: Int = DURATION_NORMAL): TweenSpec<T> =
        tween(durationMillis = durationMs, easing = EaseOut)

    fun <T> snapSpring(): SpringSpec<T> =
        spring(stiffness = 400f, dampingRatio = 0.82f)

    fun <T> bounceSpring(): SpringSpec<T> =
        spring(stiffness = 500f, dampingRatio = Spring.DampingRatioMediumBouncy)
}

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}
```

## Steps
1. Create `Motion.kt` with `LinerMotion` and `rememberReducedMotion()`.
2. Replace inline `CubicBezierEasing(0.22f, 1f, 0.36f, 1f)` in `NowPlayingScreen.kt:685` with `LinerMotion.EaseOut`.
3. Replace inline `tween(...)` and `spring(...)` calls in `LinerComponents.kt`, `SyncedLyricsView.kt`, `MiniPlayerBar.kt`, and `NowPlayingScreen.kt` with `LinerMotion.easeOutTween(...)` and `LinerMotion.snapSpring()`.
4. Where `rememberReducedMotion()` is `true`, pass `0` duration (`tween(0)`) to `animateColorAsState`, `AnimatedVisibility`, and `AnimatedContent` transitions.

## Scope Boundary
Do not change layout, colors, or gesture thresholds in this plan.

## Verification
- Run `.\gradlew.bat assembleDebug` and verify zero compilation errors.
- **Feel check:** Enable "Remove animations" in Android Developer Options / Accessibility and verify screen/player transitions snap cleanly with no sliding.
