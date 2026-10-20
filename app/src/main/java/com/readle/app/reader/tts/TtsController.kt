package com.readle.app.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.readle.app.reader.reflow.SpeechChunk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface TtsState {
    data object Idle : TtsState
    data object Starting : TtsState
    data object Speaking : TtsState
    data object Paused : TtsState

    /** [needsSetup] means the user can fix it in Android's text-to-speech settings. */
    data class Unavailable(val message: String, val needsSetup: Boolean) : TtsState
}

/**
 * Speaks chapter text through Android's installed TTS engine. Voices and languages depend entirely on
 * the engines on the device. One controller lives for the whole app process (see [ReadAloudManager]),
 * so speech can continue after the reader is closed while a foreground service keeps the process alive.
 *
 * "Pause" is implemented as stop-and-remember (TextToSpeech has no true pause), resuming from the last
 * spoken word.
 */
class TtsController(context: Context) {
    /** Set by the owner; called on a TTS binder thread. */
    @Volatile var onPosition: (start: Int, end: Int) -> Unit = { _, _ -> }
    @Volatile var onChapterFinished: () -> Unit = { }

    /** Package of the speech engine the user picked, or null for the phone's default. Takes effect after [invalidateEngine]. */
    @Volatile var engineName: String? = null

    /** Called when the picked engine isn't installed any more and the default engine was used instead. */
    @Volatile var onEngineMissing: () -> Unit = { }

    /** Name of the voice the user picked, or null for automatic. Applied when it matches the book's language. */
    @Volatile var voiceName: String? = null

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingStart: (() -> Unit)? = null
    private var focusRequest: AudioFocusRequest? = null

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var chunks: List<SpeechChunk> = emptyList()
    private var language: Locale = Locale.getDefault()
    private var rate = 1.0f
    private var lastOffset = 0

    /** Offset (in the speech text) of the word spoken last; -1 before anything was spoken. */
    val position: Int get() = if (chunks.isEmpty()) -1 else lastOffset
    val currentRate: Float get() = rate
    private var generation = 0

    /** Starts speaking [newChunks] (all from one chapter). Safe to call repeatedly. */
    fun speak(newChunks: List<SpeechChunk>, bookLanguage: String?, speechRate: Float) {
        if (newChunks.isEmpty()) {
            _state.value = TtsState.Idle
            return
        }
        chunks = newChunks
        rate = speechRate
        language = bookLanguage?.let { Locale.forLanguageTag(it) }?.takeIf { it.language.isNotEmpty() } ?: Locale.getDefault()
        lastOffset = newChunks.first().offset
        _state.value = TtsState.Starting
        ensureEngine { startSpeaking(newChunks) }
    }

    fun pause() {
        if (_state.value != TtsState.Speaking) return
        generation++
        tts?.stop()
        abandonFocus()
        _state.value = TtsState.Paused
    }

    /** Resumes from the last spoken position. */
    fun resume() {
        if (_state.value != TtsState.Paused) return
        val rest = remaining(lastOffset)
        if (rest.isEmpty()) {
            stop()
            return
        }
        _state.value = TtsState.Starting
        ensureEngine { startSpeaking(rest) }
    }

    fun stop() {
        generation++
        tts?.stop()
        abandonFocus()
        pendingStart = null
        if (_state.value !is TtsState.Unavailable) _state.value = TtsState.Idle
    }

    fun setRate(newRate: Float) {
        rate = newRate
        tts?.setSpeechRate(newRate)
        if (_state.value == TtsState.Speaking) {
            // Rate changes only apply to new utterances, so restart from the current word.
            generation++
            tts?.stop()
            val rest = remaining(lastOffset)
            if (rest.isNotEmpty()) startSpeaking(rest)
        }
    }

    /**
     * Drops the engine connection so the next speech starts a fresh one. Used after the engine or its voices
     * changed (a new voice was downloaded, another engine was chosen). Does nothing while speaking.
     */
    fun invalidateEngine() {
        val st = _state.value
        if (st == TtsState.Speaking || st == TtsState.Starting) return
        pendingStart = null
        try { tts?.stop() } catch (_: Exception) { }
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
        ready = false
    }

    fun release() {
        generation++
        pendingStart = null
        abandonFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        _state.value = TtsState.Idle
    }

