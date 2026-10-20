package com.readle.app.reader.tts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import com.readle.app.data.prefs.SettingsStore
import com.readle.app.data.repo.LibraryRepository
import com.readle.app.data.repo.ReaderRepository
import com.readle.app.domain.DocFormat
import com.readle.app.domain.Locator
import com.readle.app.files.FileCache
import com.readle.app.reader.reflow.BookOpener
import com.readle.app.reader.reflow.ReflowBook
import com.readle.app.reader.reflow.ReflowMath
import com.readle.app.reader.reflow.TtsChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is being read aloud right now. [spine] is the chapter that is speaking (or about to). */
data class SpeechSession(
    val docId: Long,
    val title: String,
    val spine: Int,
    val chapterTitle: String?
)

/** A reader screen that can show the spoken text and turn chapters itself while it is in front of the user. */
interface SpeechUi {
    val docId: Long
    fun onPosition(start: Int, end: Int)
    fun onChapterFinished()

    /** The sleep timer ran out: reading has stopped, and the reader should save its place and close. */
    fun onSleepTimerEnded()
}

/**
 * Owns the one [TtsController] of the app so speech outlives the reader screen.
 *
 *  - While a reader is in front, it turns chapters itself (it has the page, so it can highlight words).
 *  - When none is (screen closed, app in the background, reader left), the next chapters are read straight
 *    from the book file here, with no page involved, and the reading position is saved as it advances.
 *  - A foreground service ([ReadAloudService]) with a notification keeps the process alive and lets the user
 *    pause or stop from the notification bar. It is only started when the user has allowed background reading.
 */
