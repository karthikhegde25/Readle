package com.readle.app.reader.reflow

import java.io.File
import java.nio.charset.Charset

/**
 * MOBI / AZW / PRC reader (Mobipocket "MOBI 6", the format of most .mobi and many .azw files).
 *
 * Supported: uncompressed and PalmDOC (LZ77) text, UTF-8 and Windows-1252, embedded images, the
 * title/author/cover from the EXTH block, chapter breaks, internal links, and plain PalmDOC ("TEXtREAd") files.
 * Not supported, and reported plainly instead of showing garbage: DRM-protected files, HUFF/CDIC
 * compressed files, and KF8-only files (most .azw3 files made by recent Kindle tools).
 */
object MobiBook {
    const val MAX_BYTES = 96L * 1024 * 1024
    private const val MAX_TEXT_BYTES = 80 * 1024 * 1024

    fun open(file: File, fallbackTitle: String?): MemoryBook = parse(file.readBoundedBytes(MAX_BYTES), fallbackTitle)

    fun parse(data: ByteArray, fallbackTitle: String?): MemoryBook {
        try {
            return parseInternal(data, fallbackTitle)
        } catch (e: BookOpenException) {
            throw e
        } catch (e: Exception) {
            throw BookOpenException(BookOpenException.Kind.CORRUPT, "The MOBI file is damaged or malformed", e)
        }
    }

    private fun u16(d: ByteArray, o: Int): Int = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    private fun u32(d: ByteArray, o: Int): Long =
        (u16(d, o).toLong() shl 16) or u16(d, o + 2).toLong()

    private fun bad(msg: String): Nothing = throw BookOpenException(BookOpenException.Kind.CORRUPT, msg)

