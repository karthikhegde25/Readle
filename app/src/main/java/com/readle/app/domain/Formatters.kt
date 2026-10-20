package com.readle.app.domain

import java.util.Locale

object Formatters {
    /** 1536 -> "1.5 KB". Decimal units, one decimal place from KB upwards. */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "Unknown size"
        if (bytes < 1000) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1000 && i < units.lastIndex) {
            value /= 1000
            i++
        }
        return if (value >= 100) String.format(Locale.US, "%.0f %s", value, units[i])
        else String.format(Locale.US, "%.1f %s", value, units[i])
    }

    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()

    /** Spoken description of a library row for screen readers. */
    fun describe(item: LibraryItem): String = buildString {
        append(item.doc.title)
        item.doc.author?.let { append(", by ").append(it) }
        item.format?.let { append(", ").append(it.label) }
        when (item.status) {
            ReadStatus.UNREAD -> append(", not started")
            ReadStatus.READING -> append(", ").append(percent(item.progressFraction)).append(" percent read")
            ReadStatus.FINISHED -> append(", finished")
        }
        if (item.doc.isFavorite) append(", favorite")
        if (!item.isAvailable) append(", file unavailable")
        if (item.isDuplicate) append(", possible duplicate")
    }

    /** "Chapter 3 · 42%" style subtitle used in the library's compact rows. */
    fun subtitle(item: LibraryItem): String {
        val parts = ArrayList<String>()
        item.doc.author?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        item.format?.let { parts.add(it.label) }
        if (item.status == ReadStatus.READING) parts.add("${percent(item.progressFraction)}%")
        if (item.status == ReadStatus.FINISHED) parts.add("Finished")
        return parts.joinToString(" · ")
    }
}
