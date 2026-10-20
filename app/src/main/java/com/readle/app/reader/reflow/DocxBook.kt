package com.readle.app.reader.reflow

import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Word documents (.docx). Headings, paragraphs, bold / italic / underline / strike, super- and
 * subscript, lists, tables, hyperlinks and inline pictures are converted to HTML.
 * Not converted: headers / footers, footnotes, text boxes, page layout, tracked-change history.
 * Password-protected files and old binary .doc files are not zip archives and are reported as such.
 */
object DocxBook {
    private const val MAX_XML = 48 * 1024 * 1024
    private const val MAX_IMAGE = 16 * 1024 * 1024

    fun open(file: File, fallbackTitle: String?): MemoryBook {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw BookOpenException(
                BookOpenException.Kind.UNSUPPORTED,
                "This isn't a readable .docx file. It may be password-protected or an old binary .doc file, which Readle can't open.", e
            )
        } catch (e: IOException) {
            throw BookOpenException(BookOpenException.Kind.CORRUPT, "Could not read the document", e)
        }
        try {
            return parse(zip, fallbackTitle)
        } catch (e: BookOpenException) {
            zip.close(); throw e
        } catch (e: Exception) {
            zip.close()
            throw BookOpenException(BookOpenException.Kind.CORRUPT, "The document is damaged or malformed", e)
        }
    }

    private class Block(val html: String, val headingLevel: Int, val headingText: String?)

    private fun parse(zip: ZipFile, fallbackTitle: String?): MemoryBook {
        fun entry(name: String) = zip.getEntry(name)
        fun xml(name: String): Element? = entry(name)?.let { e ->
            XmlUtil.parse(zip.getInputStream(e).use { it.readBounded(MAX_XML) }).documentElement
        }
        val doc = xml("word/document.xml")
            ?: throw BookOpenException(BookOpenException.Kind.CORRUPT, "Missing word/document.xml")
        val body = doc.firstChildNamed("body") ?: throw BookOpenException(BookOpenException.Kind.CORRUPT, "The document has no body")

        // styles: id -> heading level
        val headingOf = HashMap<String, Int>()
        xml("word/styles.xml")?.childElements()?.filter { it.local() == "style" }?.forEach { st ->
            val id = st.attrOrNull("w:styleId") ?: return@forEach
            val name = st.firstChildNamed("name")?.attrOrNull("w:val")?.lowercase().orEmpty()
            val m = Regex("^heading\\s*(\\d)$").find(name)
            when {
                m != null -> headingOf[id] = m.groupValues[1].toInt().coerceIn(1, 6)
                name == "title" -> headingOf[id] = 1
                name == "subtitle" -> headingOf[id] = 2
            }
        }

        // numbering: numId -> is bullet list
        val bulletOf = HashMap<String, Boolean>()
        xml("word/numbering.xml")?.let { n ->
            val abstract = HashMap<String, Boolean>()
            n.childElements().filter { it.local() == "abstractNum" }.forEach { a ->
                val id = a.attrOrNull("w:abstractNumId") ?: return@forEach
                val fmt = a.childElements().firstOrNull { it.local() == "lvl" }?.firstChildNamed("numFmt")?.attrOrNull("w:val")
                abstract[id] = fmt == null || fmt == "bullet" || fmt == "none"
            }
            n.childElements().filter { it.local() == "num" }.forEach { num ->
                val id = num.attrOrNull("w:numId") ?: return@forEach
                val a = num.firstChildNamed("abstractNumId")?.attrOrNull("w:val")
                bulletOf[id] = abstract[a] ?: true
            }
        }

        // relationships: rId -> (target, external)
        class Rel(val target: String, val external: Boolean)
        val rels = HashMap<String, Rel>()
        xml("word/_rels/document.xml.rels")?.childElements()?.forEach { r ->
            val id = r.attrOrNull("Id") ?: return@forEach
            val target = r.attrOrNull("Target") ?: return@forEach
            rels[id] = Rel(target, r.getAttribute("TargetMode").equals("External", true))
        }

        val resources = LinkedHashMap<String, LazyResource>()
        fun imageFor(rid: String): String? {
            val rel = rels[rid] ?: return null
            if (rel.external) return null
            val path = PathUtil.resolve("word", rel.target)
            val mime = Mime.forPath(path)
            if (!mime.startsWith("image/") || mime == "image/svg+xml") return null
            val key = MemoryBook.resourcePath(path.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            if (key !in resources) {
                val e = entry(path) ?: return null
                resources[key] = LazyResource(mime) { zip.getInputStream(e).use { it.readBounded(MAX_IMAGE) } }
            }
            return "../$key"
        }

        // ---- inline conversion ----
        fun runHtml(r: Element): String {
            val props = r.firstChildNamed("rPr")
            fun on(name: String): Boolean {
                val p = props?.firstChildNamed(name) ?: return false
                val v = p.getAttribute("w:val")
                return v != "0" && v != "false" && v != "none"
            }
            val sb = StringBuilder()
            for (c in r.childElements()) {
                when (c.local()) {
                    "t" -> sb.append(HtmlText.escape(c.textContent.orEmpty()))
                    "tab" -> sb.append("&emsp;")
                    "br", "cr" -> if (c.getAttribute("w:type") != "page") sb.append("<br>")
                    "noBreakHyphen" -> sb.append("-")
                    "drawing", "pict" -> {
                        val rid = c.descendantsNamed("blip").firstOrNull()?.attrOrNull("r:embed")
                            ?: c.descendantsNamed("imagedata").firstOrNull()?.attrOrNull("r:id")
                        rid?.let { imageFor(it) }?.let { sb.append("<img src=\"$it\" alt=\"\">") }
                    }
                }
            }
            var out = sb.toString()
            if (out.isEmpty()) return out
            if (on("b")) out = "<strong>$out</strong>"
            if (on("i")) out = "<em>$out</em>"
            if (on("u")) out = "<u>$out</u>"
            if (on("strike") || on("dstrike")) out = "<s>$out</s>"
            when (props?.firstChildNamed("vertAlign")?.getAttribute("w:val")) {
                "superscript" -> out = "<sup>$out</sup>"
                "subscript" -> out = "<sub>$out</sub>"
            }
            return out
        }

        fun inlineHtml(p: Element): String {
            fun walk(parent: Element): String {
                val sb = StringBuilder()
                for (c in parent.childElements()) {
                    when (c.local()) {
                        "r" -> sb.append(runHtml(c))
                        "hyperlink" -> {
                            val inner = walk(c)
                            val rel = c.attrOrNull("r:id")?.let { rels[it] }
                            val href = rel?.target
                            if (rel != null && rel.external && href != null && Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(href))
                                sb.append("<a href=\"${HtmlText.escape(href)}\">").append(inner).append("</a>")
                            else sb.append(inner)
                        }
                        "ins", "smartTag", "fldSimple", "sdtContent", "customXml" -> sb.append(walk(c))
                        "sdt" -> c.firstChildNamed("sdtContent")?.let { sb.append(walk(it)) }
                    }
                }
                return sb.toString()
            }
            return walk(p)
        }

        // ---- blocks ----
        val blocks = ArrayList<Block>()
        var openList: String? = null // "ul" | "ol"
        val listBuf = StringBuilder()
        fun closeList() {
            if (openList != null) {
                blocks.add(Block("<$openList>$listBuf</$openList>", 0, null))
                listBuf.setLength(0); openList = null
            }
        }

        fun paragraph(p: Element) {
            val ppr = p.firstChildNamed("pPr")
            val styleId = ppr?.firstChildNamed("pStyle")?.attrOrNull("w:val")
            val outline = ppr?.firstChildNamed("outlineLvl")?.attrOrNull("w:val")?.toIntOrNull()?.plus(1)
            val level = styleId?.let { headingOf[it] } ?: outline ?: 0
            val inner = inlineHtml(p)
            val plain = HtmlText.toPlain(inner).replace('\n', ' ').trim()
            val numPr = ppr?.firstChildNamed("numPr")
            val numId = numPr?.firstChildNamed("numId")?.attrOrNull("w:val")
            if (numId != null && numId != "0" && level == 0) {
                val kind = if (bulletOf[numId] ?: true) "ul" else "ol"
                if (openList != null && openList != kind) closeList()
                openList = kind
                val lvl = numPr?.firstChildNamed("ilvl")?.attrOrNull("w:val")?.toIntOrNull() ?: 0
                listBuf.append("<li style=\"margin-left:${lvl * 1.5}em\">").append(inner).append("</li>")
                return
            }
            closeList()
            if (level in 1..6) {
                if (plain.isNotEmpty()) blocks.add(Block("<h$level>$inner</h$level>", level, plain))
                return
            }
            if (inner.isBlank()) { blocks.add(Block("<p>&nbsp;</p>", 0, null)); return }
            val align = when (ppr?.firstChildNamed("jc")?.attrOrNull("w:val")) {
                "center" -> "text-align:center"
                "right", "end" -> "text-align:right"
                "both", "distribute" -> "text-align:justify"
                else -> null
            }
            blocks.add(Block(if (align != null) "<p style=\"$align\">$inner</p>" else "<p>$inner</p>", 0, null))
        }

        fun container(parent: Element) {
            for (c in parent.childElements()) {
                when (c.local()) {
                    "p" -> paragraph(c)
                    "sdt" -> c.firstChildNamed("sdtContent")?.let { container(it) }
                    "tbl" -> {
                        closeList()
                        val sb = StringBuilder("<table>")
                        for (tr in c.childElements().filter { it.local() == "tr" }) {
                            sb.append("<tr>")
                            for (tc in tr.childElements().filter { it.local() == "tc" }) {
                                sb.append("<td>")
                                for (p in tc.childElements().filter { it.local() == "p" }) {
                                    val h = inlineHtml(p)
                                    if (h.isNotBlank()) sb.append("<p>").append(h).append("</p>")
                                }
                                sb.append("</td>")
                            }
                            sb.append("</tr>")
                        }
                        sb.append("</table>")
                        blocks.add(Block(sb.toString(), 0, null))
                    }
                }
            }
        }
        container(body)
        closeList()
        if (blocks.all { it.html == "<p>&nbsp;</p>" }) {
            throw BookOpenException(BookOpenException.Kind.CORRUPT, "The document has no readable text")
        }

        // ---- chapters: split at the top heading level in use (1 or 2) ----
        val splitLevel = blocks.map { it.headingLevel }.filter { it in 1..2 }.minOrNull()
        val chapters = ArrayList<MemoryChapter>()
        val cur = StringBuilder()
        var curTitle: String? = null
        fun flush() {
            if (cur.isNotBlank()) {
                MemoryBook.splitAtBlocks(cur.toString()).forEachIndexed { k, html -> chapters.add(MemoryChapter(if (k == 0) curTitle else null, html)) }
            }
            cur.setLength(0); curTitle = null
        }
        for (b in blocks) {
            if (splitLevel != null && b.headingLevel == splitLevel) {
                flush()
                curTitle = b.headingText
            }
            cur.append(b.html)
        }
        flush()

        val core = xml("docProps/core.xml")
        val title = core?.descendantsNamed("title")?.firstOrNull()?.collapsedText()?.ifEmpty { null }
        val author = core?.descendantsNamed("creator")?.firstOrNull()?.collapsedText()?.ifEmpty { null }
        return MemoryBook(BookMeta(title ?: fallbackTitle, author, null, null, null), chapters, resources) { runCatching { zip.close() } }
    }
}
