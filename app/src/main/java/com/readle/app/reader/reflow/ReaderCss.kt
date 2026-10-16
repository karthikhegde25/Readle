package com.readle.app.reader.reflow

import com.readle.app.domain.ReaderTheme
import com.readle.app.domain.TypographySettings
import java.util.Locale

/** Colours of one reading theme, as ARGB ints (shared with the Compose chrome). */
data class ReaderPalette(val background: Int, val text: Int, val link: Int, val muted: Int) {
    companion object {
        fun of(theme: ReaderTheme, highContrast: Boolean): ReaderPalette = when (theme) {
            ReaderTheme.DAY ->
                if (highContrast) ReaderPalette(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF0033CC.toInt(), 0xFF333333.toInt())
                else ReaderPalette(0xFFFFFFFF.toInt(), 0xFF1B1B1B.toInt(), 0xFF1A5FB4.toInt(), 0xFF5F6368.toInt())
            ReaderTheme.SEPIA ->
                if (highContrast) ReaderPalette(0xFFF4ECD8.toInt(), 0xFF1A1208.toInt(), 0xFF6B3A00.toInt(), 0xFF3A2E1C.toInt())
                else ReaderPalette(0xFFF4ECD8.toInt(), 0xFF433422.toInt(), 0xFF8A4B08.toInt(), 0xFF7A6A52.toInt())
            ReaderTheme.NIGHT ->
                if (highContrast) ReaderPalette(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFA8C7FF.toInt(), 0xFFCCCCCC.toInt())
                else ReaderPalette(0xFF121212.toInt(), 0xFFD6D6D6.toInt(), 0xFF8AB4F8.toInt(), 0xFF9AA0A6.toInt())
            // Always maximum contrast, whatever the global high-contrast switch says.
            ReaderTheme.NIGHT_CONTRAST -> ReaderPalette(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFA8C7FF.toInt(), 0xFFCCCCCC.toInt())
            ReaderTheme.SEPIA_CONTRAST -> ReaderPalette(0xFFF4ECD8.toInt(), 0xFF1A1208.toInt(), 0xFF6B3A00.toInt(), 0xFF3A2E1C.toInt())
        }
    }
}

object ReaderCss {
    fun hex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    private fun num(v: Float): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    /**
     * Builds the stylesheet injected into every chapter. Key rules use !important so they win over the
     * book's own CSS, while headings keep their relative sizes (only the root size is scaled).
     */
    fun build(t: TypographySettings, highContrast: Boolean = false, paged: Boolean = false, animate: Boolean = true): String {
        val p = ReaderPalette.of(t.theme, highContrast)
        val ls = num(t.lineSpacing)
        val para = num(t.paragraphSpacing)
        return buildString {
            append("html{-webkit-text-size-adjust:100%;font-size:").append(t.fontSizePercent).append("% !important;")
            append("background:").append(hex(p.background)).append(" !important;}")
            append("body{background:").append(hex(p.background)).append(" !important;")
            append("color:").append(hex(p.text)).append(" !important;")
            append("font-family:").append(t.font.css).append(" !important;")
            append("font-size:1rem !important;line-height:").append(ls).append(" !important;")
            append("text-align:").append(t.align.css).append(" !important;")
            append("margin:0 !important;padding:12px ").append(t.marginDp).append("px 24px !important;")
            append("overflow-wrap:break-word;word-wrap:break-word;box-sizing:border-box;}")
            append("p,li,blockquote,dd,dt,div{line-height:").append(ls).append(" !important;}")
            // The reader's text-size setting wins over absolute sizes in the book's own CSS.
            append("p,li,dd,dt{font-size:1rem !important;}")
            append("p{margin-top:0 !important;margin-bottom:").append(para).append("em !important;}")
            append("p,li,blockquote,dd,dt,span,div,td,th,h1,h2,h3,h4,h5,h6{color:inherit !important;")
            append("background-color:transparent !important;}")
            append("a,a:visited{color:").append(hex(p.link)).append(" !important;}")
            append("img,svg,video{max-width:100% !important;height:auto !important;}")
            append("table{max-width:100% !important;}")
            append("pre{white-space:pre-wrap !important;overflow-wrap:break-word;}")
            append("::selection{background:").append(hex(p.link)).append("55;}")
            append("mark.rl-hl{color:inherit !important;border-radius:2px;padding:0;}")
            append("::highlight(rl-tts){background-color:rgba(255,193,7,0.55);color:inherit;}")
            append("mark.rl-tts{background:rgba(255,193,7,0.55) !important;color:inherit !important;border-radius:2px;padding:0;}")
            append("mark.rl-find{background:#FF9F43 !important;color:#000 !important;border-radius:2px;}")
            append("#rl-footer{margin:32px 0 8px;text-align:center;font-size:0.9rem;color:")
            append(hex(p.muted)).append(" !important;}")
            append("#rl-footer button{font:inherit;color:").append(hex(p.link)).append(";background:transparent;")
            append("border:1px solid ").append(hex(p.muted)).append(";border-radius:20px;padding:10px 18px;margin:6px;")
            append("min-height:44px;}")
            append("#rl-header{margin:4px 0 20px;text-align:center;}")
            append("#rl-header button{font:inherit;color:").append(hex(p.link)).append(";background:transparent;")
            append("border:1px solid ").append(hex(p.muted)).append(";border-radius:20px;padding:10px 18px;min-height:44px;}")
            if (paged) append(pagedCss(t.marginDp, animate))
        }
    }

