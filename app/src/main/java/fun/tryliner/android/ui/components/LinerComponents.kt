package `fun`.tryliner.android.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import `fun`.tryliner.android.ui.theme.LinerMotion
import `fun`.tryliner.android.ui.theme.rememberReducedMotion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import `fun`.tryliner.android.R
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes
import kotlin.math.max

val LocalAppHazeState = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun AtmosphericBackdrop(
    modifier: Modifier = Modifier,
    tintColor: Color = LinerColors.AmbientBlue,
    coverUrl: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val coverAmbient = rememberCoverAmbient(coverUrl)
    val reducedMotion = rememberReducedMotion()
    val ambientDuration = if (reducedMotion) 0 else LinerMotion.DURATION_AMBIENT
    val animatedTint by animateColorAsState(
        targetValue = coverAmbient?.backdropColor ?: tintColor,
        animationSpec = LinerMotion.easeOutTween(ambientDuration),
        label = "atmosphericBackdropTint"
    )
    val fullScreenBase = LinerColors.AmbientBase
    val softTopTint = lerp(LinerColors.AmbientBase, animatedTint, 0.62f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(fullScreenBase)
    ) {
        val blurredBitmap = coverAmbient?.blurredBitmap
        if (blurredBitmap != null) {
            Crossfade(
                targetState = blurredBitmap,
                animationSpec = LinerMotion.easeOutTween(ambientDuration),
                label = "atmosphericCoverBlur"
            ) { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.22f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // Desaturated top ambient glow gently dissolving into #0D0F14 across 55% height
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to softTopTint.copy(alpha = 0.78f),
                            0.28f to softTopTint.copy(alpha = 0.42f),
                            0.58f to fullScreenBase.copy(alpha = 0.94f),
                            1.00f to fullScreenBase,
                            startY = 0f,
                            endY = size.height
                        )
                    )

                    val maxDim = max(size.width, size.height)
                    // Primary top-left desaturated ambient glow
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                animatedTint.copy(alpha = 0.34f),
                                animatedTint.copy(alpha = 0.10f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.24f, size.height * 0.06f),
                            radius = maxDim * 0.72f
                        )
                    )

                    // Subtle secondary upper-right ambient glow
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                animatedTint.copy(alpha = 0.20f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.86f, size.height * 0.26f),
                            radius = maxDim * 0.60f
                        )
                    )
                },
            content = content
        )
    }
}

@Composable
fun LinerPanel(
    modifier: Modifier = Modifier,
    shape: Shape = LinerShapes.Item,
    containerColor: Color = LinerColors.HeroGlass,
    borderColor: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        border = if (borderColor == Color.Transparent) null else BorderStroke(0.5.dp, borderColor)
    ) {
        Box(content = content)
    }
}

@Composable
fun LinerChip(
    text: String,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = LinerShapes.Pill,
        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.08f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) LinerColors.AmbientBase else LinerColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun LinerGlassPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.White.copy(alpha = 0.08f),
    contentColor: Color = LinerColors.TextPrimary,
    borderColor: Color = Color.Transparent,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        shape = LinerShapes.Pill,
        border = if (borderColor == Color.Transparent) BorderStroke(0.dp, Color.Transparent) else BorderStroke(0.5.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun LinerCoverArt(
    url: String?,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = LinerShapes.Control
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(LinerColors.BgPanel),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrBlank()) {
            CoverPlaceholder()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { CoverPlaceholder() },
                error = { CoverPlaceholder() }
            )
        }
    }
}

@Composable
fun LikedTracksArtwork(
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = LinerShapes.Control
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF232735),
                        Color(0xFF151822),
                        Color(0xFF0E1017)
                    )
                )
            )
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(this.size.width * 0.28f, this.size.height * 0.24f),
                        radius = this.size.maxDimension * 0.85f
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mgc_heart_fill),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(size * 0.40f)
        )
    }
}

@Composable
fun LinerShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.13f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = alpha))
    )
}

@Composable
fun TrackListSkeleton(
    count: Int = 7,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(count) { idx ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinerShimmerBox(
                    modifier = Modifier.size(44.dp),
                    shape = LinerShapes.Tile
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LinerShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(if (idx % 2 == 0) 0.62f else 0.48f)
                            .height(13.dp)
                    )
                    LinerShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth(if (idx % 2 == 0) 0.38f else 0.52f)
                            .height(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LinerColors.BgPanel),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mgc_music_fill),
            contentDescription = null,
            tint = LinerColors.TextTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

fun formatDurationMs(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
