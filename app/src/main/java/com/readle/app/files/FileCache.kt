package com.readle.app.files

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * A bounded, evictable on-device copy of documents that need random access (EPUB is a ZIP archive and
 * a SAF stream is sequential). This is a cache, not an import: the original file stays where it is, is
 * never modified, and the library still points at the original URI.
 */
class FileCache(context: Context, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val resolver = context.applicationContext.contentResolver
    private val dir = File(context.applicationContext.cacheDir, "docs").also { it.mkdirs() }

    init {
        // Temp copies never survive a process restart.
        File(dir, "tmp").deleteRecursively()
    }

    /**
     * A local copy that stays on disk until the cache is cleared or the app restarts. Only used as a
     * fallback when a provider's file descriptor cannot be seeked (PdfRenderer needs seeking).
     */
    suspend fun materializeTemp(uri: Uri, ext: String): File = withContext(Dispatchers.IO) {
        val tmpDir = File(dir, "tmp").also { it.mkdirs() }
        val tmp = File.createTempFile("seek_", ".$ext", tmpDir)
        try {
            val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open $uri")
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
            tmp
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** Copies [uri] into the cache (streaming, never fully in memory) unless an up-to-date copy exists. */
    suspend fun materialize(uri: Uri, docId: Long, size: Long, lastModified: Long, ext: String): File =
        withContext(Dispatchers.IO) {
            val target = File(dir, "${docId}_${lastModified}_${size}.$ext")
            if (target.exists() && target.length() > 0 && (size <= 0 || target.length() == size)) {
                target.setLastModified(System.currentTimeMillis())
                return@withContext target
            }
            val tmp = File(dir, "${target.name}.part")
            try {
                val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open $uri")
                input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            // A newer version of the same document makes older cached copies useless.
            dir.listFiles()?.filter { it.name.startsWith("${docId}_") && it != target }?.forEach { it.delete() }
            evictIfNeeded(keep = target)
            target
        }

    /**
     * Gives [block] a short-lived local copy of [uri] and deletes it afterwards. Used for metadata
     * extraction so scanning a large library does not churn the persistent cache.
     */
    suspend fun <T> withTempCopy(uri: Uri, ext: String, block: suspend (File) -> T): T {
        val tmpDir = File(dir, "tmp").also { it.mkdirs() }
        val tmp = File.createTempFile("scan_", ".$ext", tmpDir)
        try {
            withContext(Dispatchers.IO) {
                val input = resolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open $uri")
                input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
            }
            return block(tmp)
        } finally {
            tmp.delete()
        }
    }

    private fun evictIfNeeded(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.filter { it != keep }.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun sizeBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    companion object {
        const val DEFAULT_MAX_BYTES = 300L * 1024 * 1024
    }
}
