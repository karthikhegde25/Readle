package com.readle.app.reader.reflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(name: String, files: Map<String, String>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((path, content) in files) {
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return f
    }

    private val container = """<?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>"""

    private val chapter1 = """<?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>c1</title><style>p{color:red}</style></head>
        <body><h1 id="start">Chapter One</h1><p>Hello&nbsp;world &amp; friends.</p><p>Second paragraph.</p></body></html>"""

    private val chapter2 = """<html><body><p>The second chapter says goodbye.</p></body></html>"""

    private val epub3Opf = """<?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Test Book</dc:title>
            <dc:creator>Jane Doe</dc:creator>
            <dc:creator>John Roe</dc:creator>
            <dc:language>en-US</dc:language>
            <meta name="calibre:series" content="Saga One"/>
            <meta name="cover" content="cover-img"/>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="ch1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover-img" href="images/cover.png" media-type="image/png"/>
          </manifest>
          <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
        </package>"""

    private val nav = """<?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
          <nav epub:type="toc"><ol>
            <li><a href="text/ch%201.xhtml#start">Chapter&nbsp;One</a>
              <ol><li><a href="text/ch2.xhtml">Second Chapter</a></li></ol>
            </li>
          </ol></nav>
        </body></html>"""

    private fun epub3(extra: Map<String, String> = emptyMap()): File = zip(
        "book3.epub",
        linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to epub3Opf,
            "OEBPS/nav.xhtml" to nav,
            "OEBPS/text/ch 1.xhtml" to chapter1,
            "OEBPS/text/ch2.xhtml" to chapter2,
            "OEBPS/images/cover.png" to "PNGDATA"
        ) + extra
    )

    @Test
    fun parsesMetadataSpineAndCover() {
        EpubBook.open(epub3()).use { book ->
            assertEquals("Test Book", book.meta.title)
            assertEquals("Jane Doe, John Roe", book.meta.author)
            assertEquals("en-US", book.meta.language)
            assertEquals("Saga One", book.meta.series)
            assertEquals("OEBPS/images/cover.png", book.meta.coverPath)
            assertEquals(listOf("OEBPS/text/ch 1.xhtml", "OEBPS/text/ch2.xhtml"), book.spine.map { it.path })
            assertTrue(book.spine.all { it.sizeBytes > 0 })
        }
    }

    @Test
    fun parsesNestedNavTocWithFragmentsAndEntities() {
        EpubBook.open(epub3()).use { book ->
            assertEquals(2, book.toc.size)
            val first = book.toc[0]
            assertEquals("Chapter One", first.title.replace(' ', ' '))
            assertEquals(0, first.spineIndex)
            assertEquals("start", first.fragment)
            assertEquals(0, first.depth)
            val second = book.toc[1]
            assertEquals("Second Chapter", second.title)
            assertEquals(1, second.spineIndex)
            assertNull(second.fragment)
            assertEquals(1, second.depth)
            assertEquals("Chapter One".length, book.chapterTitle(0).replace(' ', ' ').length)
        }
    }

    @Test
    fun servesResourcesWithMimeTypesAndConvertsChaptersToText() {
        EpubBook.open(epub3()).use { book ->
            val res = book.open("OEBPS/text/ch 1.xhtml")
            assertNotNull(res)
            assertEquals("text/html", res!!.mime)
            res.stream.close()
            assertEquals("image/png", book.open("OEBPS/images/cover.png")!!.also { it.stream.close() }.mime)
            assertNull(book.open("OEBPS/missing.xhtml"))
            val text = book.chapterText(0)
            assertTrue(text, text.contains("Hello world & friends."))
            assertTrue(text, text.contains("Second paragraph."))
            assertTrue("style content must not leak into text", !text.contains("color:red"))
            assertEquals("", book.chapterText(99))
        }
    }

    @Test
    fun fallsBackToNcxWhenThereIsNoNavDocument() {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old Book</dc:title></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="a" href="a.html" media-type="application/xhtml+xml"/>
                <item id="b" href="b.html" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine>
            </package>"""
        val ncx = """<?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
              <navPoint id="1"><navLabel><text>Part A</text></navLabel><content src="a.html"/>
                <navPoint id="2"><navLabel><text>Part B</text></navLabel><content src="b.html#x"/></navPoint>
              </navPoint>
            </navMap></ncx>"""
        val f = zip(
            "old.epub",
            linkedMapOf(
                "META-INF/container.xml" to container.replace("OEBPS/content.opf", "content.opf"),
                "content.opf" to opf,
                "toc.ncx" to ncx,
                "a.html" to "<html><body>A</body></html>",
                "b.html" to "<html><body>B</body></html>"
            )
        )
        EpubBook.open(f).use { book ->
            assertEquals("Old Book", book.meta.title)
            assertNull(book.meta.author)
            assertEquals(listOf("Part A", "Part B"), book.toc.map { it.title })
            assertEquals(listOf(0, 1), book.toc.map { it.depth })
            assertEquals("x", book.toc[1].fragment)
        }
    }

    @Test
    fun generatesChapterNamesWhenThereIsNoToc() {
        val opf = """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata/>
              <manifest><item id="a" href="a.html" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="a"/></spine></package>"""
        val f = zip(
            "notoc.epub",
            linkedMapOf(
                "META-INF/container.xml" to container.replace("OEBPS/content.opf", "c.opf"),
                "c.opf" to opf,
                "a.html" to "<html><body>A</body></html>"
            )
        )
        EpubBook.open(f).use { book ->
            assertEquals(listOf("Chapter 1"), book.toc.map { it.title })
        }
    }

    @Test
    fun rejectsNonZipFilesAsCorrupt() {
        val f = tmp.newFile("bad.epub").also { it.writeText("this is not a zip") }
        try {
            EpubBook.open(f)
            fail("expected BookOpenException")
        } catch (e: BookOpenException) {
            assertEquals(BookOpenException.Kind.CORRUPT, e.kind)
        }
    }

    @Test
    fun rejectsArchivesWithoutContainerAsCorrupt() {
        val f = zip("empty.epub", mapOf("hello.txt" to "hi"))
        try {
            EpubBook.open(f)
            fail("expected BookOpenException")
        } catch (e: BookOpenException) {
            assertEquals(BookOpenException.Kind.CORRUPT, e.kind)
        }
    }

    @Test
    fun rejectsDrmProtectedBooksButAcceptsFontObfuscation() {
        val drm = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <EncryptedData><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>"""
        try {
            EpubBook.open(epub3(mapOf("META-INF/encryption.xml" to drm)))
            fail("expected DRM rejection")
        } catch (e: BookOpenException) {
            assertEquals(BookOpenException.Kind.DRM, e.kind)
        }

        val fonts = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <EncryptedData><EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/></EncryptedData></encryption>"""
        val ok = zip(
            "fonts.epub",
            linkedMapOf(
                "META-INF/container.xml" to container,
                "META-INF/encryption.xml" to fonts,
                "OEBPS/content.opf" to epub3Opf,
                "OEBPS/nav.xhtml" to nav,
                "OEBPS/text/ch 1.xhtml" to chapter1,
                "OEBPS/text/ch2.xhtml" to chapter2
            )
        )
        EpubBook.open(ok).use { assertEquals(2, it.spine.size) }
    }
}
