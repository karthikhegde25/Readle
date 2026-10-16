package com.readle.app.domain

import java.util.Locale

/**
 * A format-appropriate reading position, persisted as (type, value) strings.
 *
 *  - [PdfPage]: zero-based page index.
 *  - [Reflow]: spine (chapter) index plus a 0..1 scroll fraction inside that chapter.
 *    This is a chapter + offset locator, not an EPUB CFI; it is stable across restarts and
 *    across font-size changes within a chapter to within a screen or so.
 */
sealed interface Locator {
    val type: String
    fun encode(): String

    data class PdfPage(val page: Int) : Locator {
        override val type: String get() = TYPE_PDF
        override fun encode(): String = page.coerceAtLeast(0).toString()
    }

    data class Reflow(val spine: Int, val fraction: Float) : Locator {
        override val type: String get() = TYPE_REFLOW
        override fun encode(): String =
            "${spine.coerceAtLeast(0)}:${String.format(Locale.US, "%.4f", fraction.coerceIn(0f, 1f))}"
    }

    companion object {
        const val TYPE_PDF = "PDF_PAGE"
        const val TYPE_REFLOW = "REFLOW"

        fun decode(type: String?, value: String?): Locator? {
            if (type == null || value == null) return null
            return when (type) {
                TYPE_PDF -> value.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { PdfPage(it) }
                TYPE_REFLOW -> {
                    val parts = value.split(':')
                    if (parts.size != 2) return null
                    val spine = parts[0].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    val fraction = parts[1].trim().toFloatOrNull() ?: return null
                    Reflow(spine, fraction.coerceIn(0f, 1f))
                }
                else -> null
            }
        }

        /** Encodes as "TYPE|value" for passing through navigation routes. */
        fun toRouteArg(locator: Locator): String = "${locator.type}|${locator.encode()}"

        fun fromRouteArg(arg: String?): Locator? {
            if (arg.isNullOrBlank()) return null
            val idx = arg.indexOf('|')
            if (idx <= 0) return null
            return decode(arg.substring(0, idx), arg.substring(idx + 1))
        }
    }
}
