package com.readle.app.reader.reflow

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File

/**
 * FictionBook 2 (.fb2). Each top-level section becomes a chapter; footnote bodies are gathered into a
 * final "Notes" chapter; embedded images are decoded on demand.
 */
object Fb2Book {
    const val MAX_BYTES = 64L * 1024 * 1024

    fun open(file: File, fallbackTitle: String?): MemoryBook = parse(file.readBoundedBytes(MAX_BYTES), fallbackTitle)

    fun parse(bytes: ByteArray, fallbackTitle: String?): MemoryBook {
        val root = try {
            XmlUtil.parseDeclared(bytes).documentElement
        } catch (e: Exception) {
            throw BookOpenException(BookOpenException.Kind.CORRUPT, "The FB2 file is not valid XML", e)
        }
        if (root.local() != "FictionBook") throw BookOpenException(BookOpenException.Kind.CORRUPT, "Not an FB2 book")

        val info = root.firstChildNamed("description")?.firstChildNamed("title-info")
        val title = info?.firstChildNamed("book-title")?.collapsedText()?.ifEmpty { null }
        val authors = info?.childElements()?.filter { it.local() == "author" }?.map { a ->
            val parts = listOf("first-name", "middle-name", "last-name").mapNotNull { a.firstChildNamed(it)?.collapsedText()?.ifEmpty { null } }
            if (parts.isNotEmpty()) parts.joinToString(" ") else a.firstChildNamed("nickname")?.collapsedText().orEmpty()
        }?.filter { it.isNotEmpty() }.orEmpty()
        val seq = info?.firstChildNamed("sequence")
        val series = seq?.attrOrNull("name")
        val lang = info?.firstChildNamed("lang")?.collapsedText()?.ifEmpty { null }

        // Images
        val resources = LinkedHashMap<String, LazyResource>()
        for (b in root.childElements().filter { it.local() == "binary" }) {
            val id = b.attrOrNull("id") ?: continue
            val mime = b.attrOrNull("content-type")?.lowercase()?.takeIf { it.startsWith("image/") } ?: continue
            if (mime == "image/svg+xml") continue
            val b64 = b.textContent.orEmpty()
            resources[MemoryBook.resourcePath(safe(id))] = LazyResource(mime) {
                java.util.Base64.getMimeDecoder().decode(b64)
            }
        }
        val coverId = info?.firstChildNamed("coverpage")?.firstChildNamed("image")?.href()?.removePrefix("#")
        val coverPath = coverId?.let { MemoryBook.resourcePath(safe(it)) }?.takeIf { it in resources }

        // Chapters
        val bodies = root.childElements().filter { it.local() == "body" }
        val main = bodies.filter { it.attrOrNull("name").let { n -> n != "notes" && n != "comments" } }
        val notes = bodies.filter { it.attrOrNull("name").let { n -> n == "notes" || n == "comments" } }

        class Part(val title: String?, val elements: List<Element>, val depth: Int)
        val units = ArrayList<Part>()
        for (body in main) {
            val front = ArrayList<Element>()
            for (c in body.childElements()) {
                if (c.local() == "section") {
                    if (front.isNotEmpty()) { units.add(Part(null, front.toList(), 0)); front.clear() }
                    units.add(Part(c.firstChildNamed("title")?.collapsedText()?.ifEmpty { null }, listOf(c), 0))
                } else front.add(c)
            }
            if (front.isNotEmpty()) units.add(Part(null, front.toList(), 0))
        }
        if (notes.isNotEmpty()) units.add(Part("Notes", notes.flatMap { b -> b.childElements() }, 0))
        if (units.isEmpty()) throw BookOpenException(BookOpenException.Kind.CORRUPT, "The FB2 book has no text")

        val chapterOfId = HashMap<String, Int>()
        // First pass: ids -> chapter index (accounting for splits happening later is unnecessary:
        // links point to the first piece of the unit, which is where the anchor lives for section ids,
        // and unit-level granularity is good enough for footnotes).
        units.forEachIndexed { i, u ->
            u.elements.forEach { e -> collectIds(e, i, chapterOfId) }
        }
        val rendered = ArrayList<List<String>>()
        units.forEach { u ->
            val sb = StringBuilder()
            u.elements.forEach { renderBlock(it, sb, 1, chapterOfId) }
            rendered.add(MemoryBook.splitAtBlocks(sb.toString()))
        }
        // Indices of the first piece of each unit (links target units, pieces may follow).
        val firstPiece = IntArray(units.size)
        var acc = 0
        rendered.forEachIndexed { i, pieces -> firstPiece[i] = acc; acc += pieces.size }
        val chapters = ArrayList<MemoryChapter>()
        rendered.forEachIndexed { i, pieces ->
            pieces.forEachIndexed { k, html ->
                chapters.add(MemoryChapter(if (k == 0) units[i].title else null, fixLinks(html, firstPiece)))
            }
        }
        return MemoryBook(
            BookMeta(title ?: fallbackTitle, authors.joinToString(", ").ifEmpty { null }, series, lang, coverPath),
            chapters, resources
        )
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun Element.href(): String? {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val n = attrs.item(i)
            if (n.nodeName == "href" || n.nodeName.endsWith(":href")) return n.nodeValue
        }
        return null
    }