    private fun remaining(fromOffset: Int): List<SpeechChunk> {
        val out = ArrayList<SpeechChunk>()
        for (c in chunks) {
            val end = c.offset + c.text.length
            if (end <= fromOffset) continue
            if (c.offset >= fromOffset) out.add(c)
            else out.add(SpeechChunk(fromOffset, c.text.substring((fromOffset - c.offset).coerceIn(0, c.text.length))))
        }
        return out.filter { it.text.isNotBlank() }
    }

    private fun ensureEngine(then: () -> Unit) {
        if (ready) {
            then()
            return
        }
        pendingStart = then
        if (tts != null) return // initialisation already in flight
        val requested = engineName
        tts = TextToSpeech(appContext, { status ->
            if (status != TextToSpeech.SUCCESS) {
                tts?.shutdown()
                tts = null
                if (requested != null && engineName == requested) {
                    // The chosen engine is gone (uninstalled or disabled): fall back to the phone's default engine.
                    engineName = null
                    onEngineMissing()
                    val retry = pendingStart
                    pendingStart = null
                    if (retry != null) ensureEngine(retry)
                    return@TextToSpeech
                }
                _state.value = TtsState.Unavailable(
                    "No text-to-speech engine is available on this device. Install one in Android settings.",
                    needsSetup = true
                )
                pendingStart = null
                return@TextToSpeech
            }
            ready = true
            tts?.setOnUtteranceProgressListener(listener)
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val start = pendingStart
            pendingStart = null
            start?.invoke()
        }, requested)
    }

    private fun startSpeaking(toSpeak: List<SpeechChunk>) {
        val engine = tts ?: return
        // A chosen voice is only used for books in that voice's language, so a Hindi book is never read by an English voice.
        var voiceApplied = false
        val wanted = voiceName
        if (wanted != null) {
            val voice = try { engine.voices?.firstOrNull { it.name == wanted } } catch (e: Exception) { null }
            if (voice != null && voice.locale.language == language.language) {
                voiceApplied = try { engine.setVoice(voice) == TextToSpeech.SUCCESS } catch (e: Exception) { false }
            }
        }
        val result = if (voiceApplied) TextToSpeech.SUCCESS else engine.setLanguage(language)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            val fallback = engine.setLanguage(Locale.getDefault())
            if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                _state.value = TtsState.Unavailable(
                    "Your text-to-speech engine has no voice for ${language.displayLanguage.ifEmpty { "this language" }}. " +
                        "Install voice data in Android settings.",
                    needsSetup = true
                )
                return
            }
        }
        if (!requestFocus()) {
            _state.value = TtsState.Unavailable("Another app is using the audio. Try again in a moment.", needsSetup = false)
            return
        }
        engine.setSpeechRate(rate)
        val gen = ++generation
        engine.stop()
        toSpeak.forEachIndexed { i, chunk ->
            val id = "g${gen}_${chunk.offset}_${if (i == toSpeak.lastIndex) "last" else "mid"}"
            engine.speak(chunk.text, TextToSpeech.QUEUE_ADD, null, id)
        }
        _state.value = TtsState.Speaking
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val parsed = parse(utteranceId) ?: return
            if (parsed.first != generation) return
            lastOffset = parsed.second
            // Mark just the first word of the chunk until the engine reports individual words.
            val text = chunks.firstOrNull { it.offset == parsed.second }?.text.orEmpty()
            val firstWord = text.indexOfFirst { it.isWhitespace() }.let { if (it <= 0) text.length.coerceAtLeast(1) else it }
            onPosition(parsed.second, parsed.second + firstWord)
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val parsed = parse(utteranceId) ?: return
            if (parsed.first != generation) return
            lastOffset = parsed.second + start
            onPosition(lastOffset, parsed.second + end)
        }

        override fun onDone(utteranceId: String?) {
            val parsed = parse(utteranceId) ?: return
            if (parsed.first != generation) return
            if (utteranceId?.endsWith("_last") == true) {
                abandonFocus()
                _state.value = TtsState.Idle
                onChapterFinished()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (parse(utteranceId)?.first != generation) return
            abandonFocus()
            _state.value = TtsState.Unavailable("Speech stopped because the text-to-speech engine reported an error.", needsSetup = true)
        }
    }

    private fun parse(id: String?): Pair<Int, Int>? {
        val p = id?.split('_') ?: return null
        if (p.size < 3 || !p[0].startsWith("g")) return null
        val gen = p[0].substring(1).toIntOrNull() ?: return null
        val off = p[1].toIntOrNull() ?: return null
        return gen to off
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Called on the main thread by the system; pausing keeps the position so the user can resume.
            pause()
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
