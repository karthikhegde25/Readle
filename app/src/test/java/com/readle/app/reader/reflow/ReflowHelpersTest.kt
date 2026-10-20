package com.readle.app.reader.reflow

import com.readle.app.data.db.AnnotationEntity
import com.readle.app.domain.HighlightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflowHelpersTest {

    private fun ann(id: Long, start: String, end: String, note: String? = null, color: Int = HighlightColors.YELLOW) =
        AnnotationEntity(
            id = id, documentId = 1, type = AnnotationEntity.HIGHLIGHT, selectedText = "x", noteText = note,
            color = color, startLocation = start, endLocation = end, createdAt = 0, updatedAt = 0
        )

    @Test
    fun highlightJsonOnlyIncludesTheRequestedChapterAndValidAnchors() {
        val json = HighlightJson.build(
            listOf(
                ann(1, "2:10", "2:25"),
                ann(2, "3:0", "3:5"),
                ann(3, "garbage", "2:9"),
                ann(4, "2:7", "2:7"),
                ann(5, "2:40", "2:60", note = "my note", color = HighlightColors.GREEN)
            ),
            spine = 2
        )
        assertEquals(
            "[{\"id\":1,\"start\":10,\"end\":25,\"color\":\"#FFE066\",\"note\":false}," +
                "{\"id\":5,\"start\":40,\"end\":60,\"color\":\"#A8E6A1\",\"note\":true}]",
            json
        )
        assertEquals("[]", HighlightJson.build(emptyList(), 0))
    }

    @Test
    fun normalizeLowercasesAndCollapsesWhitespace() {
        // Mirrors the page script exactly: leading whitespace is dropped, a trailing run becomes one space
        // (only the *query* is trimmed, on both sides, before matching).
        assertEquals("hello big world ", BookSearch.normalize("  Hello \n\t BIG   World "))
        assertEquals("", BookSearch.normalize("   \n "))
    }

    @Test
    fun searchCountsNonOverlappingOccurrencesCaseInsensitively() {
        val text = "The needle appears once here, and the Needle appears twice,\nand NEEDLE a third time. aaaa"
        val hits = BookSearch.findInChapter(4, text, "needle")
        assertEquals(listOf(0, 1, 2), hits.map { it.nth })
        assertTrue(hits.all { it.spine == 4 })
        assertTrue(hits[0].snippet, hits[0].snippet.contains("needle appears once"))
        assertEquals(2, BookSearch.findInChapter(0, text, "aa").size) // "aaaa" -> 2 non-overlapping
    }

    @Test
    fun searchQueryIsWhitespaceInsensitiveAndRespectsLimit() {
        val hits = BookSearch.findInChapter(0, "alpha  beta\ngamma alpha beta", "  ALPHA   beta ")
        assertEquals(2, hits.size)
        assertEquals(1, BookSearch.findInChapter(0, "x x x x", "x", maxHits = 1).size)
        assertTrue(BookSearch.findInChapter(0, "abc", "  ").isEmpty())
        assertTrue(BookSearch.findInChapter(0, "abc", "zzz").isEmpty())
    }

    @Test
    fun snippetsAreTrimmedAndMarkedWhenTruncated() {
        val text = "a".repeat(200) + " target " + "b".repeat(200)
        val s = BookSearch.findInChapter(0, text, "target", snippetRadius = 10).single().snippet
        assertTrue(s, s.startsWith("…") && s.endsWith("…") && s.contains("target"))
        val short = BookSearch.findInChapter(0, "target", "target").single().snippet
        assertEquals("target", short)
    }

    // ---- TtsChunker ----

    @Test
    fun chunksNeverExceedTheLimitAndCoverAllText() {
        val para = (1..40).joinToString(" ") { "Sentence number $it ends here." }
        val text = (1..12).joinToString("\n") { para }
        val chunks = TtsChunker.split(text, maxLen = 500)
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { it.text.length <= 500 })
        val rebuilt = chunks.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ")
        assertEquals(text.replace(Regex("\\s+"), " ").trim(), rebuilt.trim())
        chunks.forEach { assertEquals(it.text, text.substring(it.offset, it.offset + it.text.length)) }
    }

    @Test
    fun chunkingPrefersSentenceBoundaries() {
        val text = "First sentence is here. Second sentence follows it closely. Third one ends the passage."
        val chunks = TtsChunker.split(text, maxLen = 60)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks[0].text, chunks[0].text.endsWith("."))
    }

    @Test
    fun veryLongWordsStillGetSplit() {
        val text = "x".repeat(1000)
        val chunks = TtsChunker.split(text, maxLen = 300)
        assertEquals(4, chunks.size)
        assertEquals(1000, chunks.sumOf { it.text.length })
    }

    @Test
    fun chunkingFromAnOffsetSkipsEarlierText() {
        val text = "Intro paragraph.\nSecond paragraph here.\nThird."
        val from = text.indexOf("Second")
        val chunks = TtsChunker.split(text, from = from, maxLen = 200)
        assertEquals(from, chunks.first().offset)
        assertTrue(chunks.first().text.startsWith("Second"))
        assertTrue(TtsChunker.split("", 0).isEmpty())
        assertTrue(TtsChunker.split("abc", 99).isEmpty())
    }

    @Test
    fun paragraphStartMovesBackToTheLineStart() {
        val text = "One.\nTwo two two.\nThree."
        assertEquals(5, TtsChunker.paragraphStart(text, text.indexOf("two two")))
        assertEquals(0, TtsChunker.paragraphStart(text, 2))
        assertEquals(0, TtsChunker.paragraphStart(text, 0))
        assertEquals(text.indexOf("Three"), TtsChunker.paragraphStart(text, text.length))
    }
}
