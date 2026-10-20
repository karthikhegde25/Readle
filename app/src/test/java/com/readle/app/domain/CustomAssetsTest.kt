package com.readle.app.domain

import com.readle.app.data.db.BookReaderSettingsEntity
import com.readle.app.reader.reflow.ReaderCss
import com.readle.app.reader.reflow.ReaderPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAssetsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun fontExtensionsAreCheckedCaseInsensitively() {
        assertEquals("ttf", CustomRules.fontExtension("My Font.TTF"))
        assertEquals("woff2", CustomRules.fontExtension("a.b.woff2"))
        assertEquals("otf", CustomRules.fontExtension("x.otf"))
        assertNull(CustomRules.fontExtension("notes.txt"))
        assertNull(CustomRules.fontExtension("font"))
        assertNull(CustomRules.fontExtension("font."))
        assertNull(CustomRules.fontExtension("font.ttc"))
        assertNull(CustomRules.fontExtension(null))
    }

    @Test fun fontHeadersMustMatchTheExtension() {
        assertTrue(CustomRules.fontHeaderOk("ttf", bytes(0, 1, 0, 0)))
        assertTrue(CustomRules.fontHeaderOk("otf", "OTTO".toByteArray()))
        assertTrue(CustomRules.fontHeaderOk("woff", "wOFF".toByteArray()))
        assertTrue(CustomRules.fontHeaderOk("woff2", "wOF2".toByteArray()))
        assertFalse(CustomRules.fontHeaderOk("woff", "wOF2".toByteArray()))
        assertFalse(CustomRules.fontHeaderOk("ttf", "<htm".toByteArray()))
        assertFalse(CustomRules.fontHeaderOk("ttf", bytes(0, 1)))
        assertFalse(CustomRules.fontHeaderOk("exe", "OTTO".toByteArray()))
    }

    @Test fun onlyGeneratedFileNamesAreServed() {
        assertTrue(CustomRules.isSafeFileName(CustomRules.newId() + ".ttf"))
        assertTrue(CustomRules.isSafeFileName("0123456789abcdef.jpg"))
        assertFalse(CustomRules.isSafeFileName("../secret.ttf"))
        assertFalse(CustomRules.isSafeFileName("a/b.ttf"))
        assertFalse(CustomRules.isSafeFileName("short.ttf"))
        assertFalse(CustomRules.isSafeFileName("0123456789abcdef.html"))
        assertTrue(CustomRules.isSafeId("0123456789abcdef"))
        assertFalse(CustomRules.isSafeId("../x"))
        assertFalse(CustomRules.isSafeId(null))
    }

    @Test fun namesAreCleanedAndCheckedForClashes() {
        assertEquals("My theme", CustomRules.cleanName("  My   theme  "))
        assertEquals(CustomRules.MAX_NAME, CustomRules.cleanName("x".repeat(80)).length)
        assertNotNull(CustomRules.nameError("   ", emptyList(), emptyList()))
        assertNotNull(CustomRules.nameError("night", emptyList(), listOf("Night")))
        assertNotNull(CustomRules.nameError("Forest", listOf("forest"), emptyList()))
        assertNull(CustomRules.nameError("Forest", listOf("Ocean"), listOf("Night")))
        assertEquals("my cool font", CustomRules.fontDisplayName("my_cool-font.ttf"))
    }

    @Test fun automaticTextColourContrastsWithThePicture() {
        val darkPicture = CustomRules.themeFor("0123456789abcdef", "Dark", "0123456789abcdef.jpg", 0xFF101820.toInt(), ThemeText.AUTO)
        assertTrue(darkPicture.dark)
        assertEquals(0xFFFFFFFF.toInt(), darkPicture.text)
        val lightPicture = CustomRules.themeFor("0123456789abcdef", "Light", "0123456789abcdef.jpg", 0xFFF0E6D0.toInt(), ThemeText.AUTO)
        assertFalse(lightPicture.dark)
        assertEquals(0xFF000000.toInt(), lightPicture.text)
        assertTrue(CustomRules.themeFor("0123456789abcdef", "x", "0123456789abcdef.jpg", 0xFFF0E6D0.toInt(), ThemeText.LIGHT).dark)
        assertFalse(CustomRules.themeFor("0123456789abcdef", "x", "0123456789abcdef.jpg", 0xFF000000.toInt(), ThemeText.DARK).dark)
    }

    private val theme = CustomRules.themeFor("0123456789abcdef", "Forest", "0123456789abcdef.jpg", 0xFF203020.toInt(), ThemeText.AUTO)
    private val font = CustomFont("fedcba9876543210", "Fancy", "fedcba9876543210.woff2", "woff2")

    @Test fun customThemeAndFontReachTheStylesheet() {
        val css = ReaderCss.build(TypographySettings(), customTheme = theme, customFont = font)
        assertTrue(css, css.contains("url('https://readle.local/user/theme/0123456789abcdef.jpg')"))
        assertTrue(css, css.contains("background-attachment:fixed"))
        assertTrue(css, css.contains("body{background:transparent !important;"))
        assertTrue(css, css.contains("@font-face{font-family:'rl-fedcba9876543210';src:url('https://readle.local/user/font/fedcba9876543210.woff2') format('woff2')"))
        assertTrue(css, css.contains("font-family:'rl-fedcba9876543210',"))
        assertTrue(css, css.contains("color:#FFFFFF !important"))
        // A dark picture gets a dark scrim.
        assertTrue(css, css.contains("rgba(0,0,0,0.42)"))
    }

    @Test fun unsafeFileNamesNeverReachTheStylesheet() {
        val bad = theme.copy(file = "../../etc/passwd")
        val css = ReaderCss.build(TypographySettings(), customTheme = bad, customFont = font.copy(file = "x/y.ttf"))
        assertFalse(css, css.contains("etc/passwd"))
        assertFalse(css, css.contains("@font-face"))
        assertFalse(css, css.contains("url("))
    }

    @Test fun withoutCustomItemsTheStylesheetIsUnchanged() {
        val css = ReaderCss.build(TypographySettings())
        assertFalse(css, css.contains("@font-face"))
        assertFalse(css, css.contains("/user/"))
        assertTrue(css, css.contains("body{background:#FFFFFF !important;"))
    }

    @Test fun customPaletteComesFromTheTheme() {
        val p = ReaderPalette.forCustom(theme)
        assertEquals(theme.background, p.background)
        assertEquals(theme.text, p.text)
    }

    @Test fun perBookOverridesCanPointAtCustomItems() {
        val global = TypographySettings()
        val t = global.copy(customThemeId = theme.id, customFontId = font.id)
        val entity = TypographyMerge.toEntity(5L, t)
        assertEquals("custom:" + theme.id, entity.theme)
        assertEquals("custom:" + font.id, entity.fontFamily)
        val back = TypographyMerge.effective(global, entity)
        assertEquals(theme.id, back.customThemeId)
        assertEquals(font.id, back.customFontId)
        // A built-in choice in the override clears a global custom choice.
        val builtIn = TypographyMerge.effective(t, BookReaderSettingsEntity(documentId = 5L, theme = "NIGHT", fontFamily = "MONO"))
        assertNull(builtIn.customThemeId)
        assertNull(builtIn.customFontId)
        assertEquals(ReaderTheme.NIGHT, builtIn.theme)
        // An unset override keeps the global custom choice.
        val keep = TypographyMerge.effective(t, BookReaderSettingsEntity(documentId = 5L))
        assertEquals(theme.id, keep.customThemeId)
        // A tampered id is ignored.
        val tampered = TypographyMerge.effective(global, BookReaderSettingsEntity(documentId = 5L, theme = "custom:../x"))
        assertNull(tampered.customThemeId)
    }

    @Test fun hexColoursParseStrictly() {
        assertEquals(0xFFF0DEC6.toInt(), CustomRules.parseHex("#F0DEC6"))
        assertEquals(0xFFF0DEC6.toInt(), CustomRules.parseHex("f0dec6"))
        assertEquals(0xFFAABBCC.toInt(), CustomRules.parseHex("#abc"))
        assertNull(CustomRules.parseHex("#FODEC6")) // the letter O is not a hex digit
        assertNull(CustomRules.parseHex("#12345"))
        assertNull(CustomRules.parseHex("#GGGGGG"))
        assertNull(CustomRules.parseHex(""))
        assertNull(CustomRules.parseHex(null))
        assertEquals("#F0DEC6", CustomRules.toHex(0xFFF0DEC6.toInt()))
    }

    @Test fun contrastRatioMatchesKnownValues() {
        assertEquals(21.0, CustomRules.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, CustomRules.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
        assertTrue(CustomRules.contrast(0xFF000100.toInt(), 0xFFF0DEC6.toInt()) > 12)
        assertTrue(CustomRules.contrast(0xFF888888.toInt(), 0xFF8A8A8A.toInt()) < CustomRules.MIN_COMFORT_CONTRAST)
    }

    @Test fun chosenTextColourOverridesAutomatic() {
        val t = CustomRules.themeFor("0123456789abcdef", "Plain", "", 0xFFF0DEC6.toInt(), ThemeText.AUTO, 0xFF000100.toInt())
        assertEquals(0xFF000100.toInt(), t.text)
        assertFalse(t.dark)
        assertEquals("", t.file)
        val light = CustomRules.themeFor("0123456789abcdef", "Plain", "", 0xFF101010.toInt(), ThemeText.AUTO, 0xFFFFE082.toInt())
        assertTrue(light.dark)
        // A plain colour theme never produces a picture URL.
        val css = ReaderCss.build(TypographySettings(), customTheme = t)
        assertFalse(css, css.contains("url("))
        assertTrue(css, css.contains("body{background:#F0DEC6 !important;"))
        assertTrue(css, css.contains("color:#000100 !important"))
    }

    @Test fun paletteHasOnlyOpaqueDistinctColours() {
        assertEquals(CustomRules.PALETTE.size, CustomRules.PALETTE.toSet().size)
        assertTrue(CustomRules.PALETTE.all { (it ushr 24) == 0xFF })
        assertTrue(CustomRules.PALETTE.contains(0xFFF0DEC6.toInt()))
    }
}
