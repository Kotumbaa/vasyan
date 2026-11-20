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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
