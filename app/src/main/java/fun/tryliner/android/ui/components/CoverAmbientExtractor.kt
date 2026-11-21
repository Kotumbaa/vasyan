package `fun`.tryliner.android.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import `fun`.tryliner.android.ui.theme.LinerColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CoverAmbientResult(
    val accentColor: Color,
    val backdropColor: Color,
    val blurredBitmap: ImageBitmap?
)

object CoverAmbientExtractor {
    private const val SAMPLE_SIZE = 48
    private const val BLUR_RADIUS = 8

    private val cache = object : LruCache<String, CoverAmbientResult>(64) {}

    fun getCached(coverUrl: String?): CoverAmbientResult? {
        if (coverUrl.isNullOrBlank()) return null
        return synchronized(cache) { cache.get(coverUrl) }
    }

    suspend fun extract(context: Context, coverUrl: String): CoverAmbientResult? {
        getCached(coverUrl)?.let { return it }

        return withContext(Dispatchers.IO) {
            val imageLoader = SingletonImageLoader.get(context)
            val request = ImageRequest.Builder(context)
                .data(coverUrl)
                .size(SAMPLE_SIZE, SAMPLE_SIZE)
                .allowHardware(false)
                .build()

            val result = runCatching { imageLoader.execute(request) }.getOrNull()
            val drawableBitmap = (result as? SuccessResult)?.image?.toBitmap()
                ?: return@withContext null

            withContext(Dispatchers.Default) {
                val safeBitmap = if (drawableBitmap.config == Bitmap.Config.ARGB_8888 &&
                    drawableBitmap.width <= SAMPLE_SIZE &&
                    drawableBitmap.height <= SAMPLE_SIZE
                ) {
                    drawableBitmap
                } else {
                    val w = drawableBitmap.width.coerceIn(1, SAMPLE_SIZE)
                    val h = drawableBitmap.height.coerceIn(1, SAMPLE_SIZE)
                    Bitmap.createScaledBitmap(drawableBitmap, w, h, true)
                        .copy(Bitmap.Config.ARGB_8888, false)
                }

                val (accentColor, backdropColor) = extractAmbientColors(safeBitmap)
                val blurred = fastBoxBlur(safeBitmap, BLUR_RADIUS).asImageBitmap()

                val ambient = CoverAmbientResult(
                    accentColor = accentColor,
                    backdropColor = backdropColor,
                    blurredBitmap = blurred
                )
                synchronized(cache) {
                    cache.put(coverUrl, ambient)
                }
                ambient
            }
        }
    }

    /**
     * Port of desktop MiniPlayer.tsx `extractColorsFromCanvas`:
     * samples vibrant pixels weighted by saturation and mid-lightness,
     * returning a luminous accent color (for mini-player progress fill)
     * and a deeper desaturated ambient color (for backdrops).
     */
    private fun extractAmbientColors(bitmap: Bitmap): Pair<Color, Color> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalWeight = 0.0
        var weightedR = 0.0
        var weightedG = 0.0
        var weightedB = 0.0
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L

        val hsl = FloatArray(3)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalR += r
            totalG += g
            totalB += b

