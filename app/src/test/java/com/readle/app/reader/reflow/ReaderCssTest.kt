package com.readle.app.reader.reflow

import com.readle.app.domain.ReaderAlign
import com.readle.app.domain.ReaderFont
import com.readle.app.domain.ReaderTheme
import com.readle.app.domain.TextAnchor
import com.readle.app.domain.TypographySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCssTest {

    @Test
    fun cssReflectsEveryTypographySetting() {
        val css = ReaderCss.build(
            TypographySettings(
                theme = ReaderTheme.SEPIA, font = ReaderFont.SANS, fontSizePercent = 140,
                lineSpacing = 1.8f, paragraphSpacing = 0.25f, marginDp = 30, align = ReaderAlign.JUSTIFY
            )
        )
        assertTrue(css, css.contains("font-size:140% !important"))
        assertTrue(css, css.contains("line-height:1.8 !important"))
        assertTrue(css, css.contains("margin-bottom:0.25em !important"))
        assertTrue(css, css.contains("padding:12px 30px 24px"))
        assertTrue(css, css.contains("text-align:justify"))
        assertTrue(css, css.contains("Helvetica"))
        assertTrue(css, css.contains("#F4ECD8"))
    }

    @Test
    fun themesUseDistinctPalettesAndHighContrastChangesThem() {
        val day = ReaderPalette.of(ReaderTheme.DAY, false)
        val night = ReaderPalette.of(ReaderTheme.NIGHT, false)
        val sepia = ReaderPalette.of(ReaderTheme.SEPIA, false)
        assertNotEquals(day.background, night.background)
        assertNotEquals(day.background, sepia.background)
        assertNotEquals(day.text, ReaderPalette.of(ReaderTheme.DAY, true).text)
        assertEquals(0xFF000000.toInt(), ReaderPalette.of(ReaderTheme.NIGHT, true).background)
    }

    @Test
    fun textIsReadableAgainstBackgroundInEveryTheme() {
        for (theme in ReaderTheme.entries) for (hc in listOf(false, true)) {
            val p = ReaderPalette.of(theme, hc)
            val ratio = contrast(p.background, p.text)
            assertTrue("$theme hc=$hc contrast $ratio", ratio >= 7.0)
            assertTrue("$theme hc=$hc link contrast", contrast(p.background, p.link) >= 4.5)
        }
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val l1 = lum(a)
        val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun injectsIntoExistingHeadAndAddsTailStyle() {
        val html = "<?xml version=\"1.0\"?><!DOCTYPE html><html><head><title>t</title></head><body><p>x</p></body></html>"
        val out = HtmlInjector.inject(html, "p{}", "var a=1;", "{}", "N0NCE")
        assertTrue(out, out.startsWith("<!DOCTYPE html>"))
        assertFalse(out, out.contains("<?xml"))
        assertEquals("one doctype only", 1, Regex("<!DOCTYPE", RegexOption.IGNORE_CASE).findAll(out).count())
        assertTrue(out, out.indexOf("<head>") < out.indexOf("class=\"rl-style\""))
        assertTrue(out, out.indexOf("class=\"rl-style\"") < out.indexOf("<title>"))
        assertEquals("style in head and before </body>", 2, Regex("class=\"rl-style\"").findAll(out).count())
        assertTrue(out, out.indexOf("rl-style", out.indexOf("<p>")) < out.indexOf("</body>"))
        assertTrue(out, out.contains("<script nonce=\"N0NCE\">var a=1;</script>"))
        assertTrue(out, out.contains("window.__readleInit={};"))
    }

    @Test
    fun injectsWhenThereIsNoHeadOrNoHtmlAtAll() {
        val noHead = HtmlInjector.inject("<html><body>hi</body></html>", "c", "s", "{}", "n")
        assertTrue(noHead, noHead.contains("<html><head><meta charset"))
        val bare = HtmlInjector.inject("<p>just a fragment</p>", "c", "s", "{}", "n")
        assertTrue(bare, bare.startsWith("<!DOCTYPE html><head>"))
        assertTrue(bare, bare.contains("just a fragment"))
        assertTrue(bare, bare.trimEnd().endsWith("</style>"))
    }

    @Test
    fun headDetectionIgnoresHeaderElements() {
        val out = HtmlInjector.inject("<html><body><header>h</header><p>x</p></body></html>", "c", "s", "{}", "n")
        assertTrue(out, out.contains("<html><head>"))
        assertTrue(out, out.indexOf("<head>") < out.indexOf("<body>"))
    }

    @Test
    fun cspAllowsOnlyTheNoncedScriptAndBlocksNetwork() {
        val csp = HtmlInjector.contentSecurityPolicy("abc123", "readle.local")
        assertTrue(csp, csp.contains("script-src 'nonce-abc123'"))
        assertFalse(csp, csp.contains("script-src 'unsafe-inline'"))
        assertTrue(csp, csp.contains("connect-src 'none'"))
        assertTrue(csp, csp.contains("default-src 'none'"))
        assertTrue(csp, csp.contains("img-src https://readle.local data:"))
    }

    @Test
    fun jsonStringEscapesDangerousCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\"", HtmlInjector.jsonString("a\"b\\c\n"))
        val s = HtmlInjector.jsonString("</script><script>alert(1)")
        assertFalse(s, s.contains("</script>"))
        assertTrue(s, s.contains("\\u003c/script>"))
        assertEquals("\"\\u0001\"", HtmlInjector.jsonString("\u0001"))
    }

    @Test
    fun initJsonCarriesTargetAndFlags() {
        val json = HtmlInjector.initJson(ChapterTarget(2, fraction = 0.5f), isFirst = false, isLast = true, highlightsJson = "[]")
        assertEquals("{\"fraction\":0.50000,\"anchor\":null,\"offset\":null,\"first\":false,\"last\":true,\"zoomable\":false,\"highlights\":[]}", json)
        val withAnchor = HtmlInjector.initJson(ChapterTarget(0, anchor = "fn\"1", textOffset = 42), true, false, "[]")
        assertTrue(withAnchor, withAnchor.contains("\"anchor\":\"fn\\\"1\""))
        assertTrue(withAnchor, withAnchor.contains("\"offset\":42"))
    }

    @Test
    fun cssForJsStringIsSafeToEmbed() {
        val out = ReaderCss.forJsString("a{content:'x'}\nb{}\\")
        assertEquals("a{content:\\'x\\'}\\nb{}\\\\", out)
    }

    // ---- TextAnchor ----

    @Test
    fun textAnchorRoundTripsAndValidates() {
        val a = TextAnchor(3, 120, 180)
        assertEquals(a, TextAnchor.decode(a.encodeStart(), a.encodeEnd()))
        assertNull(TextAnchor.decode("3:10", "4:20"))
        assertNull(TextAnchor.decode("3:30", "3:10"))
        assertNull(TextAnchor.decode("x:1", "x:2"))
        assertNull(TextAnchor.decode(null, "3:1"))
    }

    @Test fun contrastThemesAreAlwaysMaximumContrast() {
        for (hc in listOf(false, true)) {
            val n = ReaderPalette.of(ReaderTheme.NIGHT_CONTRAST, hc)
            assertEquals(0xFF000000.toInt(), n.background)
            assertEquals(0xFFFFFFFF.toInt(), n.text)
            val s = ReaderPalette.of(ReaderTheme.SEPIA_CONTRAST, hc)
            assertEquals(0xFFF0DEC6.toInt(), s.background)
            assertEquals(0xFF000100.toInt(), s.text)
        }
        assertNotEquals(ReaderPalette.of(ReaderTheme.SEPIA, false).text, ReaderPalette.of(ReaderTheme.SEPIA_CONTRAST, false).text)
        assertNotEquals(ReaderPalette.of(ReaderTheme.NIGHT, false).background, ReaderPalette.of(ReaderTheme.NIGHT_CONTRAST, false).background)
    }
}
