package `fun`.tryliner.android.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

object LinerMotion {
    /** Desktop DESIGN.md default curve: [0.22, 1, 0.36, 1] */
    val EaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    const val DURATION_FAST = 140
    const val DURATION_NORMAL = 220
    const val DURATION_SHEET = 300
    const val DURATION_AMBIENT = 550

    fun <T> easeOutTween(durationMs: Int = DURATION_NORMAL): TweenSpec<T> =
        tween(durationMillis = durationMs, easing = EaseOut)

    /** Apple WWDC critically damped default (damping 1.0, zero overshoot). */
    fun <T> appleDefaultSpring(): SpringSpec<T> =
        spring(stiffness = 420f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** Apple WWDC momentum spring (damping ~0.82, slight physical settle after flick). */
    fun <T> appleMomentumSpring(): SpringSpec<T> =
        spring(stiffness = 380f, dampingRatio = 0.82f)

    fun <T> snapSpring(): SpringSpec<T> =
        spring(stiffness = 400f, dampingRatio = 0.82f)

    fun <T> bounceSpring(): SpringSpec<T> =
        spring(stiffness = 500f, dampingRatio = Spring.DampingRatioMediumBouncy)

    /**
     * Apple "Designing Fluid Interfaces" exponential-decay momentum projection:
     * projects resting offset from release velocity.
     */
    fun projectMomentum(velocityPerSec: Float, decelerationRate: Float = 0.992f): Float {
        return (velocityPerSec / 1000f) * decelerationRate / (1f - decelerationRate)
    }

    /**
     * Apple soft-boundary rubber-banding past an edge.
     */
    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }
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

fun Modifier.pressScale(
    interactionSource: InteractionSource,
    targetScale: Float = 0.97f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) targetScale else 1f,
        animationSpec = spring(stiffness = 700f, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
