package com.nexora.aicamera.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import com.google.android.gms.media.effect.enhancement.Enhancement
import com.google.android.gms.media.effect.enhancement.EnhancementCallback
import com.google.android.gms.media.effect.enhancement.EnhancementMode
import com.google.android.gms.media.effect.enhancement.EnhancementOptions
import com.google.android.gms.media.effect.enhancement.EnhancementSession
import com.google.android.gms.media.effect.enhancement.EnhancementSessionCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * NEXORA's photo finishing pipeline.
 * Google Media Enhancement is used where supported; every result then receives
 * a controlled detail/color finish. Unsupported devices use the same local finish
 * without pretending to generate detail that was never captured.
 */
class PhotoEnhancer(private val context: Context) {

    data class Result(
        val bitmap: Bitmap,
        val engineName: String,
        val note: String
    )

    suspend fun enhance(source: Bitmap, strength: Float, vivid: Boolean): Result =
        withContext(Dispatchers.IO) {
            val safeStrength = strength.coerceIn(0f, 1f)
            try {
                val aiResult = runGoogleEnhancement(source)
                val aiBlend = blend(source, aiResult, safeStrength)
                // Drop the intermediate AI bitmap as soon as it is no longer needed.
                if (aiResult !== source && aiResult !== aiBlend && !aiResult.isRecycled) {
                    aiResult.recycle()
                }
                val finished = try {
                    LocalImagePipeline.enhance(aiBlend, safeStrength, vivid)
                } finally {
                    if (aiBlend !== source && !aiBlend.isRecycled) aiBlend.recycle()
                }
                Result(
                    bitmap = finished,
                    engineName = "NEXORA AI · DETAIL PRO",
                    note = "AI deblur/denoise dan tone mapping dilanjutkan dengan pengurangan noise warna, pemulihan micro-detail, local contrast, sharpening berbasis tepi, highlight roll-off, dan vibrance adaptif."
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OutOfMemoryError) {
                val reduced = downscale(source, 2600)
                val fallback = LocalImagePipeline.enhance(reduced, safeStrength * 0.88f, vivid)
                Result(
                    bitmap = fallback,
                    engineName = "NEXORA DETAIL LITE",
                    note = "Memori perangkat terbatas. Foto diproses pada resolusi lebih ringan dengan detail dan warna yang tetap dijaga."
                )
            } catch (error: Exception) {
                val fallback = LocalImagePipeline.enhance(source, safeStrength, vivid)
                Result(
                    bitmap = fallback,
                    engineName = "NEXORA DETAIL ENGINE",
                    note = "AI hardware Google tidak tersedia di perangkat ini. Dipakai pemrosesan lokal multi-tahap: denoise adaptif, micro-contrast, edge sharpening, highlight roll-off, dan vibrance."
                )
            }
        }

    private suspend fun runGoogleEnhancement(source: Bitmap): Bitmap {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Google Media Enhancement requires Android 11 or newer."
        }

        val client = Enhancement.getClient(context)
        check(client.isDeviceSupported().await()) { "This device does not support Media Enhancement." }

        if (!client.isModuleInstalled().await()) {
            check(client.installModule(null).await()) { "AI enhancement module could not be installed." }
        }

        // Avoid a possible 4x pixel-count jump on already large camera files.
        val pixelCount = source.width.toLong() * source.height.toLong()
        val enablePhotoUpscale = pixelCount <= 5_000_000L && max(source.width, source.height) <= 2600
        val options = EnhancementOptions(
            source.width,
            source.height,
            EnhancementMode.BITMAP,
            true,
            true,
            false,
            enablePhotoUpscale,
            false
        )

        val sessionCallback = object : EnhancementSessionCallback {
            override fun onSessionCreated(session: EnhancementSession) = Unit
            override fun onSessionCreationFailed(status: com.google.android.gms.common.api.Status) = Unit
            override fun onSessionDestroyed() = Unit
            override fun onSessionDisconnected(status: com.google.android.gms.common.api.Status) = Unit
        }

        val session = client.createSession(options, sessionCallback).await()
            ?: error("AI enhancement session could not be created.")

        return try {
            suspendCancellableCoroutine { continuation ->
                session.process(source, options, object : EnhancementCallback {
                    override fun onBitmapProcessed(bitmap: Bitmap) {
                        if (continuation.isActive) continuation.resume(bitmap)
                    }

                    override fun onError(statusCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Media Enhancement failed with status $statusCode")
                            )
                        }
                    }

                    override fun onSurfaceProcessed(timestamp: Long) = Unit

                    override fun onCancelled(statusCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("Media Enhancement was cancelled with status $statusCode")
                            )
                        }
                    }
                })
            }
        } finally {
            session.release()
        }
    }

    private fun blend(original: Bitmap, enhanced: Bitmap, strength: Float): Bitmap {
        if (strength >= 0.999f) return enhanced
        if (strength <= 0.001f) return original

        val output = Bitmap.createBitmap(enhanced.width, enhanced.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bounds = Rect(0, 0, enhanced.width, enhanced.height)
        val originalPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = ((1f - strength) * 255).roundToInt()
        }
        val enhancedPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (strength * 255).roundToInt()
        }
        canvas.drawBitmap(original, null, bounds, originalPaint)
        canvas.drawBitmap(enhanced, null, bounds, enhancedPaint)
        return output
    }

    private fun downscale(source: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(source.width, source.height)
        if (edge <= maxEdge) return source
        val ratio = maxEdge.toFloat() / edge.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }
}