    /**
     * Real pages: the chapter is laid out in CSS columns exactly one screen wide and one screen tall,
     * with blank space above and below the text. The script moves between columns a screen at a time;
     * the page cannot be scrolled freely. One column stride equals the viewport width.
     */
    private fun pagedCss(marginDp: Int, animate: Boolean): String {
        val m = marginDp.coerceAtLeast(8)
        return buildString {
            append("html{overflow:hidden !important;height:100% !important;--rl-paged:1;--rl-m:").append(m)
            append(";--rl-anim:").append(if (animate) 1 else 0)
            append(";--rl-pt:").append(PAGE_TOP_PX).append(";--rl-pb:").append(PAGE_BOTTOM_PX).append(";overflow-anchor:none;}")
            append("body{overflow:hidden !important;height:100% !important;margin:0 !important;padding:0 !important;}")
            append("#rl-vp{position:fixed;overflow:hidden;}")
            append("#rl-col{column-fill:auto;}")
            append("#rl-col > *:first-child{margin-top:0 !important;}")
            append("h1,h2,h3,h4,h5,h6{break-after:avoid;}")
            append("img,svg,figure,table,pre,blockquote{break-inside:avoid;}")
            append("p{orphans:2;widows:2;}")
            append("img,svg,video{max-height:var(--rl-h,70vh) !important;object-fit:contain;}")
            append("#rl-header,#rl-footer{display:none !important;}")
        }
    }

    const val PAGE_TOP_PX = 56
    const val PAGE_BOTTOM_PX = 56

    /** Escapes CSS for embedding in a JS single-quoted string literal. */
    fun forJsString(css: String): String = css
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
}

/** Where a chapter should open: exactly one of these is normally set. */
data class ChapterTarget(
    val spine: Int,
    val fraction: Float? = null,
    val anchor: String? = null,
    val textOffset: Int? = null
)

/** Builds the HTML a chapter is served as: base chapter markup with Readle's CSS, script and CSP wired in. */
object HtmlInjector {
    private val XML_DECL = Regex("^\\s*<\\?xml[^>]*\\?>", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("^\\s*<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val BODY_CLOSE = Regex("</body\\s*>", RegexOption.IGNORE_CASE)

    fun contentSecurityPolicy(nonce: String, host: String): String =
        "default-src 'none'; " +
            "script-src 'nonce-$nonce'; " +
            "style-src https://$host 'unsafe-inline'; " +
            "img-src https://$host data:; " +
            "font-src https://$host data:; " +
            "media-src https://$host; " +
            "frame-src 'none'; connect-src 'none'; object-src 'none'; form-action 'none'"

    fun initJson(target: ChapterTarget, isFirst: Boolean, isLast: Boolean, highlightsJson: String): String {
        val sb = StringBuilder("{")
        sb.append("\"fraction\":").append(target.fraction?.let { String.format(Locale.US, "%.5f", it) } ?: "null")
        sb.append(",\"anchor\":").append(target.anchor?.let { jsonString(it) } ?: "null")
        sb.append(",\"offset\":").append(target.textOffset ?: "null")
        sb.append(",\"first\":").append(isFirst)
        sb.append(",\"last\":").append(isLast)
        sb.append(",\"highlights\":").append(highlightsJson)
        sb.append('}')
        return sb.toString()
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c") // keeps "</script>" out of inline script data
                ' ' -> append("\\u2028")
                ' ' -> append("\\u2029")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    fun inject(
        rawHtml: String,
        css: String,
        script: String,
        initJson: String,
        nonce: String,
        baseHref: String? = null
    ): String {
        var html = XML_DECL.replace(rawHtml, "")
        html = DOCTYPE.replace(html, "")
        val headBits = buildString {
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            baseHref?.let { append("<base href=\"").append(it).append("\">") }
            append("<style class=\"rl-style\">").append(css).append("</style>")
            append("<script nonce=\"").append(nonce).append("\">window.__readleInit=").append(initJson).append(";</script>")
            append("<script nonce=\"").append(nonce).append("\">").append(script).append("</script>")
        }
        val tailStyle = "<style class=\"rl-style\">$css</style>"

        val head = HEAD_OPEN.find(html)
        html = when {
            head != null -> html.substring(0, head.range.last + 1) + headBits + html.substring(head.range.last + 1)
            else -> {
                val h = HTML_OPEN.find(html)
                if (h != null) html.substring(0, h.range.last + 1) + "<head>$headBits</head>" + html.substring(h.range.last + 1)
                else "<head>$headBits</head>$html"
            }
        }
        val closes = BODY_CLOSE.findAll(html).lastOrNull()
        html = if (closes != null) html.substring(0, closes.range.first) + tailStyle + html.substring(closes.range.first)
        else html + tailStyle
        return "<!DOCTYPE html>$html"
    }
}
