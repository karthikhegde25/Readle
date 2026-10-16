package com.readle.app.dict

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** One meaning of a word: part of speech, the synonyms in its synset, a definition and examples. */
data class Sense(
    val pos: Char,
    val synonyms: List<String>,
    val definition: String,
    val examples: List<String>
) {
    val posLabel: String get() = posName(pos)
}

/** All senses found for one dictionary headword. [form] is the headword; [lookedUp] is what the reader selected. */
data class Entry(val lemma: String, val senses: List<Sense>)

fun posName(pos: Char): String = when (pos) {
    'n' -> "noun"; 'v' -> "verb"; 'a' -> "adjective"; 'r' -> "adverb"; else -> "word"
}

/**
 * Read-only view over Readle's compact WordNet file (see tools/build_wordnet.py). Pure JVM so it can be
 * unit-tested; the Android side only supplies the bytes. Lookups are binary searches over a sorted lemma table.
 */
class WordNet(private val buf: ByteBuffer, exceptions: String) {
    private val count: Int
    private val senseBase: Int
    private val exc: Map<Char, Map<String, List<String>>>

    init {
        require(buf.capacity() > 16 && buf.getInt(0) == 0x524C574E) { "Not a Readle dictionary file" }
        require(buf.getInt(4) == 1) { "Unsupported dictionary version" }
        count = buf.getInt(8)
        senseBase = buf.getInt(12)
        val m = HashMap<Char, HashMap<String, MutableList<String>>>()
        exceptions.lineSequence().forEach { line ->
            val t = line.split('\t')
            if (t.size == 3 && t[0].isNotEmpty()) m.getOrPut(t[0][0]) { HashMap() }.getOrPut(t[1]) { ArrayList(1) }.add(t[2])
        }
        exc = m
    }

    private fun keyAt(i: Int): ByteArray {
        val o = buf.getInt(16 + 4 * i)
        val len = buf.get(o).toInt() and 0xFF
        return ByteArray(len).also { for (j in 0 until len) it[j] = buf.get(o + 1 + j) }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** Senses for an exact lemma (lower case, spaces as '_' or ' '), in WordNet's frequency order. */
    fun senses(lemma: String): List<Sense> {
        val key = lemma.lowercase().replace(' ', '_').toByteArray(StandardCharsets.UTF_8)
        if (key.isEmpty() || key.size > 255) return emptyList()
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(keyAt(mid), key)
            if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return readSenses(buf.getInt(16 + 4 * mid), key.size)
        }
        return emptyList()
    }

    private fun readSenses(recordOffset: Int, keyLen: Int): List<Sense> {
        var p = recordOffset + 1 + keyLen
        val n = buf.get(p).toInt() and 0xFF
        p += 1
        return (0 until n).map { readSense(senseBase + buf.getInt(p + 4 * it)) }
    }

    private fun readSense(o: Int): Sense {
        val pos = (buf.get(o).toInt() and 0xFF).toChar()
        val len = buf.getShort(o + 1).toInt() and 0xFFFF
        val bytes = ByteArray(len).also { for (j in 0 until len) it[j] = buf.get(o + 3 + j) }
        val f = String(bytes, StandardCharsets.UTF_8).split('\t')
        return Sense(pos, f[0].split('|').filter { it.isNotEmpty() }, f.getOrElse(1) { "" }, f.drop(2))
    }

    /**
     * Looks up what the reader selected. Tries the exact text first, then base forms (irregular forms from
     * WordNet's exception lists, then suffix-detachment rules). Each headword appears once.
     */
    fun define(selected: String): List<Entry> {
        val word = normalise(selected)
        if (word.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Entry>()
        fun add(lemma: String, only: Char? = null) {
            if (out.containsKey(lemma)) return
            val s = senses(lemma).let { all -> if (only == null) all else all.filter { it.pos == only } }
            if (s.isNotEmpty()) out[lemma] = Entry(lemma.replace('_', ' '), s)
        }
        add(word)
        for ((pos, base) in Morphology.baseForms(word, exc)) add(base, pos)
        return out.values.toList()
    }

    companion object {
        /** Lower-cases, straightens apostrophes, trims punctuation and a trailing possessive. */
        fun normalise(raw: String): String {
            var s = raw.trim().replace('’', '\'').replace('‘', '\'').lowercase()
            s = s.trim { !it.isLetterOrDigit() }
            if (s.endsWith("'s")) s = s.dropLast(2)
            return s.replace(Regex("\\s+"), "_")
        }
    }
}

/** WordNet "morphy": irregular forms from the exception lists, then regular suffix detachment. */
object Morphology {
    private val rules: Map<Char, List<Pair<String, String>>> = mapOf(
        'n' to listOf("s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y"),
        'v' to listOf("s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to ""),
        'a' to listOf("er" to "", "est" to "", "er" to "e", "est" to "e")
    )

    /** Candidate (part of speech, base form) pairs for [word], irregulars first, without duplicates. */
    fun baseForms(word: String, exc: Map<Char, Map<String, List<String>>>): List<Pair<Char, String>> {
        val out = LinkedHashSet<Pair<Char, String>>()
        for (pos in listOf('n', 'v', 'a', 'r')) exc[pos]?.get(word)?.forEach { out.add(pos to it) }
        for ((pos, rs) in rules) for ((suffix, repl) in rs) {
            if (word.length > suffix.length && word.endsWith(suffix)) {
                val stem = word.dropLast(suffix.length) + repl
                if (stem.length < 2) continue
                out.add(pos to stem)
                // "running" -> "runn" -> "run", "stopped" -> "stopp" -> "stop"
                if (repl.isEmpty() && stem.length > 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
                    out.add(pos to stem.dropLast(1))
                }
            }
        }
        out.removeAll { it.second == word }
        return out.toList()
    }
}
