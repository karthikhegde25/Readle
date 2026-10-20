package com.readle.app.reader.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.readle.app.reader.DocumentOpenException
import com.readle.app.reader.OpenFailure
import com.readle.app.reader.reflow.CbzBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A comic archive (.cbz) shown by the same page viewer as PDFs, so pages zoom freely, pan, scroll and turn by tap.
 * Each image is one page. Pages are read from the archive when needed; zoomed views decode only the visible
 * part of the image, so memory stays small however far the user zooms in.
 */
class CbzPageSource private constructor(
    private val zip: ZipFile,
    private val entries: List<ZipEntry>
) : PageSource {

    override val pageCount: Int = entries.size
    private val mutex = Mutex()
    private val sizes = ConcurrentHashMap<Int, Pair<Int, Int>>()
    private val decoders = LinkedHashMap<Int, BitmapRegionDecoder>(4, 0.75f, true)

    override fun cachedPageRatio(index: Int): Float? = sizes[index]?.let { it.second.toFloat() / it.first.coerceAtLeast(1) }

    override suspend fun pageSize(index: Int): Pair<Int, Int> {
        sizes[index]?.let { return it }
        return withContext(Dispatchers.IO) { mutex.withLock { sizeLocked(index) } }
    }

    override suspend fun renderRegion(index: Int, viewW: Int, viewH: Int, scale: Float, tx: Float, ty: Float): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val (iw, ih) = sizeLocked(index)
                val out = Bitmap.createBitmap(viewW.coerceAtLeast(1), viewH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                out.eraseColor(Color.TRANSPARENT)
                val canvas = Canvas(out)
                canvas.drawRect(tx, ty, tx + iw * scale, ty + ih * scale, Paint().apply { color = Color.WHITE })

                // The part of the image that is on screen, in image pixels.
                val l = floor(maxOf(0f, -tx / scale)).toInt().coerceIn(0, iw)
                val t = floor(maxOf(0f, -ty / scale)).toInt().coerceIn(0, ih)
                val r = ceil(minOf(iw.toFloat(), (viewW - tx) / scale)).toInt().coerceIn(0, iw)
                val b = ceil(minOf(ih.toFloat(), (viewH - ty) / scale)).toInt().coerceIn(0, ih)
                if (r > l && b > t) {
                    var sample = 1
                    while (sample < 64 && sample * 2 <= 1f / scale) sample *= 2
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    val part = try {
                        decoderFor(index)?.decodeRegion(Rect(l, t, r, b), BitmapFactory.Options().apply { inSampleSize = sample })
                    } catch (e: Exception) {
                        null
                    }
                    if (part != null) {
                        val m = Matrix().apply {
                            postScale(scale * sample, scale * sample)
                            postTranslate(tx + l * scale, ty + t * scale)
                        }
                        canvas.drawBitmap(part, m, paint)
                        part.recycle()
                    } else {
                        // Formats the region decoder can't handle (GIF, BMP): decode the page at a reduced size instead.
                        val data = bytes(index)
                        val full = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        if (full != null) {
                            val m = Matrix().apply {
                                postScale(scale * iw / full.width, scale * ih / full.height)
                                postTranslate(tx, ty)
                            }
                            canvas.drawBitmap(full, m, paint)
                            full.recycle()
                        }
                    }
                }
                out
            }
        }

    override suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (iw, ih) = sizeLocked(index)
            var w = widthPx.coerceAtLeast(1)
            var h = (w.toFloat() * ih / iw).roundToInt().coerceAtLeast(1)
            if (w.toLong() * h > MAX_PIXELS) {
                val k = sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h)).toFloat()
                w = (w * k).roundToInt().coerceAtLeast(1)
                h = (h * k).roundToInt().coerceAtLeast(1)
            }
            var sample = 1
            while (sample < 64 && iw / (sample * 2) >= w) sample *= 2
            val data = bytes(index)
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("This page image can't be decoded")
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.WHITE)
            Canvas(out).drawBitmap(decoded, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            out
        }
    }

    override fun close() {
        try {
            decoders.values.forEach { runCatching { it.recycle() } }
            decoders.clear()
        } catch (_: Exception) {
        }
        try {
            zip.close()
        } catch (_: IOException) {
        }
    }

    // ---- helpers (call with the mutex held) ----

    private fun bytes(index: Int): ByteArray {
        val e = entries[index]
        if (e.size > MAX_PAGE_BYTES) throw IOException("This page image is too large")
        return zip.getInputStream(e).use { it.readBytes() }
    }

    private fun decoderFor(index: Int): BitmapRegionDecoder? {
        decoders[index]?.let { return it }
        val data = bytes(index)
        @Suppress("DEPRECATION")
        val d = try {
            BitmapRegionDecoder.newInstance(data, 0, data.size, false)
        } catch (e: Exception) {
            null
        } ?: return null
        decoders[index] = d
        while (decoders.size > 3) {
            val eldest = decoders.keys.first()
            decoders.remove(eldest)?.let { runCatching { it.recycle() } }
        }
        return d
    }

    private fun sizeLocked(index: Int): Pair<Int, Int> {
        sizes[index]?.let { return it }
        val fromDecoder = try {
            decoderFor(index)?.let { it.width to it.height }
        } catch (e: Exception) {
            null
        }
        val size = fromDecoder ?: run {
            val data = bytes(index)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, o)
            o.outWidth to o.outHeight
        }
        if (size.first <= 0 || size.second <= 0) throw IOException("This page image can't be read")
        sizes[index] = size
        return size
    }

    companion object {
        private const val MAX_PIXELS = 12_000_000L
        private const val MAX_PAGE_BYTES = 40L * 1024 * 1024

        suspend fun open(file: File): CbzPageSource = withContext(Dispatchers.IO) {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw DocumentOpenException(
                    OpenFailure.UNSUPPORTED,
                    "This isn't a ZIP-based comic archive. RAR-based .cbr files aren't supported; re-save the comic as .cbz.", e
                )
            } catch (e: IOException) {
                throw DocumentOpenException(OpenFailure.CORRUPT, "Could not read the comic archive", e)
            }
            val pages = try {
                CbzBook.pageEntries(zip)
            } catch (e: Exception) {
                zip.close()
                throw DocumentOpenException(OpenFailure.CORRUPT, "The comic archive is damaged", e)
            }
            if (pages.isEmpty()) {
                zip.close()
                throw DocumentOpenException(OpenFailure.CORRUPT, "The archive contains no page images")
            }
            CbzPageSource(zip, pages)
        }
    }
}
