package com.readle.app.ui.theme

/**
 * Readle's design tokens as plain ARGB ints (no Compose dependency, so contrast can be unit-tested).
 * Token names follow the product spec: background, surface, surfaceVariant, textPrimary, textSecondary,
 * divider, accent, error, readerPage, readerText, highlight.
 */
data class DesignTokens(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val divider: Int,
    val accent: Int,
    val onAccent: Int,
    val accentContainer: Int,
    val onAccentContainer: Int,
    val error: Int,
    val readerPage: Int,
    val readerText: Int,
    val highlight: Int
) {
    companion object {
        private val LIGHT = DesignTokens(
            background = 0xFFFBF8F3.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            surfaceVariant = 0xFFF1ECE3.toInt(),
            textPrimary = 0xFF1D2328.toInt(),
            textSecondary = 0xFF565E66.toInt(),
            divider = 0xFFD9D2C5.toInt(),
            accent = 0xFF2F4B5C.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            accentContainer = 0xFFDCE9F0.toInt(),
            onAccentContainer = 0xFF10242F.toInt(),
            error = 0xFFB3261E.toInt(),
            readerPage = 0xFFFFFFFF.toInt(),
            readerText = 0xFF1B1B1B.toInt(),
            highlight = 0xFFFFE066.toInt()
        )

        private val DARK = DesignTokens(
            background = 0xFF121417.toInt(),
            surface = 0xFF1B1F23.toInt(),
            surfaceVariant = 0xFF262B30.toInt(),
            textPrimary = 0xFFE6E8EA.toInt(),
            textSecondary = 0xFFA3AAB1.toInt(),
            divider = 0xFF3A4046.toInt(),
            accent = 0xFF8CB8D0.toInt(),
            onAccent = 0xFF0B1E29.toInt(),
            accentContainer = 0xFF24404F.toInt(),
            onAccentContainer = 0xFFDCE9F0.toInt(),
            error = 0xFFF2B8B5.toInt(),
            readerPage = 0xFF121212.toInt(),
            readerText = 0xFFD6D6D6.toInt(),
            highlight = 0xFF8C7A12.toInt()
        )

        /** High contrast pushes secondary text and dividers to the strongest values the palette allows. */
        fun of(dark: Boolean, highContrast: Boolean): DesignTokens {
            val base = if (dark) DARK else LIGHT
            if (!highContrast) return base
            return if (dark) base.copy(
                textPrimary = 0xFFFFFFFF.toInt(), textSecondary = 0xFFE6E8EA.toInt(),
                divider = 0xFF9AA3AB.toInt(), accent = 0xFFB9DAEC.toInt(), background = 0xFF000000.toInt(),
                surface = 0xFF0B0D0F.toInt()
            ) else base.copy(
                textPrimary = 0xFF000000.toInt(), textSecondary = 0xFF1D2328.toInt(),
                divider = 0xFF5A5F66.toInt(), accent = 0xFF12303F.toInt(), background = 0xFFFFFFFF.toInt()
            )
        }
    }
}

object Contrast {
    private fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    /** WCAG contrast ratio between two colours (1..21). */
    fun ratio(a: Int, b: Int): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}
