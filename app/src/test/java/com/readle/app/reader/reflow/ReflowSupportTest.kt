package com.readle.app.reader.reflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReflowSupportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---- PathUtil ----

    @Test
    fun resolvesRelativeHrefsAgainstTheirDirectory() {
        assertEquals("OEBPS/text/ch2.xhtml", PathUtil.resolve("OEBPS/text", "ch2.xhtml"))
        assertEquals("OEBPS/images/a.png", PathUtil.resolve("OEBPS/text", "../images/a.png"))
        assertEquals("a/b.html", PathUtil.resolve("", "a/./b.html"))
        assertEquals("root.html", PathUtil.resolve("x/y", "/root.html"))
        assertEquals("OEBPS/ch 1.xhtml", PathUtil.resolve("OEBPS", "ch%201.xhtml#frag"))
        assertEquals("b.html", PathUtil.resolve("a", "../../b.html"))
    }

    @Test
    fun percentDecodingHandlesUtf8AndLeavesPlusAlone() {
        assertEquals("a b", PathUtil.decode("a%20b"))
        assertEquals("café", PathUtil.decode("caf%C3%A9"))
        assertEquals("a+b", PathUtil.decode("a+b"))
        assertEquals("100%", PathUtil.decode("100%"))
        assertEquals("bad%zzx", PathUtil.decode("bad%zzx"))
        assertEquals("😀", PathUtil.decode("😀"))
    }

    @Test
    fun fragmentsAreSeparated() {
        assertEquals("sec1", PathUtil.fragmentOf("a.html#sec1"))
        assertNull(PathUtil.fragmentOf("a.html"))
        assertNull(PathUtil.fragmentOf("a.html#"))
        assertEquals("a.html", PathUtil.stripFragment("a.html#x"))
    }

    // ---- HtmlText ----

    @Test
    fun htmlToPlainTextKeepsParagraphBreaksAndDecodesEntities() {
        val t = HtmlText.toPlain("<h1>Title</h1><p>One &amp; two&nbsp;three</p><script>var x=1;</script><p>Caf&#233;&#x21;</p>")
        assertEquals("Title\nOne & two three\nCafé!", t)
    }

    @Test
    fun escapeProtectsMarkup() {
        assertEquals("&lt;b&gt; &amp; &quot;q&quot;", HtmlText.escape("<b> & \"q\""))
    }

    // ---- ReflowMath ----

    private val spine = listOf(SpineItem("a", 100), SpineItem("b", 300), SpineItem("c", 600))

    @Test
    fun overallProgressIsWeightedBySize() {
        assertEquals(0f, ReflowMath.overallFraction(spine, 0, 0f), 0.0001f)
        assertEquals(0.05f, ReflowMath.overallFraction(spine, 0, 0.5f), 0.0001f)
        assertEquals(0.1f, ReflowMath.overallFraction(spine, 1, 0f), 0.0001f)
        assertEquals(0.4f, ReflowMath.overallFraction(spine, 2, 0f), 0.0001f)
        assertEquals(1f, ReflowMath.overallFraction(spine, 2, 1f), 0.0001f)
        assertEquals(0f, ReflowMath.overallFraction(emptyList(), 0, 0.5f), 0f)
    }

    @Test
    fun locateIsTheInverseOfOverallFraction() {
        for (idx in 0..2) {
            for (f in listOf(0.1f, 0.5f, 0.9f)) {
                val overall = ReflowMath.overallFraction(spine, idx, f)
                val (i, frac) = ReflowMath.locate(spine, overall)
                assertEquals(idx, i)
                assertEquals(f, frac, 0.001f)
            }
        }
        assertEquals(2 to 1f, ReflowMath.locate(spine, 1f))
        assertEquals(0 to 0f, ReflowMath.locate(spine, 0f))
        assertEquals(0 to 0f, ReflowMath.locate(emptyList(), 0.5f))
    }

    // ---- TxtBook ----

    @Test
    fun shortTextBecomesOnePartWithParagraphs() {
        val book = TxtBook.fromText("First paragraph.\n\nSecond paragraph.", "Notes")
        assertEquals(1, book.spine.size)
        assertEquals("Notes", book.toc[0].title)
        val html = book.open("txt/part0.html")!!.stream.readBytes().toString(Charsets.UTF_8)
        assertTrue(html, html.contains("<p>First paragraph.</p>"))
        assertTrue(html, html.contains("<p>Second paragraph.</p>"))
        assertNull(book.open("txt/part1.html"))
        assertNull(book.open("other/part0.html"))
    }

    @Test
    fun hardWrappedProseIsRejoinedButVerseKeepsLineBreaks() {
        val prose = "This is a long line of prose that was hard wrapped at about seventy chars\n" +
            "and continues here on the second line of the very same paragraph okay."
        val verse = "Roses are red\nViolets are blue"
        val book = TxtBook.fromText("$prose\n\n$verse", null)
        val html = book.open("txt/part0.html")!!.stream.readBytes().toString(Charsets.UTF_8)
        assertTrue(html, html.contains("seventy chars and continues"))
        assertTrue(html, html.contains("Roses are red<br>Violets are blue"))
    }

    @Test
    fun textIsEscapedSoItCannotInjectMarkup() {
        val book = TxtBook.fromText("<script>alert(1)</script> & more", null)
        val html = book.open("txt/part0.html")!!.stream.readBytes().toString(Charsets.UTF_8)
        assertTrue(html, !html.contains("<script>"))
        assertTrue(html, html.contains("&lt;script&gt;"))
    }

    @Test
    fun longTextIsSplitIntoPartsAtParagraphBoundaries() {
        val para = "x".repeat(900)
        val text = (1..120).joinToString("\n\n") { "$it $para" }
        val book = TxtBook.fromText(text, "Big")
        assertTrue("expected several parts, got ${book.spine.size}", book.spine.size >= 3)
        assertEquals(book.spine.size, book.toc.size)
        assertTrue(book.toc[1].title.startsWith("Part 2"))
        val total = (0 until book.spine.size).sumOf { book.chapterText(it).length }
        assertTrue(total > 100_000)
    }

    @Test
    fun decodesUtf8Utf8WithBomAndFallsBackToWindows1252() {
        assertEquals("café", TxtBook.decode("café".toByteArray(Charsets.UTF_8)))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        assertEquals("hi", TxtBook.decode(bom))
        // 0xE9 alone is invalid UTF-8 but is "é" in windows-1252.
        assertEquals("café", TxtBook.decode(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 'h'.code.toByte(), 0, 'i'.code.toByte(), 0)
        assertEquals("hi", TxtBook.decode(utf16))
    }

    @Test
    fun oversizedTextFilesAreRejectedWithoutReadingThem() {
        val f = tmp.newFile("big.txt")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(TxtBook.MAX_BYTES + 1) }
        try {
            TxtBook.open(f, null)
            fail("expected TOO_LARGE")
        } catch (e: BookOpenException) {
            assertEquals(BookOpenException.Kind.TOO_LARGE, e.kind)
        }
        val small = tmp.newFile("small.txt").also { it.writeText("hello\n\nworld") }
        assertNotNull(TxtBook.open(small, "Small"))
    }
}