    private fun parseInternal(d: ByteArray, fallbackTitle: String?): MemoryBook {
        if (d.size < 100) bad("Not a MOBI file")
        val type = String(d, 60, 8, Charsets.ISO_8859_1)
        if (type != "BOOKMOBI" && type != "TEXtREAd") {
            if (type.startsWith("TPZ")) throw BookOpenException(BookOpenException.Kind.UNSUPPORTED, "Topaz (.tpz/.azw1) books are not supported")
            bad("Not a MOBI file")
        }
        val numRecords = u16(d, 76)
        if (numRecords < 2 || 78 + numRecords * 8 > d.size) bad("Invalid record table")
        val offsets = LongArray(numRecords + 1)
        for (i in 0 until numRecords) offsets[i] = u32(d, 78 + i * 8)
        offsets[numRecords] = d.size.toLong()
        for (i in 0 until numRecords) if (offsets[i] > offsets[i + 1] || offsets[i] > d.size) bad("Invalid record offsets")
        fun rec(i: Int): ByteArray = d.copyOfRange(offsets[i].toInt(), offsets[i + 1].toInt().coerceAtMost(d.size))

        val r0 = offsets[0].toInt()
        val compression = u16(d, r0)
        val textLength = u32(d, r0 + 4)
        val textRecords = u16(d, r0 + 8)
        val encryption = u16(d, r0 + 12)
        if (encryption != 0) throw BookOpenException(BookOpenException.Kind.DRM, "This book is protected by DRM and cannot be opened")
        if (compression != 1 && compression != 2) {
            val msg = if (compression == 17480) "This MOBI uses HUFF/CDIC compression, which Readle can't read yet. Convert it to EPUB (for example with Calibre)."
            else "Unknown MOBI compression type $compression"
            throw BookOpenException(BookOpenException.Kind.UNSUPPORTED, msg)
        }
        if (textLength > MAX_TEXT_BYTES) throw BookOpenException(BookOpenException.Kind.TOO_LARGE, "This book is too large")
        if (textRecords < 1 || textRecords >= numRecords) bad("Invalid text record count")

        var charset: Charset = Charset.forName("windows-1252")
        var extraFlags = 0
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var coverOffset = -1
        var firstImage = -1
        val hasMobiHeader = String(d, r0 + 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (hasMobiHeader) {
            val headerLen = u32(d, r0 + 20).toInt()
            val encoding = u32(d, r0 + 28).toInt()
            val version = u32(d, r0 + 36).toInt()
            if (encoding == 65001) charset = Charsets.UTF_8
            if (version >= 8 && headerLen >= 0xE4 && compression != 0) {
                // KF8 keeps its text in skeleton/fragment parts that need the index tables to reassemble.
                throw BookOpenException(
                    BookOpenException.Kind.UNSUPPORTED,
                    "This is a KF8 (AZW3) book, which Readle can't read yet. Convert it to EPUB (for example with Calibre)."
                )
            }
            if (headerLen >= 0xE4 && r0 + 0xF4 <= d.size) extraFlags = u16(d, r0 + 0xF2)
            val nameOff = u32(d, r0 + 0x54).toInt()
            val nameLen = u32(d, r0 + 0x58).toInt()
            if (nameOff > 0 && nameLen in 1..1024 && r0 + nameOff + nameLen <= d.size) {
                title = String(d, r0 + nameOff, nameLen, charset)
            }
            if (headerLen >= 0x70) firstImage = u32(d, r0 + 0x6C).toInt().let { if (it in 1 until numRecords) it else -1 }
            val exthFlags = if (r0 + 0x84 <= d.size) u32(d, r0 + 0x80) else 0L
            val exthAt = r0 + 16 + headerLen
            if ((exthFlags and 0x40L) != 0L && exthAt + 12 <= d.size && String(d, exthAt, 4, Charsets.ISO_8859_1) == "EXTH") {
                val count = u32(d, exthAt + 8).toInt().coerceIn(0, 2000)
                var p = exthAt + 12
                val authors = ArrayList<String>()
                for (k in 0 until count) {
                    if (p + 8 > d.size) break
                    val t = u32(d, p).toInt()
                    val len = u32(d, p + 4).toInt()
                    if (len < 8 || p + len > d.size) break
                    val payloadStart = p + 8
                    val payloadLen = len - 8
                    when (t) {
                        100 -> authors.add(String(d, payloadStart, payloadLen, charset).trim())
                        503 -> title = String(d, payloadStart, payloadLen, charset).trim().ifEmpty { title }
                        524 -> language = String(d, payloadStart, payloadLen, charset).trim()
                        201 -> if (payloadLen == 4) coverOffset = u32(d, payloadStart).toInt()
                        401, 402, 403 -> Unit
                    }
                    p += len
                }
                author = authors.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
            }
        }

        // ---- text ----
        val text = java.io.ByteArrayOutputStream(textLength.toInt().coerceIn(1024, MAX_TEXT_BYTES))
        for (i in 1..textRecords) {
            var rec = rec(i)
            val end = rec.size - trailingBytes(rec, extraFlags)
            if (end < 0) bad("Corrupt text record")
            rec = rec.copyOf(end)
            if (compression == 2) palmDecompress(rec, text) else text.write(rec)
            if (text.size() > MAX_TEXT_BYTES) throw BookOpenException(BookOpenException.Kind.TOO_LARGE, "This book is too large")
        }
        val raw = text.toByteArray()
        val resources = LinkedHashMap<String, LazyResource>()
        var coverPath: String? = null

        if (firstImage > 0) {
            for (i in firstImage until numRecords) {
                val mime = sniffImage(d, offsets[i].toInt(), offsets[i + 1].toInt().coerceAtMost(d.size)) ?: continue
                val n = i - firstImage + 1
                val idx = i
                resources[MemoryBook.resourcePath("img$n")] = LazyResource(mime) { rec(idx) }
            }
            if (coverOffset >= 0 && resources.containsKey(MemoryBook.resourcePath("img${coverOffset + 1}"))) {
                coverPath = MemoryBook.resourcePath("img${coverOffset + 1}")
            }
        }

        val chapters = if (!hasMobiHeader) plainChapters(String(raw, charset)) else htmlChapters(raw, charset, resources.keys)
        val finalTitle = (title ?: fallbackTitle)?.trim()?.ifEmpty { null }
        return MemoryBook(
            BookMeta(finalTitle, author, null, language, coverPath),
            chapters, resources
        )
    }

    private fun trailingBytes(rec: ByteArray, extraFlags: Int): Int {
        var num = 0
        var flags = extraFlags shr 1
        while (flags != 0) {
            if (flags and 1 != 0) {
                var pos = rec.size - num
                var bit = 0
                var value = 0
                while (pos > 0) {
                    val v = rec[pos - 1].toInt() and 0xFF
                    value = value or ((v and 0x7F) shl bit)
                    bit += 7
                    pos--
                    if (v and 0x80 != 0 || bit >= 28 || pos == 0) break
                }
                num += value
            }
            flags = flags shr 1
        }
        if (extraFlags and 1 != 0 && rec.size - num - 1 >= 0) {
            num += (rec[rec.size - num - 1].toInt() and 3) + 1
        }
        return num
    }

    /** PalmDOC LZ77 decompression. Stops quietly at damaged data instead of throwing. */
    fun palmDecompress(input: ByteArray, out: java.io.ByteArrayOutputStream) {
        val buf = ByteArray(input.size * 8 + 64)
        var o = 0
        var out2 = buf
        fun ensure(n: Int) {
            if (o + n > out2.size) out2 = out2.copyOf(maxOf(out2.size * 2, o + n))
        }
        var i = 0
        while (i < input.size) {
            val c = input[i++].toInt() and 0xFF
            when {
                c == 0 || c in 9..0x7F -> { ensure(1); out2[o++] = c.toByte() }
                c in 1..8 -> {
                    val n = minOf(c, input.size - i)
                    ensure(n)
                    System.arraycopy(input, i, out2, o, n)
                    o += n; i += n
                }
                c >= 0xC0 -> { ensure(2); out2[o++] = ' '.code.toByte(); out2[o++] = (c xor 0x80).toByte() }
                else -> {
                    if (i >= input.size) break
                    val pair = (c shl 8) or (input[i++].toInt() and 0xFF)
                    val dist = (pair shr 3) and 0x7FF
                    val len = (pair and 7) + 3
                    if (dist == 0 || dist > o) break
                    ensure(len)
                    for (k in 0 until len) { out2[o] = out2[o - dist]; o++ }
                }
            }
        }
        out.write(out2, 0, o)
    }

    private fun sniffImage(d: ByteArray, start: Int, end: Int): String? {
        if (end - start < 8) return null
        fun b(i: Int) = d[start + i].toInt() and 0xFF
        return when {
            b(0) == 0xFF && b(1) == 0xD8 -> "image/jpeg"
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "image/png"
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 -> "image/gif"
            b(0) == 0x42 && b(1) == 0x4D -> "image/bmp"
            else -> null
        }
    }

    private fun plainChapters(text: String): List<MemoryChapter> {
        val html = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n[ \t]*\n+"))
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>" + HtmlText.escape(it.trim()).replace("\n", "<br>") + "</p>" }
        return MemoryBook.splitAtBlocks(html).map { MemoryChapter(null, it) }
    }

    private val PAGEBREAK = Regex("<mbp:pagebreak[^>]*>", RegexOption.IGNORE_CASE)
    private val FILEPOS = Regex("filepos\\s*=\\s*[\"']?0*(\\d+)", RegexOption.IGNORE_CASE)
    private val ANCHOR_ID = Regex("<a id=\"fp(\\d+)\"></a>")
    private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val RECINDEX = Regex("(?<![a-z])recindex\\s*=\\s*[\"']?0*(\\d+)", RegexOption.IGNORE_CASE)
    private val A_FILEPOS = Regex("<a\\b([^>]*?)filepos\\s*=\\s*[\"']?0*(\\d+)[\"']?([^>]*)>", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("<h[1-3]\\b[^>]*>([\\s\\S]*?)</h[1-3]>", RegexOption.IGNORE_CASE)
    private val HEAD = Regex("<head\\b[\\s\\S]*?</head\\s*>", RegexOption.IGNORE_CASE)
    private val GUIDE = Regex("<guide\\b[\\s\\S]*?</guide\\s*>", RegexOption.IGNORE_CASE)
    private val WRAPPERS = Regex("</?(html|body|mbp:[a-z]+)\\b[^>]*>", RegexOption.IGNORE_CASE)

    private fun htmlChapters(raw: ByteArray, charset: Charset, imageKeys: Set<String>): List<MemoryChapter> {
        // Work on a 1:1 byte view so that "filepos" byte offsets stay valid.
        var s = String(raw, Charsets.ISO_8859_1)
        val targets = FILEPOS.findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 0 until s.length }.toSortedSet()
        if (targets.isNotEmpty()) {
            val sb = StringBuilder(s)
            for (off in targets.toList().sortedDescending()) {
                val at = if (s[off] == '<') off else s.indexOf('<', off).takeIf { it >= 0 } ?: continue
                sb.insert(at, "<a id=\"fp$off\"></a>")
            }
            s = sb.toString()
        }

        // Split at page breaks, merge tiny pieces, split huge ones at block boundaries.
        val pieces = ArrayList<String>()
        var last = 0
        for (m in PAGEBREAK.findAll(s)) {
            pieces.add(s.substring(last, m.range.first))
            last = m.range.last + 1
        }
        pieces.add(s.substring(last))
        val merged = ArrayList<String>()
        val cur = StringBuilder()
        for (p in pieces) {
            cur.append(p)
            if (cur.length >= MIN_CHAPTER) { merged.add(cur.toString()); cur.setLength(0) }
        }
        if (cur.isNotBlank() || merged.isEmpty()) {
            if (merged.isNotEmpty() && cur.length < MIN_CHAPTER) merged[merged.lastIndex] = merged.last() + cur else merged.add(cur.toString())
        }
        val parts = merged.flatMap { MemoryBook.splitAtBlocks(it) }.filter { it.isNotBlank() }.ifEmpty { listOf("<p></p>") }

        val chapterOfAnchor = HashMap<Int, Int>()
        parts.forEachIndexed { i, p -> ANCHOR_ID.findAll(p).forEach { chapterOfAnchor[it.groupValues[1].toInt()] = i } }

        return parts.map { part ->
            var h = part
            h = HEAD.replace(h, "")
            h = GUIDE.replace(h, "")
            h = WRAPPERS.replace(h, "")
            h = A_FILEPOS.replace(h) { m ->
                val target = m.groupValues[2].toIntOrNull()
                val ch = target?.let { t -> chapterOfAnchor[t] ?: chapterOfAnchor.entries.filter { it.key <= t }.maxByOrNull { it.key }?.value }
                if (target == null || ch == null) "<a${m.groupValues[1]}${m.groupValues[3]}>"
                else "<a${m.groupValues[1]}${m.groupValues[3]} href=\"c$ch.html#fp$target\">"
            }
            h = IMG_TAG.replace(h) { m ->
                val n = RECINDEX.find(m.value)?.groupValues?.get(1)
                if (n != null && MemoryBook.resourcePath("img$n") in imageKeys) "<img src=\"../r/img$n\" alt=\"\">" else ""
            }
            val decoded = String(h.toByteArray(Charsets.ISO_8859_1), charset)
            val title = HEADING.find(decoded)?.groupValues?.get(1)?.let { HtmlText.toPlain(it).replace('\n', ' ').trim() }?.takeIf { it.isNotEmpty() && it.length < 120 }
            MemoryChapter(title, decoded)
        }
    }

    private const val MIN_CHAPTER = 6000
}
