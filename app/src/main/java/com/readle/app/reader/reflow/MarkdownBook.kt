package com.readle.app.reader.reflow

import java.io.File

/**
 * Markdown (.md, .markdown) converted to HTML: headings, paragraphs, emphasis, code, quotes, lists
 * (including task lists), tables, rules, links. Raw HTML inside the file is shown as text, never run.
 * Images are replaced by their alt text because a lone .md file has no image files next to it.
 */
object MarkdownBook {
    const val MAX_BYTES = 12L * 1024 * 1024

    fun open(file: File, fallbackTitle: String?): MemoryBook = parse(TxtBook.decode(file.readBoundedBytes(MAX_BYTES)), fallbackTitle)

    private class Block(val html: String, val level: Int, val text: String?)

    fun parse(source: String, fallbackTitle: String?): MemoryBook {
        var text = source.replace("\r\n", "\n").replace('\r', '\n')
        var fmTitle: String? = null
        var fmAuthor: String? = null
        Regex("^---\\n([\\s\\S]*?)\\n---[ \\t]*(\\n|$)").find(text)?.let { m ->
            Regex("(?m)^title:\\s*(.+)$").find(m.groupValues[1])?.let { fmTitle = it.groupValues[1].trim().trim('"', '\'') }
            Regex("(?m)^author:\\s*(.+)$").find(m.groupValues[1])?.let { fmAuthor = it.groupValues[1].trim().trim('"', '\'') }
            text = text.substring(m.range.last + 1)
        }
        val blocks = ArrayList<Block>()
        parseBlocks(text.split('\n'), blocks)
        if (blocks.isEmpty()) throw BookOpenException(BookOpenException.Kind.CORRUPT, "The Markdown file is empty")

        val splitLevel = blocks.map { it.level }.filter { it in 1..2 }.minOrNull()
        val chapters = ArrayList<MemoryChapter>()
        val cur = StringBuilder()
        var curTitle: String? = null
        fun flush() {
            if (cur.isNotBlank()) MemoryBook.splitAtBlocks(cur.toString()).forEachIndexed { k, h -> chapters.add(MemoryChapter(if (k == 0) curTitle else null, h)) }
            cur.setLength(0); curTitle = null
        }
        val headingsAtSplit = blocks.count { it.level == splitLevel }
        for (b in blocks) {
            if (splitLevel != null && headingsAtSplit > 1 && b.level == splitLevel) { flush(); curTitle = b.text }
            cur.append(b.html)
        }
        flush()
        val firstH1 = blocks.firstOrNull { it.level == 1 }?.text
        return MemoryBook(BookMeta(fmTitle ?: firstH1 ?: fallbackTitle, fmAuthor, null, null, null), chapters)
    }

    private val FENCE = Regex("^\\s{0,3}(```|~~~)\\s*([\\w+-]*)\\s*$")
    private val ATX = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val HR = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val SETEXT = Regex("^\\s{0,3}(=+|-+)\\s*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    private fun parseBlocks(lines: List<String>, out: MutableList<Block>) {
        var i = 0
        fun isBlank(s: String) = s.isBlank()
        while (i < lines.size) {
            val line = lines[i]
            if (isBlank(line)) { i++; continue }

            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                val marker = fence.groupValues[1]
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(marker)) { code.append(lines[i]).append('\n'); i++ }
                i++
                out.add(Block("<pre><code>${HtmlText.escape(code.toString().trimEnd('\n'))}</code></pre>", 0, null))
                continue
            }

            val atx = ATX.matchEntire(line)
            if (atx != null) {
                val level = atx.groupValues[1].length
                val inner = inline(atx.groupValues[2])
                out.add(Block("<h$level>$inner</h$level>", level, HtmlText.toPlain(inner).replace('\n', ' ').trim()))
                i++; continue
            }
            if (HR.matches(line)) { out.add(Block("<hr>", 0, null)); i++; continue }

