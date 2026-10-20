package com.readle.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {
    private val variants = listOf(false to false, false to true, true to false, true to true)

    @Test
    fun bodyTextMeetsWcagAaOnEverySurface() {
        for ((dark, hc) in variants) {
            val t = DesignTokens.of(dark, hc)
            for (bg in listOf(t.background, t.surface, t.surfaceVariant)) {
                assertTrue("primary dark=$dark hc=$hc", Contrast.ratio(t.textPrimary, bg) >= 7.0)
                assertTrue("secondary dark=$dark hc=$hc ratio=${Contrast.ratio(t.textSecondary, bg)}", Contrast.ratio(t.textSecondary, bg) >= 4.5)
            }
        }
    }

    @Test
    fun accentAndErrorAreReadable() {
        for ((dark, hc) in variants) {
            val t = DesignTokens.of(dark, hc)
            assertTrue("accent on surface dark=$dark hc=$hc", Contrast.ratio(t.accent, t.surface) >= 4.5)
            assertTrue("accent on background dark=$dark hc=$hc", Contrast.ratio(t.accent, t.background) >= 4.5)
            assertTrue("onAccent on accent dark=$dark hc=$hc", Contrast.ratio(t.onAccent, t.accent) >= 4.5)
            assertTrue("onAccentContainer dark=$dark hc=$hc", Contrast.ratio(t.onAccentContainer, t.accentContainer) >= 7.0)
            assertTrue("error on surface dark=$dark hc=$hc", Contrast.ratio(t.error, t.surface) >= 4.5)
        }
    }

    @Test
    fun readerTextIsComfortableInBothModes() {
        for (dark in listOf(false, true)) {
            val t = DesignTokens.of(dark, false)
            assertTrue(Contrast.ratio(t.readerText, t.readerPage) >= 7.0)
        }
    }

    @Test
    fun highContrastIsStrictlyStrongerThanDefault() {
        for (dark in listOf(false, true)) {
            val normal = DesignTokens.of(dark, false)
            val high = DesignTokens.of(dark, true)
            assertTrue(Contrast.ratio(high.textSecondary, high.background) > Contrast.ratio(normal.textSecondary, normal.background))
            assertTrue(Contrast.ratio(high.divider, high.background) > Contrast.ratio(normal.divider, normal.background))
        }
    }

    @Test
    fun contrastMathMatchesKnownValues() {
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF777777.toInt(), 0xFF777777.toInt()), 0.0001)
    }
}
