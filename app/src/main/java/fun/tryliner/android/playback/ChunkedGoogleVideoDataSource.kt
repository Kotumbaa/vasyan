package `fun`.tryliner.android.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Media3 [DataSource] that fetches `*.googlevideo.com` audio streams in
 * `&range=start-end` chunks with a 4-stream concurrent prefetch window
 * (matching Liner Desktop's `chunkedAudioDownload.ts` concurrency = 4)
 * to avoid YouTube single-stream throttling.
 * Non-GoogleVideo URIs are delegated to [DefaultHttpDataSource].
 */
@OptIn(UnstableApi::class)
class ChunkedGoogleVideoDataSource(
    private val okHttpClient: OkHttpClient,
    private val fallbackDataSource: DataSource,
    private val prefetchExecutor: ExecutorService,
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
        private val userAgent: String = DEFAULT_USER_AGENT,
    ) : DataSource.Factory {
        private val fallbackFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        private val prefetchExecutor: ExecutorService =
            Executors.newFixedThreadPool(PREFETCH_CONCURRENCY)

        override fun createDataSource(): DataSource {
            return ChunkedGoogleVideoDataSource(
                okHttpClient = okHttpClient,
                fallbackDataSource = fallbackFactory.createDataSource(),
                prefetchExecutor = prefetchExecutor,
            )
        }

        companion object {
            private const val DEFAULT_USER_AGENT =
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

            fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    private var activeFallback = false
    private var opened = false
    private var currentUri: Uri? = null
    private var currentPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var knownTotalLength: Long? = null
    private var requestEndExclusive: Long? = null

    private var chunkBytes = ByteArray(0)
    private var chunkOffset = 0
    private var chunkLength = 0

    private val prefetchedChunks = LinkedHashMap<Long, CompletableFuture<ChunkResult>>()

    private data class ChunkResult(
        val bytes: ByteArray,
        val totalLength: Long?,
    )

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host
        if (host == null || !host.endsWith(".googlevideo.com", ignoreCase = true)) {
            activeFallback = true
            return fallbackDataSource.open(dataSpec)
        }

        cancelAllPrefetches()
        activeFallback = false
        currentUri = dataSpec.uri
        currentPosition = dataSpec.position
        knownTotalLength = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0L }
        requestEndExclusive = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.position + dataSpec.length
        } else {
            null
        }

        transferInitializing(dataSpec)

        val firstChunk = fetchChunk(currentPosition)
        if (knownTotalLength == null && firstChunk.totalLength != null) {
            knownTotalLength = firstChunk.totalLength
        }

        chunkBytes = firstChunk.bytes
        chunkOffset = 0
        chunkLength = firstChunk.bytes.size

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            knownTotalLength != null -> (knownTotalLength!! - currentPosition).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        fillPrefetchWindow(currentPosition + chunkLength)

        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (activeFallback) {
            return fallbackDataSource.read(buffer, offset, length)
        }
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        while (chunkOffset >= chunkLength) {
            val total = knownTotalLength
            if (total != null && currentPosition >= total) {
                return C.RESULT_END_OF_INPUT
            }
            val reqEnd = requestEndExclusive
            if (reqEnd != null && currentPosition >= reqEnd) {
                return C.RESULT_END_OF_INPUT
            }

            val next = takePrefetchedOrFetch(currentPosition)
            if (knownTotalLength == null && next.totalLength != null) {
                knownTotalLength = next.totalLength
            }
            if (next.bytes.isEmpty()) {
                return C.RESULT_END_OF_INPUT
            }
            chunkBytes = next.bytes
            chunkOffset = 0
            chunkLength = next.bytes.size
            fillPrefetchWindow(currentPosition + chunkLength)
        }

        val availableInChunk = chunkLength - chunkOffset
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            min(length, availableInChunk)
        } else {
            min(length.toLong(), min(availableInChunk.toLong(), bytesRemaining)).toInt()
        }

        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        System.arraycopy(chunkBytes, chunkOffset, buffer, offset, toRead)
        chunkOffset += toRead
        currentPosition += toRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= toRead
        }
        bytesTransferred(toRead)
        return toRead
    }

    override fun getUri(): Uri? {
        return if (activeFallback) fallbackDataSource.uri else currentUri
    }

    override fun close() {
        if (activeFallback) {
            activeFallback = false
            fallbackDataSource.close()
            return
        }
        cancelAllPrefetches()
        currentUri = null
        chunkBytes = ByteArray(0)
        chunkOffset = 0
        chunkLength = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun takePrefetchedOrFetch(start: Long): ChunkResult {
        val future = prefetchedChunks.remove(start)
        if (future != null) {
            return try {
                future.get()
            } catch (_: Exception) {
                fetchChunk(start)
            }
        }
        cancelAllPrefetches()
        return fetchChunk(start)
    }

    private fun fillPrefetchWindow(firstNextStart: Long) {
        val uri = currentUri ?: return
        val total = knownTotalLength
        val reqEnd = requestEndExclusive

        // Drop any stale prefetches before firstNextStart
        val iter = prefetchedChunks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key < firstNextStart) {
                entry.value.cancel(true)
                iter.remove()
            }
        }

        var candidateStart = firstNextStart
        for (i in 0 until PREFETCH_CONCURRENCY) {
            if (total != null && candidateStart >= total) break
            if (reqEnd != null && candidateStart >= reqEnd) break
            if (!prefetchedChunks.containsKey(candidateStart)) {
                val startForTask = candidateStart
                prefetchedChunks[startForTask] = CompletableFuture.supplyAsync(
                    { fetchChunkForUri(uri, startForTask) },
                    prefetchExecutor,
                )
            }
            candidateStart += CHUNK_SIZE
        }
    }

    private fun cancelAllPrefetches() {
        for (future in prefetchedChunks.values) {
            future.cancel(true)
        }
        prefetchedChunks.clear()
    }

    private fun fetchChunk(start: Long): ChunkResult {
        val uri = currentUri ?: throw IOException("URI is null")
        return fetchChunkForUri(uri, start)
    }

    private fun fetchChunkForUri(uri: Uri, start: Long): ChunkResult {
        val maxEndByTotal = knownTotalLength?.let { it - 1 }
        val maxEndByRequest = requestEndExclusive?.let { it - 1 }
        var end = start + CHUNK_SIZE - 1
        if (maxEndByTotal != null) end = min(end, maxEndByTotal)
        if (maxEndByRequest != null) end = min(end, maxEndByRequest)

        if (end < start) {
            return ChunkResult(ByteArray(0), knownTotalLength)
        }

        val rangedUri = withRangeParam(uri, start, end)
        val request = Request.Builder()
            .url(rangedUri.toString())
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("GoogleVideo chunk $start-$end returned HTTP ${response.code}")
            }
            val totalFromHeader = parseTotalFromContentRange(response.header("Content-Range"))
            val bodyBytes = response.body?.bytes() ?: ByteArray(0)
            return ChunkResult(bodyBytes, totalFromHeader)
        }
    }

    companion object {
        private const val CHUNK_SIZE = 512L * 1024L
        private const val PREFETCH_CONCURRENCY = 4
        private val CONTENT_RANGE_TOTAL_REGEX = Regex("""/\s*(\d+)$""")

        private fun withRangeParam(uri: Uri, start: Long, end: Long): Uri {
            val builder = uri.buildUpon().clearQuery()
            for (name in uri.queryParameterNames) {
                if (!name.equals("range", ignoreCase = true)) {
                    for (value in uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
            builder.appendQueryParameter("range", "$start-$end")
            return builder.build()
        }

        private fun parseTotalFromContentRange(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            return CONTENT_RANGE_TOTAL_REGEX.find(header)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }
    }
}
