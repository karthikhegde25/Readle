package com.readle.app.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.readle.app.reader.DocumentOpenException
import com.readle.app.reader.OpenFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Thin, thread-safe wrapper over Android's [PdfRenderer] (the real fixed-layout renderer; pages are
 * drawn exactly as authored). PdfRenderer allows only one open page at a time, so every operation is
 * serialised through a mutex and runs off the main thread.
 */
class PdfDocumentHandle private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    val pageCount: Int = renderer.pageCount
    private val mutex = Mutex()
    private val sizes = ConcurrentHashMap<Int, Pair<Int, Int>>()

    /** Page size in PDF points (width, height). */
    suspend fun pageSize(index: Int): Pair<Int, Int> {
        sizes[index]?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                renderer.openPage(index).use { p -> (p.width to p.height).also { sizes[index] = it } }
            }
        }
    }

    /** Height/width of a page if it has already been measured, else null (no I/O). */
    fun cachedPageRatio(index: Int): Float? = sizes[index]?.let { it.second.toFloat() / it.first.coerceAtLeast(1) }

    /**
     * Renders only what is visible: a [viewW] x [viewH] bitmap showing the page scaled by [scale]
     * (pixels per PDF point) with the page's top-left corner at ([tx], [ty]) in bitmap pixels.
     * The memory cost is one viewport no matter how far the user zooms, and the result is sharp at any zoom.
     * Areas outside the page are transparent; the page itself is painted white first.
     */
    suspend fun renderRegion(index: Int, viewW: Int, viewH: Int, scale: Float, tx: Float, ty: Float): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                renderer.openPage(index).use { page ->
                    sizes[index] = page.width to page.height
                    val bmp = Bitmap.createBitmap(viewW.coerceAtLeast(1), viewH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.TRANSPARENT)
                    val paper = android.graphics.Canvas(bmp)
                    val paint = android.graphics.Paint().apply { color = Color.WHITE }
                    paper.drawRect(tx, ty, tx + page.width * scale, ty + page.height * scale, paint)
                    val m = android.graphics.Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(tx, ty)
                    }
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }

    /** Renders a page to a white-backed bitmap whose width is [widthPx] (reduced if it would be huge). */
    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            renderer.openPage(index).use { page ->
                sizes[index] = page.width to page.height
                var w = widthPx.coerceAtLeast(1)
                var h = (w.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                if (w.toLong() * h > MAX_PIXELS) {
                    val scale = sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h)).toFloat()
                    w = (w * scale).roundToInt().coerceAtLeast(1)
                    h = (h * scale).roundToInt().coerceAtLeast(1)
                }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    override fun close() {
        try {
            renderer.close()
        } catch (_: Exception) {
        }
        try {
            pfd.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val MAX_PIXELS = 12_000_000L

        /**
         * Opens [uri]. If the provider hands back a non-seekable descriptor (PdfRenderer needs seeking),
         * [fallbackFile] is asked for a cached local copy and opening is retried once.
         */
        suspend fun open(
            context: Context,
            uri: Uri,
            fallbackFile: (suspend () -> File)? = null
        ): PdfDocumentHandle = withContext(Dispatchers.IO) {
            try {
                openFd(openDescriptor(context, uri))
            } catch (e: NotSeekableException) {
                val file = fallbackFile?.invoke()
                    ?: throw DocumentOpenException(OpenFailure.CORRUPT, "The file source does not allow random access", e)
                openFd(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            }
        }

        private class NotSeekableException(cause: Throwable) : Exception(cause)

        private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw DocumentOpenException(OpenFailure.MISSING, "The provider returned no file")
        } catch (e: FileNotFoundException) {
            throw DocumentOpenException(OpenFailure.MISSING, cause = e)
        } catch (e: SecurityException) {
            throw DocumentOpenException(OpenFailure.PERMISSION, cause = e)
        }

        private fun openFd(pfd: ParcelFileDescriptor): PdfDocumentHandle {
            try {
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount <= 0) {
                    renderer.close()
                    throw DocumentOpenException(OpenFailure.CORRUPT, "The PDF has no pages")
                }
                return PdfDocumentHandle(pfd, renderer)
            } catch (e: DocumentOpenException) {
                pfd.close()
                throw e
            } catch (e: SecurityException) {
                pfd.close()
                throw DocumentOpenException(OpenFailure.PASSWORD, cause = e)
            } catch (e: IOException) {
                pfd.close()
                val seekable = e.message?.contains("seek", ignoreCase = true) == true
                if (seekable) throw NotSeekableException(e)
                throw DocumentOpenException(OpenFailure.CORRUPT, cause = e)
            } catch (e: Exception) {
                pfd.close()
                throw DocumentOpenException(OpenFailure.CORRUPT, cause = e)
            }
        }
    }
}