            rgbToHsl(r, g, b, hsl)
            val s = hsl[1]
            val l = hsl[2]
            if (l > 0.08f && l < 0.95f && s > 0.10f) {
                val vibrancy = s * (1f - abs(l - 0.5f) * 1.5f)
                val weight = max(0.01f, vibrancy * vibrancy).toDouble()
                weightedR += r * weight
                weightedG += g * weight
                weightedB += b * weight
                totalWeight += weight
            }
        }

        val count = pixels.size.coerceAtLeast(1)
        val finalR = if (totalWeight > 0.5) (weightedR / totalWeight).roundToInt() else (totalR / count).toInt()
        val finalG = if (totalWeight > 0.5) (weightedG / totalWeight).roundToInt() else (totalG / count).toInt()
        val finalB = if (totalWeight > 0.5) (weightedB / totalWeight).roundToInt() else (totalB / count).toInt()

        rgbToHsl(finalR.coerceIn(0, 255), finalG.coerceIn(0, 255), finalB.coerceIn(0, 255), hsl)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]

        if (s < 0.12f) {
            return Color.White to LinerColors.AmbientBlue
        }

        val accentL = max(0.65f, min(0.82f, if (l < 0.45f) 0.70f else l))
        val accentS = max(0.70f, s)
        val accentRgb = hslToRgb(h, accentS, accentL)
        val accentColor = Color(accentRgb[0], accentRgb[1], accentRgb[2])

        val backdropS = s.coerceIn(0.28f, 0.62f)
        val backdropL = l.coerceIn(0.18f, 0.34f)
        val backdropRgb = hslToRgb(h, backdropS, backdropL)
        val backdropColor = Color(backdropRgb[0], backdropRgb[1], backdropRgb[2])

        return accentColor to backdropColor
    }

    /**
     * Fast 2-pass separable box blur on low-res bitmap (used on all APIs;
     * on API 31+ Compose layers can also apply RenderEffect blur on top).
     */
    private fun fastBoxBlur(source: Bitmap, radius: Int): Bitmap {
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        val tmp = IntArray(w * h)
        val dst = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)

        val window = radius * 2 + 1
        // Two passes approximate a smooth Gaussian blur
        repeat(2) { pass ->
            val input = if (pass == 0) src else dst
            // Horizontal pass
            for (y in 0 until h) {
                val rowOffset = y * w
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dx in -radius..radius) {
                    val px = input[rowOffset + dx.coerceIn(0, w - 1)]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                }
                for (x in 0 until w) {
                    tmp[rowOffset + x] = (0xFF shl 24) or
                        ((rSum / window) shl 16) or
                        ((gSum / window) shl 8) or
                        (bSum / window)

                    val outIdx = (x - radius).coerceIn(0, w - 1)
                    val inIdx = (x + radius + 1).coerceIn(0, w - 1)
                    val pxOut = input[rowOffset + outIdx]
                    val pxIn = input[rowOffset + inIdx]
                    rSum += ((pxIn shr 16) and 0xFF) - ((pxOut shr 16) and 0xFF)
                    gSum += ((pxIn shr 8) and 0xFF) - ((pxOut shr 8) and 0xFF)
                    bSum += (pxIn and 0xFF) - (pxOut and 0xFF)
                }
            }
            // Vertical pass
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dy in -radius..radius) {
                    val px = tmp[dy.coerceIn(0, h - 1) * w + x]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = (0xFF shl 24) or
                        ((rSum / window) shl 16) or
                        ((gSum / window) shl 8) or
                        (bSum / window)

                    val outIdx = (y - radius).coerceIn(0, h - 1)
                    val inIdx = (y + radius + 1).coerceIn(0, h - 1)
                    val pxOut = tmp[outIdx * w + x]
                    val pxIn = tmp[inIdx * w + x]
                    rSum += ((pxIn shr 16) and 0xFF) - ((pxOut shr 16) and 0xFF)
                    gSum += ((pxIn shr 8) and 0xFF) - ((pxOut shr 8) and 0xFF)
                    bSum += (pxIn and 0xFF) - (pxOut and 0xFF)
                }
            }
        }

        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = max(rf, max(gf, bf))
        val min = min(rf, min(gf, bf))
        val l = (max + min) / 2f
        var h = 0f
        var s = 0f

        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                rf -> (gf - bf) / d + (if (gf < bf) 6f else 0f)
                gf -> (bf - rf) / d + 2f
                else -> (rf - gf) / d + 4f
            }
            h /= 6f
        }
        out[0] = h
        out[1] = s
        out[2] = l
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
        if (s == 0f) {
            val v = (l * 255f).roundToInt().coerceIn(0, 255)
            return intArrayOf(v, v, v)
        }
        fun hue2rgb(p: Float, q: Float, tRaw: Float): Float {
            var t = tRaw
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 0.5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val r = (hue2rgb(p, q, h + 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
        val g = (hue2rgb(p, q, h) * 255f).roundToInt().coerceIn(0, 255)
        val b = (hue2rgb(p, q, h - 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
        return intArrayOf(r, g, b)
    }
}

@Composable
fun rememberCoverAmbient(coverUrl: String?): CoverAmbientResult? {
    val context = LocalContext.current.applicationContext
    var ambient by remember(coverUrl) {
        mutableStateOf(CoverAmbientExtractor.getCached(coverUrl))
    }

    LaunchedEffect(coverUrl) {
        if (coverUrl.isNullOrBlank()) {
            ambient = null
            return@LaunchedEffect
        }
        val cached = CoverAmbientExtractor.getCached(coverUrl)
        if (cached != null) {
            ambient = cached
        } else {
            val extracted = CoverAmbientExtractor.extract(context, coverUrl)
            if (extracted != null) {
                ambient = extracted
            }
        }
    }

    return ambient
}
