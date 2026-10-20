package com.readle.app.domain

import com.readle.app.data.db.BookmarkEntity
import kotlin.math.abs

object BookmarkRules {
    /** Two reflow bookmarks closer than this fraction of a chapter count as the same place. */
    const val REFLOW_TOLERANCE = 0.01f

    /** The existing bookmark at [locator], if any: same PDF page, or same chapter within ~1% of its length. */
    fun findAt(existing: List<BookmarkEntity>, locator: Locator): BookmarkEntity? = existing.firstOrNull { b ->
        val other = Locator.decode(b.positionType, b.positionValue)
        when {
            other is Locator.PdfPage && locator is Locator.PdfPage -> other.page == locator.page
            other is Locator.Reflow && locator is Locator.Reflow ->
                other.spine == locator.spine && abs(other.fraction - locator.fraction) < REFLOW_TOLERANCE
            else -> false
        }
    }

    /** Prevents accidental duplicates. */
    fun isDuplicate(existing: List<BookmarkEntity>, locator: Locator): Boolean = findAt(existing, locator) != null

    fun defaultLabel(locator: Locator, chapterTitle: String?, overallFraction: Float): String = when (locator) {
        is Locator.PdfPage -> "Page ${locator.page + 1}"
        is Locator.Reflow -> {
            val pct = (overallFraction * 100).toInt().coerceIn(0, 100)
            if (chapterTitle.isNullOrBlank()) "$pct%" else "$chapterTitle · $pct%"
        }
    }
}
