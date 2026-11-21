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

fun Modifier.pressScale(
    interactionSource: InteractionSource,
    targetScale: Float = 0.97f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) targetScale else 1f,
        animationSpec = spring(stiffness = 500f, dampingRatio = 0.8f),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