            if (line.trimStart().startsWith(">")) {
                val q = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    q.add(lines[i].trimStart().removePrefix(">").removePrefix(" ")); i++
                }
                val inner = ArrayList<Block>()
                parseBlocks(q, inner)
                out.add(Block("<blockquote>${inner.joinToString("") { it.html }}</blockquote>", 0, null))
                continue
            }

            val bm = BULLET.matchEntire(line)
            val om = ORDERED.matchEntire(line)
            if ((bm != null || om != null) && !HR.matches(line)) {
                val ordered = om != null
                val baseIndent = (bm ?: om)!!.groupValues[1].length
                val items = ArrayList<MutableList<String>>()
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.isBlank()) {
                        // blank line: the list continues only if the next non-blank line is another item or indented
                        val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
                        if (next != null && (BULLET.matches(next) || ORDERED.matches(next) || next.startsWith("  ") || next.startsWith("\t"))) { i++; continue }
                        break
                    }
                    val b2 = BULLET.matchEntire(l)
                    val o2 = ORDERED.matchEntire(l)
                    val m = b2 ?: o2
                    if (m != null && m.groupValues[1].length == baseIndent && (o2 != null) == ordered && !HR.matches(l)) {
                        items.add(mutableListOf(m.groupValues[3])); i++
                    } else if (items.isNotEmpty() && (l.startsWith("  ") || l.startsWith("\t") || (m != null && m.groupValues[1].length > baseIndent))) {
                        items.last().add(l.removePrefix("\t").let { if (it.startsWith("  ")) it.substring(minOf(baseIndent + 2, it.length - it.trimStart().length)) else it }); i++
                    } else if (items.isNotEmpty() && m == null && ATX.matchEntire(l) == null && !l.trimStart().startsWith(">") && FENCE.matchEntire(l) == null) {
                        items.last().add(l.trim()); i++ // lazy continuation
                    } else break
                }
                val tag = if (ordered) "ol" else "ul"
                val start = if (ordered) om!!.groupValues[2].toIntOrNull()?.takeIf { it != 1 } else null
                val sb = StringBuilder("<$tag${if (start != null) " start=\"$start\"" else ""}>")
                for (item in items) {
                    var first = item.first()
                    var box = ""
                    Regex("^\\[([ xX])\\]\\s+").find(first)?.let { box = if (it.groupValues[1] == " ") "☐ " else "☑ "; first = first.substring(it.range.last + 1) }
                    val rest = item.drop(1)
                    sb.append("<li>").append(box).append(inline(first))
                    if (rest.any { it.isNotBlank() }) {
                        val nested = ArrayList<Block>()
                        parseBlocks(rest, nested)
                        sb.append(nested.joinToString("") { it.html })
                    }
                    sb.append("</li>")
                }
                sb.append("</$tag>")
                out.add(Block(sb.toString(), 0, null))
                continue
            }

            if (line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                fun cells(s: String) = s.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
                val head = cells(line)
                val sb = StringBuilder("<table><tr>")
                head.forEach { sb.append("<th>").append(inline(it)).append("</th>") }
                sb.append("</tr>")
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    sb.append("<tr>")
                    cells(lines[i]).forEach { sb.append("<td>").append(inline(it)).append("</td>") }
                    sb.append("</tr>"); i++
                }
                sb.append("</table>")
                out.add(Block(sb.toString(), 0, null))
                continue
            }

            if (line.startsWith("    ") || line.startsWith("\t")) {
                val code = StringBuilder()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                    code.append(lines[i].removePrefix("    ").removePrefix("\t")).append('\n'); i++
                }
                out.add(Block("<pre><code>${HtmlText.escape(code.toString().trimEnd('\n'))}</code></pre>", 0, null))
                continue
            }

            // paragraph (or setext heading)
            val para = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                val l = lines[i]
                if (para.isNotEmpty() && SETEXT.matches(l)) {
                    val level = if (l.trim().startsWith("=")) 1 else 2
                    val inner = inline(para.joinToString(" ") { it.trim() })
                    out.add(Block("<h$level>$inner</h$level>", level, HtmlText.toPlain(inner).replace('\n', ' ').trim()))
                    para.clear(); i++
                    break
                }
                if (para.isNotEmpty() && (ATX.matches(l) || FENCE.matches(l) || HR.matches(l) ||
                        l.trimStart().startsWith(">") || BULLET.matches(l) || ORDERED.matches(l))) break
                para.add(l); i++
            }
            if (para.isNotEmpty()) {
                val joined = StringBuilder()
                para.forEachIndexed { k, l ->
                    val hard = l.endsWith("  ") || l.endsWith("\\")
                    joined.append(l.trim().removeSuffix("\\"))
                    if (k < para.lastIndex) joined.append(if (hard) "\u0001" else " ")
                }
                out.add(Block("<p>${inline(joined.toString()).replace("\u0001", "<br>")}</p>", 0, null))
            }
        }
    }

    private const val PH = '\uE000'

    internal fun inline(raw: String): String {
        val stash = ArrayList<String>()
        fun hold(html: String): String { stash.add(html); return "$PH${stash.size - 1}$PH" }
        var s = raw
        // backslash escapes
        s = Regex("\\\\([\\\\`*_{}\\[\\]()#+.!|>~-])").replace(s) { hold(HtmlText.escape(it.groupValues[1])) }
        // code spans
        s = Regex("(`+)([\\s\\S]+?)\\1").replace(s) { hold("<code>${HtmlText.escape(it.groupValues[2].trim())}</code>") }
        // images -> alt text
        s = Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)").replace(s) { m -> m.groupValues[1].takeIf { it.isNotBlank() }?.let { hold("<em>[${HtmlText.escape(it)}]</em>") }.orEmpty() }
        // links
        s = Regex("\\[([^\\]]+)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)").replace(s) { m ->
            val url = m.groupValues[2]
            val label = m.groupValues[1]
            if (Regex("^(https?://|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(url))
                hold("<a href=\"${HtmlText.escape(url)}\">") + label + hold("</a>")
            else label
        }
        // autolinks
        s = Regex("<((?:https?://|mailto:)[^>\\s]+)>").replace(s) { hold("<a href=\"${HtmlText.escape(it.groupValues[1])}\">${HtmlText.escape(it.groupValues[1])}</a>") }
        s = HtmlText.escape(s)
        s = Regex("\\*\\*(?=\\S)([\\s\\S]*?\\S)\\*\\*").replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        s = Regex("(?<![\\w])__(?=\\S)([\\s\\S]*?\\S)__(?![\\w])").replace(s) { "<strong>${it.groupValues[1]}</strong>" }
        s = Regex("\\*(?=\\S)([^*]*?\\S)\\*").replace(s) { "<em>${it.groupValues[1]}</em>" }
        s = Regex("(?<![\\w])_(?=\\S)([^_]*?\\S)_(?![\\w])").replace(s) { "<em>${it.groupValues[1]}</em>" }
        s = Regex("~~(?=\\S)([\\s\\S]*?\\S)~~").replace(s) { "<s>${it.groupValues[1]}</s>" }
        // restore stashed pieces (they may nest: escapes inside link labels)
        val ph = Regex("$PH(\\d+)$PH")
        var guard = 0
        while (ph.containsMatchIn(s) && guard++ < 5) s = ph.replace(s) { stash[it.groupValues[1].toInt()] }
        return s
    }
}
