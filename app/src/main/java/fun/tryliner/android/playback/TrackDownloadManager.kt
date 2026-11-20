package `fun`.tryliner.android.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import `fun`.tryliner.android.core.model.Track
import `fun`.tryliner.android.core.network.InnertubeClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface TrackDownloadState {
    data class Downloading(val progress: Float) : TrackDownloadState
    data object Downloaded : TrackDownloadState
    data class Failed(val message: String) : TrackDownloadState
}

/**
 * Persistent offline downloads. Audio files live in `filesDir/downloads/<id>.audio`
 * (not the LRU playback cache, so they are never evicted), track metadata in
 * `filesDir/downloads/index.json`.
 */
class TrackDownloadManager(
    context: Context,
    private val innertubeClient: InnertubeClient,
    private val scope: CoroutineScope,
) {
    private val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val indexFile = File(downloadsDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val indexSerializer = ListSerializer(Track.serializer())
    private val indexMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _downloadedTracks = MutableStateFlow<List<Track>>(emptyList())
    /** Downloaded tracks, most recent first. */
    val downloadedTracks: StateFlow<List<Track>> = _downloadedTracks.asStateFlow()

    private val _states = MutableStateFlow<Map<String, TrackDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, TrackDownloadState>> = _states.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { loadIndex() }
    }

    private suspend fun loadIndex() {
        val tracks = indexMutex.withLock {
            runCatching {
                if (indexFile.exists()) json.decodeFromString(indexSerializer, indexFile.readText()) else emptyList()
            }.getOrDefault(emptyList())
        }.filter { audioFile(it.id).exists() }
        _downloadedTracks.value = tracks
        _states.update { current ->
            current + tracks.associate { it.id to TrackDownloadState.Downloaded }
        }
        // Clean up partial leftovers from interrupted downloads.
        downloadsDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun audioFile(trackId: String): File = File(downloadsDir, "${safeName(trackId)}.audio")
    private fun partFile(trackId: String): File = File(downloadsDir, "${safeName(trackId)}.audio.part")
    private fun safeName(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun isDownloaded(trackId: String): Boolean =
        _states.value[trackId] == TrackDownloadState.Downloaded && audioFile(trackId).exists()

    /** Local file URI for a downloaded track, or null. */
    fun localUriFor(trackId: String): Uri? =
        audioFile(trackId).takeIf { it.exists() && it.length() > 0 }?.let { Uri.fromFile(it) }

    fun download(track: Track) {
        if (jobs[track.id]?.isActive == true || isDownloaded(track.id)) return
        setState(track.id, TrackDownloadState.Downloading(0f))
        val job = scope.launch(Dispatchers.IO) {
            try {
                val stream = innertubeClient.resolveAudioStream(track.id)
                val part = partFile(track.id)
                part.delete()
                downloadChunked(stream.url, stream.contentLength, part) { progress ->
                    setState(track.id, TrackDownloadState.Downloading(progress))
                }
                val target = audioFile(track.id)
                target.delete()
                if (!part.renameTo(target)) throw IOException("Failed to finalize download")
                indexMutex.withLock {
                    val updated = listOf(track) + _downloadedTracks.value.filterNot { it.id == track.id }
                    indexFile.writeText(json.encodeToString(indexSerializer, updated))
                    _downloadedTracks.value = updated
                }
                setState(track.id, TrackDownloadState.Downloaded)
            } catch (e: CancellationException) {
                partFile(track.id).delete()
                clearState(track.id)
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Download failed for ${track.id}", e)
                partFile(track.id).delete()
                setState(track.id, TrackDownloadState.Failed(e.message ?: "Download failed"))
            } finally {
                jobs.remove(track.id)
            }
        }
        jobs[track.id] = job
    }

    fun cancel(trackId: String) {
        jobs.remove(trackId)?.cancel()
        clearState(trackId)
    }

    fun delete(trackId: String) {
        cancel(trackId)
        scope.launch(Dispatchers.IO) {
            audioFile(trackId).delete()
            partFile(trackId).delete()
            indexMutex.withLock {
                val updated = _downloadedTracks.value.filterNot { it.id == trackId }
                indexFile.writeText(json.encodeToString(indexSerializer, updated))
                _downloadedTracks.value = updated
            }
        }
    }

    private fun setState(trackId: String, state: TrackDownloadState) {
        _states.update { it + (trackId to state) }
    }

    private fun clearState(trackId: String) {
        _states.update { it - trackId }
    }

    /**
     * Downloads [url] into [target] using `&range=` chunks with [CONCURRENCY] parallel
     * requests (same approach as playback, avoids googlevideo throttling).
     */
    private suspend fun downloadChunked(
        url: String,
        knownLength: Long?,
        target: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        val isGoogleVideo = uri.host?.endsWith(".googlevideo.com", ignoreCase = true) == true
        if (!isGoogleVideo) {
            downloadPlain(url, target, onProgress)
            return@withContext
        }

        var total = knownLength?.takeIf { it > 0 }
            ?: uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
        if (total == null) {
            // Probe first chunk to learn total length from Content-Range.
            val (bytes, probedTotal) = fetchRange(uri, 0, CHUNK_SIZE - 1)
            total = probedTotal ?: throw IOException("Unknown content length")
            RandomAccessFile(target, "rw").use { it.write(bytes) }
        }
        val totalLength = total

        RandomAccessFile(target, "rw").use { it.setLength(totalLength) }
        val done = AtomicLong(0)
        val starts = (0 until totalLength step CHUNK_SIZE).toList()

        coroutineScope {
            starts.chunked(CONCURRENCY).forEach { batch ->
                batch.map { start ->
                    async {
                        ensureActive()
                        val end = minOf(start + CHUNK_SIZE - 1, totalLength - 1)
                        val (bytes, _) = retry(3) { fetchRange(uri, start, end) }
                        synchronized(target) {
                            RandomAccessFile(target, "rw").use { raf ->
                                raf.seek(start)
                                raf.write(bytes)
                            }
                        }
                        val sum = done.addAndGet(bytes.size.toLong())
                        onProgress((sum.toFloat() / totalLength).coerceIn(0f, 1f))
                    }
                }.awaitAll()
            }
        }
    }

    private fun downloadPlain(url: String, target: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty body")
            val total = body.contentLength()
            var read = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    private fun fetchRange(uri: Uri, start: Long, end: Long): Pair<ByteArray, Long?> {
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name.equals("range", ignoreCase = true)) continue
            for (value in uri.getQueryParameters(name)) builder.appendQueryParameter(name, value)
        }
        builder.appendQueryParameter("range", "$start-$end")
        val request = Request.Builder().url(builder.build().toString()).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Chunk $start-$end HTTP ${response.code}")
            val total = response.header("Content-Range")
                ?.substringAfterLast('/')?.trim()?.toLongOrNull()
            return (response.body?.bytes() ?: ByteArray(0)) to total
        }
    }

    private inline fun <T> retry(times: Int, block: () -> T): T {
        var last: Throwable? = null
        repeat(times) {
            try {
                return block()
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("Retry failed")
    }

    companion object {
        private const val TAG = "TrackDownloads"
        private const val CHUNK_SIZE = 512L * 1024L
        private const val CONCURRENCY = 4
    }
}
