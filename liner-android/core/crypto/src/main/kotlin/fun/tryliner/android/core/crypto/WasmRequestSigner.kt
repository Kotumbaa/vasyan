package `fun`.tryliner.android.core.crypto

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.Parser
import java.io.InputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

data class SignedRequestHeaders(
    val signature: String,
    val timestamp: Long,
    val nonce: String,
) {
    fun toHeaderMap(): Map<String, String> = mapOf(
        "x-liner-signature" to signature,
        "x-liner-timestamp" to timestamp.toString(),
        "x-liner-nonce" to nonce,
    )
}

class WasmRequestSigner private constructor(
    private val instance: Instance,
) {
    private val lock = Any()
    private val random = SecureRandom()
    private val serverTimeOffsetMs = AtomicLong(0L)

    private val memory: Memory = instance.memory()
    private val allocFn: ExportFunction = instance.export("liner_alloc")
    private val deallocFn: ExportFunction = instance.export("liner_dealloc")
    private val getResultPtrFn: ExportFunction = instance.export("liner_get_result_ptr")
    private val getResultLenFn: ExportFunction = instance.export("liner_get_result_len")
    private val signCoverUrlFn: ExportFunction = instance.export("liner_sign_cover_url")
    private val signRequestFn: ExportFunction = instance.export("liner_sign_request")
    private val signMonitorRequestFn: ExportFunction = instance.export("liner_sign_monitor_request")
    private val signRawPayloadFn: ExportFunction = instance.export("liner_sign_raw_payload")

    constructor(resourcePath: String = DEFAULT_WASM_RESOURCE) : this(
        loadFromResource(resourcePath),
    )

    constructor(wasmBytes: ByteArray) : this(
        Instance.builder(Parser.parse(wasmBytes)).build(),
    )

    constructor(wasmStream: InputStream) : this(
        Instance.builder(Parser.parse(wasmStream)).build(),
    )

    fun setServerTimeOffset(offsetMs: Long) {
        serverTimeOffsetMs.set(offsetMs)
    }

    fun getServerTimeOffset(): Long = serverTimeOffsetMs.get()

    fun syncServerTime(serverTimeMs: Long): Long {
        if (serverTimeMs > 0L) {
            val offset = serverTimeMs - System.currentTimeMillis()
            serverTimeOffsetMs.set(offset)
        }
        return serverTimeOffsetMs.get()
    }

    fun syncServerTime(dateHeader: String): Long {
        val trimmed = dateHeader.trim()
        if (trimmed.isEmpty()) return serverTimeOffsetMs.get()
        val parsedMs = parseHttpDateMs(trimmed)
        if (parsedMs != null && parsedMs > 0L) {
            serverTimeOffsetMs.set(parsedMs - System.currentTimeMillis())
        }
        return serverTimeOffsetMs.get()
    }

    fun getAdjustedTimestamp(): Long =
        Math.floorDiv(System.currentTimeMillis() + serverTimeOffsetMs.get(), 1000L)

    fun signCoverUrl(payload: String): String = synchronized(lock) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val ptr = alloc(bytes.size)
        try {
            if (bytes.isNotEmpty()) {
                memory.write(ptr, bytes)
            }
            val code = signCoverUrlFn.apply(ptr.toLong(), bytes.size.toLong())[0]
            check(code == 0L) { "wasm sign_cover_url failed with code $code" }
            readResult()
        } finally {
            dealloc(ptr, bytes.size)
        }
    }

    fun signRequest(
        method: String,
        path: String,
        body: String? = null,
        timestamp: Long? = null,
        nonce: String? = null,
    ): SignedRequestHeaders = signInternal(
        fn = signRequestFn,
        fnName = "sign_request",
        method = method,
        path = path,
        body = body,
        timestamp = timestamp,
        nonce = nonce,
    )

    fun signMonitorRequest(
        method: String,
        path: String,
        body: String? = null,
        timestamp: Long? = null,
        nonce: String? = null,
    ): SignedRequestHeaders = signInternal(
        fn = signMonitorRequestFn,
        fnName = "sign_monitor_request",
        method = method,
        path = path,
        body = body,
        timestamp = timestamp,
        nonce = nonce,
    )

    fun signRawPayload(payload: ByteArray): String = synchronized(lock) {
        val ptr = alloc(payload.size)
        try {
            if (payload.isNotEmpty()) {
                memory.write(ptr, payload)
            }
            val code = signRawPayloadFn.apply(ptr.toLong(), payload.size.toLong())[0]
            check(code == 0L) { "wasm sign_raw_payload failed with code $code" }
            readResult()
        } finally {
            dealloc(ptr, payload.size)
        }
    }

    private fun signInternal(
        fn: ExportFunction,
        fnName: String,
        method: String,
        path: String,
        body: String?,
        timestamp: Long?,
        nonce: String?,
    ): SignedRequestHeaders {
        val cleanMethod = method.trim().uppercase()
        val cleanPath = path.substringBefore('?').trim()
        val bodyStr = body ?: ""
        val ts = timestamp ?: getAdjustedTimestamp()
        val actualNonce = nonce ?: generateNonce()

        val methodBytes = cleanMethod.toByteArray(Charsets.UTF_8)
        val pathBytes = cleanPath.toByteArray(Charsets.UTF_8)
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        val nonceBytes = actualNonce.toByteArray(Charsets.UTF_8)

        val signature = synchronized(lock) {
            val methodPtr = allocAndWrite(methodBytes)
            val pathPtr = allocAndWrite(pathBytes)
            val bodyPtr = allocAndWrite(bodyBytes)
            val noncePtr = allocAndWrite(nonceBytes)
            try {
                val code = fn.apply(
                    methodPtr.toLong(),
                    methodBytes.size.toLong(),
                    pathPtr.toLong(),
                    pathBytes.size.toLong(),
                    bodyPtr.toLong(),
                    bodyBytes.size.toLong(),
                    ts,
                    noncePtr.toLong(),
                    nonceBytes.size.toLong(),
                )[0]
                check(code == 0L) { "wasm $fnName failed with code $code" }
                readResult()
            } finally {
                dealloc(methodPtr, methodBytes.size)
                dealloc(pathPtr, pathBytes.size)
                dealloc(bodyPtr, bodyBytes.size)
                dealloc(noncePtr, nonceBytes.size)
            }
        }

        return SignedRequestHeaders(
            signature = signature,
            timestamp = ts,
            nonce = actualNonce,
        )
    }

    private fun alloc(len: Int): Int = allocFn.apply(len.toLong())[0].toInt()

    private fun dealloc(ptr: Int, len: Int) {
        deallocFn.apply(ptr.toLong(), len.toLong())
    }

    private fun allocAndWrite(bytes: ByteArray): Int {
        val ptr = alloc(bytes.size)
        if (bytes.isNotEmpty()) {
            memory.write(ptr, bytes)
        }
        return ptr
    }

    private fun readResult(): String {
        val ptr = getResultPtrFn.apply()[0].toInt()
        val len = getResultLenFn.apply()[0].toInt()
        if (ptr == 0 || len <= 0) return ""
        return memory.readBytes(ptr, len).toString(Charsets.UTF_8)
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseHttpDateMs(dateHeader: String): Long? {
        runCatching {
            return ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }
        runCatching {
            return Instant.parse(dateHeader).toEpochMilli()
        }
        return null
    }

    companion object {
        const val DEFAULT_WASM_RESOURCE = "/liner/signer.wasm"

        private fun loadFromResource(resourcePath: String): Instance {
            val stream = WasmRequestSigner::class.java.getResourceAsStream(resourcePath)
                ?: error("Missing WASM signer resource at $resourcePath")
            return stream.use { Instance.builder(Parser.parse(it)).build() }
        }
    }
}
