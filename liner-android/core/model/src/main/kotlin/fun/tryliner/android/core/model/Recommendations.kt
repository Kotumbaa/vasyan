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

enum class WaveVibe(val id: String, val label: String) {
    ALL("all", "Для меня"),
    FAVORITE("favorite", "Любимое"),
    DISCOVER("discover", "Незнакомое"),
    ENERGY("energy", "Бодрое"),
    CALM("calm", "Спокойное");

    companion object {
        fun fromId(id: String?): WaveVibe =
            entries.firstOrNull { it.id == id } ?: ALL
    }
}

@Serializable
data class MyWaveRequest(
    val vibe: String = "all",
    val excludeIds: List<String> = emptyList(),
    val limit: Int = 20,
)

@Serializable
data class MyWaveResponse(
    val items: List<Track> = emptyList(),
    val vibe: String = "all",
    val seedLabel: String = "",
    val generatedAt: String = "",
)
