package `fun`.tryliner.android.playback

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Persistent disk-backed LRU store mapping `trackId -> loudnessDb` (relative to -14 LUFS).
 * Keeps up to [MAX_ENTRIES] entries in `filesDir/loudness_cache.json` so measured or
 * Innertube-provided loudness survives app restarts and works offline / via Worker relay.
 */
class TrackLoudnessStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val storeFile = File(context.filesDir, "loudness_cache.json")
    private val tempFile = File(context.filesDir, "loudness_cache.json.tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), Float.serializer())
    private val ioMutex = Mutex()
    private val memLock = Any()

    private val entries = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    private var saveJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            loadFromDisk()
        }
    }

    fun get(trackId: String): Float? {
        if (trackId.isBlank()) return null
        return synchronized(memLock) {
            entries[trackId]?.takeIf { it.isFinite() }
        }
    }

    fun put(trackId: String, loudnessDb: Float) {
        if (trackId.isBlank() || !loudnessDb.isFinite()) return
        val clamped = loudnessDb.coerceIn(-24f, 18f)
        val changed = synchronized(memLock) {
            val prev = entries[trackId]
            entries[trackId] = clamped
            prev == null || kotlin.math.abs(prev - clamped) >= 0.05f
        }
        if (changed) {
            scheduleSave()
        }
    }

    fun remove(trackId: String) {
        val removed = synchronized(memLock) {
            entries.remove(trackId) != null
        }
        if (removed) {
            scheduleSave()
        }
    }

    private suspend fun loadFromDisk() {
        ioMutex.withLock {
            runCatching {
                if (!storeFile.exists()) return@runCatching
                val parsed = json.decodeFromString(mapSerializer, storeFile.readText())
                synchronized(memLock) {
                    for ((k, v) in parsed) {
                        if (k.isNotBlank() && v.isFinite() && !entries.containsKey(k)) {
                            entries[k] = v.coerceIn(-24f, 18f)
                        }
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Failed to load loudness cache: ${err.message}")
            }
        }
    }

    private fun scheduleSave() {
        synchronized(memLock) {
            saveJob?.cancel()
            saveJob = scope.launch(Dispatchers.IO) {
                delay(800L)
                persistNow()
            }
        }
    }

    private suspend fun persistNow() {
        val snapshot: Map<String, Float> = synchronized(memLock) {
            LinkedHashMap(entries)
        }
        ioMutex.withLock {
            runCatching {
                val raw = json.encodeToString(mapSerializer, snapshot)
                tempFile.writeText(raw)
                if (!tempFile.renameTo(storeFile)) {
                    storeFile.writeText(raw)
                    tempFile.delete()
                }
            }.onFailure { err ->
                Log.w(TAG, "Failed to save loudness cache: ${err.message}")
            }
        }
    }

    companion object {
        private const val TAG = "TrackLoudnessStore"
        private const val MAX_ENTRIES = 2_000
    }
}
