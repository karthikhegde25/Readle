package com.readle.app.reader.reflow

import java.io.ByteArrayInputStream

/** A resource (image, ...) that a converted book serves to the WebView, loaded only when requested. */
class LazyResource(val mime: String, val loader: () -> ByteArray)

/** One converted chapter. [title] is shown in the table of contents; null means "no heading of its own". */
class MemoryChapter(val title: String?, val bodyHtml: String)

/**
 * A reflowable book whose chapters were produced by a converter (MOBI, FB2, DOCX, RTF, HTML, Markdown,
 * comics...). Chapters are plain HTML strings; images and other assets are served from [resources]
 * under "r/<name>". The reader treats it exactly like an EPUB.
 */
class MemoryBook(
    override val meta: BookMeta,
    chapters: List<MemoryChapter>,
    private val resources: Map<String, LazyResource> = emptyMap(),
    private val onClose: () -> Unit = {}
) : ReflowBook {

    private val pages: List<MemoryChapter> = chapters.ifEmpty { listOf(MemoryChapter(null, "<p></p>")) }
    private val htmls: List<String> = pages.map { wrap(it.bodyHtml) }

    override val spine: List<SpineItem> = htmls.mapIndexed { i, h -> SpineItem(chapterPath(i), h.length.toLong()) }

    override val toc: List<TocEntry> = run {
        val titled = pages.mapIndexedNotNull { i, c -> c.title?.takeIf { it.isNotBlank() }?.let { TocEntry(it.trim(), i, null, 0) } }
        if (titled.isNotEmpty()) titled else pages.indices.map { TocEntry("Section ${it + 1}", it, null, 0) }
    }

    override fun open(path: String): ResourceData? {
        val p = PathUtil.normalize(path)
        CHAPTER.matchEntire(p)?.let { m ->
            val html = htmls.getOrNull(m.groupValues[1].toInt()) ?: return null
            return ResourceData("text/html", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
        val res = resources[p] ?: return null
        val bytes = try {
            res.loader()
        } catch (e: Exception) {
            return null
        }
        return ResourceData(res.mime, ByteArrayInputStream(bytes))
    }

    override fun chapterText(index: Int): String = htmls.getOrNull(index)?.let { HtmlText.toPlain(it) }.orEmpty()

    override fun close() = onClose()

    companion object {
        private val CHAPTER = Regex("^b/c(\\d+)\\.html$")

        fun chapterPath(index: Int) = "b/c$index.html"

        fun resourcePath(name: String) = "r/$name"

        fun wrap(body: String): String =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body>$body</body></html>"

        /**
         * Splits [html] into pieces of roughly [maxChars] at block boundaries so that very long books
         * become several chapters (faster loads, better progress). Splits only at closing block tags.
         */
        fun splitAtBlocks(html: String, maxChars: Int = 120_000): List<String> {
            if (html.length <= maxChars) return listOf(html)
            val out = ArrayList<String>()
            var start = 0
            val boundary = Regex("</(p|div|li|h[1-6]|blockquote|table|pre)>", RegexOption.IGNORE_CASE)
            while (start < html.length) {
                val limit = start + maxChars
                if (limit >= html.length) {
                    out.add(html.substring(start))
                    break
                }
                val cut = boundary.findAll(html, limit).firstOrNull()?.let { it.range.last + 1 }
                    ?: boundary.findAll(html.substring(start, limit)).lastOrNull()?.let { start + it.range.last + 1 }
                    ?: limit
                // A closing tag far past the limit (one giant block) is not worth waiting for.
                val end = if (cut - limit > maxChars) html.indexOf('>', limit).let { if (it < 0) html.length else it + 1 } else cut
                out.add(html.substring(start, end))
                start = end
            }
            return out
        }
    }
}

/** Reads a whole file into memory, refusing inputs larger than [limit] bytes. */
internal fun java.io.File.readBoundedBytes(limit: Long): ByteArray {
    if (length() > limit) {
        throw BookOpenException(BookOpenException.Kind.TOO_LARGE, "Files larger than ${limit / (1024 * 1024)} MB are not supported in this format")
    }
    return readBytes()
}
