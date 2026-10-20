package com.readle.app.reader

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readle.app.data.db.AnnotationEntity
import com.readle.app.domain.HighlightColors
import com.readle.app.domain.Locator
import com.readle.app.domain.OrientationPref
import com.readle.app.domain.PageTurnMode
import com.readle.app.reader.pdf.PdfPagesGrid
import com.readle.app.reader.pdf.PdfReaderView
import com.readle.app.reader.pdf.rememberPdfViewState
import com.readle.app.reader.reflow.ReflowReaderView
import com.readle.app.reader.tts.TtsState
import com.readle.app.ui.components.ConfirmDialog
import com.readle.app.ui.components.EmptyState
import com.readle.app.ui.components.TextInputDialog
import com.readle.app.ui.components.minTouchTarget

private enum class Sheet { NONE, TOC, MARKS, SEARCH, SETTINGS, PAGES }

/**
 * The reading screen for every supported format. The toolbar is transient (tap the middle of the page);
 * everything it offers is backed by [ReaderViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    docId: Long,
    startAt: Locator?,
    startAnnotationId: Long?,
    onExit: () -> Unit,
    onOpenDetails: (Long) -> Unit,
    onOpenThemes: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ReaderViewModel = viewModel(
        key = "reader:$docId:${startAt?.hashCode()}:$startAnnotationId",
        factory = ReaderViewModel.Factory(app, docId, startAt, startAnnotationId)
    )
    val state by vm.state.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val annotations by vm.annotations.collectAsState()
    val pageSpec by vm.pageSpec.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var renameBookmarkId by remember { mutableStateOf<Long?>(null) }
    var deleteBookmarkId by remember { mutableStateOf<Long?>(null) }
    var noteDraft by remember { mutableStateOf(false) }
    var defineText by remember { mutableStateOf<String?>(null) }
    var lastQuery by remember { mutableStateOf("") }
    var goToPageDialog by remember { mutableStateOf(false) }

    val settings = state.settings
    val palette = state.palette

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Persist position whenever the reader is no longer in front of the user.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.setUiActive(true)
                Lifecycle.Event.ON_STOP -> { vm.setUiActive(false); vm.flush() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); vm.setUiActive(false); vm.flush() }
    }

    // Window behaviour: brightness, orientation, keep-screen-on and immersive mode all revert on exit.
    val activity = context as? Activity
    DisposableEffect(settings.brightnessOverride, settings.keepScreenOn, settings.orientation) {
        val window = activity?.window
        val prevOrientation = activity?.requestedOrientation
        if (window != null) {
            val lp = window.attributes
            lp.screenBrightness = settings.brightnessOverride ?: android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            if (settings.keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        activity?.requestedOrientation = when (settings.orientation) {
            OrientationPref.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationPref.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationPref.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            if (window != null) {
                val lp = window.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            if (prevOrientation != null) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    val showChrome = state.chromeVisible || sheet != Sheet.NONE || state.content !is ReaderContent.Pdf && state.content !is ReaderContent.Reflow
    DisposableEffect(showChrome) {
        val window = activity?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showChrome) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        when {
            sheet != Sheet.NONE -> sheet = Sheet.NONE
            state.selection != null -> vm.clearSelection()
            state.search.query.isNotEmpty() && state.isReflow -> vm.clearSearch()
            else -> onExit()
        }
    }

    var askedNotifications by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    var showReadAloud by remember { mutableStateOf(false) }
    val startOrToggle: () -> Unit = {
        // Android 13+ needs a one-time OK before the read-aloud notification can be shown.
        if (android.os.Build.VERSION.SDK_INT >= 33 && settings.backgroundReading && !askedNotifications &&
            (state.tts == TtsState.Idle || state.tts is TtsState.Unavailable) &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.toggleTts()
    }
    LaunchedEffect(Unit) { vm.exitRequests.collect { onExit() } }
    val sleepLeft by vm.sleepRemaining.collectAsState()

    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.relink(uri)
    }

    val bg = Color(palette.background)
    val fg = Color(palette.text)

    Box(Modifier.fillMaxSize().background(bg)) {
        when (val c = state.content) {
            ReaderContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is ReaderContent.Failed -> FailureView(
                failure = c.failure,
                detail = c.detail,
                onRetry = vm::retry,
                onRelink = { relinkPicker.launch(com.readle.app.domain.FormatSupport.pickerMimeTypes) },
                onBack = onExit
            )
            is ReaderContent.Pdf -> {
                val pdfState = rememberPdfViewState(state.page)
                PdfReaderView(
                    handle = c.handle,
                    state = pdfState,
                    // PDF pages are images, so an image theme becomes a night filter (dark pictures) or a tint of its average colour.
                    theme = state.customTheme?.let { if (it.dark) com.readle.app.domain.ReaderTheme.NIGHT else com.readle.app.domain.ReaderTheme.DAY } ?: state.typography.theme,
                    tint = state.customTheme?.takeIf { !it.dark }?.let { Color(it.background) },
                    highContrast = settings.highContrast,
                    tapToTurn = settings.pageTurn == PageTurnMode.TAP,
                    reducedMotion = settings.reducedMotion,
                    goTo = vm.pdfGoTo,
                    onPageChanged = vm::onPdfPage,
                    onTapCenter = vm::toggleChrome
                )
                if (sheet == Sheet.PAGES) {
                    androidx.compose.material3.ModalBottomSheet(
                        onDismissRequest = { sheet = Sheet.NONE },
                        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ) {
                        PdfPagesGrid(
                            handle = c.handle,
                            currentPage = state.page,
                            bookmarkedPages = bookmarks.mapNotNull { (Locator.decode(it.positionType, it.positionValue) as? Locator.PdfPage)?.page }.toSet(),
                            onPick = { vm.goToPdfPage(it); sheet = Sheet.NONE },
                            modifier = Modifier.height(480.dp)
                        )
                    }
                }
                // Zoom controls live in the bottom bar below, driven through this state.
                PdfControls(visible = showChrome, paged = settings.pageTurn == PageTurnMode.TAP, onFit = pdfState::fitWidth,
                    onToggleMode = { vm.updateSettings { s -> s.copy(pageTurn = if (s.pageTurn == PageTurnMode.TAP) PageTurnMode.SCROLL else PageTurnMode.TAP) } },
                    onZoomIn = { pdfState.zoomBy(1.25f) }, onZoomOut = { pdfState.zoomBy(0.8f) },
                    modifier = Modifier.align(Alignment.CenterEnd))
            }
            is ReaderContent.Reflow -> {
                val spec = pageSpec
                if (spec != null) {
                    ReflowReaderView(
                        book = c.book,
                        spec = spec,
                        commands = vm.commands,
                        listener = vm,
                        zoomable = com.readle.app.domain.DocFormat.fromStored(state.doc?.format.orEmpty()).let {
                            it == com.readle.app.domain.DocFormat.DOCX || it == com.readle.app.domain.DocFormat.RTF
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        val loaded = state.content is ReaderContent.Pdf || state.content is ReaderContent.Reflow

        // ---- top bar ----
        AnimatedVisibility(
            visible = showChrome && loaded,
            enter = if (settings.reducedMotion) fadeIn() else fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Column(Modifier.statusBarsPadding().fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onExit, modifier = Modifier.size(minTouchTarget())) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close book")
                    }
                    Text(
                        state.doc?.title.orEmpty(), Modifier.weight(1f).padding(end = 12.dp).clickable { state.doc?.let { onOpenDetails(it.id) } },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Row(
                    Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).widthIn(min = maxWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (state.canGoBack) IconButton(onClick = vm::back, modifier = Modifier.size(minTouchTarget())) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Go back to where you were")
                    }
                    if (state.isReflow) IconButton(onClick = { sheet = Sheet.SEARCH }, modifier = Modifier.size(minTouchTarget())) {
                        Icon(Icons.Filled.Search, "Search in book")
                    }
                    if (state.isReflow) IconButton(onClick = { sheet = Sheet.TOC }, modifier = Modifier.size(minTouchTarget())) {
                        Icon(Icons.Filled.FormatListBulleted, "Contents")
                    }
                    if (state.isPdf) IconButton(onClick = { sheet = Sheet.PAGES }, modifier = Modifier.size(minTouchTarget())) {
                        Icon(Icons.Filled.GridView, "Page thumbnails")
                    }
                    val marked = remember(bookmarks, state.page, state.overall, state.spine) { vm.isBookmarkedHere() }
                    IconButton(onClick = vm::toggleBookmark, modifier = Modifier.size(minTouchTarget())) {
                        Icon(
                            if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (marked) "Remove bookmark from this place" else "Bookmark this place"
                        )
                    }
                    IconButton(onClick = { sheet = Sheet.MARKS }, modifier = Modifier.size(minTouchTarget())) {
                        Icon(Icons.Filled.EditNote, "Bookmarks and notes")
                    }
                    IconButton(onClick = { sheet = Sheet.SETTINGS }, modifier = Modifier.size(minTouchTarget())) {
                        Icon(Icons.Filled.TextFields, "Reading settings")
                    }
                }
                }
                }
            }
        }

        // ---- bottom bar ----
        AnimatedVisibility(
            visible = showChrome && loaded && state.selection == null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Column(Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    var drag by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = drag ?: state.overall,
                        onValueChange = { drag = it },
                        onValueChangeFinished = { drag?.let(vm::jumpToOverall); drag = null },
                        modifier = Modifier.semantics { contentDescription = "Reading position" }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = when {
                            state.isPdf -> "Page ${state.page + 1} of ${state.pageCount}"
                            else -> buildString {
                                if (state.sectionPages > 0) append("Page ${state.sectionPage} of ${state.sectionPages} · ")
                                append("Section ${state.spine + 1} of ${state.spineCount} · ${(((drag ?: state.overall)) * 100).toInt()}%")
                            }
                        }
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (state.isPdf) TextButton(onClick = { goToPageDialog = true }) { Text("Go to page") }
                        if (state.isReflow) TtsControls(state.tts, onToggle = startOrToggle, onOptions = { showReadAloud = true }, onStop = vm::stopTts, rate = settings.ttsRate, onRate = vm::setTtsRate, context = context)
                    }
                }
            }
        }

        // ---- selection action bar (reflow only) ----
        val sel = state.selection
        if (sel != null && state.isReflow) {
            SelectionBar(
                text = sel.text,
                onHighlight = vm::highlightSelection,
                onNote = { noteDraft = true },
                onQuote = vm::quoteSelection,
                onCopy = { clipboard.setText(AnnotatedString(sel.text)); vm.clearSelection() },
                onDefine = { defineText = sel.text.trim() },
                onSaveWord = { vm.saveWord(sel.text); vm.clearSelection() },
                onDismiss = vm::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        sleepLeft?.let { ms ->
            val total = (ms + 999) / 1000
            val text = if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60) else "%d:%02d".format(total / 60, total % 60)
            androidx.compose.material3.Surface(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = if (showChrome) 72.dp else 8.dp),
                color = Color(0xFF1565C0),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) {
                Text(
                    text, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color.White, style = MaterialTheme.typography.labelLarge
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (showChrome) 96.dp else 16.dp))
    }

    // ---- sheets ----
    when (sheet) {
        Sheet.TOC -> TocSheet(state.toc, state.spine, onPick = { vm.goToToc(it); sheet = Sheet.NONE }, onDismiss = { sheet = Sheet.NONE })
        Sheet.MARKS -> MarksSheet(
            bookmarks = bookmarks,
            annotations = annotations,
            canAnnotate = state.isReflow,
            onBookmark = { vm.goToBookmark(it); sheet = Sheet.NONE },
            onRenameBookmark = { renameBookmarkId = it.id },
            onDeleteBookmark = { deleteBookmarkId = it.id },
            onAnnotation = { vm.goToAnnotation(it); sheet = Sheet.NONE },
            onEditAnnotation = { vm.editAnnotation(it.id); sheet = Sheet.NONE },
            onDismiss = { sheet = Sheet.NONE }
        )
        Sheet.SEARCH -> SearchSheet(
            state = state.search,
            initial = lastQuery.ifEmpty { state.search.query },
            onSearch = { lastQuery = it; vm.search(it) },
            onPick = { vm.openSearchHit(it); sheet = Sheet.NONE },
            onClear = { lastQuery = ""; vm.clearSearch() },
            onDismiss = { sheet = Sheet.NONE }
        )
        Sheet.SETTINGS -> ReaderSettingsSheet(
            reflow = state.isReflow,
            typography = state.typography,
            perBook = state.perBook,
            settings = settings,
            onTypography = vm::updateTypography,
            onPerBook = vm::setPerBook,
            onSettings = vm::updateSettings,
            onOpenThemes = { sheet = Sheet.NONE; onOpenThemes() },
            onDismiss = { sheet = Sheet.NONE }
        )
        Sheet.PAGES, Sheet.NONE -> Unit
    }

    // ---- dialogs ----
    if (showReadAloud) ReadAloudDialog(
        idle = state.tts == TtsState.Idle || state.tts is TtsState.Unavailable,
        settings = settings,
        onSettings = vm::updateSettings,
        onRate = vm::setTtsRate,
        onStart = { minutes -> showReadAloud = false; vm.setSleepMinutes(minutes); startOrToggle() },
        onDismiss = { showReadAloud = false }
    )
    renameBookmarkId?.let { id ->
        val b = bookmarks.firstOrNull { it.id == id }
        if (b == null) renameBookmarkId = null else TextInputDialog(
            title = "Rename bookmark", initial = b.label, label = "Name",
            onConfirm = { vm.renameBookmark(id, it.trim()); renameBookmarkId = null },
            onDismiss = { renameBookmarkId = null }
        )
    }
    deleteBookmarkId?.let { id ->
        ConfirmDialog(
            title = "Delete bookmark?", message = "This bookmark will be removed. Your reading position is not affected.",
            confirmLabel = "Delete", destructive = true,
            onConfirm = { vm.deleteBookmark(id); deleteBookmarkId = null },
            onDismiss = { deleteBookmarkId = null }
        )
    }
    defineText?.let { word ->
        DefinitionDialog(
            selected = word,
            onSave = { w, def -> vm.saveWord(w, def); vm.clearSelection() },
            onOtherApp = {
                val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_PROCESS_TEXT, word)
                    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                }
                try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar("No other dictionary app is installed.") }
                }
            },
            onDismiss = { defineText = null }
        )
    }

    if (noteDraft && state.selection != null) {
        NoteDialog(
            title = "Add note", initialNote = "", initialColor = HighlightColors.YELLOW, canChangeType = false, initialType = AnnotationEntity.NOTE,
            onSave = { note, color, _ -> noteDraft = false; vm.noteSelection(note, color) },
            onDelete = null,
            onDismiss = { noteDraft = false }
        )
    } else if (noteDraft) noteDraft = false

    state.editingAnnotationId?.let { id ->
        val a = annotations.firstOrNull { it.id == id }
        if (a == null) vm.editAnnotation(null) else {
            var confirmDelete by remember(id) { mutableStateOf(false) }
            if (confirmDelete) {
                ConfirmDialog(
                    title = "Delete this ${a.type.lowercase()}?", message = "It will be removed from the book and from your notes.",
                    confirmLabel = "Delete", destructive = true,
                    onConfirm = { vm.deleteAnnotation(id) },
                    onDismiss = { confirmDelete = false }
                )
            } else NoteDialog(
                title = "Edit ${a.type.lowercase()}", quote = a.selectedText, initialNote = a.noteText.orEmpty(), initialColor = a.color,
                canChangeType = true, initialType = a.type,
                onSave = { note, color, type -> vm.updateAnnotation(id, type, note.ifBlank { null }, color); vm.editAnnotation(null) },
                onDelete = { confirmDelete = true },
                onDismiss = { vm.editAnnotation(null) }
            )
        }
    }

    state.externalLink?.let { url ->
        ConfirmDialog(
            title = "Open link?",
            message = "This book links to a website:\n$url\n\nIt will open in your browser; Readle itself never goes online.",
            confirmLabel = "Open", onConfirm = {
                vm.dismissExternalLink()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) { }
            },
            onDismiss = vm::dismissExternalLink
        )
    }

    if (goToPageDialog) {
        TextInputDialog(
            title = "Go to page", label = "Page (1–${state.pageCount})",
            validate = { v -> v.trim().toIntOrNull()?.takeIf { it in 1..state.pageCount }?.let { null } ?: "Enter a number from 1 to ${state.pageCount}" },
            confirmLabel = "Go",
            onConfirm = { vm.goToPdfPage(it.trim().toInt() - 1); goToPageDialog = false },
            onDismiss = { goToPageDialog = false }
        )
    }
}

@Composable
private fun FailureView(
    failure: OpenFailure,
    detail: String?,
    onRetry: () -> Unit,
    onRelink: () -> Unit,
    onBack: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        EmptyState(
            icon = Icons.Filled.WarningAmber,
            title = failure.title,
            message = failure.explanation + (detail?.let { "\n\n$it" } ?: ""),
            primaryLabel = if (failure.canRelink) "Locate the file" else "Try again",
            onPrimary = if (failure.canRelink) onRelink else onRetry,
            secondaryLabel = "Back to library",
            onSecondary = onBack
        )
    }
}

@Composable
private fun PdfControls(
    visible: Boolean,
    paged: Boolean,
    onFit: () -> Unit,
    onToggleMode: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), tonalElevation = 3.dp, shadowElevation = 4.dp, modifier = Modifier.padding(end = 8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onZoomIn, Modifier.size(minTouchTarget())) { Icon(Icons.Filled.ZoomIn, "Zoom in") }
                IconButton(onClick = onZoomOut, Modifier.size(minTouchTarget())) { Icon(Icons.Filled.ZoomOut, "Zoom out") }
                IconButton(onClick = onFit, Modifier.size(minTouchTarget())) {
                    if (paged) Icon(Icons.Filled.FitScreen, "Fit whole page to the screen") else Icon(Icons.Filled.SwapHoriz, "Fit to page width")
                }
                IconButton(onClick = onToggleMode, Modifier.size(minTouchTarget())) {
                    if (paged) Icon(Icons.Filled.ViewDay, "Switch to continuous scrolling") else Icon(Icons.Filled.ViewCarousel, "Switch to pages mode")
                }
            }
        }
    }
}

@Composable
private fun TtsControls(
    tts: TtsState,
    onToggle: () -> Unit,
    onOptions: () -> Unit,
    onStop: () -> Unit,
    rate: Float,
    onRate: (Float) -> Unit,
    context: android.content.Context
) {
    when (tts) {
        is TtsState.Unavailable -> {
            var info by remember { mutableStateOf(false) }
            IconButton(onClick = { info = true }) { Icon(Icons.Filled.VolumeUp, "Read aloud (unavailable)") }
            if (info) AlertDialog(
                onDismissRequest = { info = false },
                title = { Text("Read aloud isn't available") },
                text = { Text(tts.message) },
                confirmButton = {
                    if (tts.needsSetup) TextButton(onClick = {
                        info = false
                        try {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
                        }
                    }) { Text("Open settings") } else TextButton(onClick = { info = false }) { Text("OK") }
                },
                dismissButton = if (tts.needsSetup) ({ TextButton(onClick = { info = false }) { Text("Cancel") } }) else null
            )
        }
        else -> {
            TextButton(onClick = onOptions) { Text("${"%.1f".format(rate)}×") }
            IconButton(onClick = { if (tts == TtsState.Speaking || tts == TtsState.Paused || tts == TtsState.Starting) onToggle() else onOptions() }) {
                when (tts) {
                    TtsState.Speaking -> Icon(Icons.Filled.Pause, "Pause reading aloud")
                    TtsState.Starting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Icon(Icons.Filled.VolumeUp, if (tts == TtsState.Paused) "Resume reading aloud" else "Read aloud")
                }
            }
            if (tts == TtsState.Speaking || tts == TtsState.Paused) IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, "Stop reading aloud") }
        }
    }
}

@Composable
private fun SelectionBar(
    text: String,
    onHighlight: (Int) -> Unit,
    onNote: () -> Unit,
    onQuote: () -> Unit,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onSaveWord: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(12.dp)) {
            Text(
                "“${text.take(120)}${if (text.length > 120) "…" else ""}”",
                style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HighlightColors.all.forEach { (name, argb) ->
                    Box(
                        Modifier.size(minTouchTarget()).clickable(onClickLabel = "Highlight $name") { onHighlight(argb) }
                            .semantics { contentDescription = "Highlight $name" },
                        contentAlignment = Alignment.Center
                    ) { Box(Modifier.size(28.dp).background(Color(argb), CircleShape)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onNote) { Text("Note") }
                TextButton(onClick = onQuote) { Text("Quote") }
                TextButton(onClick = onCopy) { Text("Copy") }
                // Single words only: define/save are meaningless for a whole paragraph.
                if (text.trim().split(Regex("\\s+")).size <= 3) {
                    TextButton(onClick = onDefine) { Text("Define") }
                    TextButton(onClick = onSaveWord) { Text("Save word") }
                }
            }
        }
    }
}

@Composable
private fun NoteDialog(
    title: String,
    quote: String? = null,
    initialNote: String,
    initialColor: Int,
    canChangeType: Boolean,
    initialType: String,
    onSave: (note: String, color: Int, type: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf(initialNote) }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quote?.let { Text("“${it.take(200)}”", style = MaterialTheme.typography.bodySmall, maxLines = 4) }
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HighlightColors.all.forEach { (name, argb) ->
                        Box(
                            Modifier.size(minTouchTarget()).clickable { color = argb }
                                .semantics { contentDescription = if (argb == color) "$name, selected" else name },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(if (argb == color) 34.dp else 26.dp).background(Color(argb), CircleShape)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val type = when {
                    note.isNotBlank() -> AnnotationEntity.NOTE
                    initialType == AnnotationEntity.NOTE -> AnnotationEntity.HIGHLIGHT
                    else -> initialType
                }
                onSave(note.trim(), color, type)
            }, enabled = !(onDelete == null && note.isBlank())) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
