package com.readle.app.domain

import com.readle.app.data.db.BookReaderSettingsEntity
import com.readle.app.data.db.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRulesTest {

    private fun bm(id: Long, locator: Locator) = BookmarkEntity(
        id = id, documentId = 1, label = "b$id", positionType = locator.type,
        positionValue = locator.encode(), progressFraction = 0f, createdAt = 0
    )

    @Test
    fun pdfBookmarksDuplicateOnlyOnTheSamePage() {
        val existing = listOf(bm(1, Locator.PdfPage(4)))
        assertTrue(BookmarkRules.isDuplicate(existing, Locator.PdfPage(4)))
        assertFalse(BookmarkRules.isDuplicate(existing, Locator.PdfPage(5)))
        assertEquals(1L, BookmarkRules.findAt(existing, Locator.PdfPage(4))?.id)
        assertNull(BookmarkRules.findAt(existing, Locator.PdfPage(0)))
    }

    @Test
    fun reflowBookmarksUseAChapterAndToleranceWindow() {
        val existing = listOf(bm(1, Locator.Reflow(2, 0.50f)))
        assertTrue(BookmarkRules.isDuplicate(existing, Locator.Reflow(2, 0.505f)))
        assertFalse(BookmarkRules.isDuplicate(existing, Locator.Reflow(2, 0.52f)))
        assertFalse(BookmarkRules.isDuplicate(existing, Locator.Reflow(3, 0.50f)))
        // A PDF locator never matches a reflow bookmark.
        assertFalse(BookmarkRules.isDuplicate(existing, Locator.PdfPage(2)))
    }

    @Test
    fun defaultLabels() {
        assertEquals("Page 8", BookmarkRules.defaultLabel(Locator.PdfPage(7), null, 0.1f))
        assertEquals("Chapter 3 · 42%", BookmarkRules.defaultLabel(Locator.Reflow(2, 0.3f), "Chapter 3", 0.42f))
        assertEquals("42%", BookmarkRules.defaultLabel(Locator.Reflow(2, 0.3f), "  ", 0.42f))
    }

    // ---- per-book typography overrides ----

    private val global = TypographySettings(
        theme = ReaderTheme.DAY, font = ReaderFont.SERIF, fontSizePercent = 100,
        lineSpacing = 1.5f, paragraphSpacing = 0.6f, marginDp = 16, align = ReaderAlign.LEFT
    )

    @Test
    fun noOverrideMeansGlobalSettings() {
        assertSame(global, TypographyMerge.effective(global, null))
    }

    @Test
    fun nullColumnsFallThroughAndSetColumnsWin() {
        val o = BookReaderSettingsEntity(documentId = 1, theme = "NIGHT", fontSizePercent = 150)
        val e = TypographyMerge.effective(global, o)
        assertEquals(ReaderTheme.NIGHT, e.theme)
        assertEquals(150, e.fontSizePercent)
        assertEquals(ReaderFont.SERIF, e.font)
        assertEquals(1.5f, e.lineSpacing, 0f)
        assertEquals(16, e.marginDp)
    }

    @Test
    fun invalidOrOutOfRangeOverridesAreSanitised() {
        val o = BookReaderSettingsEntity(
            documentId = 1, theme = "NOT_A_THEME", fontSizePercent = 9999, lineSpacing = 0.1f, marginDp = -5, textAlign = "??"
        )
        val e = TypographyMerge.effective(global, o)
        assertEquals(ReaderTheme.DAY, e.theme)
        assertEquals(TypographySettings.MAX_FONT_PERCENT, e.fontSizePercent)
        assertEquals(TypographySettings.MIN_LINE_SPACING, e.lineSpacing, 0f)
        assertEquals(TypographySettings.MIN_MARGIN, e.marginDp)
        assertEquals(ReaderAlign.LEFT, e.align)
    }

    @Test
    fun entityRoundTripPreservesEverything() {
        val t = TypographySettings(ReaderTheme.SEPIA, ReaderFont.MONO, 130, 1.9f, 0.3f, 24, ReaderAlign.JUSTIFY)
        val entity = TypographyMerge.toEntity(7, t)
        assertEquals(7L, entity.documentId)
        assertNotNull(entity.theme)
        assertEquals(t, TypographyMerge.effective(global, entity))
    }
}
