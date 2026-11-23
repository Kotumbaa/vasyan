package `fun`.tryliner.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import `fun`.tryliner.android.ui.theme.LinerMotion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.playback.TrackDownloadManager
import `fun`.tryliner.android.playback.TrackDownloadState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.tryliner.android.R
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isLiked: Boolean,
    isPlayingCurrent: Boolean,
    onClick: () -> Unit,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val openTrackMenu = LocalTrackMenuOpener.current
    val rowShape = RoundedCornerShape(10.dp)
    val coverShape = LinerShapes.Tile
    val rowBg = if (isPlayingCurrent) LinerColors.Fill else Color.Transparent
    val rowModifier = modifier
        .fillMaxWidth()
        .clip(rowShape)
        .background(rowBg)
        .combinedClickable(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            onLongClick = openTrackMenu?.let { opener ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    opener(track)
                }
            }
        )
        .padding(horizontal = 10.dp, vertical = 7.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            LinerCoverArt(
                url = track.cover?.url ?: track.cover?.fallbackUrl,
                contentDescription = track.title,
                size = 44.dp,
                shape = coverShape
            )
            if (isPlayingCurrent) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(coverShape)
                        .background(LinerColors.AmbientBase.copy(alpha = 0.56f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = LinerColors.TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (isPlayingCurrent) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = LinerColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (track.explicit == true) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .padding(horizontal = 4.dp, vertical = 0.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "E",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
                Text(
                    text = track.artistNames.ifBlank { "Неизвестный артист" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LinerColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (track.durationMs > 0L) {
            Text(
                text = formatDurationMs(track.durationMs),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = LinerColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }

        DownloadButton(track = track, buttonSize = 32.dp, iconSize = 17.dp)

        val heartScale = remember { Animatable(1f) }
        LaunchedEffect(isLiked) {
            if (isLiked) {
                heartScale.animateTo(1.22f, tween(90, easing = LinerMotion.EaseOut))
                heartScale.animateTo(1f, LinerMotion.bounceSpring())
            } else {
                heartScale.snapTo(1f)
            }
        }
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleLike()
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                painter = painterResource(
                    if (isLiked) R.drawable.ic_mgc_heart_fill else R.drawable.ic_mgc_heart_line
                ),
                contentDescription = stringResource(if (isLiked) R.string.cd_unlike else R.string.cd_like),
                tint = if (isLiked) LinerColors.TextPrimary else Color.White.copy(alpha = 0.36f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        scaleX = heartScale.value
                        scaleY = heartScale.value
                    }
            )
        }

        if (openTrackMenu != null) {
            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    openTrackMenu(track)
                },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Действия",
                    tint = Color.White.copy(alpha = 0.44f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/** Provided in LinerApp so every track row can offer offline downloads. */
val LocalTrackDownloads = staticCompositionLocalOf<TrackDownloadManager?> { null }

@Composable
fun DownloadButton(
    track: Track,
    modifier: Modifier = Modifier,
    iconSize: Dp = 19.dp,
    buttonSize: Dp = 36.dp,
) {
    val manager = LocalTrackDownloads.current ?: return
    val states by manager.states.collectAsStateWithLifecycle()
    val state = states[track.id]
    var confirmDelete by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            when (state) {
                TrackDownloadState.Downloaded -> confirmDelete = true
                is TrackDownloadState.Downloading -> manager.cancel(track.id)
                else -> manager.download(track)
            }
        },
        modifier = modifier.size(buttonSize)
    ) {
        when (state) {
            is TrackDownloadState.Downloading -> {
                val smoothProgress by animateFloatAsState(
                    targetValue = state.progress,
                    animationSpec = LinerMotion.easeOutTween(180),
                    label = "trackDownloadProgress"
                )
                Box(contentAlignment = Alignment.Center) {
                    if (state.progress <= 0f) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(iconSize),
                            strokeWidth = 2.dp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { smoothProgress },
                            modifier = Modifier.size(iconSize),
                            strokeWidth = 2.dp,
                            color = Color.White.copy(alpha = 0.85f),
                            trackColor = Color.White.copy(alpha = 0.15f)
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_mgc_close_line),
                        contentDescription = "Отменить загрузку",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(iconSize * 0.5f)
                    )
                }
            }
            TrackDownloadState.Downloaded -> Icon(
                painter = painterResource(R.drawable.ic_mgc_check_line),
                contentDescription = "Скачано",
                tint = LinerColors.AccentPrimary,
                modifier = Modifier.size(iconSize)
            )
            is TrackDownloadState.Failed -> Icon(
                painter = painterResource(R.drawable.ic_mgc_warning_line),
                contentDescription = "Ошибка загрузки, повторить",
                tint = LinerColors.AccentRed,
                modifier = Modifier.size(iconSize)
            )
            null -> Icon(
                painter = painterResource(R.drawable.ic_mgc_download_line),
                contentDescription = "Скачать",
                tint = LinerColors.TextTertiary,
                modifier = Modifier.size(iconSize)
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_download_title)) },
            text = { Text(stringResource(R.string.delete_download_message, track.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    manager.delete(track.id)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
