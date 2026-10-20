package com.readle.app.reader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Says a single word with Android's installed text-to-speech engine. WordNet carries no recorded or
 * phonetic pronunciations, so this is a synthesized voice, and [available] is false (the Speak button is
 * hidden) unless the device has an English voice ready.
 */
class WordSpeaker(context: Context, private val onReady: (Boolean) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ok = false

    val available: Boolean get() = ok

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            ok = status == TextToSpeech.SUCCESS && engine != null && runCatching {
                val r = engine.setLanguage(Locale.ENGLISH)
                r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }.getOrDefault(false)
            onReady(ok)
        }
    }

    fun speak(word: String) {
        if (!ok) return
        tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "readle-word")
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        ok = false
    }
}
