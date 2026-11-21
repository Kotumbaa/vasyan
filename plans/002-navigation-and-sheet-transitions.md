# 002 — Crisp Navigation & NowPlaying Sheet Transitions

- **Commit:** `bcb517b`
- **Severity:** HIGH (Easing & Duration)
- **Depends on:** `001-motion-tokens-and-reduced-motion.md`
- **Files:**
  - Update: `app/src/main/java/fun/tryliner/android/ui/LinerApp.kt` (lines 254–322)

## Problem
1. `NavHost` (`LinerApp.kt:254-315`) has no `enterTransition` / `exitTransition`, so Navigation Compose defaults to `fadeIn(tween(700))` / `fadeOut(tween(700))`. Switching between `Home`, `Search`, and `Library` tabs feels sluggish on a 100+/day action.
2. `AnimatedVisibility` for `NowPlayingScreen` (`LinerApp.kt:318-322`) uses default `slideInVertically` / `slideOutVertically` spring without explicit `EaseOut` timing or fade.

## Current Code (`LinerApp.kt:254-260, 318-322`)
```kotlin
NavHost(
    navController = navController,
    startDestination = LinerRoutes.HOME,
    modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
) { ... }

AnimatedVisibility(
    visible = isNowPlayingOpen && (currentTrack != null || activeRemote?.track != null),
    enter = slideInVertically(initialOffsetY = { it }),
    exit = slideOutVertically(targetOffsetY = { it })
) { ... }
```

## Exact Target Spec
- **Tab routes (`HOME`, `SEARCH`, `LIBRARY`)**:
  - `enterTransition`: `fadeIn(tween(if (reducedMotion) 0 else 140, easing = LinerMotion.EaseOut))`
  - `exitTransition`: `fadeOut(tween(if (reducedMotion) 0 else 110, easing = LinerMotion.EaseOut))`
- **Detail route (`PLAYLIST_DETAIL`)**:
  - `enterTransition`: `slideInHorizontally(tween(220, easing = LinerMotion.EaseOut)) { it / 8 } + fadeIn(tween(180, easing = LinerMotion.EaseOut))`
  - `popExitTransition`: `slideOutHorizontally(tween(200, easing = LinerMotion.EaseOut)) { it / 8 } + fadeOut(tween(160, easing = LinerMotion.EaseOut))`
- **`NowPlayingScreen` `AnimatedVisibility`**:
  - `enter`: `slideInVertically(tween(if (reducedMotion) 0 else 300, easing = LinerMotion.EaseOut)) { it } + fadeIn(tween(180, easing = LinerMotion.EaseOut))`
  - `exit`: `slideOutVertically(tween(if (reducedMotion) 0 else 240, easing = LinerMotion.EaseOut)) { it } + fadeOut(tween(180, easing = LinerMotion.EaseOut))`

## Steps
1. Read `reducedMotion = rememberReducedMotion()` in `LinerAppContent`.
2. Add explicit `enterTransition` / `exitTransition` on `NavHost` and `popEnterTransition` / `popExitTransition` on `PLAYLIST_DETAIL`.
3. Update `AnimatedVisibility` for `NowPlayingScreen` with the exact `300ms` / `240ms` `LinerMotion.EaseOut` specs.

## Scope Boundary
Only `LinerApp.kt` navigation & sheet transition specs; do not alter routes or ViewModels.

## Verification
- Build with `.\gradlew.bat assembleDebug`.
- **Feel check:** Rapidly tap `Home -> Search -> Library -> Home`; tab swaps should feel immediate (<150ms) with zero sluggish overlap. Open a playlist and press Back; verify subtle horizontal slide without jarring jump.
