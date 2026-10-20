package com.readle.app.reader.reflow

import java.io.Closeable
import java.io.InputStream

/** One chapter-sized unit in reading order. [sizeBytes] weights it when computing whole-book progress. */
data class SpineItem(val path: String, val sizeBytes: Long)

/** A table-of-contents entry already resolved to a spine position. */
data class TocEntry(val title: String, val spineIndex: Int, val fragment: String?, val depth: Int)

data class BookMeta(
    val title: String?,
    val author: String?,
    val series: String?,
    val language: String?,
    /** Zip path of the cover image, when the book declares one. */
    val coverPath: String?
)

class ResourceData(val mime: String, val stream: InputStream)

/** Why a document could not be opened. Mapped to a user-readable message by the UI. */
class BookOpenException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { CORRUPT, UNSUPPORTED, TOO_LARGE, DRM }
}

/**
 * A reflowable document: an ordered list of chapter resources the WebView can load.
 * Implemented by [EpubBook] and [TxtBook]; the reader UI only depends on this interface.
 */
interface ReflowBook : Closeable {
    val meta: BookMeta
    val spine: List<SpineItem>
    val toc: List<TocEntry>

    /** Opens a resource (chapter, stylesheet, image, font) by normalised path, or null if absent. */
    fun open(path: String): ResourceData?

    /** Plain text of a chapter, used for search. */
    fun chapterText(index: Int): String

    fun chapterTitle(index: Int): String {
        toc.firstOrNull { it.spineIndex == index }?.let { return it.title }
        return "Section ${index + 1}"
    }

    fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.path == path }
}

/** Whole-book progress maths, weighting chapters by their size so progress is roughly linear in text read. */
object ReflowMath {
    private fun weights(spine: List<SpineItem>): List<Long> = spine.map { it.sizeBytes.coerceAtLeast(1L) }

    fun overallFraction(spine: List<SpineItem>, spineIndex: Int, fraction: Float): Float {
        if (spine.isEmpty()) return 0f
        val w = weights(spine)
        val total = w.sum().toDouble()
        val idx = spineIndex.coerceIn(0, spine.lastIndex)
        val before = w.take(idx).sum().toDouble()
        val within = w[idx] * fraction.coerceIn(0f, 1f).toDouble()
        return ((before + within) / total).toFloat().coerceIn(0f, 1f)
    }

    /** Inverse of [overallFraction]. */
    fun locate(spine: List<SpineItem>, overall: Float): Pair<Int, Float> {
        if (spine.isEmpty()) return 0 to 0f
        val w = weights(spine)
        val total = w.sum().toDouble()
        val target = overall.coerceIn(0f, 1f) * total
        var acc = 0.0
        for (i in w.indices) {
            if (target <= acc + w[i] || i == w.lastIndex) {
                val frac = ((target - acc) / w[i]).toFloat().coerceIn(0f, 1f)
                return i to frac
            }
            acc += w[i]
        }
        return spine.lastIndex to 1f
    }
}
