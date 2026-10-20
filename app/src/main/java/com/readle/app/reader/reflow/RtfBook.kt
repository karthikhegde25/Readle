package com.readle.app.reader.reflow

import java.io.File

/**
 * Rich Text Format (.rtf), text only: paragraphs, tabs, special characters, unicode escapes, and
 * bold / italic runs. Pictures, tables, headers / footers and page layout are dropped.
 */
object RtfBook {
    const val MAX_BYTES = 32L * 1024 * 1024
    private val SKIP_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
        "footerl", "footerr", "footnote", "fldinst", "themedata", "colorschememapping", "latentstyles",
        "datastore", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "private", "shpinst", "bin"
    )

    fun open(file: File, fallbackTitle: String?): MemoryBook = parse(file.readBoundedBytes(MAX_BYTES), fallbackTitle)

    fun parse(bytes: ByteArray, fallbackTitle: String?): MemoryBook {
        val head = String(bytes, 0, minOf(bytes.size, 16), Charsets.ISO_8859_1)
        if (!head.startsWith("{\\rtf")) throw BookOpenException(BookOpenException.Kind.CORRUPT, "Not an RTF document")
        val src = String(bytes, Charsets.ISO_8859_1)
        val html = StringBuilder()
        val para = StringBuilder()

        class State(var skip: Boolean, var bold: Boolean, var italic: Boolean, var uc: Int)
        val stack = ArrayList<State>()
        var st = State(false, false, false, 1)
        var pendingSkip = 0
        var openB = false
        var openI = false

        fun styleTo(b: Boolean, i: Boolean) {
            if (openI && !i) { para.append("</em>"); openI = false }
            if (openB && !b) { para.append("</strong>"); openB = false }
            if (b && !openB) { para.append("<strong>"); openB = true }
            if (i && !openI) { para.append("<em>"); openI = true }
        }
        fun text(s: String) {
            if (st.skip || s.isEmpty()) return
            styleTo(st.bold, st.italic)
            para.append(HtmlText.escape(s))
        }
        fun endParagraph() {
            styleTo(false, false)
            val p = para.toString()
            para.setLength(0)
            if (HtmlText.toPlain(p).isNotBlank()) html.append("<p>").append(p).append("</p>\n")
        }

        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when (c) {
                '{' -> { stack.add(State(st.skip, st.bold, st.italic, st.uc)); i++ }
                '}' -> { if (stack.isNotEmpty()) st = stack.removeAt(stack.lastIndex); i++ }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val d = src[i]
                    when {
                        d == '\\' || d == '{' || d == '}' -> { text(d.toString()); i++ }
                        d == '\'' -> {
                            val hex = src.substring(minOf(i + 1, n), minOf(i + 3, n)).toIntOrNull(16)
                            if (hex != null) {
                                if (pendingSkip > 0) pendingSkip-- else text(String(byteArrayOf(hex.toByte()), charset("windows-1252")))
                            }
                            i += 3
                        }
                        d == '*' -> { st.skip = true; i++ }
                        d == '~' -> { text(" "); i++ }
                        d == '-' || d == '_' -> { text(if (d == '_') "‑" else ""); i++ }
                        d == '\n' || d == '\r' -> { if (!st.skip) endParagraph(); i++ }
                        d.isLetter() -> {
                            var j = i
                            while (j < n && src[j].isLetter()) j++
                            val word = src.substring(i, j)
                            var k = j
                            if (k < n && (src[k] == '-' || src[k].isDigit())) {
                                k++
                                while (k < n && src[k].isDigit()) k++
                            }
                            val param = src.substring(j, k).toIntOrNull()
                            // one optional delimiter space belongs to the control word
                            i = if (k < n && src[k] == ' ') k + 1 else k
                            when (word) {
                                "par", "sect", "page" -> if (!st.skip) endParagraph()
                                "line" -> if (!st.skip) { styleTo(st.bold, st.italic); para.append("<br>") }
                                "tab" -> text(" ")
                                "emdash" -> text("—")
                                "endash" -> text("–")
                                "lquote" -> text("‘")
                                "rquote" -> text("’")
                                "ldblquote" -> text("“")
                                "rdblquote" -> text("”")
                                "bullet" -> text("•")
                                "b" -> st.bold = param != 0
                                "i" -> st.italic = param != 0
                                "pard", "plain" -> { st.bold = false; st.italic = false }
                                "uc" -> st.uc = param ?: 1
                                "u" -> {
                                    if (param != null) {
                                        val cp = if (param < 0) param + 65536 else param
                                        text(String(Character.toChars(cp)))
                                        pendingSkip = st.uc
                                    }
                                }
                                else -> if (word in SKIP_DESTINATIONS) st.skip = true
                            }
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (pendingSkip > 0) { pendingSkip--; i++ }
                    else {
                        var j = i
                        while (j < n && src[j] != '\\' && src[j] != '{' && src[j] != '}' && src[j] != '\r' && src[j] != '\n') j++
                        // Plain bytes are windows-1252.
                        text(String(src.substring(i, j).toByteArray(Charsets.ISO_8859_1), charset("windows-1252")))
                        i = j
                    }
                }
            }
        }
        endParagraph()
        if (html.isEmpty()) throw BookOpenException(BookOpenException.Kind.CORRUPT, "The RTF document has no readable text")
        val chapters = MemoryBook.splitAtBlocks(html.toString()).map { MemoryChapter(null, it) }
        return MemoryBook(BookMeta(fallbackTitle, null, null, null, null), chapters)
    }
}