/**
 * Detail-first CPU finishing pipeline used after Google's AI and as a fallback.
 * It works on luminance separately from chroma so sharpening does not create
 * coloured halos. A soft threshold removes low-amplitude noise in flat regions;
 * stronger edges and texture get a controlled micro-contrast boost.
 */
private object LocalImagePipeline {

    fun enhance(source: Bitmap, strength: Float, vivid: Boolean): Bitmap {
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0.001f) return source

        val width = source.width
        val height = source.height
        require(width > 0 && height > 0) { "Invalid source bitmap dimensions." }

        // First pass: estimate scene contrast from a luminance histogram.
        val histogram = IntArray(256)
        val sampleRow = IntArray(width)
        var total = 0L
        for (y in 0 until height) {
            source.getPixels(sampleRow, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                histogram[luminance(sampleRow[x]).roundToInt().coerceIn(0, 255)]++
                total++
                x++
            }
        }
        val p05 = percentile(histogram, total, 0.05f)
        val p95 = percentile(histogram, total, 0.95f)
        val sceneRange = (p95 - p05).coerceAtLeast(1f)
        // Low-dynamic-range scenes get slightly more contrast, not an exposure lift.
        val contrastGain = 1f + amount * (if (sceneRange < 105f) 0.145f else 0.085f)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        var abovePixels = IntArray(width)
        var currentPixels = IntArray(width)
        var belowPixels = IntArray(width)
        var aboveLuma = FloatArray(width)
        var currentLuma = FloatArray(width)
        var belowLuma = FloatArray(width)
        val outputRow = IntArray(width)

        loadRow(source, 0, width, abovePixels, aboveLuma)
        loadRow(source, 0, width, currentPixels, currentLuma)
        loadRow(source, min(1, height - 1), width, belowPixels, belowLuma)

        val colorBoostBase = (if (vivid) 0.255f else 0.145f) * amount

