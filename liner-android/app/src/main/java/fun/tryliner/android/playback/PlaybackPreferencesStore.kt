package `fun`.tryliner.android.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PreferredPlatform(val key: String, val label: String) {
    YOUTUBE("youtube", "YouTube"),
    SOUNDCLOUD("soundcloud", "SoundCloud");

    companion object {
        fun fromKey(raw: String?): PreferredPlatform =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: YOUTUBE
    }
}

data class PlaybackPreferences(
    val hasPlatformPriority: Boolean = true,
    val preferredPlatform: PreferredPlatform = PreferredPlatform.YOUTUBE,
)

class PlaybackPreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(load())
    val preferences: StateFlow<PlaybackPreferences> = _preferences.asStateFlow()

    private fun load(): PlaybackPreferences {
        val hasPriority = prefs.getBoolean(KEY_HAS_PRIORITY, true)
        val platform = PreferredPlatform.fromKey(prefs.getString(KEY_PLATFORM, PreferredPlatform.YOUTUBE.key))
        return PlaybackPreferences(
            hasPlatformPriority = hasPriority,
            preferredPlatform = platform,
        )
    }

    fun setHasPlatformPriority(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_PRIORITY, enabled).apply()
        _preferences.value = _preferences.value.copy(hasPlatformPriority = enabled)
    }

    fun setPreferredPlatform(platform: PreferredPlatform) {
        prefs.edit().putString(KEY_PLATFORM, platform.key).apply()
        _preferences.value = _preferences.value.copy(preferredPlatform = platform)
    }

    private companion object {
        const val PREFS_NAME = "liner_playback_prefs"
        const val KEY_HAS_PRIORITY = "has_platform_priority"
        const val KEY_PLATFORM = "preferred_platform"
    }
}
