package com.readle.app.reader.reflow

import java.io.File
import java.nio.charset.Charset

/**
 * A single saved web page / HTML document (.html, .htm, .xhtml). Scripts, frames, external styles and
 * remote images are removed (the reader has no network anyway); the text and structure are kept.
 */
object HtmlDocBook {
    const val MAX_BYTES = 24L * 1024 * 1024

    private val REMOVE_BLOCKS = Regex("<(script|style|iframe|object|embed|noscript|svg|template)\\b[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val REMOVE_VOID = Regex("<(link|meta|base)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val COMMENTS = Regex("<!--[\\s\\S]*?-->")
    private val BODY = Regex("<body\\b[^>]*>([\\s\\S]*?)(</body\\s*>|$)", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("<title[^>]*>([\\s\\S]*?)</title\\s*>", RegexOption.IGNORE_CASE)
    private val IMG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SRC = Regex("\\bsrc\\s*=\\s*[\"']?(data:image/[^\"'\\s>]+)", RegexOption.IGNORE_CASE)
    private val ALT = Regex("\\balt\\s*=\\s*\"([^\"]*)\"|\\balt\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE)
    private val EVENT_ATTR = Regex("\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)
    private val JS_HREF = Regex("href\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\1", RegexOption.IGNORE_CASE)
    private val H = Regex("<h([1-2])\\b[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE)

    fun open(file: File, fallbackTitle: String?): MemoryBook = parse(file.readBoundedBytes(MAX_BYTES), fallbackTitle)

    fun parse(bytes: ByteArray, fallbackTitle: String?): MemoryBook {
        val text = decode(bytes)
        val title = TITLE.find(text)?.groupValues?.get(1)?.let { HtmlText.toPlain(it).replace('\n', ' ').trim() }?.ifEmpty { null }
        var body = BODY.find(text)?.groupValues?.get(1) ?: text
        body = COMMENTS.replace(body, "")
        body = REMOVE_BLOCKS.replace(body, "")
        body = REMOVE_VOID.replace(body, "")
        body = EVENT_ATTR.replace(body, "")
        body = JS_HREF.replace(body, "")
        body = IMG.replace(body) { m ->
            val data = SRC.find(m.value)?.groupValues?.get(1)
            if (data != null) "<img src=\"${HtmlText.escape(data)}\" alt=\"\">"
            else ALT.find(m.value)?.let { a -> (a.groupValues[1].ifEmpty { a.groupValues[2] }).takeIf { it.isNotBlank() }?.let { "<em>[${it}]</em>" } }.orEmpty()
        }
        if (HtmlText.toPlain(body).isBlank() && !body.contains("<img", true)) {
            throw BookOpenException(BookOpenException.Kind.CORRUPT, "The HTML file has no readable content")
        }
        val pieces = MemoryBook.splitAtBlocks(body, 150_000)
        val chapters = pieces.mapIndexed { i, html ->
            val heading = H.find(html)?.groupValues?.get(2)?.let { HtmlText.toPlain(it).replace('\n', ' ').trim() }?.takeIf { it.isNotEmpty() && it.length < 120 }
            MemoryChapter(heading ?: if (i == 0) title else null, html)
        }
        return MemoryBook(BookMeta(title ?: fallbackTitle, null, null, null, null), chapters)
    }

    /** BOM, then a declared `<meta charset>`, then strict UTF-8, then windows-1252. */
    internal fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val declared = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_.:-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        val hasBom = bytes.size >= 2 && ((bytes[0].toInt() and 0xFF == 0xFE && bytes[1].toInt() and 0xFF == 0xFF) ||
            (bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xFF == 0xFE) ||
            (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()))
        if (!hasBom && declared != null && !declared.equals("utf-8", true) && !declared.equals("utf8", true)) {
            try {
                return String(bytes, Charset.forName(declared))
            } catch (e: Exception) {
                // fall through to detection
            }
        }
        return TxtBook.decode(bytes)
    }
}
