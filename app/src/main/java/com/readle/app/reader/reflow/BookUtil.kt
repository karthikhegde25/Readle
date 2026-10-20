package com.readle.app.reader.reflow

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Path handling for archive-relative hrefs (percent-encoded, may contain "../" and fragments). */
object PathUtil {
    /** Percent-decodes UTF-8 sequences; unlike URLDecoder it leaves '+' alone. */
    fun decode(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            val cp = s.codePointAt(i)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i += Character.charCount(cp)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun stripFragment(href: String): String = href.substringBefore('#').substringBefore('?')

    fun fragmentOf(href: String): String? =
        if (href.contains('#')) href.substringAfter('#').takeIf { it.isNotEmpty() } else null

    fun dirOf(path: String): String = if (path.contains('/')) path.substringBeforeLast('/') else ""

    /** Resolves [href] against the directory [baseDir] and normalises "." / ".." segments. */
    fun resolve(baseDir: String, href: String): String {
        val clean = decode(stripFragment(href))
        val raw = if (clean.startsWith("/")) clean.removePrefix("/")
        else if (baseDir.isEmpty()) clean else "$baseDir/$clean"
        return normalize(raw)
    }

    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }
}

object Mime {
    fun forPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "xhtml", "html", "htm", "xht" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "xml", "ncx", "opf" -> "application/xml"
        else -> "application/octet-stream"
    }

    fun isHtml(path: String): Boolean = forPath(path) == "text/html"
}

/** Very small HTML-to-text converter for search snippets; not a general HTML parser. */
object HtmlText {
    private val SCRIPT_STYLE = Regex("<(script|style)[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("</(p|div|h[1-6]|li|tr|blockquote|section|article)\\s*>|<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z]+);")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val BLANKS = Regex("\\n{3,}")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘",
        "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "copy" to "©"
    )

    fun toPlain(html: String): String {
        var t = SCRIPT_STYLE.replace(html, " ")
        t = BLOCK_END.replace(t, "\n")
        t = TAG.replace(t, "")
        t = ENTITY.replace(t) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { codePointToString(it) } ?: ""
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let { codePointToString(it) } ?: ""
                else -> named[body.lowercase()] ?: ""
            }
        }
        t = SPACES.replace(t, " ")
        t = t.lines().joinToString("\n") { it.trim() }
        return BLANKS.replace(t, "\n\n").trim()
    }

    private fun codePointToString(cp: Int): String =
        if (cp in 0..0x10FFFF) String(Character.toChars(cp)) else ""

    fun escape(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }
}

/** Reads at most [limit] bytes; throws if the stream is longer. Used to bound memory for hostile inputs. */
fun InputStream.readBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw BookOpenException(BookOpenException.Kind.TOO_LARGE, "Entry larger than $limit bytes")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
