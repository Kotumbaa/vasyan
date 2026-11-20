package `fun`.tryliner.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `fun`.tryliner.android.core.lyrics.LyricLine
import `fun`.tryliner.android.core.lyrics.SyncedLyrics
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

@Composable
fun SyncedLyricsView(
    lyrics: SyncedLyrics?,
    positionMs: Long,
    isLoading: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        isLoading && lyrics == null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LinerColors.TextSecondary)
            }
        }
        lyrics == null -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No lyrics available",
                    style = MaterialTheme.typography.titleMedium,
                    color = LinerColors.TextTertiary
                )
            }
        }
        lyrics.isInstrumental -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Instrumental",
                    style = MaterialTheme.typography.headlineMedium,
                    color = LinerColors.TextSecondary
                )
            }
        }
        lyrics.isSynced && lyrics.lines.isNotEmpty() -> {
            val activeIndex = lyrics.findActiveLineIndex(positionMs)
            val listState = rememberLazyListState()

            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0) {
                    val target = (activeIndex - 2).coerceAtLeast(0)
                    listState.animateScrollToItem(target)
                }
            }

            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 32.dp, horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(
                    items = lyrics.lines,
                    key = { index, line -> "${index}_${line.startMs}" }
                ) { index, line ->
                    SyncedLyricLineRow(
                        line = line,
                        isActive = index == activeIndex,
                        isPast = activeIndex >= 0 && index < activeIndex,
                        onClick = { onSeekTo(line.startMs) }
                    )
                }
            }
        }
        !lyrics.plainText.isNullOrBlank() -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Text(
                    text = lyrics.plainText.orEmpty(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        lineHeight = 28.sp
                    ),
                    color = LinerColors.TextSecondary
                )
            }
        }
        else -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No lyrics available",
                    style = MaterialTheme.typography.titleMedium,
                    color = LinerColors.TextTertiary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SyncedLyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    onClick: () -> Unit
) {
    val targetColor = when {
        isActive -> LinerColors.TextPrimary
        isPast -> LinerColors.TextTertiary
        else -> LinerColors.TextSecondary.copy(alpha = 0.55f)
    }
    val textColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 180),
        label = "lyricColor"
    )

    val displayText = if (line.isInstrumental && line.text.isBlank()) {
        "• • •"
    } else {
        line.text
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LinerShapes.Control)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = if (isActive) 22.sp else 19.sp,
                lineHeight = if (isActive) 28.sp else 25.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold
            ),
            color = textColor
        )
        if (!line.subtitle.isNullOrBlank()) {
            Text(
                text = line.subtitle.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