class ReadAloudManager(
    private val context: Context,
    private val libraryRepo: LibraryRepository,
    private val readerRepo: ReaderRepository,
    private val fileCache: FileCache,
    private val settings: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val controller = TtsController(context)

    private val _session = MutableStateFlow<SpeechSession?>(null)
    val session: StateFlow<SpeechSession?> = _session.asStateFlow()

    /** Time left on the sleep timer in milliseconds, or null when no timer is running. */
    private val _sleepRemaining = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemaining.asStateFlow()

    @Volatile private var sleepMinutes = 0
    private var sleepJob: Job? = null
    private var headlessSpine = -1
    private var headlessLength = 0

    @Volatile private var activeUi: SpeechUi? = null
    private var backgroundAllowed = true
    private var serviceRunning = false
    private var book: ReflowBook? = null
    private var bookDocId = -1L
    private var headlessJob: Job? = null

    init {
        controller.onPosition = { s, e -> activeUi?.let { ui -> if (ui.docId == _session.value?.docId) ui.onPosition(s, e) } }
        controller.onChapterFinished = { scope.launch { chapterFinished() } }
        controller.onEngineMissing = {
            scope.launch { settings.update { it.copy(ttsEngine = null) } }
        }
        scope.launch {
            settings.settings.map { it.ttsVoice }.distinctUntilChanged().collect { controller.voiceName = it }
        }
        scope.launch {
            settings.settings.map { it.ttsEngine }.distinctUntilChanged().collect {
                controller.engineName = it
                controller.invalidateEngine()
            }
        }
        scope.launch {
            settings.settings.map { it.sleepTimerMinutes }.distinctUntilChanged().collect { sleepMinutes = it }
        }
        scope.launch {
            controller.state.collect { st ->
                // An engine that fails mid-book ends the session so the notification doesn't linger.
                if (st is TtsState.Unavailable && _session.value != null) end()
            }
        }
    }

    // ------------------------------------------------------------------ session

    /** Called by a reader when it starts speaking. [background] says whether the user allows reading after leaving. */
    fun begin(docId: Long, title: String, spine: Int, chapterTitle: String?, background: Boolean) {
        backgroundAllowed = background
        // Only a brand-new reading session starts the timer; chapter turns and restarts keep the running one.
        if (_session.value == null) startSleepTimer()
        _session.value = SpeechSession(docId, title, spine, chapterTitle)
        if (background) startService()
    }

    /** Sets the timer length used by the next reading session immediately (the settings flow catches up later). */
    fun setSleepMinutes(minutes: Int) {
        sleepMinutes = minutes.coerceIn(0, 600)
    }

    /** Records which chapter is current without touching the audio. */
    fun setSpine(spine: Int, chapterTitle: String?) {
        _session.value = _session.value?.copy(spine = spine, chapterTitle = chapterTitle)
    }

    /** Stops speaking and ends the session (and with it the notification). */
    fun end() {
        headlessJob?.cancel()
        headlessJob = null
        cancelSleepTimer()
        controller.stop()
        _session.value = null
        headlessSpine = -1
        closeBook()
    }

    // ------------------------------------------------------------------ sleep timer

    private fun startSleepTimer() {
        cancelSleepTimer()
        val minutes = sleepMinutes
        if (minutes <= 0) return
        val endAt = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
        _sleepRemaining.value = minutes * 60_000L
        sleepJob = scope.launch {
            while (true) {
                val left = endAt - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) break
                _sleepRemaining.value = left
                delay(500)
            }
            onSleepTimerExpired()
        }
    }

    private fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemaining.value = null
    }

    /** Stops reading, saves the place that was reached and (if a reader is open) asks it to close. */
    private suspend fun onSleepTimerExpired() {
        val s = _session.value
        val ui = activeUi
        if (s != null && (ui == null || ui.docId != s.docId)) {
            // No reader is open, so this class saves the place itself: the spoken position inside the current section.
            val b = book
            if (b != null && headlessSpine == s.spine && headlessLength > 0) {
                val fraction = (controller.position.coerceAtLeast(0).toFloat() / headlessLength).coerceIn(0f, 1f)
                saveProgress(s.docId, b, s.spine, fraction)
            }
        }
        sleepJob = null
        end()
        if (ui != null && s != null && ui.docId == s.docId) ui.onSleepTimerEnded()
    }

    /** Pause or resume, used by the notification button. */
    fun togglePlayback() {
        when (controller.state.value) {
            TtsState.Speaking -> controller.pause()
            TtsState.Paused -> controller.resume()
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ reader screens

    fun activate(ui: SpeechUi) {
        activeUi = ui
    }

    /** The reader is no longer usable (stopped or closed). Reading carries on without it if background reading is on. */
    fun deactivate(ui: SpeechUi) {
        if (activeUi !== ui) return
        activeUi = null
        val s = _session.value ?: return
        if (!backgroundAllowed) {
            end()
            return
        }
        // If the reader was between chapters (it had asked for the next one but nothing is speaking yet), carry on here.
        if (controller.state.value is TtsState.Idle) startHeadless(s.spine)
    }

    // ------------------------------------------------------------------ headless chapter turning

    private suspend fun chapterFinished() {
        val s = _session.value ?: return
        val ui = activeUi
        if (ui != null && ui.docId == s.docId) {
            ui.onChapterFinished()
            return
        }
        if (!backgroundAllowed) {
            end()
            return
        }
        startHeadless(s.spine + 1)
    }

    private fun startHeadless(fromSpine: Int) {
        headlessJob?.cancel()
        headlessJob = scope.launch { speakChapterHeadless(fromSpine) }
    }

    /** Reads the first chapter at or after [fromSpine] that has text, straight from the book file. */
    private suspend fun speakChapterHeadless(fromSpine: Int) {
        val s = _session.value ?: return
        val opened = ensureBook(s.docId)
        if (opened == null) {
            end()
            return
        }
        var i = fromSpine.coerceAtLeast(0)
        while (i <= opened.spine.lastIndex) {
            val text = withContext(Dispatchers.IO) { try { opened.chapterText(i) } catch (e: Exception) { "" } }
            if (_session.value?.docId != s.docId) return // ended or replaced while extracting
            if (text.isNotBlank()) {
                _session.value = s.copy(spine = i, chapterTitle = opened.chapterTitle(i))
                saveProgress(s.docId, opened, i, 0f)
                headlessSpine = i
                headlessLength = text.length
                val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(100, 600)
                controller.speak(TtsChunker.split(text, 0, limit), opened.meta.language, controller.currentRate)
                return
            }
            i++
        }
        end() // reached the end of the book
    }

    private suspend fun ensureBook(docId: Long): ReflowBook? {
        if (book != null && bookDocId == docId) return book
        closeBook()
        return try {
            val doc = libraryRepo.getDocument(docId) ?: return null
            val format = DocFormat.fromStored(doc.format) ?: return null
            if (format == DocFormat.PDF) return null
            val file = fileCache.materialize(Uri.parse(doc.uri), doc.id, doc.sizeBytes, doc.lastModified, format.ext)
            val opened = withContext(Dispatchers.IO) { BookOpener.open(format, file, doc.title) }
            book = opened
            bookDocId = docId
            opened
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    private fun closeBook() {
        try { book?.close() } catch (_: Exception) { }
        book = null
        bookDocId = -1L
    }

    private suspend fun saveProgress(docId: Long, b: ReflowBook, spine: Int, fraction: Float) {
        try {
            val overall = ReflowMath.overallFraction(b.spine, spine, fraction)
            readerRepo.saveProgress(docId, Locator.Reflow(spine, fraction), overall, spine + 1, b.spine.size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ service

    private fun startService() {
        if (serviceRunning) return
        serviceRunning = true
        try {
            ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java))
        } catch (e: Exception) {
            // Starting a service can be refused (for example when the app isn't allowed to run in the background).
            // Speech then simply behaves as it did before: it continues while the reader is open.
            serviceRunning = false
            backgroundAllowed = false
        }
    }

    /** Called by the service when it has shut down. */
    fun onServiceStopped() {
        serviceRunning = false
    }
}
