package com.readle.app.domain

import java.util.Locale
import java.util.UUID

/** A reading theme built from one of the user's images. Files live in the app's private storage. */
data class CustomTheme(
    val id: String,
    val name: String,
    /** File name inside the private themes folder (always a generated, safe name). */
    val file: String,
    /** Average colour of the image (ARGB); used for the screen chrome and while pages load. */
    val background: Int,
    val text: Int,
    val link: Int,
    val muted: Int,
    /** True when the text is light (the image is dark). */
    val dark: Boolean
)

/** A font the user imported. */
data class CustomFont(
    val id: String,
    val name: String,
    /** File name inside the private fonts folder (always a generated, safe name). */
    val file: String,
    /** ttf, otf, woff or woff2. */
    val format: String
)

enum class ThemeText(val label: String) { AUTO("Automatic"), DARK("Dark text"), LIGHT("Light text") }

/** Pure rules for custom fonts and themes (validation, naming, colour choice), kept free of Android so they are unit-tested. */
object CustomRules {
    val FONT_EXTENSIONS = listOf("ttf", "otf", "woff", "woff2")
    const val MAX_FONT_BYTES = 25L * 1024 * 1024
    const val MAX_NAME = 24
    const val MAX_ITEMS = 40
    const val MAX_IMAGE_SIDE = 1600

    /** Fixed scrim laid over the image so text stays readable on busy pictures. */
    const val SCRIM_ALPHA = 0.42f

    private val SAFE_FILE = Regex("^[a-z0-9]{8,32}\\.(ttf|otf|woff|woff2|jpg)$")
    private val ID = Regex("^[a-z0-9]{8,32}$")

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun isSafeId(id: String?): Boolean = id != null && ID.matches(id)

    /** Only generated names are ever served or opened, which rules out path tricks. */
    fun isSafeFileName(name: String): Boolean = SAFE_FILE.matches(name)

    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()

    /** @return a user-readable problem, or null when [name] is fine. */
    fun nameError(name: String, existing: Collection<String>, reserved: Collection<String>): String? {
        val n = cleanName(name)
        if (n.isEmpty()) return "Enter a name."
        if (existing.any { it.equals(n, ignoreCase = true) } || reserved.any { it.equals(n, ignoreCase = true) }) {
            return "A theme or font called \"$n\" already exists."
        }
        return null
    }

    /** Lower-case extension if it is one Readle accepts, otherwise null. */
    fun fontExtension(fileName: String?): String? {
        val dot = fileName?.lastIndexOf('.') ?: -1
        if (fileName == null || dot < 0 || dot == fileName.lastIndex) return null
        val ext = fileName.substring(dot + 1).lowercase(Locale.ROOT)
        return ext.takeIf { it in FONT_EXTENSIONS }
    }

    fun fontDisplayName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        return cleanName(base.replace('_', ' ').replace('-', ' ')).ifEmpty { "Custom font" }
    }

    /** True when the first bytes match the format the extension claims. */
    fun fontHeaderOk(ext: String, header: ByteArray): Boolean {
        if (header.size < 4) return false
        fun tag(s: String) = s.indices.all { header[it] == s[it].code.toByte() }
        val sfnt = header[0] == 0.toByte() && header[1] == 1.toByte() && header[2] == 0.toByte() && header[3] == 0.toByte()
        return when (ext) {
            "ttf", "otf" -> sfnt || tag("OTTO") || tag("true")
            "woff" -> tag("wOFF")
            "woff2" -> tag("wOF2")
            else -> false
        }
    }

    fun fontMime(ext: String): String? = when (ext) {
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> null
    }

    fun fontCssFormat(ext: String): String = when (ext) {
        "ttf" -> "truetype"
        "otf" -> "opentype"
        else -> ext
    }

    /** WCAG relative luminance of an ARGB colour, 0 (black) to 1 (white). */
    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    /** Contrast ratio between two colours, 1 (none) to 21 (black on white). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Below this ratio the text is hard to read, and the editor says so. */
    const val MIN_COMFORT_CONTRAST = 3.0

    /** "#RRGGBB", "RRGGBB" or "#RGB" to an opaque ARGB colour, or null when it isn't valid. */
    fun parseHex(raw: String?): Int? {
        var t = raw?.trim()?.removePrefix("#") ?: return null
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return (0xFF shl 24) or t.toInt(16)
    }

    fun toHex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    /** Swatches offered by the colour picker (opaque ARGB). */
    val PALETTE: List<Int> = listOf(
        0xFFFFFFFF, 0xFFF5F5F5, 0xFFE0E0E0, 0xFF9E9E9E, 0xFF424242, 0xFF121212, 0xFF000000,
        0xFFFAF3E0, 0xFFF0DEC6, 0xFFD8C3A5, 0xFF8D6E63, 0xFF4E342E,
        0xFFFFF8C4, 0xFFFFE082, 0xFFFFB74D, 0xFFE57373, 0xFFB71C1C,
        0xFFF8BBD0, 0xFFCE93D8, 0xFF7E57C2, 0xFF283593,
        0xFFBBDEFB, 0xFF64B5F6, 0xFF1565C0, 0xFF0B1F3A,
        0xFFB2DFDB, 0xFFC8E6C9, 0xFF81C784, 0xFF2E7D32, 0xFF1B3B2A
    ).map { it.toInt() }

    /**
     * Builds a theme. [average] is the picture's average colour, or the chosen background for a plain colour theme
     * (then [file] is empty). A [textColor] overrides the automatic black/white choice; [mode] only applies without one.
     */
    fun themeFor(id: String, name: String, file: String, average: Int, mode: ThemeText, textColor: Int? = null): CustomTheme {
        val avg = average or (0xFF shl 24)
        val chosen = textColor?.let { it or (0xFF shl 24) }
        val dark = when {
            chosen != null -> luminance(chosen) > 0.4
            mode == ThemeText.DARK -> false
            mode == ThemeText.LIGHT -> true
            // Pick whichever of black / white contrasts more with the image's average colour.
            else -> luminance(avg) < 0.179
        }
        return CustomTheme(
            id = id,
            name = cleanName(name),
            file = file,
            background = avg,
            text = chosen ?: if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
            link = if (dark) 0xFFA8C7FF.toInt() else 0xFF0B3D91.toInt(),
            muted = if (dark) 0xFFCCCCCC.toInt() else 0xFF333333.toInt(),
            dark = dark
        )
    }
}
