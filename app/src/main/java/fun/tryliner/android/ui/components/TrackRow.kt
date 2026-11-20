package `fun`.tryliner.android.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.tryliner.android.R
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.ui.theme.LinerColors

@Composable
fun TrackRow(
    track: Track,
    isLiked: Boolean,
    isPlayingCurrent: Boolean,
    onClick: () -> Unit,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowShape = RoundedCornerShape(10.dp)
    val coverShape = RoundedCornerShape(6.dp)
    val rowBg = if (isPlayingCurrent) Color.White.copy(alpha = 0.07f) else Color.Transparent
    val rowModifier = modifier
        .fillMaxWidth()
        .clip(rowShape)
        .background(rowBg)
        .clickable(onClick = onClick)
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
                        .background(Color.Black.copy(alpha = 0.52f)),
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
                    text = track.artistNames.ifBlank { "Unknown Artist" },
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
                style = MaterialTheme.typography.bodySmall,
                color = LinerColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        DownloadButton(track = track)

        IconButton(
            onClick = onToggleLike,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(if (isLiked) R.string.cd_unlike else R.string.cd_like),
                tint = if (isLiked) Color.White.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(19.dp)
            )
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
            is TrackDownloadState.Downloading -> Box(contentAlignment = Alignment.Center) {
                if (state.progress <= 0f) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(iconSize),
                        strokeWidth = 2.dp,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.size(iconSize),
                        strokeWidth = 2.dp,
                        color = Color.White.copy(alpha = 0.85f),
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Отменить загрузку",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(iconSize * 0.5f)
                )
            }
            TrackDownloadState.Downloaded -> Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = "Скачано",
                tint = LinerColors.AccentPrimary,
                modifier = Modifier.size(iconSize)
            )
            is TrackDownloadState.Failed -> Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Ошибка загрузки, повторить",
                tint = Color(0xFFFF6B6B),
                modifier = Modifier.size(iconSize)
            )
            null -> Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = "Скачать",
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(iconSize)
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить загрузку?") },
            text = { Text("«${track.title}» будет удалён с устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    manager.delete(track.id)
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            }
        )
    }
}
