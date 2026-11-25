package `fun`.tryliner.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class DailyMixItem(
    val id: String,
    val title: String,
    val description: String = "",
    val clusterArtists: List<String> = emptyList(),
    val cover: Cover? = null,
    val trackCount: Int = 0,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class DailyMixesResponse(
    val items: List<DailyMixItem> = emptyList(),
    val generatedAt: String = "",
)
