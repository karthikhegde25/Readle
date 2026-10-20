package com.readle.app.reader.reflow

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Plain text presented as a virtual book: the text is split into ~[PART_CHARS]-character parts at
 * paragraph boundaries and each part becomes a chapter, so the same WebView reflow engine
 * (themes, fonts, progress, TTS, bookmarks, search) works for TXT files.
 */
class TxtBook private constructor(
    private val parts: List<String>,
    private val partTitles: List<String>,
    override val meta: BookMeta
) : ReflowBook {

    override val spine: List<SpineItem> = parts.mapIndexed { i, html ->
        SpineItem("txt/part$i.html", html.length.toLong())
    }

    override val toc: List<TocEntry> = partTitles.mapIndexed { i, t -> TocEntry(t, i, null, 0) }

    override fun open(path: String): ResourceData? {
        val m = Regex("^txt/part(\\d+)\\.html$").matchEntire(PathUtil.normalize(path)) ?: return null
        val idx = m.groupValues[1].toIntOrNull() ?: return null
        val html = parts.getOrNull(idx) ?: return null
        return ResourceData("text/html", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    }

    override fun chapterText(index: Int): String = parts.getOrNull(index)?.let { HtmlText.toPlain(it) }.orEmpty()

    override fun close() = Unit

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val PART_CHARS = 40_000

        fun open(file: File, title: String?): TxtBook {
            if (file.length() > MAX_BYTES) {
                throw BookOpenException(
                    BookOpenException.Kind.TOO_LARGE,
                    "Text files larger than ${MAX_BYTES / (1024 * 1024)} MB are not supported"
                )
            }
            val text = decode(file.readBytes())
            return fromText(text, title)
        }

        fun fromText(text: String, title: String?): TxtBook {
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            val blocks = normalized.split(Regex("\n[ \t]*\n+")).map { it.trim('\n') }.filter { it.isNotBlank() }
            val partsText = ArrayList<StringBuilder>()
            var current = StringBuilder()
            for (b in blocks) {
                if (current.isNotEmpty() && current.length + b.length > PART_CHARS) {
                    partsText.add(current)
                    current = StringBuilder()
                }
                current.append(b).append("\n\n")
            }
            if (current.isNotEmpty() || partsText.isEmpty()) partsText.add(current)

            val htmlParts = partsText.map { toHtml(it.toString()) }
            val titles = partsText.mapIndexed { i, sb ->
                val first = sb.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                val preview = if (first.length > 48) first.take(48).trimEnd() + "…" else first
                if (partsText.size == 1) (title ?: "Text") else "Part ${i + 1}" + if (preview.isNotEmpty()) " – $preview" else ""
            }
            return TxtBook(htmlParts, titles, BookMeta(title, null, null, null, null))
        }

        private fun toHtml(text: String): String {
            val body = StringBuilder()
            for (block in text.split(Regex("\n[ \t]*\n+")).filter { it.isNotBlank() }) {
                val lines = block.trim('\n').lines()
                val avg = lines.sumOf { it.length } / lines.size.coerceAtLeast(1)
                // Hard-wrapped prose (long average lines) is re-joined; short lines (verse, lists) keep their breaks.
                val joined = if (lines.size > 1 && avg > 40) {
                    lines.joinToString(" ") { it.trim() }.let { HtmlText.escape(it) }
                } else {
                    lines.joinToString("<br>") { HtmlText.escape(it.trimEnd()) }
                }
                body.append("<p>").append(joined).append("</p>\n")
            }
            return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body>$body</body></html>"
        }

        /** BOM aware; tries strict UTF-8 first and falls back to windows-1252 for legacy files. */
        fun decode(bytes: ByteArray): String {
            if (bytes.size >= 2) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
                    .toString()
            } catch (e: CharacterCodingException) {
                String(bytes, start, bytes.size - start, Charset.forName("windows-1252"))
            }
        }
    }
}