    private fun collectIds(e: Element, unit: Int, out: HashMap<String, Int>) {
        e.attrOrNull("id")?.let { out[it] = unit }
        e.childElements().forEach { collectIds(it, unit, out) }
    }

    private val UNIT_LINK = Regex("href=\"@u(\\d+)#")

    /** Link targets were written as unit numbers; swap them for real chapter file names. */
    private fun fixLinks(html: String, firstPiece: IntArray): String =
        UNIT_LINK.replace(html) { m ->
            val u = m.groupValues[1].toInt().coerceIn(0, firstPiece.lastIndex)
            "href=\"c${firstPiece[u]}.html#"
        }

    private fun renderBlock(e: Element, sb: StringBuilder, depth: Int, ids: Map<String, Int>) {
        val id = e.attrOrNull("id")?.let { " id=\"${HtmlText.escape(it)}\"" }.orEmpty()
        when (e.local()) {
            "section" -> {
                sb.append("<section$id>")
                e.childElements().forEach { renderBlock(it, sb, depth + 1, ids) }
                sb.append("</section>")
            }
            "title" -> {
                val level = depth.coerceIn(1, 5) + 1
                val parts = e.childElements().map { inline(it, ids).trim() }.filter { it.isNotEmpty() }
                sb.append("<h$level$id>").append(parts.joinToString("<br>").ifEmpty { HtmlText.escape(e.collapsedText()) }).append("</h$level>")
            }
            "subtitle" -> sb.append("<h6$id>").append(inline(e, ids)).append("</h6>")
            "p" -> sb.append("<p$id>").append(inline(e, ids)).append("</p>")
            "empty-line" -> sb.append("<p>&nbsp;</p>")
            "epigraph", "cite", "annotation" -> {
                sb.append("<blockquote$id>")
                e.childElements().forEach { renderBlock(it, sb, depth, ids) }
                sb.append("</blockquote>")
            }
            "text-author" -> sb.append("<p style=\"text-align:right\"><em>").append(inline(e, ids)).append("</em></p>")
            "poem" -> {
                sb.append("<div$id>")
                e.childElements().forEach { renderBlock(it, sb, depth, ids) }
                sb.append("</div>")
            }
            "stanza" -> {
                sb.append("<p>")
                sb.append(e.childElements().filter { it.local() == "v" }.joinToString("<br>") { inline(it, ids) })
                sb.append("</p>")
            }
            "date" -> sb.append("<p><em>").append(inline(e, ids)).append("</em></p>")
            "image" -> imageTag(e)?.let { sb.append("<p style=\"text-align:center\">").append(it).append("</p>") }
            "table" -> {
                sb.append("<table>")
                for (tr in e.childElements().filter { it.local() == "tr" }) {
                    sb.append("<tr>")
                    for (c in tr.childElements()) {
                        val tag = if (c.local() == "th") "th" else "td"
                        sb.append("<$tag>").append(inline(c, ids)).append("</$tag>")
                    }
                    sb.append("</tr>")
                }
                sb.append("</table>")
            }
            else -> Unit
        }
    }

    private fun imageTag(e: Element): String? {
        val ref = e.href()?.removePrefix("#") ?: return null
        return "<img src=\"../r/${HtmlText.escape(safe(ref))}\" alt=\"\">"
    }

    private fun inline(e: Element, ids: Map<String, Int>): String {
        val sb = StringBuilder()
        var n: Node? = e.firstChild
        while (n != null) {
            val node: Node = n
            when (node) {
                is Element -> {
                    val inner = { inline(node, ids) }
                    when (node.local()) {
                        "emphasis" -> sb.append("<em>").append(inner()).append("</em>")
                        "strong" -> sb.append("<strong>").append(inner()).append("</strong>")
                        "strikethrough" -> sb.append("<s>").append(inner()).append("</s>")
                        "sub" -> sb.append("<sub>").append(inner()).append("</sub>")
                        "sup" -> sb.append("<sup>").append(inner()).append("</sup>")
                        "code" -> sb.append("<code>").append(inner()).append("</code>")
                        "image" -> imageTag(node)?.let { sb.append(it) }
                        "a" -> {
                            val href = node.href().orEmpty()
                            if (href.startsWith("#")) {
                                val target = href.removePrefix("#")
                                val unit = ids[target]
                                if (unit != null) sb.append("<a href=\"@u$unit#${HtmlText.escape(target)}\">").append(inner()).append("</a>")
                                else sb.append(inner())
                            } else if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) {
                                sb.append("<a href=\"${HtmlText.escape(href)}\">").append(inner()).append("</a>")
                            } else sb.append(inner())
                        }
                        "style" -> sb.append(inner())
                        else -> sb.append(inner())
                    }
                }
                is org.w3c.dom.Text -> sb.append(HtmlText.escape(node.data))
            }
            n = node.nextSibling
        }
        return sb.toString()
    }
}
