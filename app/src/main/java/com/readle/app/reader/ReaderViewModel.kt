package com.readle.app.reader

import android.app.Application
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.readle.app.ReadleApp
import com.readle.app.data.db.AnnotationEntity
import com.readle.app.data.db.BookmarkEntity
import com.readle.app.data.db.DocumentEntity
import com.readle.app.data.repo.FileOpResult
import com.readle.app.domain.AppSettings
import com.readle.app.domain.BookmarkRules
import com.readle.app.domain.DocFormat
import com.readle.app.domain.HighlightColors
import com.readle.app.domain.Locator
import com.readle.app.domain.TextAnchor
import com.readle.app.domain.TypographyMerge
import com.readle.app.domain.CustomFont
import com.readle.app.domain.CustomTheme
import com.readle.app.domain.TypographySettings
import com.readle.app.reader.pdf.CbzPageSource
import com.readle.app.reader.pdf.PageSource
import com.readle.app.reader.pdf.PdfDocumentHandle
import com.readle.app.reader.reflow.BookOpenException
import com.readle.app.reader.reflow.BookOpener
import com.readle.app.reader.reflow.BookSearch
import com.readle.app.reader.reflow.ChapterTarget
import com.readle.app.reader.reflow.EpubBook
import com.readle.app.reader.reflow.HighlightJson
import com.readle.app.reader.reflow.PageSpec
import com.readle.app.reader.reflow.ReaderCss
import com.readle.app.reader.reflow.ReaderPalette
import com.readle.app.reader.reflow.ReflowBook
import com.readle.app.reader.reflow.ReflowCommand
import com.readle.app.reader.reflow.ReflowListener
import com.readle.app.reader.reflow.ReflowMath
import com.readle.app.reader.reflow.SearchHit
import com.readle.app.reader.reflow.TocEntry
import com.readle.app.reader.reflow.TtsChunker
import com.readle.app.reader.reflow.TxtBook
import com.readle.app.reader.tts.SpeechUi
import com.readle.app.reader.tts.TtsController
import com.readle.app.reader.tts.TtsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.roundToInt

sealed interface ReaderContent {
    data object Loading : ReaderContent
    data class Failed(val failure: OpenFailure, val detail: String?) : ReaderContent
    /** Fixed pages shown by the page viewer: a PDF, or a comic (CBZ) archive. */
    data class Pdf(val handle: PageSource) : ReaderContent
    data class Reflow(val book: ReflowBook) : ReaderContent
}

data class SelectionInfo(val text: String, val start: Int, val end: Int, val spine: Int)

data class SearchState(
    val query: String = "",
    val running: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val truncated: Boolean = false,
    val completed: Boolean = false
)

data class ReaderUiState(
    val doc: DocumentEntity? = null,
    val content: ReaderContent = ReaderContent.Loading,
    val chromeVisible: Boolean = true,
    val spine: Int = 0,
    val spineCount: Int = 0,
    val page: Int = 0,
    val pageCount: Int = 0,
    /** Screen-sized pages in the current reflowable section (0 until the page reports them). */
    val sectionPage: Int = 0,
    val sectionPages: Int = 0,
    val overall: Float = 0f,
    val canGoBack: Boolean = false,
    val selection: SelectionInfo? = null,
    val editingAnnotationId: Long? = null,
    val typography: TypographySettings = TypographySettings(),
    /** The imported theme / font the typography currently points at (null when none or when it was deleted). */
    val customTheme: CustomTheme? = null,
    val customFont: CustomFont? = null,
    val perBook: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val search: SearchState = SearchState(),
    val tts: TtsState = TtsState.Idle,
    val externalLink: String? = null,
    val toc: List<TocEntry> = emptyList()
) {
    val isPdf: Boolean get() = content is ReaderContent.Pdf
    val isReflow: Boolean get() = content is ReaderContent.Reflow
    val palette: ReaderPalette
        get() = customTheme?.let { ReaderPalette.forCustom(it) } ?: ReaderPalette.of(typography.theme, settings.highContrast)
}

/**
 * Owns one open document: opens it, restores and saves the reading position, drives navigation (with a
 * back-stack), bookmarks, highlights, in-book search and text-to-speech. The UI only renders [state]
 * and forwards user actions; page events from the WebView arrive through [ReflowListener].
 */
