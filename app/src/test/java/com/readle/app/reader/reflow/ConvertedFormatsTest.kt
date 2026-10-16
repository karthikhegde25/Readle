package com.readle.app.reader.reflow

import com.readle.app.domain.DocFormat
import com.readle.app.domain.FormatSupport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ConvertedFormatsTest {

    private fun text(b: ReflowBook, i: Int) = b.chapterText(i)
    private fun html(b: ReflowBook, i: Int) = String(b.open(b.spine[i].path)!!.stream.readBytes(), Charsets.UTF_8)
    private fun tmp(name: String, bytes: ByteArray): File = File.createTempFile("rdr", name).also { it.writeBytes(bytes); it.deleteOnExit() }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> files.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return bo.toByteArray()
    }

    // ---------------------------------------------------------------- detection

    @Test fun detectsExtensionsAndMimes() {
        assertEquals(DocFormat.MOBI, FormatSupport.detect("a.azw3", null))
        assertEquals(DocFormat.MOBI, FormatSupport.detect("a.PRC", null))
        assertEquals(DocFormat.FB2, FormatSupport.detect("book.fb2", "application/octet-stream"))
        assertEquals(DocFormat.HTML, FormatSupport.detect("x.htm", null))
        assertEquals(DocFormat.MD, FormatSupport.detect("x.markdown", null))
        assertEquals(DocFormat.CBZ, FormatSupport.detect("x.cbz", null))
        assertEquals(DocFormat.DOCX, FormatSupport.detect(null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertNull(FormatSupport.detect("x.djvu", null))
        assertTrue("application/x-mobipocket-ebook" in FormatSupport.pickerMimeTypes)
        assertFalse(DocFormat.PDF.reflowable)
    }

    // ---------------------------------------------------------------- MOBI

    private fun mobi(compression: Int, encryption: Int, version: Int, body: String, title: String = "My Mobi", author: String = "Ann Author"): ByteArray {
        val textBytes = body.toByteArray(Charsets.UTF_8)
        val exth = ByteArrayOutputStream().apply {
            fun rec(t: Int, d: ByteArray) { write(ByteBuffer.allocate(8).putInt(t).putInt(d.size + 8).array()); write(d) }
            rec(100, author.toByteArray()); rec(503, title.toByteArray())
        }.toByteArray()
        val exthBlock = "EXTH".toByteArray() + ByteBuffer.allocate(8).putInt(exth.size + 12).putInt(2).array() + exth
        val headerLen = 232
        val r0 = ByteBuffer.allocate(16 + headerLen + exthBlock.size + 8)
        r0.putShort(0, compression.toShort()); r0.putInt(4, textBytes.size); r0.putShort(8, 1); r0.putShort(10, 4096); r0.putShort(12, encryption.toShort())
        "MOBI".toByteArray().forEachIndexed { i, b -> r0.put(16 + i, b) }
        r0.putInt(20, headerLen); r0.putInt(28, 65001); r0.putInt(36, version)
        r0.putInt(0x6C, 0); r0.putInt(0x80, 0x40)
        r0.position(16 + headerLen); r0.put(exthBlock)
        val rec0 = r0.array()
        val numRecords = 2
        val header = ByteArray(78 + numRecords * 8 + 2)
        "Test".toByteArray().copyInto(header)
        "BOOKMOBI".toByteArray().copyInto(header, 60)
        ByteBuffer.wrap(header).putShort(76, numRecords.toShort())
        val off0 = header.size
        val off1 = off0 + rec0.size
        ByteBuffer.wrap(header).putInt(78, off0).putInt(86, off1)
        return header + rec0 + textBytes
    }

    @Test fun mobiUncompressedBuildsChaptersMetadataAndLinks() {
        val pad = "<p>" + "word ".repeat(1500) + "</p>"
        val body = "<html><head><title>x</title></head><body><h1>First</h1>$pad<mbp:pagebreak/><h1>Second</h1><p><a filepos=0000000000>back</a></p>$pad</body></html>"
        val book = MobiBook.parse(mobi(1, 0, 6, body), "fallback")
        assertEquals("My Mobi", book.meta.title)
        assertEquals("Ann Author", book.meta.author)
        assertEquals(2, book.spine.size)
        assertEquals(listOf("First", "Second"), book.toc.map { it.title })
        assertTrue(html(book, 1).contains("href=\"c0.html#fp0\""))
        assertFalse(html(book, 0).contains("<title>"))
        assertTrue(text(book, 0).contains("word word"))
    }

    @Test fun mobiPalmDocDecompression() {
        // literal "abc", then a back-reference (distance 3, length 3), then space+'x' (0xC0|0x78 -> ' ' , 'x')
        val comp = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x80.toByte(), ((3 shl 3) or 0).toByte().let { (0x80 or (3 shr 5)).toByte() }.let { 0 }.toByte())
        // build the pair explicitly: 0x8000 | (dist<<3) | (len-3)
        val pair = 0x8000 or (3 shl 3) or 0
        val stream = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), (pair shr 8).toByte(), (pair and 0xFF).toByte(), (0xC0 or 'x'.code).toByte())
        val out = ByteArrayOutputStream()
        MobiBook.palmDecompress(stream, out)
        assertEquals("abcabc x", out.toString("ISO-8859-1"))
        assertNotNull(comp)
    }

    @Test fun mobiRejectsDrmHuffAndKf8() {
        fun kind(b: ByteArray) = try { MobiBook.parse(b, null); null } catch (e: BookOpenException) { e.kind }
        assertEquals(BookOpenException.Kind.DRM, kind(mobi(1, 2, 6, "<p>x</p>")))
        assertEquals(BookOpenException.Kind.UNSUPPORTED, kind(mobi(17480, 0, 6, "<p>x</p>")))
        assertEquals(BookOpenException.Kind.UNSUPPORTED, kind(mobi(2, 0, 8, "<p>x</p>")))
        assertEquals(BookOpenException.Kind.CORRUPT, kind(ByteArray(200)))
    }

    // ---------------------------------------------------------------- FB2

    private val fb2 = """<?xml version="1.0" encoding="windows-1251"?>
<FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info>
<author><first-name>Лев</first-name><last-name>Толстой</last-name></author>
<book-title>Война</book-title><lang>ru</lang><sequence name="Saga" number="1"/>
<coverpage><image l:href="#cover.jpg"/></coverpage></title-info></description>
<body><section id="c1"><title><p>Глава 1</p></title><p>Текст<a l:href="#n1" type="note">[1]</a> <emphasis>курсив</emphasis></p></section>
<section><title><p>Глава 2</p></title><poem><stanza><v>строка 1</v><v>строка 2</v></stanza></poem></section></body>
<body name="notes"><section id="n1"><p>Примечание</p></section></body>
<binary id="cover.jpg" content-type="image/jpeg">/9j/4AAQ</binary></FictionBook>"""

    @Test fun fb2Windows1251() {
        val book = Fb2Book.parse(fb2.toByteArray(charset("windows-1251")), "fb")
        assertEquals("Война", book.meta.title)
        assertEquals("Лев Толстой", book.meta.author)
        assertEquals("Saga #1".substringBefore(" #"), book.meta.series)
        assertEquals("r/cover.jpg", book.meta.coverPath)
        assertEquals(listOf("Глава 1", "Глава 2", "Notes"), book.toc.map { it.title })
        assertTrue(html(book, 0).contains("<em>курсив</em>"))
        assertTrue(html(book, 0).contains("href=\"c2.html#n1\""))
        assertTrue(html(book, 1).contains("строка 1<br>строка 2"))
        assertEquals("image/jpeg", book.open("r/cover.jpg")!!.mime)
        assertEquals(6, book.open("r/cover.jpg")!!.stream.readBytes().size)
    }

    @Test fun fb2RejectsOtherXml() {
        try { Fb2Book.parse("<a/>".toByteArray(), null); fail() } catch (e: BookOpenException) { assertEquals(BookOpenException.Kind.CORRUPT, e.kind) }
    }

    // ---------------------------------------------------------------- DOCX

    @Test fun docxConvertsHeadingsListsTablesAndLinks() {
        val doc = """<?xml version="1.0"?><w:document xmlns:w="w" xmlns:r="r"><w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Intro</w:t></w:r></w:p>
<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r><w:r><w:t xml:space="preserve"> plain &amp; more</w:t></w:r></w:p>
<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>item</w:t></w:r></w:p>
<w:p><w:hyperlink r:id="rId9"><w:r><w:t>site</w:t></w:r></w:hyperlink></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Next</w:t></w:r></w:p><w:p><w:r><w:t>end</w:t></w:r></w:p>
</w:body></w:document>"""
        val styles = """<w:styles xmlns:w="w"><w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val numbering = """<w:numbering xmlns:w="w"><w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>"""
        val rels = """<Relationships><Relationship Id="rId9" Target="https://example.org" TargetMode="External"/></Relationships>"""
        val core = """<cp:coreProperties xmlns:cp="c" xmlns:dc="d"><dc:title>Doc Title</dc:title><dc:creator>Dee</dc:creator></cp:coreProperties>"""
        val f = tmp(".docx", zip("word/document.xml" to doc.toByteArray(), "word/styles.xml" to styles.toByteArray(),
            "word/numbering.xml" to numbering.toByteArray(), "word/_rels/document.xml.rels" to rels.toByteArray(), "docProps/core.xml" to core.toByteArray()))
        val book = DocxBook.open(f, "fb")
        book.use {
            assertEquals("Doc Title", book.meta.title)
            assertEquals("Dee", book.meta.author)
            assertEquals(listOf("Intro", "Next"), book.toc.map { it.title })
            val h = html(book, 0)
            assertTrue(h.contains("<strong>Bold</strong>"))
            assertTrue(h.contains("plain &amp; more"))
            assertTrue(h.contains("<ol>"))
            assertTrue(h.contains("<a href=\"https://example.org\">site</a>"))
            assertTrue(h.contains("<td><p>cell</p></td>"))
        }
    }

    @Test fun docxNonZipIsUnsupported() {
        try { DocxBook.open(tmp(".docx", ByteArray(600) { 7 }), null); fail() } catch (e: BookOpenException) { assertEquals(BookOpenException.Kind.UNSUPPORTED, e.kind) }
    }

    // ---------------------------------------------------------------- RTF

    @Test fun rtfText() {
        val bs = 92.toChar()
        val rtf = "{${bs}rtf1${bs}ansi{${bs}fonttbl{${bs}f0 Arial;}}{${bs}info{${bs}title hidden}}${bs}pard Hello {${bs}b bold} caf${bs}'e9 ${bs}u8364?${bs}par Second${bs}line line${bs}par}"
        val book = RtfBook.parse(rtf.toByteArray(Charsets.ISO_8859_1), "r")
        val h = html(book, 0)
        assertTrue(h, h.contains("<p>Hello <strong>bold</strong> café €</p>"))
        assertTrue(h, h.contains("<p>Second<br>line</p>"))
        assertFalse(h.contains("Arial")); assertFalse(h.contains("hidden"))
    }

    @Test fun rtfRejectsNonRtf() {
        try { RtfBook.parse("hello".toByteArray(), null); fail() } catch (e: BookOpenException) { }
    }

    // ---------------------------------------------------------------- HTML

    @Test fun htmlStripsActiveContent() {
        val src = """<!doctype html><html><head><title>Page &amp; T</title><meta charset="utf-8"><style>p{color:red}</style><script>alert(1)</script></head>
<body onload="x()"><h1>Head</h1><p onclick="y()">Hi <a href="javascript:evil()">l</a></p><img src="http://x/y.png" alt="logo"><iframe src="a"></iframe></body></html>"""
        val book = HtmlDocBook.parse(src.toByteArray(), "fb")
        val h = html(book, 0)
        assertEquals("Page & T", book.meta.title)
        assertFalse(h.contains("alert")); assertFalse(h.contains("onclick")); assertFalse(h.contains("javascript:"))
        assertFalse(h.contains("iframe")); assertFalse(h.contains("color:red"))
        assertTrue(h.contains("<em>[logo]</em>"))
        assertTrue(h.contains("Hi"))
    }

    @Test fun htmlHonoursDeclaredCharset() {
        val src = "<html><head><meta charset=\"windows-1251\"></head><body><p>Привет</p></body></html>"
        val book = HtmlDocBook.parse(src.toByteArray(charset("windows-1251")), null)
        assertTrue(text(book, 0).contains("Привет"))
    }

    // ---------------------------------------------------------------- Markdown

    @Test fun markdownBasics() {
        val md = "---\ntitle: My Notes\nauthor: Me\n---\n# One\n\nHello *world* and **bold** and `code` <b>raw</b> [link](https://a.b) \\*lit\\*\n\n- a\n- [x] b\n  - nested\n\n1. x\n2. y\n\n> quote\n\n```\nif (a < b) {}\n```\n\n| h1 | h2 |\n|----|----|\n| c1 | c2 |\n\n# Two\n\nSecond  \nline\n\n---\n"
        val book = MarkdownBook.parse(md, "fb")
        assertEquals("My Notes", book.meta.title); assertEquals("Me", book.meta.author)
        assertEquals(listOf("One", "Two"), book.toc.map { it.title })
        val h = html(book, 0)
        assertTrue(h, h.contains("<em>world</em>")); assertTrue(h.contains("<strong>bold</strong>"))
        assertTrue(h.contains("<code>code</code>")); assertTrue(h.contains("&lt;b&gt;raw&lt;/b&gt;"))
        assertTrue(h.contains("<a href=\"https://a.b\">link</a>")); assertTrue(h.contains("*lit*"))
        assertTrue(h, h.contains("<ul><li>a</li><li>☑ b<ul><li>nested</li></ul></li></ul>"))
        assertTrue(h.contains("<ol><li>x</li><li>y</li></ol>"))
        assertTrue(h.contains("<blockquote><p>quote</p></blockquote>"))
        assertTrue(h.contains("<pre><code>if (a &lt; b) {}</code></pre>"))
        assertTrue(h.contains("<th>h1</th>") && h.contains("<td>c2</td>"))
        assertTrue(html(book, 1).contains("Second<br>line"))
    }

    @Test fun markdownDoesNotHangOnOddInput() {
        MarkdownBook.parse("* * *\n- \n1.\n>\n```\nunterminated\n|a|\n    code\n=====\n", "x")
    }

    // ---------------------------------------------------------------- CBZ

    @Test fun cbzSortsPagesNaturallyAndReadsComicInfo() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val info = "<ComicInfo><Title>Issue</Title><Writer>Wri</Writer><Series>Ser</Series><Number>3</Number></ComicInfo>"
        val f = tmp(".cbz", zip("p10.png" to png + byteArrayOf(10), "p2.png" to png + byteArrayOf(2), "p1.png" to png + byteArrayOf(1),
            "__MACOSX/p1.png" to png, "ComicInfo.xml" to info.toByteArray(), "readme.txt" to "x".toByteArray()))
        CbzBook.open(f, "fb").use { b ->
            assertEquals(3, b.spine.size)
            assertEquals("Issue", b.meta.title); assertEquals("Wri", b.meta.author); assertEquals("Ser #3", b.meta.series)
            assertEquals(1, b.open("r/p1")!!.stream.readBytes().last().toInt())
            assertEquals(10, b.open("r/p3")!!.stream.readBytes().last().toInt())
            assertEquals("image/png", b.open("r/p1")!!.mime)
        }
        assertTrue(CbzBook.naturalCompare("a2", "a10") < 0)
    }

    @Test fun cbzNonZipExplainsCbr() {
        try { CbzBook.open(tmp(".cbz", ByteArray(500) { 1 }), null); fail() } catch (e: BookOpenException) {
            assertEquals(BookOpenException.Kind.UNSUPPORTED, e.kind); assertTrue(e.message!!.contains("cbr"))
        }
    }

    @Test fun splitAtBlocksKeepsAllContent() {
        val h = "<p>" + "x".repeat(50) + "</p>"
        val big = h.repeat(100)
        val parts = MemoryBook.splitAtBlocks(big, 1000)
        assertTrue(parts.size > 3)
        assertEquals(big, parts.joinToString(""))
        assertTrue(parts.all { it.endsWith("</p>") })
    }
}
