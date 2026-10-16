package com.readle.app.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class WordNetTest {
    private val dir: File? = listOf("src/main/assets/wordnet", "app/src/main/assets/wordnet", "/home/claude/Readle/app/src/main/assets/wordnet")
        .map { File(it) }.firstOrNull { File(it, "wordnet.bin").exists() }

    private val wn: WordNet by lazy {
        val d = dir!!
        val map = RandomAccessFile(File(d, "wordnet.bin"), "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        WordNet(map, File(d, "wordnet-exc.txt").readText())
    }

    @Test fun exactWordHasDefinitions() {
        assumeTrue(dir != null)
        val e = wn.define("dog").first()
        assertEquals("dog", e.lemma)
        assertEquals('n', e.senses.first().pos)
        assertTrue(e.senses.first().definition.contains("domestic"))
        assertTrue(e.senses.any { it.pos == 'v' })
    }

    @Test fun caseAndPunctuationAreIgnored() {
        assumeTrue(dir != null)
        assertEquals(wn.define("dog"), wn.define("  “Dog,” "))
        assertEquals(wn.define("dog"), wn.define("dog\u2019s"))
    }

    @Test fun regularInflectionsFindTheBase() {
        assumeTrue(dir != null)
        assertTrue(wn.define("boxes").any { it.lemma == "box" })
        assertTrue(wn.define("running").any { it.lemma == "run" && it.senses.any { s -> s.pos == 'v' } })
        assertTrue(wn.define("stopped").any { it.lemma == "stop" })
        assertTrue(wn.define("happier").any { it.lemma == "happy" })
        assertTrue(wn.define("studies").any { it.lemma == "study" })
    }

    @Test fun irregularFormsUseExceptionLists() {
        assumeTrue(dir != null)
        assertTrue(wn.define("mice").any { it.lemma == "mouse" })
        assertTrue(wn.define("went").any { it.lemma == "go" })
        assertTrue(wn.define("better").any { it.lemma == "good" || it.lemma == "well" })
    }

    @Test fun phrasesAndUnknownWords() {
        assumeTrue(dir != null)
        assertTrue(wn.define("ice cream").isNotEmpty())
        assertTrue(wn.define("qwzxv").isEmpty())
        assertTrue(wn.define("   ").isEmpty())
        assertTrue(wn.define("…").isEmpty())
    }

    @Test fun synonymsAndExamplesAreParsed() {
        assumeTrue(dir != null)
        val s = wn.senses("happy").first()
        assertEquals('a', s.pos)
        assertTrue(s.synonyms.isNotEmpty())
        assertFalse(s.definition.isBlank())
        assertTrue(wn.senses("run").any { it.examples.isNotEmpty() })
    }

    @Test fun morphologyHasNoSelfOrDuplicateCandidates() {
        val c = Morphology.baseForms("cats", mapOf('n' to mapOf("cats" to listOf("cat"))))
        assertEquals(c.size, c.toSet().size)
        assertTrue(c.none { it.second == "cats" })
        assertTrue(('n' to "cat") in c)
    }
}
