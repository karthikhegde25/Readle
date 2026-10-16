package com.readle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainBasicsTest {

    // ---- MetadataNormalizer ----

    @Test
    fun titleFromFileNameCleansExtensionAndSeparators() {
        assertEquals("my great book", MetadataNormalizer.titleFromFileName("my_great_book.epub"))
        assertEquals("War and Peace", MetadataNormalizer.titleFromFileName("War.and.Peace.pdf"))
        assertEquals("notes v2", MetadataNormalizer.titleFromFileName("notes__v2.txt"))
        // An unknown extension is not stripped, but separators are still cleaned.
        assertEquals("archive zip", MetadataNormalizer.titleFromFileName("archive.zip"))
    }

    @Test
    fun cleanFieldDropsBlankAndPlaceholderValues() {
        assertNull(MetadataNormalizer.cleanField(null))
        assertNull(MetadataNormalizer.cleanField("   "))
        assertNull(MetadataNormalizer.cleanField("Unknown"))
        assertNull(MetadataNormalizer.cleanField(" N/A "))
        assertEquals("Jane Austen", MetadataNormalizer.cleanField("  Jane \n  Austen "))
    }

    @Test
    fun cleanTitleFallsBackToFileName() {
        assertEquals("Emma", MetadataNormalizer.cleanTitle("Emma", "x.epub"))
        assertEquals("fallback name", MetadataNormalizer.cleanTitle("untitled", "fallback_name.epub"))
        assertEquals("fallback name", MetadataNormalizer.cleanTitle(null, "fallback_name.epub"))
    }

    @Test
    fun languageIsReducedToPrimarySubtag() {
        assertEquals("en", MetadataNormalizer.cleanLanguage("en-US"))
        assertEquals("fr", MetadataNormalizer.cleanLanguage("FR"))
        assertEquals("pt", MetadataNormalizer.cleanLanguage("pt_BR"))
        assertNull(MetadataNormalizer.cleanLanguage("x"))
        assertNull(MetadataNormalizer.cleanLanguage(""))
    }

    // ---- FormatSupport ----

    @Test
    fun formatDetectionPrefersFileNameThenMime() {
        assertEquals(DocFormat.PDF, FormatSupport.fromFileName("Book.PDF"))
        assertEquals(DocFormat.EPUB, FormatSupport.fromFileName("a.b.epub"))
        assertEquals(DocFormat.MOBI, FormatSupport.fromFileName("book.mobi"))
        assertNull(FormatSupport.fromFileName("book.djvu"))
        assertNull(FormatSupport.fromFileName("noextension"))
        assertNull(FormatSupport.fromFileName(null))
        assertEquals(DocFormat.EPUB, FormatSupport.detect("weird.bin", "application/epub+zip"))
        assertEquals(DocFormat.PDF, FormatSupport.detect("x.pdf", "application/octet-stream"))
        assertNull(FormatSupport.detect("x.djvu", "application/octet-stream"))
    }

    @Test
    fun onlyPdfIsFixedLayout() {
        assertEquals(false, DocFormat.PDF.reflowable)
        assertEquals(true, DocFormat.EPUB.reflowable)
        assertEquals(true, DocFormat.TXT.reflowable)
    }

    // ---- Locator ----

    @Test
    fun locatorRoundTrips() {
        val pdf = Locator.PdfPage(41)
        assertEquals(pdf, Locator.decode(pdf.type, pdf.encode()))
        val reflow = Locator.Reflow(7, 0.4521f)
        val decoded = Locator.decode(reflow.type, reflow.encode()) as Locator.Reflow
        assertEquals(7, decoded.spine)
        assertEquals(0.4521f, decoded.fraction, 0.00001f)
    }

    @Test
    fun locatorRejectsGarbage() {
        assertNull(Locator.decode(null, "1"))
        assertNull(Locator.decode("PDF_PAGE", "abc"))
        assertNull(Locator.decode("PDF_PAGE", "-3"))
        assertNull(Locator.decode("REFLOW", "3"))
        assertNull(Locator.decode("REFLOW", "x:0.5"))
        assertNull(Locator.decode("UNKNOWN", "1"))
    }

    @Test
    fun locatorClampsFractionAndSurvivesRouteArgs() {
        val l = Locator.decode("REFLOW", "2:1.7") as Locator.Reflow
        assertEquals(1f, l.fraction, 0f)
        val arg = Locator.toRouteArg(Locator.Reflow(3, 0.25f))
        val back = Locator.fromRouteArg(arg) as Locator.Reflow
        assertEquals(3, back.spine)
        assertEquals(0.25f, back.fraction, 0.0001f)
        assertNull(Locator.fromRouteArg(null))
        assertNull(Locator.fromRouteArg("nonsense"))
    }
}
