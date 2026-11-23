package `fun`.tryliner.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.AppContainer
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.model.UserPlaylist
import `fun`.tryliner.android.playback.TrackDownloadState
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalTrackMenuOpener = staticCompositionLocalOf<((Track) -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionSheet(
    track: Track,
    container: AppContainer,
    onOpenArtist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val likedIds by container.likedTrackIds.collectAsStateWithLifecycle()
    val downloadStates by container.downloadManager.states.collectAsStateWithLifecycle()
    val isLiked = likedIds.contains(track.id)
    val isDownloaded = downloadStates[track.id] is TrackDownloadState.Downloaded

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf<List<UserPlaylist>>(emptyList()) }
    var loadingPlaylists by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showPlaylistPicker) {
        if (showPlaylistPicker && playlists.isEmpty()) {
            loadingPlaylists = true
            playlists = runCatching {
                withContext(Dispatchers.IO) { container.apiClient.listPlaylists() }
            }.getOrDefault(emptyList())
            loadingPlaylists = false
        }
    }

    val primaryArtist = track.artists.firstOrNull()
    val artistTargetId = when {
        primaryArtist != null && primaryArtist.id.isNotBlank() && primaryArtist.id != "unknown" -> primaryArtist.id
        primaryArtist != null && primaryArtist.name.isNotBlank() -> "name:${primaryArtist.name}"
        track.artistNames.isNotBlank() -> "name:${track.artistNames.split(",").first().trim()}"
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF13161D),
        contentColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.22f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinerCoverArt(
                    url = track.cover?.url ?: track.cover?.fallbackUrl,
                    contentDescription = track.title,
                    size = 48.dp,
                    shape = LinerShapes.Tile
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = LinerColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artistNames.ifBlank { "Неизвестный артист" },
                        style = MaterialTheme.typography.bodySmall,
                        color = LinerColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (statusMessage != null) {
                Text(
                    text = statusMessage.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 6.dp)
            )

            if (showPlaylistPicker) {
                Text(
                    text = "Выберите плейлист",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = LinerColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
                if (loadingPlaylists) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else if (playlists.isEmpty()) {
                    Text(
                        text = "Нет плейлистов",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LinerColors.TextTertiary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        items(playlists, key = { it.id }) { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        scope.launch {
                                            val ok = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    container.apiClient.addPlaylistTrack(pl.id, track.id, track)
                                                }
                                            }.isSuccess
                                            statusMessage = if (ok) "Добавлено в «${pl.title}»" else "Не удалось добавить"
                                            showPlaylistPicker = false
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LinerCoverArt(
                                    url = pl.coverUrls?.firstOrNull(),
                                    contentDescription = pl.title,
                                    size = 40.dp,
                                    shape = LinerShapes.Tile
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = pl.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = LinerColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            } else {
                SheetActionRow(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    label = "Играть следующим",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        container.playerController.playNext(track)
                        onDismiss()
                    }
                )
                SheetActionRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    label = "Добавить в плейлист",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showPlaylistPicker = true
                    }
                )
                SheetActionRow(
                    icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    label = if (isLiked) "Убрать из понравившихся" else "В понравившиеся",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        container.toggleLike(track)
                        onDismiss()
                    }
                )
                if (artistTargetId != null) {
                    SheetActionRow(
                        icon = Icons.Rounded.Person,
                        label = "Перейти к артисту",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                            onOpenArtist(artistTargetId)
                        }
                    )
                }
                SheetActionRow(
                    icon = if (isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    label = if (isDownloaded) "Удалить из загрузок" else "Скачать офлайн",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (isDownloaded) {
                            container.downloadManager.delete(track.id)
                        } else {
                            container.downloadManager.download(track)
                        }
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = LinerColors.TextPrimary
        )
    }
}
