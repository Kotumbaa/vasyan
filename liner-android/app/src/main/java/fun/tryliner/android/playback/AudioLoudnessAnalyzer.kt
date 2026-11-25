package `fun`.tryliner.android.playback

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Computes integrated loudness (`loudnessDb` relative to -14 LUFS, matching YouTube's
 * `loudnessDb` convention) for a local audio file using Android's native [MediaExtractor]
 * + [MediaCodec] and ITU-R BS.1770 K-weighting + dual-stage gating.
 */
object AudioLoudnessAnalyzer {
    private const val TAG = "LoudnessAnalyzer"
    private const val TARGET_LUFS = -14.0
    private const val MAX_ANALYZED_SECONDS = 120.0
    private const val DECODE_TIMEOUT_US = 10_000L

    fun measureLoudnessDb(file: File): Float? {
        if (!file.exists() || file.length() <= 0L) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/", ignoreCase = true)) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) return null

            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var sampleRate = trackFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = trackFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2).coerceIn(1, 8)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            var filters = createKWeightingFilters(sampleRate, channelCount)
            val blockSizeFrames = (sampleRate * 0.4).toInt().coerceAtLeast(1) // 400 ms block
            val stepSizeFrames = (blockSizeFrames / 4).coerceAtLeast(1)       // 100 ms hop (75% overlap)

            val blockEnergies = ArrayList<Double>(1024)
            var currentBlockEnergy = 0.0
            var framesInBlock = 0
            val ringEnergies = DoubleArray(4)
            var ringPos = 0

            var totalFrames = 0L
            val maxFrames = (sampleRate * MAX_ANALYZED_SECONDS).toLong()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && totalFrames < maxFrames) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inIdx)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, DECODE_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        val newRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        val newChannels = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount).coerceIn(1, 8)
                        pcmEncoding = outFmt.getIntegerOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        if (newRate != sampleRate || newChannels != channelCount) {
                            sampleRate = newRate
                            channelCount = newChannels
                            filters = createKWeightingFilters(sampleRate, channelCount)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val ordered = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN)

                            val framesInBuffer = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> bufferInfo.size / (4 * channelCount)
                                else -> bufferInfo.size / (2 * channelCount)
                            }

                            for (f in 0 until framesInBuffer) {
                                if (totalFrames >= maxFrames) break
                                var frameEnergy = 0.0
                                for (ch in 0 until channelCount) {
                                    val sample = when (pcmEncoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> ordered.float.toDouble().coerceIn(-1.0, 1.0)
                                        else -> ordered.short.toDouble() / 32768.0
                                    }
                                    val weighted = filters[ch].process(sample)
                                    frameEnergy += weighted * weighted
                                }
                                currentBlockEnergy += frameEnergy
                                framesInBlock++
                                totalFrames++

                                if (framesInBlock >= stepSizeFrames) {
                                    ringEnergies[ringPos] = currentBlockEnergy / stepSizeFrames
                                    ringPos = (ringPos + 1) % 4
                                    currentBlockEnergy = 0.0
                                    framesInBlock = 0
                                    if (totalFrames >= blockSizeFrames) {
                                        val meanBlock = (ringEnergies[0] + ringEnergies[1] + ringEnergies[2] + ringEnergies[3]) * 0.25
                                        blockEnergies.add(meanBlock)
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (totalFrames < sampleRate || blockEnergies.isEmpty()) return null

            // ITU-R BS.1770 dual-stage gating:
            // 1. Absolute gate at -70 LUFS
            val absGatePower = 10.0.pow((-70.0 + 0.691) / 10.0)
            var absSum = 0.0
            var absCount = 0
            for (e in blockEnergies) {
                if (e > absGatePower) {
                    absSum += e
                    absCount++
                }
            }
            if (absCount == 0) return null

            val ungatedMean = absSum / absCount
            val ungatedLufs = -0.691 + 10.0 * log10(ungatedMean)

            // 2. Relative gate at ungatedLufs - 10 LU
            val relGatePower = 10.0.pow(((ungatedLufs - 10.0) + 0.691) / 10.0)
            var relSum = 0.0
            var relCount = 0
            for (e in blockEnergies) {
                if (e > absGatePower && e > relGatePower) {
                    relSum += e
                    relCount++
                }
            }
            if (relCount == 0) return null

            val integratedLufs = -0.691 + 10.0 * log10(relSum / relCount)
            if (!integratedLufs.isFinite()) return null

            // Convert to YouTube loudnessDb convention: positive = louder than -14 LUFS
            return (integratedLufs - TARGET_LUFS).toFloat().coerceIn(-24f, 18f)
        } catch (t: Throwable) {
            Log.w(TAG, "Loudness analysis failed for ${file.name}: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private class KWeightingChannelFilter(sampleRate: Int) {
        private val stage1: Biquad
        private val stage2: Biquad

        init {
            val fs = sampleRate.toDouble().coerceAtLeast(8_000.0)

            // Stage 1: ITU-R BS.1770 high-shelf pre-filter (+4 dB @ ~1681.97 Hz)
            val f0Shelf = 1681.974450955533
            val gDb = 3.999843853973347
            val qShelf = 0.7071752369554196
            val kShelf = kotlin.math.tan(PI * f0Shelf / fs)
            val vh = 10.0.pow(gDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0s = 1.0 + kShelf / qShelf + kShelf * kShelf
            stage1 = Biquad(
                b0 = (vh + vb * kShelf / qShelf + kShelf * kShelf) / a0s,
                b1 = 2.0 * (kShelf * kShelf - vh) / a0s,
                b2 = (vh - vb * kShelf / qShelf + kShelf * kShelf) / a0s,
                a1 = 2.0 * (kShelf * kShelf - 1.0) / a0s,
                a2 = (1.0 - kShelf / qShelf + kShelf * kShelf) / a0s,
            )

            // Stage 2: RLB high-pass filter (~38.135 Hz)
            val f0Hp = 38.13547087602444
            val qHp = 0.5003270373238773
            val kHp = kotlin.math.tan(PI * f0Hp / fs)
            val a0h = 1.0 + kHp / qHp + kHp * kHp
            stage2 = Biquad(
                b0 = 1.0 / a0h,
                b1 = -2.0 / a0h,
                b2 = 1.0 / a0h,
                a1 = 2.0 * (kHp * kHp - 1.0) / a0h,
                a2 = (1.0 - kHp / qHp + kHp * kHp) / a0h,
            )
        }

        fun process(x: Double): Double = stage2.process(stage1.process(x))
    }

    private fun createKWeightingFilters(sampleRate: Int, channelCount: Int): Array<KWeightingChannelFilter> =
        Array(channelCount) { KWeightingChannelFilter(sampleRate) }
}
