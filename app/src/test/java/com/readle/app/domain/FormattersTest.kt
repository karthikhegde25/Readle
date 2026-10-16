package com.readle.app.domain

import com.readle.app.data.db.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {
    @Test
    fun fileSizes() {
        assertEquals("Unknown size", Formatters.fileSize(0))
        assertEquals("Unknown size", Formatters.fileSize(-5))
        assertEquals("999 B", Formatters.fileSize(999))
        assertEquals("1.5 KB", Formatters.fileSize(1_500))
        assertEquals("2.3 MB", Formatters.fileSize(2_345_678))
        assertEquals("120 MB", Formatters.fileSize(120_000_000))
        assertEquals("1.2 GB", Formatters.fileSize(1_234_000_000))
    }

    @Test
    fun percentIsClamped() {
        assertEquals(0, Formatters.percent(-1f))
        assertEquals(42, Formatters.percent(0.425f))
        assertEquals(100, Formatters.percent(3f))
    }

    private fun item(progress: Float?, fav: Boolean = false, available: Boolean = true, author: String? = "Jane") = LibraryItem(
        doc = DocumentEntity(
            id = 1, uri = "u", displayName = "t.epub", title = "T", author = author, format = DocFormat.EPUB.name,
            isFavorite = fav, availabilityStatus = if (available) DocumentEntity.AVAILABLE else DocumentEntity.UNAVAILABLE
        ),
        progressFraction = progress ?: 0f, hasProgress = progress != null
    )

    @Test
    fun screenReaderDescriptionsCarryStatusWithoutRelyingOnColour() {
        assertEquals("T, by Jane, EPUB, not started", Formatters.describe(item(null)))
        assertEquals("T, by Jane, EPUB, 40 percent read, favorite", Formatters.describe(item(0.4f, fav = true)))
        assertTrue(Formatters.describe(item(1f)).contains("finished"))
        assertTrue(Formatters.describe(item(null, available = false)).contains("file unavailable"))
        assertEquals("T, EPUB, not started", Formatters.describe(item(null, author = null)))
    }

    @Test
    fun subtitles() {
        assertEquals("Jane · EPUB", Formatters.subtitle(item(null)))
        assertEquals("Jane · EPUB · 40%", Formatters.subtitle(item(0.4f)))
        assertEquals("Jane · EPUB · Finished", Formatters.subtitle(item(1f)))
        assertEquals("EPUB", Formatters.subtitle(item(null, author = " ")))
    }
}
