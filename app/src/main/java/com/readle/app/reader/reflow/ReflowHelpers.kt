package com.readle.app.reader.reflow

import com.readle.app.data.db.AnnotationEntity
import com.readle.app.domain.HighlightColors
import com.readle.app.domain.TextAnchor

/** Serialises the highlights of one chapter for the page script. */
object HighlightJson {
    fun build(annotations: List<AnnotationEntity>, spine: Int): String {
        val parts = annotations.mapNotNull { a ->
            val anchor = TextAnchor.decode(a.startLocation, a.endLocation) ?: return@mapNotNull null
            if (anchor.spine != spine || anchor.end <= anchor.start) return@mapNotNull null
            // Quotes are bookmarks of passages; they are shown as highlights too so they can be found again.
            "{\"id\":${a.id},\"start\":${anchor.start},\"end\":${anchor.end}," +
                "\"color\":\"${HighlightColors.css(a.color)}\",\"note\":${!a.noteText.isNullOrBlank()}}"
        }
        return parts.joinToString(",", "[", "]")
    }
}

/** One match inside a chapter: [nth] is the occurrence index the page script uses to find it again. */
data class SearchHit(val spine: Int, val nth: Int, val snippet: String)

/**
 * Text search that mirrors the page script exactly: both lower-case the text, collapse whitespace runs to a
 * single space and count non-overlapping occurrences, so "the 3rd match here" is "the 3rd match there".
 */
object BookSearch {
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var prevSpace = true
        for (c in s) {
            if (c.isWhitespace()) {
                if (!prevSpace) {
                    sb.append(' ')
                    prevSpace = true
                }
            } else {
                val lc = c.lowercaseChar()
                sb.append(lc)
                prevSpace = false
            }
        }
        return sb.toString()
    }

    fun findInChapter(spine: Int, chapterText: String, query: String, maxHits: Int = Int.MAX_VALUE, snippetRadius: Int = 40): List<SearchHit> {
        val q = normalize(query).trim()
        if (q.isEmpty()) return emptyList()
        val text = normalize(chapterText)
        val hits = ArrayList<SearchHit>()
        var from = 0
        var nth = 0
        while (hits.size < maxHits) {
            val idx = text.indexOf(q, from)
            if (idx < 0) break
            val s = (idx - snippetRadius).coerceAtLeast(0)
            val e = (idx + q.length + snippetRadius).coerceAtMost(text.length)
            val prefix = if (s > 0) "…" else ""
            val suffix = if (e < text.length) "…" else ""
            hits.add(SearchHit(spine, nth, prefix + text.substring(s, e).trim() + suffix))
            nth++
            from = idx + q.length
        }
        return hits
    }
}

/** A speakable slice of a chapter and where it starts in the chapter text. */
data class SpeechChunk(val offset: Int, val text: String)

object TtsChunker {
    /**
     * Splits [text] (starting at [from]) into chunks of at most [maxLen] characters, preferring paragraph,
     * then sentence, then word boundaries, so no chunk exceeds the speech engine's input limit.
     */
    fun split(text: String, from: Int = 0, maxLen: Int = 3000): List<SpeechChunk> {
        require(maxLen >= 50)
        val out = ArrayList<SpeechChunk>()
        var pos = from.coerceIn(0, text.length)
        while (pos < text.length) {
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos >= text.length) break
            var end = (pos + maxLen).coerceAtMost(text.length)
            if (end < text.length) {
                val window = text.substring(pos, end)
                val cut = lastBreak(window)
                end = if (cut > 0) pos + cut else end
            }
            val chunk = text.substring(pos, end).trimEnd()
            if (chunk.isNotEmpty()) out.add(SpeechChunk(pos, chunk))
            pos = end
        }
        return out
    }

    private fun lastBreak(window: String): Int {
        val min = window.length / 3
        val para = window.lastIndexOf("\n")
        if (para >= min) return para + 1
        var sentence = -1
        for (i in window.length - 1 downTo min) {
            val c = window[i]
            if ((c == '.' || c == '!' || c == '?' || c == '。') && (i + 1 == window.length || window[i + 1].isWhitespace() || window[i + 1] == '"' || window[i + 1] == '”')) {
                sentence = i + 1
                break
            }
        }
        if (sentence >= min) return sentence
        val space = window.lastIndexOf(' ')
        return if (space >= min) space + 1 else window.length
    }

    /** Moves [pos] back to the start of its paragraph so speech restarts at a natural place. */
    fun paragraphStart(text: String, pos: Int): Int {
        val p = pos.coerceIn(0, text.length)
        val nl = text.lastIndexOf('\n', (p - 1).coerceAtLeast(0))
        return if (nl < 0) 0 else nl + 1
    }
}
