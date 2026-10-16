package com.readle.app.domain

/**
 * A content-based anchor for highlights, quotes and notes in reflowable books: the chapter (spine index)
 * plus character offsets into that chapter's text. Unlike a scroll position or page number, it does not
 * change when the user changes font size, margins or screen size.
 *
 * Stored in the annotation's start/end location columns as "spine:offset".
 */
data class TextAnchor(val spine: Int, val start: Int, val end: Int) {
    init {
        require(spine >= 0 && start >= 0 && end >= start) { "invalid anchor" }
    }

    fun encodeStart(): String = "$spine:$start"
    fun encodeEnd(): String = "$spine:$end"

    companion object {
        fun decode(startLocation: String?, endLocation: String?): TextAnchor? {
            val s = parse(startLocation) ?: return null
            val e = parse(endLocation) ?: return null
            if (s.first != e.first || e.second < s.second) return null
            return TextAnchor(s.first, s.second, e.second)
        }

        private fun parse(value: String?): Pair<Int, Int>? {
            val parts = value?.split(':') ?: return null
            if (parts.size != 2) return null
            val a = parts[0].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val b = parts[1].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return a to b
        }
    }
}

/** Highlight colours offered in the reader, as ARGB ints. */
object HighlightColors {
    const val YELLOW = 0xFFFFE066.toInt()
    const val GREEN = 0xFFA8E6A1.toInt()
    const val BLUE = 0xFF9CCBFF.toInt()
    const val PINK = 0xFFFFB3C7.toInt()
    val all: List<Pair<String, Int>> = listOf(
        "Yellow" to YELLOW, "Green" to GREEN, "Blue" to BLUE, "Pink" to PINK
    )

    fun nameOf(argb: Int): String = all.firstOrNull { it.second == argb }?.first ?: "Custom"

    fun css(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
}
