package `fun`.tryliner.android.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes
import kotlin.math.max

@Composable
fun AtmosphericBackdrop(
    modifier: Modifier = Modifier,
    tintColor: Color = LinerColors.AmbientBlue,
    coverUrl: String? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val coverAmbient = rememberCoverAmbient(coverUrl)
    val animatedTint by animateColorAsState(
        targetValue = coverAmbient?.backdropColor ?: tintColor,
        animationSpec = tween(durationMillis = 650),
        label = "atmosphericBackdropTint"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LinerColors.AmbientBase)
    ) {
        val blurredBitmap = coverAmbient?.blurredBitmap
        if (blurredBitmap != null) {
            Crossfade(
                targetState = blurredBitmap,
                animationSpec = tween(durationMillis = 650),
                label = "atmosphericCoverBlur"
            ) { bitmap ->
                val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.blur(48.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                } else {
                    Modifier
                }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.24f)
                        .then(blurModifier)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val verticalEnd = size.height * 0.56f
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.0f to animatedTint.copy(alpha = 0.72f),
                            0.30f to animatedTint.copy(alpha = 0.28f),
                            0.56f to LinerColors.AmbientBase.copy(alpha = 0.96f),
                            1.0f to LinerColors.AmbientBase,
                            startY = 0f,
                            endY = verticalEnd
                        )
                    )

                    val primaryRadius = max(size.width, size.height) * 0.70f
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                animatedTint.copy(alpha = 0.42f),
                                animatedTint.copy(alpha = 0.12f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.25f, size.height * 0.06f),
                            radius = primaryRadius
                        )
                    )

                    val secondaryRadius = max(size.width, size.height) * 0.52f
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                animatedTint.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.88f, size.height * 0.12f),
                            radius = secondaryRadius
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
                        LinerColors.LikedVioletStart,
                        LinerColors.LikedVioletEnd
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Favorite,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(size * 0.42f)
        )
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
            imageVector = Icons.Rounded.MusicNote,
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
