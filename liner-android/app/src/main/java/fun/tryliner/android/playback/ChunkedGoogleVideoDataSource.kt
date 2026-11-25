package `fun`.tryliner.android.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Media3 [DataSource] that fetches `*.googlevideo.com` audio streams in
 * `&range=start-end` chunks with a 4-stream concurrent prefetch window
 * (matching Liner Desktop's `chunkedAudioDownload.ts` concurrency = 4)
 * to avoid YouTube single-stream throttling.
 * Non-GoogleVideo URIs (e.g. SoundCloud streams/HLS) are delegated to an
 * OkHttp-backed fallback DataSource sharing the same [DpiDesyncProxy]-enabled client.
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
        private val prefetchExecutor: ExecutorService =
            Executors.newFixedThreadPool(PREFETCH_CONCURRENCY)

        override fun createDataSource(): DataSource {
            return ChunkedGoogleVideoDataSource(
                okHttpClient = okHttpClient,
                fallbackDataSource = OkHttpFallbackDataSource(
                    okHttpClient = okHttpClient,
                    userAgent = userAgent,
                ),
                prefetchExecutor = prefetchExecutor,
            )
        }

        fun release() {
            prefetchExecutor.shutdownNow()
        }

        companion object {
            private const val DEFAULT_USER_AGENT =
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

            fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private var activeFallback = false
    private var opened = false
    private var currentUri: Uri? = null
    private var currentPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    @Volatile
    private var knownTotalLength: Long? = null
    @Volatile
    private var requestEndExclusive: Long? = null

    private var chunkBytes = ByteArray(0)
    private var chunkOffset = 0
    private var chunkLength = 0

    private data class PrefetchEntry(
        val plannedSize: Long,
        val activeCall: AtomicReference<Call?>,
        val future: CompletableFuture<ChunkResult>,
    ) {
        fun cancel() {
            activeCall.getAndSet(null)?.cancel()
            future.cancel(true)
        }
    }

    private val prefetchedChunks = LinkedHashMap<Long, PrefetchEntry>()

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

        val firstChunk = fetchChunk(currentPosition, adaptiveChunkSize(leadBytes = 0L))
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

    override fun getResponseHeaders(): Map<String, List<String>> {
        return if (activeFallback) fallbackDataSource.responseHeaders else super.getResponseHeaders()
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
        val entry = prefetchedChunks.remove(start)
        if (entry != null) {
            return try {
                entry.future.get()
            } catch (e: InterruptedException) {
                entry.cancel()
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while waiting for prefetched chunk").initCause(e) as IOException
            } catch (_: Exception) {
                fetchChunk(start, adaptiveChunkSize(leadBytes = 0L))
            }
        }
        cancelAllPrefetches()
        return fetchChunk(start, adaptiveChunkSize(leadBytes = 0L))
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
                entry.value.cancel()
                iter.remove()
            }
        }

        var candidateStart = firstNextStart
        for ((start, entry) in prefetchedChunks) {
            val end = start + entry.plannedSize
            if (end > candidateStart) {
                candidateStart = end
            }
        }

        while (prefetchedChunks.size < PREFETCH_CONCURRENCY) {
            if (total != null && candidateStart >= total) break
            if (reqEnd != null && candidateStart >= reqEnd) break

            val leadBytes = (candidateStart - currentPosition).coerceAtLeast(0L)
            val targetSize = adaptiveChunkSize(leadBytes)
            val maxEndByTotal = total?.let { it - 1 }
            val maxEndByRequest = reqEnd?.let { it - 1 }
            var end = candidateStart + targetSize - 1
            if (maxEndByTotal != null) end = min(end, maxEndByTotal)
            if (maxEndByRequest != null) end = min(end, maxEndByRequest)
            val actualSize = (end - candidateStart + 1).coerceAtLeast(0L)
            if (actualSize <= 0L) break

            val startForTask = candidateStart
            val callRef = AtomicReference<Call?>(null)
            val future = CompletableFuture.supplyAsync(
                { fetchChunkForUri(uri, startForTask, actualSize, total, reqEnd, callRef) },
                prefetchExecutor,
            )
            prefetchedChunks[startForTask] = PrefetchEntry(actualSize, callRef, future)
            candidateStart += actualSize
        }
    }

    private fun cancelAllPrefetches() {
        for (entry in prefetchedChunks.values) {
            entry.cancel()
        }
        prefetchedChunks.clear()
    }

    private fun fetchChunk(start: Long, chunkSize: Long): ChunkResult {
        val uri = currentUri ?: throw IOException("URI is null")
        return fetchChunkForUri(uri, start, chunkSize, knownTotalLength, requestEndExclusive, callRef = null)
    }

    private fun fetchChunkForUri(
        uri: Uri,
        start: Long,
        chunkSize: Long,
        totalLengthSnapshot: Long?,
        reqEndSnapshot: Long?,
        callRef: AtomicReference<Call?>?,
    ): ChunkResult {
        val maxEndByTotal = totalLengthSnapshot?.let { it - 1 }
        val maxEndByRequest = reqEndSnapshot?.let { it - 1 }
        var end = start + chunkSize - 1
        if (maxEndByTotal != null) end = min(end, maxEndByTotal)
        if (maxEndByRequest != null) end = min(end, maxEndByRequest)

        if (end < start) {
            return ChunkResult(ByteArray(0), totalLengthSnapshot)
        }

        val rangedUri = withRangeParam(uri, start, end)
        val request = Request.Builder()
            .url(rangedUri.toString())
            .get()
            .build()

        val call = okHttpClient.newCall(request)
        callRef?.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IOException("GoogleVideo chunk $start-$end returned HTTP ${response.code}")
                }
                val totalFromHeader = parseTotalFromContentRange(response.header("Content-Range"))
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                return ChunkResult(bodyBytes, totalFromHeader)
            }
        } finally {
            callRef?.compareAndSet(call, null)
        }
    }

    companion object {
        private const val PREFETCH_CONCURRENCY = 4
        private val CONTENT_RANGE_TOTAL_REGEX = Regex("""/\s*(\d+)$""")

        private fun adaptiveChunkSize(leadBytes: Long): Long = when {
            leadBytes < 128L * 1024L -> 64L * 1024L          // 64 KB: instant start / seek
            leadBytes < 512L * 1024L -> 128L * 1024L         // 128 KB: early buffer
            leadBytes < 1536L * 1024L -> 256L * 1024L        // 256 KB: warming up buffer
            leadBytes < 3L * 1024L * 1024L -> 512L * 1024L   // 512 KB: steady playback
            leadBytes < 7L * 1024L * 1024L -> 1024L * 1024L  // 1 MB: large lead, fewer requests
            else -> 2048L * 1024L                            // 2 MB: deep buffer, minimal requests
        }

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

/**
 * Lightweight Media3 [BaseDataSource] backed by [OkHttpClient] so non-GoogleVideo HTTP/HLS
 * streams (e.g. SoundCloud `sndcdn.com`) inherit [DpiDesyncProxy] routing as well.
 */
@OptIn(UnstableApi::class)
private class OkHttpFallbackDataSource(
    private val okHttpClient: OkHttpClient,
    private val userAgent: String,
) : BaseDataSource(/* isNetwork = */ true) {

    private var response: Response? = null
    private var responseStream: InputStream? = null
    private var opened = false
    private var bytesToRead = C.LENGTH_UNSET.toLong()
    private var bytesRead = 0L
    private var resolvedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val requestBuilder = Request.Builder()
            .url(dataSpec.uri.toString())
            .header("User-Agent", userAgent)

        for ((name, value) in dataSpec.httpRequestHeaders) {
            requestBuilder.header(name, value)
        }

        if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val rangeValue = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val end = dataSpec.position + dataSpec.length - 1
                "bytes=${dataSpec.position}-$end"
            } else {
                "bytes=${dataSpec.position}-"
            }
            requestBuilder.header("Range", rangeValue)
        }

        val request = requestBuilder.build()
        var lastError: IOException? = null
        var executed: Response? = null
        val retryDelaysMs = longArrayOf(350L, 900L)

        for (attempt in 0..retryDelaysMs.size) {
            try {
                val res = okHttpClient.newCall(request).execute()
                if (res.isSuccessful || res.code == 206) {
                    executed = res
                    break
                }
                val code = res.code
                res.close()
                val retryableHttp = code == 502 || code == 503 || code == 504 || code == 429
                lastError = IOException("HTTP error $code for ${dataSpec.uri}")
                if (!retryableHttp || attempt == retryDelaysMs.size) {
                    throw lastError
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt == retryDelaysMs.size) {
                    throw e
                }
            }
            try {
                Thread.sleep(retryDelaysMs[attempt])
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw lastError ?: IOException("Interrupted while retrying ${dataSpec.uri}")
            }
        }

        val okResponse = executed ?: throw (lastError ?: IOException("Failed to open ${dataSpec.uri}"))
        val body = okResponse.body ?: run {
            okResponse.close()
            throw IOException("Empty response body for ${dataSpec.uri}")
        }

        response = okResponse
        responseStream = body.byteStream()
        resolvedUri = Uri.parse(okResponse.request.url.toString())
        bytesRead = 0L

        val contentLength = body.contentLength().takeIf { it >= 0L } ?: C.LENGTH_UNSET.toLong()
        bytesToRead = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength != C.LENGTH_UNSET.toLong() -> contentLength
            else -> C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        }
        val toRead = if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            min(length.toLong(), bytesToRead - bytesRead).toInt()
        } else {
            length
        }
        val stream = responseStream ?: return C.RESULT_END_OF_INPUT
        val read = stream.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = resolvedUri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return response?.headers?.toMultimap() ?: emptyMap()
    }

    override fun close() {
        runCatching { responseStream?.close() }
        runCatching { response?.close() }
        responseStream = null
        response = null
        resolvedUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