        for (y in 0 until height) {
            for (x in 0 until width) {
                val left = max(0, x - 1)
                val right = min(width - 1, x + 1)
                val up = aboveLuma[x]
                val center = currentLuma[x]
                val down = belowLuma[x]

                // 3x3 Gaussian local base (1-2-1 kernel).
                val topSum = aboveLuma[left] + 2f * up + aboveLuma[right]
                val middleSum = currentLuma[left] + 2f * center + currentLuma[right]
                val bottomSum = belowLuma[left] + 2f * down + belowLuma[right]
                val localBase = (topSum + 2f * middleSum + bottomSum) * (1f / 16f)
                val highFrequency = center - localBase

                // A strong gradient protects real edges; flat areas get more noise suppression.
                val gradient = abs(currentLuma[left] - currentLuma[right]) + abs(up - down)
                val edgeStrength = (gradient / 54f).coerceIn(0f, 1f)
                val flatness = 1f - edgeStrength
                val noiseThreshold = (1.6f + 3.0f * flatness) * amount
                val cleanDetail = if (abs(highFrequency) <= noiseThreshold) {
                    highFrequency * 0.22f
                } else {
                    sign(highFrequency) * (abs(highFrequency) - noiseThreshold * 0.38f)
                }
                val detailGain = 1f + amount * (0.48f + 0.40f * edgeStrength)
                val detailDelta = cleanDetail * detailGain - highFrequency

                // Gentle S-curve around the midtones, with a soft shoulder and black protection.
                var tone = 128f + (center - 128f) * contrastGain
                if (tone > 226f) tone = 226f + (tone - 226f) * (1f - 0.30f * amount)
                if (tone < 20f) tone = 20f + (tone - 20f) * (1f - 0.12f * amount)
                val normalizedMid = ((tone - 128f) / 128f).coerceIn(-1f, 1f)
                tone += 7.5f * amount * normalizedMid * (1f - abs(normalizedMid))
                val finalLuma = (tone + detailDelta).coerceIn(0f, 255f)

                val pixel = currentPixels[x]
                val a = pixel ushr 24 and 0xFF
                val r = red(pixel)
                val g = green(pixel)
                val b = blue(pixel)

                // Bilateral-like chroma smoothing suppresses colour speckles in flat areas,
                // but reduces the blend at edges to avoid bleeding across object boundaries.
                val leftPixel = currentPixels[left]
                val rightPixel = currentPixels[right]
                val upPixel = abovePixels[x]
                val downPixel = belowPixels[x]
                val wl = rangeWeight(abs(currentLuma[left] - center))
                val wr = rangeWeight(abs(currentLuma[right] - center))
                val wu = rangeWeight(abs(up - center))
                val wd = rangeWeight(abs(down - center))
                val weightSum = (wl + wr + wu + wd).coerceAtLeast(0.001f)
                val meanChromaR = (
                    (red(leftPixel) - currentLuma[left]) * wl +
                        (red(rightPixel) - currentLuma[right]) * wr +
                        (red(upPixel) - up) * wu +
                        (red(downPixel) - down) * wd
                    ) / weightSum
                val meanChromaG = (
                    (green(leftPixel) - currentLuma[left]) * wl +
                        (green(rightPixel) - currentLuma[right]) * wr +
                        (green(upPixel) - up) * wu +
                        (green(downPixel) - down) * wd
                    ) / weightSum
                val meanChromaB = (
                    (blue(leftPixel) - currentLuma[left]) * wl +
                        (blue(rightPixel) - currentLuma[right]) * wr +
                        (blue(upPixel) - up) * wu +
                        (blue(downPixel) - down) * wd
                    ) / weightSum
                val chromaMix = 0.12f * amount * flatness
                val cr = (r - center) * (1f - chromaMix) + meanChromaR * chromaMix
                val cg = (g - center) * (1f - chromaMix) + meanChromaG * chromaMix
                val cb = (b - center) * (1f - chromaMix) + meanChromaB * chromaMix

                // Adaptive vibrance: muted colours get more lift than already vivid ones.
                // Skin-like hues receive a lower boost to avoid orange/red faces.
                val maxChannel = max(r, max(g, b)).toFloat()
                val minChannel = min(r, min(g, b)).toFloat()
                val chroma = maxChannel - minChannel
                val mutedFactor = (1f - chroma / 210f).coerceIn(0f, 1f)
                val skinLike = r > 60 && g > 35 && b > 18 && r > g && g > b &&
                    (r - g) > 8 && (g - b) > 4
                val vibrance = colorBoostBase * mutedFactor * (if (skinLike) 0.36f else 1f)
                val colorScale = 1f + vibrance

                val outR = (finalLuma + cr * colorScale).roundToInt().coerceIn(0, 255)
                val outG = (finalLuma + cg * colorScale).roundToInt().coerceIn(0, 255)
                val outB = (finalLuma + cb * colorScale).roundToInt().coerceIn(0, 255)
                outputRow[x] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
            output.setPixels(outputRow, 0, width, 0, y, width, 1)

            if (y < height - 1) {
                val oldPixels = abovePixels
                abovePixels = currentPixels
                currentPixels = belowPixels
                belowPixels = oldPixels

                val oldLuma = aboveLuma
                aboveLuma = currentLuma
                currentLuma = belowLuma
                belowLuma = oldLuma
                loadRow(source, min(y + 2, height - 1), width, belowPixels, belowLuma)
            }
        }
        return output
    }

    private fun loadRow(bitmap: Bitmap, y: Int, width: Int, pixels: IntArray, luma: FloatArray) {
        bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
        for (x in 0 until width) luma[x] = luminance(pixels[x])
    }

    private fun percentile(histogram: IntArray, total: Long, fraction: Float): Float {
        val target = (total * fraction).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative >= target) return i.toFloat()
        }
        return 255f
    }

    private fun rangeWeight(delta: Float): Float = 1f / (1f + delta / 18f)

    private fun luminance(color: Int): Float {
        val r = red(color).toFloat()
        val g = green(color).toFloat()
        val b = blue(color).toFloat()
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun red(color: Int): Int = color ushr 16 and 0xFF
    private fun green(color: Int): Int = color ushr 8 and 0xFF
    private fun blue(color: Int): Int = color and 0xFF
}