class ReaderViewModel(
    app: Application,
    override val docId: Long,
    private val startAt: Locator?,
    private val startAnnotationId: Long?
) : AndroidViewModel(app), ReflowListener, SpeechUi {

    private val container = (app as ReadleApp).container
    private val repo = container.readerRepository
    private val libraryRepo = container.libraryRepository

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _pageSpec = MutableStateFlow<PageSpec?>(null)
    val pageSpec: StateFlow<PageSpec?> = _pageSpec.asStateFlow()

    private val commandChannel = Channel<ReflowCommand>(Channel.UNLIMITED)
    val commands: Flow<ReflowCommand> = commandChannel.receiveAsFlow()

    private val pdfGoToChannel = Channel<Int>(Channel.UNLIMITED)
    val pdfGoTo: Flow<Int> = pdfGoToChannel.receiveAsFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val bookmarks: StateFlow<List<BookmarkEntity>> = repo.observeBookmarks(docId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val annotations: StateFlow<List<AnnotationEntity>> = repo.observeAnnotations(docId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Position bookkeeping. Updated from JS/TTS threads, hence @Volatile.
    @Volatile private var curSpine = 0
    @Volatile private var curFraction = 0f
    @Volatile private var pageReady = false
    @Volatile private var positionKnown = false
    private var tokenCounter = 0L
    private val history = ArrayDeque<Locator>()
    private var pendingFind: Pair<String, Int>? = null
    private var searchJob: Job? = null
    private var currentCss = ""
    private val positionUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val finalIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // TTS
    private var ttsText = ""
    private var ttsSpine = -1
    private var ttsContinueOnReady = false
    private var ttsPending: TtsStart? = null

    /** Speech lives in an app-wide manager so it can carry on after this screen is gone. */
    private val speech = container.readAloud

    /** Time left on the read-aloud sleep timer (null when none is running). */
    val sleepRemaining: StateFlow<Long?> = speech.sleepRemainingMs

    private val _exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits when the reader should close itself (the sleep timer ended). */
    val exitRequests: SharedFlow<Unit> = _exitRequests.asSharedFlow()
    private val tts: TtsController get() = speech.controller

    init {
        viewModelScope.launch { observeSettings() }
        viewModelScope.launch { observeAnnotations() }
        viewModelScope.launch { observeTts() }
        viewModelScope.launch { observeProgressSaving() }
        viewModelScope.launch { open() }
    }

    // ------------------------------------------------------------------ opening

    private suspend fun open() {
        val doc = libraryRepo.getDocument(docId)
        if (doc == null) {
            fail(OpenFailure.MISSING, "That document is no longer in your library")
            return
        }
        _state.update { it.copy(doc = doc, content = ReaderContent.Loading) }
        try {
            when (DocFormat.fromStored(doc.format)) {
                DocFormat.PDF -> openPdf(doc)
                DocFormat.CBZ -> openComic(doc)
                null -> fail(OpenFailure.UNSUPPORTED, null)
                else -> openReflow(doc)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            fail(OpenFailure.TOO_LARGE, null)
        } catch (e: Throwable) {
            val (failure, detail) = when (e) {
                is DocumentOpenException -> e.failure to e.message
                is BookOpenException -> OpenFailure.from(e.kind) to e.message
                is FileNotFoundException -> OpenFailure.MISSING to null
                is SecurityException -> OpenFailure.PERMISSION to null
                else -> OpenFailure.UNKNOWN to null
            }
            if (failure == OpenFailure.MISSING || failure == OpenFailure.PERMISSION) {
                container.libraryManager.refreshAvailability(docId)
            }
            fail(failure, detail)
        }
    }

    private fun fail(failure: OpenFailure, detail: String?) {
        _state.update { it.copy(content = ReaderContent.Failed(failure, detail)) }
    }

    private suspend fun openPdf(doc: DocumentEntity) {
        val uri = Uri.parse(doc.uri)
        val handle = PdfDocumentHandle.open(getApplication(), uri) { container.fileCache.materializeTemp(uri, "pdf") }
        showPages(handle)
    }

    /** Comics open in the same page viewer as PDFs: free zoom and pan, scrolling or tap-to-turn pages. */
    private suspend fun openComic(doc: DocumentEntity) {
        val uri = Uri.parse(doc.uri)
        val file = container.fileCache.materialize(uri, doc.id, doc.sizeBytes, doc.lastModified, DocFormat.CBZ.ext)
        showPages(CbzPageSource.open(file))
    }

    private suspend fun showPages(handle: PageSource) {
        val stored = repo.getProgress(docId)?.let { Locator.decode(it.positionType, it.positionValue) }
        val saved = startAt as? Locator.PdfPage
            ?: stored as? Locator.PdfPage
            // Comics read before they used the page viewer saved "chapter n" for page n.
            ?: (stored as? Locator.Reflow)?.let { Locator.PdfPage(it.spine) }
        val page = (saved?.page ?: 0).coerceIn(0, handle.pageCount - 1)
        _state.update {
            it.copy(
                content = ReaderContent.Pdf(handle),
                page = page,
                pageCount = handle.pageCount,
                overall = pdfOverall(page, handle.pageCount)
            )
        }
        positionKnown = saved != null
        repo.recordOpen(docId, Locator.PdfPage(page))
    }

    private suspend fun openReflow(doc: DocumentEntity) {
        val uri = Uri.parse(doc.uri)
        val format = DocFormat.fromStored(doc.format) ?: throw BookOpenException(BookOpenException.Kind.UNSUPPORTED, "Unknown format")
        val file = container.fileCache.materialize(uri, doc.id, doc.sizeBytes, doc.lastModified, format.ext)
        val book: ReflowBook = withContext(Dispatchers.IO) { BookOpener.open(format, file, doc.title) }
        currentCss = ReaderCss.build(
            _state.value.typography, _state.value.settings.highContrast,
            _state.value.settings.pageTurn == com.readle.app.domain.PageTurnMode.TAP, !_state.value.settings.reducedMotion,
            _state.value.customTheme, _state.value.customFont
        )

        // Where to start: an annotation, an explicit locator (bookmark), or the saved position.
        var target: ChapterTarget? = null
        if (startAnnotationId != null) {
            val a = libraryRepo.getAnnotation(startAnnotationId)
            val anchor = a?.let { TextAnchor.decode(it.startLocation, it.endLocation) }
            if (anchor != null && anchor.spine < book.spine.size) target = ChapterTarget(anchor.spine, textOffset = anchor.start)
        }
        if (target == null) {
            val loc = (startAt as? Locator.Reflow)
                ?: repo.getProgress(docId)?.let { Locator.decode(it.positionType, it.positionValue) as? Locator.Reflow }
            if (loc != null && loc.spine < book.spine.size) target = ChapterTarget(loc.spine, fraction = loc.fraction)
        }
        positionKnown = target?.fraction != null
        val first = target ?: ChapterTarget(0, fraction = 0f)
        _state.update {
            it.copy(content = ReaderContent.Reflow(book), spineCount = book.spine.size, toc = book.toc)
        }
        loadChapter(first)
        repo.recordOpen(docId, Locator.Reflow(first.spine, first.fraction ?: 0f))
    }

    /** Re-opens after the user relinked the file. */
    fun retry() {
        closeContent()
        _state.update { it.copy(content = ReaderContent.Loading) }
        viewModelScope.launch { open() }
    }

    fun relink(uri: Uri) {
        viewModelScope.launch {
            when (val r = container.libraryManager.relink(docId, uri)) {
                is FileOpResult.Success -> retry()
                is FileOpResult.Failure -> _messages.tryEmit(r.reason)
            }
        }
    }

    // ------------------------------------------------------------------ settings

    private suspend fun observeSettings() {
        combine(
            container.settings.settings, repo.observeBookSettings(docId),
            container.customAssets.themes, container.customAssets.fonts
        ) { global, override, themes, fonts ->
            val typography = TypographyMerge.effective(global.typography, override)
            Resolved(
                global, typography, override != null,
                themes.firstOrNull { it.id == typography.customThemeId },
                fonts.firstOrNull { it.id == typography.customFontId }
            )
        }.collect { r ->
            val settings = r.settings
            val typography = r.typography
            val css = ReaderCss.build(
                typography, settings.highContrast, settings.pageTurn == com.readle.app.domain.PageTurnMode.TAP,
                !settings.reducedMotion, r.theme, r.font
            )
            val background = (r.theme?.let { ReaderPalette.forCustom(it) } ?: ReaderPalette.of(typography.theme, settings.highContrast)).background
            val changed = css != currentCss
            currentCss = css
            _state.update { it.copy(settings = settings, typography = typography, perBook = r.perBook, customTheme = r.theme, customFont = r.font) }
            _pageSpec.update { it?.copy(css = css, background = background) }
            if (changed && _state.value.isReflow && pageReady) commandChannel.trySend(ReflowCommand.SetStyle(css, background))
        }
    }

    private class Resolved(
        val settings: AppSettings,
        val typography: TypographySettings,
        val perBook: Boolean,
        val theme: CustomTheme?,
        val font: CustomFont?
    )

    fun updateTypography(transform: (TypographySettings) -> TypographySettings) {
        viewModelScope.launch {
            val next = transform(_state.value.typography)
            if (_state.value.perBook) repo.saveBookSettings(TypographyMerge.toEntity(docId, next))
            else container.settings.update { it.copy(typography = next) }
        }
    }

    fun setPerBook(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) repo.saveBookSettings(TypographyMerge.toEntity(docId, _state.value.typography))
            else repo.clearBookSettings(docId)
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    // ------------------------------------------------------------------ navigation

    private fun loadChapter(target: ChapterTarget) {
        val book = (_state.value.content as? ReaderContent.Reflow)?.book ?: return
        val spine = target.spine.coerceIn(0, book.spine.lastIndex)
        pageReady = false
        curSpine = spine
        curFraction = target.fraction ?: 0f
        val settings = _state.value.settings
        val bg = _state.value.palette.background
        _pageSpec.value = PageSpec(
            target = target.copy(spine = spine),
            css = currentCss,
            highlightsJson = HighlightJson.build(annotations.value, spine),
            background = bg,
            token = ++tokenCounter
        )
        _state.update {
            it.copy(
                spine = spine,
                sectionPage = 0,
                sectionPages = 0,
                selection = null,
                overall = ReflowMath.overallFraction(book.spine, spine, curFraction)
            )
        }
    }

    private fun pushHistory(locator: Locator) {
        if (history.lastOrNull() == locator) return
        history.addLast(locator)
        while (history.size > MAX_HISTORY) history.removeFirst()
        _state.update { it.copy(canGoBack = true) }
    }

    private fun currentLocator(): Locator? = when (val c = _state.value.content) {
        is ReaderContent.Pdf -> Locator.PdfPage(_state.value.page)
        is ReaderContent.Reflow -> Locator.Reflow(curSpine, curFraction)
        else -> null
    }

    fun back() {
        val target = history.removeLastOrNull()
        _state.update { it.copy(canGoBack = history.isNotEmpty()) }
        when (target) {
            is Locator.PdfPage -> pdfGoToChannel.trySend(target.page)
            is Locator.Reflow -> navigateReflow(target.spine, target.fraction, anchor = null, offset = null)
            null -> Unit
        }
    }

    private fun navigateReflow(spine: Int, fraction: Float?, anchor: String?, offset: Int?) {
        val book = (_state.value.content as? ReaderContent.Reflow)?.book ?: return
        val idx = spine.coerceIn(0, book.spine.lastIndex)
        if (idx == curSpine && pageReady && anchor == null && offset == null && fraction != null) {
            curFraction = fraction
            commandChannel.trySend(ReflowCommand.ScrollFraction(fraction))
            _state.update { it.copy(overall = ReflowMath.overallFraction(book.spine, idx, fraction)) }
            publishPosition()
        } else {
            loadChapter(ChapterTarget(idx, fraction = fraction, anchor = anchor, textOffset = offset))
            positionKnown = positionKnown || fraction != null
            publishPosition()
        }
    }

    fun goToChapter(index: Int, atEnd: Boolean = false) {
        val book = (_state.value.content as? ReaderContent.Reflow)?.book ?: return
        if (index !in book.spine.indices) {
            if (index >= book.spine.size) _messages.tryEmit("You've reached the end of the book")
            return
        }
        // Turning to the neighbouring chapter is ordinary reading, not a jump, so it is not added to the back history.
        navigateReflow(index, if (atEnd) 1f else 0f, null, null)
    }

    fun goToToc(entry: TocEntry) {
        pushHistory(currentLocator() ?: return)
        navigateReflow(entry.spineIndex, if (entry.fragment == null) 0f else null, entry.fragment, null)
    }

    fun goToBookmark(b: BookmarkEntity) {
        val locator = Locator.decode(b.positionType, b.positionValue) ?: return
        pushHistory(currentLocator() ?: return)
        when (locator) {
            is Locator.PdfPage -> pdfGoToChannel.trySend(locator.page)
            is Locator.Reflow -> navigateReflow(locator.spine, locator.fraction, null, null)
        }
    }

    fun goToAnnotation(a: AnnotationEntity) {
        val anchor = TextAnchor.decode(a.startLocation, a.endLocation) ?: return
        pushHistory(currentLocator() ?: return)
        navigateReflow(anchor.spine, null, null, anchor.start)
    }

    fun goToPdfPage(page: Int) {
        val count = _state.value.pageCount
        if (count == 0) return
        pushHistory(Locator.PdfPage(_state.value.page))
        pdfGoToChannel.trySend(page.coerceIn(0, count - 1))
    }

    /** Whole-book jump from the progress slider. */
    fun jumpToOverall(fraction: Float) {
        when (val c = _state.value.content) {
            is ReaderContent.Pdf -> goToPdfPage((fraction * (c.handle.pageCount - 1)).roundToInt())
            is ReaderContent.Reflow -> {
                val (idx, frac) = ReflowMath.locate(c.book.spine, fraction)
                pushHistory(Locator.Reflow(curSpine, curFraction))
                navigateReflow(idx, frac, null, null)
            }
            else -> Unit
        }
    }

    fun pageBy(dir: Int) {
        if (_state.value.isReflow) commandChannel.trySend(ReflowCommand.PageBy(dir))
    }

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun setChrome(visible: Boolean) = _state.update { it.copy(chromeVisible = visible) }

    // ------------------------------------------------------------------ PDF events

    fun onPdfPage(page: Int) {
        val count = _state.value.pageCount
        if (count == 0 || page == _state.value.page && positionKnown) return
        positionKnown = true
        _state.update { it.copy(page = page, overall = pdfOverall(page, count)) }
        publishPosition()
    }

    private fun pdfOverall(page: Int, count: Int): Float = if (count <= 1) 1f else page.toFloat() / (count - 1)

    // ------------------------------------------------------------------ reflow events (JS threads)

    override fun onPages(page: Int, total: Int) {
        if (!pageReady) return
        _state.update { if (it.sectionPage == page && it.sectionPages == total) it else it.copy(sectionPage = page, sectionPages = total) }
    }

    override fun onScroll(fraction: Float) {
        if (!pageReady) return // late report from the previous chapter
        curFraction = fraction
        positionKnown = true
        val book = (_state.value.content as? ReaderContent.Reflow)?.book ?: return
        _state.update { it.copy(overall = ReflowMath.overallFraction(book.spine, curSpine, fraction)) }
        publishPosition()
    }

    override fun onTap(x: Float) {
        val s = _state.value
        if (s.settings.pageTurn == com.readle.app.domain.PageTurnMode.TAP) {
            when {
                x < 0.28f -> { pageBy(-1); return }
                x > 0.72f -> { pageBy(1); return }
            }
        }
        toggleChrome()
    }

    override fun onSelection(text: String, start: Int, end: Int) {
        if (!pageReady) return
        _state.update { it.copy(selection = SelectionInfo(text, start, end, curSpine)) }
    }

    override fun onSelectionCleared() = _state.update { it.copy(selection = null) }

    override fun onNavigate(next: Boolean) {
        viewModelScope.launch { goToChapter(curSpine + if (next) 1 else -1, atEnd = !next) }
    }

    override fun onInternalJump(fractionBefore: Float) {
        viewModelScope.launch { pushHistory(Locator.Reflow(curSpine, fractionBefore)) }
    }

    override fun onHighlightTap(id: Long) = _state.update { it.copy(editingAnnotationId = id) }

    override fun onReady() {
        pageReady = true
        pendingFind?.let { (q, n) ->
            pendingFind = null
            commandChannel.trySend(ReflowCommand.Find(q, n))
        }
        if (ttsContinueOnReady) {
            ttsContinueOnReady = false
            startTts(fromChapterStart = true)
        }
    }

    override fun onPageResult(dir: Int, result: String) {
        viewModelScope.launch {
            when (result) {
                "end" -> if (dir > 0) goToChapter(curSpine + 1)
                "start" -> if (dir < 0) {
                    if (curSpine > 0) goToChapter(curSpine - 1, atEnd = true) else _messages.tryEmit("This is the beginning")
                }
            }
        }
    }

    override fun onChapterLink(spine: Int, fragment: String?) {
        viewModelScope.launch {
            pushHistory(Locator.Reflow(curSpine, curFraction))
            navigateReflow(spine, if (fragment == null) 0f else null, fragment, null)
        }
    }

    override fun onExternalLink(url: String) = _state.update { it.copy(externalLink = url) }

    fun dismissExternalLink() = _state.update { it.copy(externalLink = null) }

    // ------------------------------------------------------------------ progress saving

    private fun publishPosition() {
        positionUpdates.tryEmit(Unit)
    }

    @OptIn(FlowPreview::class)
    private suspend fun observeProgressSaving() {
        positionUpdates.debounce(SAVE_DEBOUNCE_MS).collect { saveProgressNow() }
    }

    private suspend fun saveProgressNow() {
        if (!positionKnown) return
        val s = _state.value
        when (val c = s.content) {
            is ReaderContent.Pdf -> repo.saveProgress(
                docId, Locator.PdfPage(s.page), pdfOverall(s.page, c.handle.pageCount), s.page + 1, c.handle.pageCount
            )
            is ReaderContent.Reflow -> {
                val overall = ReflowMath.overallFraction(c.book.spine, curSpine, curFraction)
                repo.saveProgress(docId, Locator.Reflow(curSpine, curFraction), overall, curSpine + 1, c.book.spine.size)
            }
            else -> Unit
        }
    }

    /** Called when the reader is stopped or left, so nothing is lost if the process is killed next. */
    fun flush() {
        searchJob?.cancel()
        finalIoScope.launch { saveProgressNow() }
    }

    // ------------------------------------------------------------------ bookmarks

    fun isBookmarkedHere(): Boolean = currentLocator()?.let { BookmarkRules.isDuplicate(bookmarks.value, it) } ?: false

    fun toggleBookmark() {
        val locator = currentLocator() ?: return
        viewModelScope.launch {
            val existing = BookmarkRules.findAt(bookmarks.value, locator)
            if (existing != null) {
                repo.deleteBookmark(existing.id)
                _messages.tryEmit("Bookmark removed")
                return@launch
            }
            val s = _state.value
            val title = (s.content as? ReaderContent.Reflow)?.book?.chapterTitle(curSpine)
            val label = BookmarkRules.defaultLabel(locator, title, s.overall)
            val added = repo.addBookmark(docId, locator, s.overall, label)
            _messages.tryEmit(if (added) "Bookmark added" else "Already bookmarked here")
        }
    }

    fun renameBookmark(id: Long, label: String) = viewModelScope.launch { repo.renameBookmark(id, label) }

    fun deleteBookmark(id: Long) = viewModelScope.launch { repo.deleteBookmark(id) }

    // ------------------------------------------------------------------ highlights, quotes, notes

    private suspend fun observeAnnotations() {
        annotations.collect { list ->
            if (_state.value.isReflow && pageReady) {
                commandChannel.trySend(ReflowCommand.Highlights(HighlightJson.build(list, curSpine)))
            }
            _pageSpec.update { it?.copy(highlightsJson = HighlightJson.build(list, curSpine)) }
        }
    }

    private fun addAnnotation(type: String, note: String?, color: Int) {
        val sel = _state.value.selection ?: return
        viewModelScope.launch {
            repo.addAnnotation(docId, type, sel.text, note, color, TextAnchor(sel.spine, sel.start, sel.end))
            clearSelection()
            _messages.tryEmit(
                when (type) {
                    AnnotationEntity.NOTE -> "Note saved"
                    AnnotationEntity.QUOTE -> "Quote saved"
                    else -> "Highlight saved"
                }
            )
        }
    }

    fun highlightSelection(color: Int) = addAnnotation(AnnotationEntity.HIGHLIGHT, null, color)

    fun quoteSelection() = addAnnotation(AnnotationEntity.QUOTE, null, HighlightColors.BLUE)

    fun noteSelection(note: String, color: Int = HighlightColors.YELLOW) = addAnnotation(AnnotationEntity.NOTE, note, color)

    fun clearSelection() {
        _state.update { it.copy(selection = null) }
        commandChannel.trySend(ReflowCommand.ClearSelection)
    }

    fun editAnnotation(id: Long?) = _state.update { it.copy(editingAnnotationId = id) }

    fun updateAnnotation(id: Long, type: String, note: String?, color: Int) {
        viewModelScope.launch { repo.updateAnnotation(id, type, note, color) }
    }

    fun deleteAnnotation(id: Long) {
        viewModelScope.launch {
            repo.deleteAnnotation(id)
            _state.update { it.copy(editingAnnotationId = null) }
        }
    }

    fun saveWord(word: String, definition: String? = null) {
        viewModelScope.launch {
            repo.addWord(word, definition, docId, currentLocator())
            _messages.tryEmit("Saved “${word.trim().take(40)}” to your vocabulary")
        }
    }

    // ------------------------------------------------------------------ search

    fun search(query: String) {
        searchJob?.cancel()
        val book = (_state.value.content as? ReaderContent.Reflow)?.book
        if (book == null || query.isBlank()) {
            _state.update { it.copy(search = SearchState(query = query)) }
            return
        }
        _state.update { it.copy(search = SearchState(query = query, running = true)) }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val all = ArrayList<SearchHit>()
            var truncated = false
            for (i in book.spine.indices) {
                ensureActive()
                val room = MAX_SEARCH_HITS - all.size
                if (room <= 0) {
                    truncated = true
                    break
                }
                val hits = BookSearch.findInChapter(i, book.chapterText(i), query, maxHits = room)
                if (hits.isNotEmpty()) {
                    all.addAll(hits)
                    _state.update { it.copy(search = SearchState(query, running = true, hits = all.toList())) }
                }
            }
            _state.update { it.copy(search = SearchState(query, running = false, hits = all.toList(), truncated = truncated, completed = true)) }
        }
    }

    fun openSearchHit(hit: SearchHit) {
        val q = _state.value.search.query
        pushHistory(currentLocator() ?: return)
        if (hit.spine == curSpine && pageReady) {
            commandChannel.trySend(ReflowCommand.Find(q, hit.nth))
        } else {
            pendingFind = q to hit.nth
            loadChapter(ChapterTarget(hit.spine, fraction = null))
        }
        setChrome(false)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = SearchState()) }
        commandChannel.trySend(ReflowCommand.ClearFind)
    }

    // ------------------------------------------------------------------ text to speech

    private suspend fun observeTts() {
        tts.state.collect { s ->
            _state.update { it.copy(tts = s) }
            if (s == TtsState.Idle || s is TtsState.Unavailable) commandChannel.trySend(ReflowCommand.TtsClear)
        }
    }

    fun toggleTts() {
        when (_state.value.tts) {
            TtsState.Speaking -> tts.pause()
            TtsState.Paused -> requestTtsText(TtsStart.RESUME)
            TtsState.Starting -> { ttsPending = null; speech.end() }
            else -> requestTtsText(TtsStart.VISIBLE)
        }
    }

    fun stopTts() {
        ttsPending = null
        speech.end()
    }

    fun setTtsRate(rate: Float) {
        updateSettings { it.copy(ttsRate = rate) }
        tts.setRate(rate)
    }

    private enum class TtsStart { VISIBLE, CHAPTER, RESUME }

    /** Reading starts from what the page shows right now, so the page itself (not an old saved position) decides. */
    private fun requestTtsText(kind: TtsStart) {
        if (!pageReady) return
        ttsPending = kind
        commandChannel.trySend(ReflowCommand.TtsPrepare(if (kind == TtsStart.RESUME) tts.position else -1))
    }

    override fun onTtsText(text: String, visibleStart: Int, lastVisible: Boolean) {
        viewModelScope.launch {
            val kind = ttsPending ?: return@launch
            ttsPending = null
            val book = (_state.value.content as? ReaderContent.Reflow)?.book ?: return@launch
            if (text.isBlank()) {
                _messages.tryEmit("There is no text to read in this section")
                return@launch
            }
            // Resume exactly where reading paused if that word is still on screen; if the reader has turned
            // pages since, start from the page they are looking at instead.
            if (kind == TtsStart.RESUME && lastVisible && text == ttsText) {
                tts.resume()
                return@launch
            }
            ttsText = text
            ttsSpine = curSpine
            speech.begin(
                docId = docId,
                title = _state.value.doc?.title ?: book.meta.title ?: "Readle",
                spine = curSpine,
                chapterTitle = book.chapterTitle(curSpine),
                background = _state.value.settings.backgroundReading
            )
            val from = if (kind == TtsStart.CHAPTER) 0 else visibleStart.coerceIn(0, text.length)
            // Short chunks keep the page and highlight close to the voice even if the engine reports no word positions.
            val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(100, 600)
            tts.speak(TtsChunker.split(text, from, limit), book.meta.language, _state.value.settings.ttsRate)
        }
    }

    private fun startTts(fromChapterStart: Boolean) = requestTtsText(if (fromChapterStart) TtsStart.CHAPTER else TtsStart.VISIBLE)

    override fun onPosition(start: Int, end: Int) {
        viewModelScope.launch { onTtsPosition(start, end) }
    }

    override fun onChapterFinished() {
        viewModelScope.launch { onTtsChapterFinished() }
    }

    override fun onSleepTimerEnded() {
        viewModelScope.launch {
            // The page already follows the voice, so the current position is the place reading stopped.
            saveProgressNow()
            _messages.tryEmit("Sleep timer ended. Your place is saved.")
            _exitRequests.emit(Unit)
        }
    }

    /**
     * Called when the reader comes to the front or leaves it. While it is away, speech carries on without it
     * (if the user allows that); when it returns, it jumps to the section being read.
     */
    /** The timer length for the reading about to start; used straight away, not only after settings are saved. */
    fun setSleepMinutes(minutes: Int) {
        speech.setSleepMinutes(minutes)
        updateSettings { it.copy(sleepTimerMinutes = minutes) }
    }

    fun setUiActive(active: Boolean) {
        if (!active) {
            ttsPending = null
            ttsContinueOnReady = false
            speech.deactivate(this)
            return
        }
        speech.activate(this)
        val s = speech.session.value ?: return
        val st = tts.state.value
        val live = st == TtsState.Speaking || st == TtsState.Paused || st == TtsState.Starting
        if (s.docId != docId || !live) return
        if (s.spine != ttsSpine) ttsText = "" // the spoken text came from another section, so there is nothing to highlight
        val book = (_state.value.content as? ReaderContent.Reflow)?.book ?: return
        if (s.spine != curSpine && s.spine in 0..book.spine.lastIndex) loadChapter(ChapterTarget(s.spine, fraction = 0f))
    }

    private fun onTtsPosition(start: Int, end: Int) {
        if (ttsText.isEmpty()) return
        positionKnown = true
        commandChannel.trySend(ReflowCommand.TtsMark(start, end))
    }

    private fun onTtsChapterFinished() {
        val book = (_state.value.content as? ReaderContent.Reflow)?.book ?: return
        // Follow the voice, not the page: the reader may have turned pages while it was reading.
        val reading = speech.session.value?.takeIf { it.docId == docId }?.spine ?: curSpine
        if (reading < book.spine.lastIndex) {
            val next = reading + 1
            speech.setSpine(next, book.chapterTitle(next))
            ttsContinueOnReady = true
            loadChapter(ChapterTarget(next, fraction = 0f))
        } else {
            speech.end()
            _messages.tryEmit("Finished reading the book aloud")
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private fun closeContent() {
        when (val c = _state.value.content) {
            is ReaderContent.Pdf -> c.handle.close()
            is ReaderContent.Reflow -> c.book.close()
            else -> Unit
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
        finalIoScope.launch { saveProgressNow() }
        speech.deactivate(this)
        closeContent()
        super.onCleared()
    }

    class Factory(
        private val app: Application,
        private val docId: Long,
        private val startAt: Locator?,
        private val startAnnotationId: Long?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(app, docId, startAt, startAnnotationId) as T
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 700L
        const val MAX_HISTORY = 50
        const val MAX_SEARCH_HITS = 300
    }
}
