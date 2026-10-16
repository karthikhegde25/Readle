package com.readle.app.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Bounded cover thumbnails: a small in-memory LRU in front of JPEG files on disk. The disk part is
 * trimmed oldest-first once it exceeds [maxDiskBytes], and a failed write (e.g. low storage) is ignored
 * so a missing thumbnail never breaks the library.
 */
class CoverCache(context: Context, private val maxDiskBytes: Long = 64L * 1024 * 1024) {
    private val dir = File(context.applicationContext.cacheDir, "covers").also { it.mkdirs() }

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun keyFor(docId: Long, lastModified: Long): String = "d${docId}_$lastModified"

    suspend fun save(key: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val scaled = scaleToWidth(bitmap, THUMB_WIDTH)
        try {
            File(dir, "$key.jpg").outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            trimIfNeeded()
        } catch (e: IOException) {
            // Low storage or similar: the library still works, it just shows a placeholder.
        }
        memory.put(key, scaled)
    }

    suspend fun load(key: String): Bitmap? {
        memory.get(key)?.let { if (!it.isRecycled) return it else memory.remove(key) }
        return withContext(Dispatchers.IO) {
            val f = File(dir, "$key.jpg")
            if (!f.exists()) return@withContext null
            val bmp = try {
                BitmapFactory.decodeFile(f.absolutePath)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                f.setLastModified(System.currentTimeMillis())
                memory.put(key, bmp)
            }
            bmp
        }
    }

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun trimIfNeeded() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxDiskBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxDiskBytes * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        const val THUMB_WIDTH = 360

        fun scaleToWidth(src: Bitmap, width: Int): Bitmap {
            if (src.width <= width) return src
            val h = (src.height.toFloat() * width / src.width).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, width, h, true)
        }

        /** Decodes image bytes down to roughly [targetWidth] without allocating the full-size bitmap. */
        fun decodeSampled(bytes: ByteArray, targetWidth: Int = THUMB_WIDTH): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}
